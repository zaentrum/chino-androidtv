package cloud.nalet.chino.tv.ui.player

/*
 * The attribute list of an HLS tag, as the player's master rewrites read and
 * change it (ManifestSubtitles.kt, SurroundAudio.kt). Pure.
 */

/** An attribute of an HLS tag: its value (unquoted), and its span in the
 *  line from the key to the end of the value. */
internal class HlsAttribute(val key: String, val value: String, val start: Int, val end: Int)

/** The attribute list after a tag's ":", as RFC 8216 writes it: KEY=VALUE
 *  pairs split by commas, a quoted value possibly holding commas. */
internal fun hlsAttributes(line: String): List<HlsAttribute> {
    val out = ArrayList<HlsAttribute>()
    var i = line.indexOf(':') + 1
    if (i == 0) return out
    while (i < line.length) {
        val eq = line.indexOf('=', i)
        if (eq < 0) break
        val key = line.substring(i, eq).trim()
        val end: Int
        val value: String
        if (eq + 1 < line.length && line[eq + 1] == '"') {
            val close = line.indexOf('"', eq + 2).let { if (it < 0) line.length else it }
            value = line.substring(eq + 2, close)
            end = minOf(close + 1, line.length)
        } else {
            val comma = line.indexOf(',', eq + 1).let { if (it < 0) line.length else it }
            value = line.substring(eq + 1, comma).trimEnd('\r')
            end = comma
        }
        out += HlsAttribute(key, value, i, end)
        if (end >= line.length || line[end] != ',') break
        i = end + 1
    }
    return out
}

/** The line without [a] and the comma that joined it to the list. */
internal fun String.withoutAttribute(a: HlsAttribute): String = when {
    a.end < length && this[a.end] == ',' -> removeRange(a.start, a.end + 1)
    a.start > 0 && this[a.start - 1] == ',' -> removeRange(a.start - 1, a.end)
    else -> removeRange(a.start, a.end)
}

/** The line with the enumerated attribute [key] (an unquoted one: DEFAULT,
 *  AUTOSELECT) set to [value]: in its place where the line has it, else at the
 *  end of the list, before a CR the line ends with. */
internal fun String.withEnumAttribute(key: String, value: String): String {
    val cr = endsWith('\r')
    val a = hlsAttributes(this).firstOrNull { it.key == key }
    if (a != null) {
        // The last value of a CRLF line spans its CR: the CR stays.
        val end = if (cr && a.end == length) a.end - 1 else a.end
        return replaceRange(a.start, end, "$key=$value")
    }
    return (if (cr) dropLast(1) else this) + ",$key=$value" + (if (cr) "\r" else "")
}
