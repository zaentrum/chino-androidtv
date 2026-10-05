package cloud.nalet.chino.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * What an addon's slot row may make the TV do. Addons register rows for named
 * slots in portal-api; chino-api serves a slot's enabled rows, in order, at
 * GET /v1/extensions?slot=…, and an empty list when there is no addon (or no
 * portal-api): the slot then shows nothing. The rows are data from elsewhere,
 * so a row is shown only when what it points at is safe behind a native
 * button — the rules chino-web's lib/extensions.ts holds them to. Its label is
 * the addon's text; the app adds none of its own.
 *
 * A TV has no browser, so the two kinds do this here:
 *  - a link's button shows its address as a QR code and as text, for a phone
 *    to open; the TV opens nothing. Only a page of the connected server is
 *    shown: a path on it ("/portal/app/x?q={q}") or an absolute http(s) URL on
 *    its own origin — the rule portal-api holds a row to when it is written.
 *    (chino-web also takes an address relative to the page it is on; the TV
 *    is on no page.)
 *  - an action is a POST, with the signed-in person's bearer, to the portal's
 *    app proxy on the server's own origin (/api/portal/apps/<key>/…) and
 *    nowhere else, so a row cannot spend the bearer on chino-api's or the
 *    portal's own endpoints.
 */

/** The one slot the TV renders: under "No results", when a search found no
 *  titles and no people. */
const val SLOT_SEARCH_EMPTY = "search.empty"

enum class SlotKind { Link, Action }

/** A row that passed: what the slot renders, its address resolved. */
data class SlotButton(
    val key: String,
    val kind: SlotKind,
    val label: String,
    /** A link's address, or the URL an action POSTs to; absolute either way. */
    val url: String,
)

/** The portal's app proxy: /api/portal/apps/<key>/… reaches an addon's own
 *  backend, forwarding the viewer's bearer for the addon to authorise. */
const val PORTAL_APP_PROXY = "/api/portal/apps/"

/** The origin of the server whose API is at [apiBaseUrl] — what a slot row
 *  resolves against; null for an address that is no http(s) URL. */
fun serverOrigin(apiBaseUrl: String): HttpUrl? =
    apiBaseUrl.trim().toHttpUrlOrNull()?.newBuilder()
        ?.username("")
        ?.password("")
        ?.encodedPath("/")
        ?.query(null)
        ?.fragment(null)
        ?.build()

/** {var} tokens in a slot URL, replaced by the value encoded as a URI
 *  component, as encodeURIComponent does; an unknown name: "". */
fun substitute(url: String, vars: Map<String, String>): String =
    VAR.replace(url) { m -> vars[m.groupValues[1]]?.let(::encodeUriComponent).orEmpty() }

/** The two kinds the TV renders; anything else (an unknown or empty kind) is
 *  not something it knows how to show, so it shows nothing. */
fun slotKind(raw: JsonElement?): SlotKind? = when (raw.stringOrNull()?.trim()?.lowercase()) {
    "link" -> SlotKind.Link
    "action" -> SlotKind.Action
    else -> null
}

/**
 * The address a slot `link` row may show, or null when it may show none: a
 * page of the server at [origin], given as a path or as an absolute http(s)
 * URL on that origin, after its {q} and the like are substituted. Anything
 * else — a javascript: or data: URL, another host, scheme or port, a
 * protocol-relative "//host", a URL with credentials, an address relative to
 * a page — renders no button at all.
 */
fun slotLinkUrl(raw: JsonElement?, origin: HttpUrl, vars: Map<String, String> = emptyMap()): String? =
    resolve(raw, origin, vars)?.toString()

/**
 * The URL a slot `action` row may POST to, or null. An action sends the
 * person's bearer, so it goes only to the portal's app proxy on the server's
 * own origin, and only as a POST (no method means POST): the bearer never
 * leaves the origin, and a row cannot spend it on chino-api's or the portal's
 * own endpoints.
 */
fun slotActionUrl(
    raw: JsonElement?,
    method: JsonElement?,
    origin: HttpUrl,
    vars: Map<String, String> = emptyMap(),
): String? {
    if (method != null && method !is JsonNull && method.stringOrNull() != "") {
        if (method.stringOrNull()?.trim()?.uppercase() != "POST") return null
    }
    val u = resolve(raw, origin, vars) ?: return null
    // An encoded separator may become a real one behind a proxy.
    if (ENCODED_SEPARATOR.containsMatchIn(u.encodedPath)) return null
    // The path is already normalised: "..", "%2e%2e" are resolved away.
    if (!APP_PROXY_PATH.containsMatchIn(u.encodedPath)) return null
    return u.toString()
}

/** The rows of a slot (chino-api's answer, as it came) the TV can render, in
 *  the order given. */
fun slotButtons(rows: JsonElement?, origin: HttpUrl, vars: Map<String, String> = emptyMap()): List<SlotButton> {
    if (rows !is JsonArray) return emptyList()
    return rows.mapIndexedNotNull { i, element ->
        val row = element as? JsonObject ?: return@mapIndexedNotNull null
        if (!row["enabled"].isTrue()) return@mapIndexedNotNull null
        val label = row["label"].stringOrNull()?.trim().orEmpty()
        if (label.isEmpty()) return@mapIndexedNotNull null
        val kind = slotKind(row["kind"]) ?: return@mapIndexedNotNull null
        val url = when (kind) {
            SlotKind.Link -> slotLinkUrl(row["url"], origin, vars)
            SlotKind.Action -> slotActionUrl(row["url"], row["method"], origin, vars)
        } ?: return@mapIndexedNotNull null
        SlotButton(
            key = row["key"].stringOrNull()?.takeIf { it.isNotEmpty() } ?: "row-$i",
            kind = kind,
            label = label,
            url = url,
        )
    }
}

/**
 * Sends a slot `action` row: a POST with an empty body and the signed-in
 * person's bearer to a URL [slotActionUrl] passed. [client] follows no
 * redirect ([cloud.nalet.chino.tv.data.api.RetrofitFactory.actionClient]): a
 * redirect could carry the bearer somewhere the URL check never saw, so it
 * counts as a failure, as on chino-web (redirect: 'error').
 */
class SlotActions(private val client: OkHttpClient) {
    /** True when the addon took it (a 2xx). Never throws but for cancellation. */
    suspend fun send(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

/** The URL a row names, resolved; null unless it is a path or an absolute
 *  http(s) URL that stays on [origin], without credentials. */
private fun resolve(raw: JsonElement?, origin: HttpUrl, vars: Map<String, String>): HttpUrl? {
    val url = raw.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val spelled = substitute(url, vars)
    // A path, or an absolute http(s) URL: nothing relative to a page, and no
    // protocol-relative "//host" (which a backslash spells too).
    if (!PATH_OR_ABSOLUTE.containsMatchIn(spelled)) return null
    val u = origin.resolve(spelled) ?: return null
    if (u.scheme != origin.scheme || u.host != origin.host || u.port != origin.port) return null
    if (u.username.isNotEmpty() || u.password.isNotEmpty()) return null
    return u
}

private val VAR = Regex("""\{(\w+)\}""")
private val PATH_OR_ABSOLUTE = Regex("""^(/(?![/\\])|https?://)""", RegexOption.IGNORE_CASE)
private val ENCODED_SEPARATOR = Regex("%2f|%5c", RegexOption.IGNORE_CASE)
private val APP_PROXY_PATH = Regex("^/api/portal/apps/[^/]+(/|$)")

/** What encodeURIComponent leaves as it is. */
private const val URI_COMPONENT_KEPT =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
private const val HEX = "0123456789ABCDEF"

/** [s] as encodeURIComponent writes it: UTF-8, every byte but the kept ASCII
 *  characters percent-encoded. */
internal fun encodeUriComponent(s: String): String = buildString {
    for (byte in s.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        if (b < 0x80 && URI_COMPONENT_KEPT.indexOf(b.toChar()) >= 0) {
            append(b.toChar())
        } else {
            append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
        }
    }
}

/** A JSON string's text; null for anything else (a number, null, an object). */
internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** True for JSON `true` alone — not the string "true", not 1. */
private fun JsonElement?.isTrue(): Boolean =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
