package cloud.nalet.chino.tv.ui.player

/*
 * Which audio track of a master with 5.1 tracks the player starts on. Pure:
 * SurroundAudioTest runs it on the JVM.
 *
 * A TV whose caps name eac3 (CodecCaps) is served one audio group in which
 * each 5.1 E-AC-3 companion stands just before the stereo rendition of its
 * source track - "English 5.1", then "English" - and the companion of the
 * stereo default is the DEFAULT. Media3 starts on the DEFAULT track of the
 * language it is asked for. Where the TV's output takes 5.1 (an E-AC-3
 * decoder or receiver behind it, as Media3's AudioCapabilities read the
 * route), that is the 5.1 track, as served; in another language the player
 * prefers the surround codecs (preferredAudioMimeTypes), its 5.1 track too.
 * Where the output is stereo - the TV decodes E-AC-3, but to two channels -
 * the stereo twin is the default instead ([withStereoDefault]), and the
 * player keeps to two channels where it picks itself (maxAudioChannelCount),
 * so another language starts in stereo too. The 5.1 tracks are in the audio
 * menu either way.
 */

/** The number of channels an EXT-X-MEDIA's CHANNELS names ("6", "16/JOC");
 *  null when it names none. */
fun channelCount(channels: String?): Int? = channels?.substringBefore('/')?.trim()?.toIntOrNull()

/** An AUDIO rendition of a master: its line, group, language, channels
 *  (null: not said) and whether it is the group's DEFAULT. */
private class AudioRendition(
    val line: Int,
    val group: String,
    val language: String?,
    val channels: Int?,
    val isDefault: Boolean,
)

/**
 * [master] for a TV whose output is stereo: in each audio group whose
 * DEFAULT rendition has more than two channels, the DEFAULT goes to its
 * stereo twin - the next rendition of the group in its language with two
 * channels or fewer (or none said), else the first such one of the group.
 * A group whose default is stereo, or whose 5.1 default has no twin, stays
 * as it is; a master with nothing to move comes back as it is.
 */
fun withStereoDefault(master: String): String {
    val lines = master.split('\n')
    val renditions = lines.mapIndexedNotNull { i, line ->
        if (!line.startsWith("#EXT-X-MEDIA:")) return@mapIndexedNotNull null
        val a = hlsAttributes(line).associate { it.key to it.value }
        if (a["TYPE"] != "AUDIO") return@mapIndexedNotNull null
        AudioRendition(i, a["GROUP-ID"].orEmpty(), a["LANGUAGE"], channelCount(a["CHANNELS"]), a["DEFAULT"] == "YES")
    }
    // Line → the DEFAULT it gets.
    val moves = HashMap<Int, Boolean>()
    for (group in renditions.groupBy { it.group }.values) {
        val surround = group.firstOrNull { it.isDefault } ?: continue
        if ((surround.channels ?: 0) <= 2) continue
        val language = languageKey(surround.language)
        val stereo = group.filter { (it.channels ?: 2) <= 2 && languageKey(it.language) == language }
        val twin = stereo.firstOrNull { it.line > surround.line } ?: stereo.firstOrNull() ?: continue
        moves[surround.line] = false
        moves[twin.line] = true
    }
    if (moves.isEmpty()) return master
    return lines.mapIndexed { i, line ->
        when (moves[i]) {
            null -> line
            // RFC 8216: a DEFAULT rendition is an AUTOSELECT one.
            true -> line.withEnumAttribute("DEFAULT", "YES").withEnumAttribute("AUTOSELECT", "YES")
            false -> line.withEnumAttribute("DEFAULT", "NO")
        }
    }.joinToString("\n")
}
