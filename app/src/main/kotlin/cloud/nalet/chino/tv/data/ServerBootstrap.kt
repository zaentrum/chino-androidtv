package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.auth.OidcDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Outcome of probing a user-entered server address. */
sealed interface BootstrapResult {
    data class Ok(val config: ServerConfig) : BootstrapResult
    data class Fail(val kind: Kind, val detail: String? = null) : BootstrapResult {
        enum class Kind { UNREACHABLE, NOT_CHINO, TLS, NO_CONFIG, DEVICE_GRANT_UNSUPPORTED }
    }
}

/**
 * Turns a typed server address into a ready [ServerConfig] by probing the
 * server: confirm it is reachable and is a Chino server (/api/healthz), read
 * its self-describing config (/api/config) for the OIDC issuer + client id,
 * then run OIDC discovery against that issuer. Distinct failure kinds let the
 * Add-Server UI show actionable errors instead of a generic stack trace.
 */
object ServerBootstrap {
    private val http = OkHttpClient.Builder()
        // Short connect timeout: probe() tries up to TWO candidates (https then
        // http) for a bare host, so 6s keeps an unreachable address under ~12s
        // total instead of ~20s.
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Trims, defaults the scheme to https, strips a trailing slash. Used for
     * the recents key and same-server comparison; [probe] derives its own
     * candidate set (which may also try http) via [candidates].
     */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s.trimEnd('/')
    }

    /**
     * Candidate base origins to probe, in priority order, each trailing-slash
     * trimmed. If the raw input carries no scheme we infer one and try https
     * THEN http (most self-host servers are https, but a LAN box may only
     * speak plain http on :80 or a custom port). If a scheme is present we
     * honour it first, then try the other scheme as a fallback.
     */
    fun candidates(raw: String): List<String> {
        val s = raw.trim()
        if (s.isEmpty()) return emptyList()
        return when {
            s.startsWith("https://") -> {
                val host = s.removePrefix("https://")
                listOf("https://$host", "http://$host")
            }
            s.startsWith("http://") -> {
                val host = s.removePrefix("http://")
                listOf("http://$host", "https://$host")
            }
            else -> listOf("https://$s", "http://$s")
        }.map { it.trimEnd('/') }.distinct()
    }

    suspend fun probe(rawUrl: String): BootstrapResult = withContext(Dispatchers.IO) {
        val bases = candidates(rawUrl)
        if (bases.isEmpty()) {
            return@withContext BootstrapResult.Fail(BootstrapResult.Fail.Kind.UNREACHABLE, "empty URL")
        }
        // Try each candidate; return the FIRST that reaches a real Chino server.
        // If none succeed, keep the most informative failure — a candidate that
        // CONNECTED but wasn't quite right (NOT_CHINO / NO_CONFIG / TLS / device
        // flow) tells the user more than a plain UNREACHABLE.
        // If the user typed an explicit scheme, honor it: only fall back to the
        // other scheme when the typed one was UNREACHABLE. A scheme that
        // CONNECTED but was wrong/unconfigured (NOT_CHINO/NO_CONFIG/TLS/…) is the
        // intended endpoint — surface that immediately rather than waste a
        // round-trip on the other scheme (and don't silently downgrade an
        // https cert error to http).
        val explicitScheme = rawUrl.trim().let { it.startsWith("http://") || it.startsWith("https://") }
        var best: BootstrapResult.Fail? = null
        for ((i, base) in bases.withIndex()) {
            when (val r = probeBase(base)) {
                is BootstrapResult.Ok -> return@withContext r
                is BootstrapResult.Fail -> {
                    best = moreInformative(best, r)
                    if (explicitScheme && i == 0 && r.kind != BootstrapResult.Fail.Kind.UNREACHABLE) {
                        return@withContext r
                    }
                }
            }
        }
        best ?: BootstrapResult.Fail(BootstrapResult.Fail.Kind.UNREACHABLE, bases.first())
    }

    /** Runs the healthz -> /api/config -> OIDC-discovery sequence on one base. */
    private suspend fun probeBase(base: String): BootstrapResult {
        val apiBase = "$base/api"

        // 1) reachability + "is this a Chino server?"
        val health = when (val r = getJson("$apiBase/healthz", Health.serializer())) {
            is Fetch.Ok -> r.value
            is Fetch.Tls -> return BootstrapResult.Fail(BootstrapResult.Fail.Kind.TLS, r.detail)
            is Fetch.Err -> return BootstrapResult.Fail(BootstrapResult.Fail.Kind.UNREACHABLE, base)
        }
        if (health?.product != "chino") {
            return BootstrapResult.Fail(BootstrapResult.Fail.Kind.NOT_CHINO, health?.product)
        }

        // 2) self-describing bootstrap config
        val cfg = when (val r = getJson("$apiBase/config", AppConfigDoc.serializer())) {
            is Fetch.Ok -> r.value
            else -> null
        }
        val issuer = cfg?.oidcIssuer
            ?: return BootstrapResult.Fail(BootstrapResult.Fail.Kind.NO_CONFIG, "$apiBase/config")
        val clientId = cfg.oidcClientId?.tv ?: "chino"

        // 3) OIDC discovery against the advertised issuer
        val ep = OidcDiscovery.discover(issuer)
            ?: return BootstrapResult.Fail(BootstrapResult.Fail.Kind.DEVICE_GRANT_UNSUPPORTED, issuer)

        return BootstrapResult.Ok(
            ServerConfig(
                // Trailing slash matches the API_BASE_URL convention Retrofit expects.
                baseUrl = "$apiBase/",
                issuer = ep.issuer,
                clientId = clientId,
                deviceAuthEndpoint = ep.deviceAuthEndpoint,
                tokenEndpoint = ep.tokenEndpoint,
                userinfoEndpoint = ep.userinfoEndpoint,
            ),
        )
    }

    /**
     * Picks the failure that better explains what went wrong. A candidate that
     * connected (anything other than UNREACHABLE) beats one that didn't; among
     * connected ones we keep the first seen.
     */
    private fun moreInformative(
        current: BootstrapResult.Fail?,
        next: BootstrapResult.Fail,
    ): BootstrapResult.Fail {
        if (current == null) return next
        val currentConnected = current.kind != BootstrapResult.Fail.Kind.UNREACHABLE
        val nextConnected = next.kind != BootstrapResult.Fail.Kind.UNREACHABLE
        return if (!currentConnected && nextConnected) next else current
    }

    private sealed interface Fetch<out T> {
        data class Ok<T>(val value: T?) : Fetch<T>
        data class Tls(val detail: String?) : Fetch<Nothing>
        data class Err(val detail: String?) : Fetch<Nothing>
    }

    private fun <T> getJson(url: String, serializer: KSerializer<T>): Fetch<T> {
        val req = Request.Builder().url(url).get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Fetch.Err("HTTP ${resp.code}")
                Fetch.Ok(json.decodeFromString(serializer, resp.body?.string().orEmpty()))
            }
        } catch (e: SSLException) {
            Fetch.Tls(e.message)
        } catch (e: Exception) {
            Fetch.Err(e.message)
        }
    }
}

@Serializable
private data class Health(val status: String? = null, val product: String? = null)

@Serializable
private data class AppConfigDoc(
    val oidcIssuer: String? = null,
    val oidcAudience: String? = null,
    val oidcClientId: ClientIds? = null,
)

@Serializable
private data class ClientIds(val tv: String? = null, val mobile: String? = null, val web: String? = null)
