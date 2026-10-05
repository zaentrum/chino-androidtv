package cloud.nalet.chino.tv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Calendar
import java.util.TimeZone

/*
 * Notices: what addons tell the signed-in person — "your title is ready".
 * portal-api keeps each person's for that person alone; chino-api's
 * /v1/notices routes forward the bearer and keep nothing. What a notice says
 * is its addon's plain text, shown as text and never as markup. A TV shows no
 * web page, so a notice's link is not followed here — the text alone is
 * shown — and its itemId opens the title's detail screen, whose item routes
 * hold a capped viewer to their cap. zaentrum-portal's src/lib/notices.ts
 * reads them the same way.
 */

/** One notice as chino-api lists it. [link] and [itemId] are "" for none,
 *  [readAt] is null while unread; times are RFC 3339, UTC. */
data class Notice(
    val id: String,
    val addon: String,
    /** The addon's app: whom the notice is from. */
    val addonTitle: String,
    val addonIcon: String,
    val title: String,
    val body: String,
    val link: String,
    val itemId: String,
    val createdAt: String,
    val readAt: String?,
) {
    val isUnread: Boolean get() = readAt == null
}

/** The person's notices, newest first, and how many are unread. [available]
 *  is false when portal-api did not answer: the list is empty then, and says
 *  nothing about their notices. */
data class NoticeList(
    val notices: List<Notice> = emptyList(),
    val unread: Int = 0,
    val available: Boolean = false,
)

/** How often the bell asks again while it is shown: the portal's minute. */
const val NOTICE_POLL_MS = 60_000L

/** GET /v1/notices as it came: what is no notice — no id, no title — is left
 *  out, a missing or mistyped field is empty, and the unread count is never
 *  below the unread notices listed. Anything but an object, or an answer
 *  that does not say portal-api answered, is not available. */
fun noticeListOf(raw: JsonElement?): NoticeList {
    val doc = raw as? JsonObject ?: return NoticeList()
    val notices = (doc["notices"] as? JsonArray).orEmpty().mapNotNull { element ->
        val n = element as? JsonObject ?: return@mapNotNull null
        val id = n["id"].stringOrNull().orEmpty()
        val title = n["title"].stringOrNull().orEmpty()
        if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
        Notice(
            id = id,
            addon = n["addon"].stringOrNull().orEmpty(),
            addonTitle = n["addonTitle"].stringOrNull().orEmpty(),
            addonIcon = n["addonIcon"].stringOrNull().orEmpty(),
            title = title,
            body = n["body"].stringOrNull().orEmpty(),
            link = n["link"].stringOrNull().orEmpty(),
            itemId = n["itemId"].stringOrNull().orEmpty(),
            createdAt = n["createdAt"].stringOrNull().orEmpty(),
            readAt = n["readAt"].stringOrNull()?.takeIf { it.isNotEmpty() },
        )
    }
    val listed = notices.count { it.isUnread }
    val unread = doc["unread"].literalOrNull()?.intOrNull?.takeIf { it >= 0 } ?: listed
    return NoticeList(
        notices = notices,
        unread = maxOf(unread, listed),
        available = doc["available"].literalOrNull()?.booleanOrNull == true,
    )
}

/** The unread count on the bell: nothing at none, 99+ past 99. */
fun badgeText(unread: Int): String = when {
    unread <= 0 -> ""
    unread > 99 -> "99+"
    else -> unread.toString()
}

/** What the bell is called to a screen reader. */
fun bellLabel(unread: Int): String = if (unread > 0) "Notices, $unread unread" else "Notices"

/** Whom a notice is from: its addon's title, else its key. */
fun fromText(n: Notice): String = n.addonTitle.trim().ifEmpty { n.addon.ifEmpty { "An addon" } }

/**
 * A notice's text as the TV shows it, plain: no control characters and none
 * of the formatting characters that turn text around — portal-api refuses
 * them, the TV drops any that get through. A body ([lines]) keeps its line
 * breaks; a title is one line.
 */
fun plainText(s: String, lines: Boolean): String = buildString {
    for (c in s.replace("\r\n", "\n")) {
        when {
            c == '\n' -> append(if (lines) '\n' else ' ')
            c == '\t' -> append(' ')
            c.isISOControl() || c in '‪'..'‮' || c in '⁦'..'⁩' -> Unit
            else -> append(c)
        }
    }
}.trim()

/** The catalog item a notice opens, or null: an id as portal-api keeps one
 *  — letters, digits and . _ : -, at most 128, a letter or digit first. */
fun noticeItemId(n: Notice): String? = n.itemId.trim().takeIf { NOTICE_ITEM.matches(it) }

/** True for an id chino-api forwards a change of: letters, digits and dashes
 *  (portal-api's are UUIDs). Any other is no notice of the person's. */
fun isNoticeId(id: String): Boolean = NOTICE_ID.matches(id)

/** The list once a notice is read, at [at]. */
fun markRead(list: NoticeList, id: String, at: String): NoticeList {
    var changed = 0
    val notices = list.notices.map { n ->
        if (n.id != id || !n.isUnread) n else n.copy(readAt = at).also { changed++ }
    }
    return list.copy(notices = notices, unread = maxOf(0, list.unread - changed))
}

/** The list once every notice is read, at [at]. */
fun markAllRead(list: NoticeList, at: String): NoticeList =
    list.copy(notices = list.notices.map { if (it.isUnread) it.copy(readAt = at) else it }, unread = 0)

/** The list without a notice. */
fun removeNotice(list: NoticeList, id: String): NoticeList {
    val gone = list.notices.firstOrNull { it.id == id }
    return list.copy(
        notices = list.notices.filter { it.id != id },
        unread = maxOf(0, list.unread - if (gone?.isUnread == true) 1 else 0),
    )
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** When a notice came, short: now, 5m, 3h, 2d — and from a week on its date
 *  in [zone], as the profile's history writes one ("Sep 12"). "" for a time
 *  that is none. */
fun ageText(createdAt: String, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val t = rfc3339Millis(createdAt) ?: return ""
    val ago = now - t
    return when {
        ago < MINUTE -> "now"
        ago < HOUR -> "${ago / MINUTE}m"
        ago < DAY -> "${ago / HOUR}h"
        ago < 7 * DAY -> "${ago / DAY}d"
        else -> Calendar.getInstance(zone).run {
            timeInMillis = t
            "${MONTHS[get(Calendar.MONTH)]} ${get(Calendar.DAY_OF_MONTH)}"
        }
    }
}

private val RFC3339 =
    Regex("""(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(?:([Zz])|([+-])(\d{2}):(\d{2}))""")

/** An RFC 3339 time ("2026-10-05T07:58:00.123456Z", "…+02:00") as epoch
 *  milliseconds; null for anything else. By hand: java.time is not there
 *  before API 26, and the app runs from 21. */
internal fun rfc3339Millis(s: String): Long? {
    val g = RFC3339.matchEntire(s.trim())?.groupValues ?: return null
    val year = g[1].toInt()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
    val millis = g[7].take(3).padEnd(3, '0').toInt()
    val offsetMinutes = if (g[8].isNotEmpty()) 0 else {
        val sign = if (g[9] == "-") -1 else 1
        sign * (g[10].toInt() * 60 + g[11].toInt())
    }
    val seconds = daysFromCivil(year, month, day) * 86_400L +
        hour * 3_600L + minute * 60L + minOf(second, 59) - offsetMinutes * 60L
    return seconds * 1_000L + millis
}

/** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's
 *  days_from_civil). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

private val NOTICE_ITEM = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val NOTICE_ID = Regex("[A-Za-z0-9-]{1,64}")

/** A JSON number, boolean or null — not a string that spells one. */
private fun JsonElement?.literalOrNull(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }
