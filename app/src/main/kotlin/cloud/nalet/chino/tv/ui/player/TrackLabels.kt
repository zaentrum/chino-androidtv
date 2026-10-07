package cloud.nalet.chino.tv.ui.player

import java.util.Locale

/*
 * What the audio and subtitle menus call a track. Pure: TrackLabelsTest runs
 * it on the JVM.
 *
 * A track is named by the language it is tagged with first, in English
 * ("German"): "zxx" - no linguistic content, the audio of a film without
 * dialogue - is "No dialogue", "mul" "Multiple languages", "mis" (a language
 * with no code) "Other language", none ("und", nothing) is "Unknown". What the
 * track calls itself - the rendition's NAME, the sidecar's label - comes
 * after: on a subtitle where it says more than the language ("English ·
 * SDH"), on audio where it tells two of one language apart ("English ·
 * Commentary"), and alone where the track names no language. A NAME that only
 * describes the source's format ("AC3 5.1 @ 640 Kbps"), numbers the track
 * ("Track 1") or is a code is no name. chino-web's rules (lib/languages.ts),
 * as the mobile app has them.
 */

/** What a track tagged "zxx" (no linguistic content) is called. */
const val NO_DIALOGUE = "No dialogue"

/** What a track is called whose language is not known ("und", none). */
const val UNKNOWN_LANGUAGE = "Unknown"

/** Codes that name no language one could read or listen to. */
private val NO_LANGUAGE = setOf("und", "zxx", "mul", "mis")

// The codes for no one language that still say what a track is in, and what
// such a track is called: no dialogue ("zxx"), several languages ("mul"), a
// language ISO 639 has no code for ("mis").
private val NOT_ONE_LANGUAGE = mapOf(
    "zxx" to NO_DIALOGUE,
    "mul" to "Multiple languages",
    "mis" to "Other language",
)

private val PRIMARY = Regex("^[a-z]{2,3}$")

/** The tag's language subtag, lower-cased ("pt" of "PT_br"); "" for none. */
private fun primarySubtag(tag: String?): String =
    tag?.trim()?.lowercase()?.split('-', '_')?.firstOrNull().orEmpty()

/** Whether the tag is "zxx": no linguistic content, no dialogue. */
fun isNoDialogue(tag: String?): Boolean = primarySubtag(tag) == "zxx"

/** The language a tag names, as [languageKey] has it ("de" of "ger"); null
 *  for none, "und", "zxx" or what is not a code. */
private fun languageOf(tag: String?): String? =
    languageKey(tag)?.takeIf { PRIMARY.matches(it) && it !in NO_LANGUAGE }

/** Whether the tag says what the track is in: a language, no dialogue,
 *  several languages or one with no code. */
private fun hasLanguage(tag: String?): Boolean =
    languageOf(tag) != null || primarySubtag(tag) in NOT_ONE_LANGUAGE

/** The language's English name ("ger", "de", "deu" -> "German"); the tag as
 *  it came when there is no name for it; "No dialogue" for "zxx", "Multiple
 *  languages" for "mul", "Other language" for "mis", "Unknown" for none. */
fun languageName(tag: String?): String {
    NOT_ONE_LANGUAGE[primarySubtag(tag)]?.let { return it }
    val key = languageOf(tag) ?: return UNKNOWN_LANGUAGE
    val name = Locale.forLanguageTag(key).getDisplayLanguage(Locale.ENGLISH)
    return if (name.isBlank() || name.equals(key, ignoreCase = true)) tag.orEmpty().trim() else name
}

/** A track's language by name ("German", "No dialogue" for zxx), else its own
 *  label for a track in no language; null for neither. What a title's
 *  subtitle languages are listed as. */
fun languageOrLabel(tag: String?, label: String?): String? =
    if (hasLanguage(tag)) languageName(tag) else label?.trim()?.takeIf { it.isNotEmpty() }

private val CODE_LIKE = Regex("^[a-z]{2,3}([-_][a-z0-9]+)*$", RegexOption.IGNORE_CASE)

/** A label that is only a language code: the track's own again ("eng" on
 *  English), or one that names no language ("und"). */
private fun sameLanguageCode(label: String, tag: String?): Boolean {
    if (!CODE_LIKE.matches(label)) return false
    val named = languageOf(label)
    return named == null || named == languageOf(tag)
}

/** The labels, the second and later of the ones that read the same numbered
 *  ("German (2)"). [key] says which read the same: by default the label. */
private fun numbered(labels: List<String>, key: (String, Int) -> String = { label, _ -> label }): List<String> {
    val seen = HashMap<String, Int>()
    return labels.mapIndexed { i, label ->
        val k = key(label, i)
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        if (n == 1) label else "$label ($n)"
    }
}

/** What a subtitle menu is told about one track. */
data class SubtitleLabelInput(
    /** The language tag as the track has it. */
    val lang: String?,
    /** Its own label: the sidecar's, or the rendition's NAME. */
    val title: String? = null,
    val forced: Boolean = false,
)

/**
 * The subtitle menu's labels, one per track, in order: the language's name
 * ("German", "No dialogue" for zxx), the track's own label when it says more
 * than that ("English · SDH"), "(forced)" for a forced track; a track tagged
 * with no language by its own label, else "Unknown"; and where two would
 * still read the same, a number for the second and later ones ("German (2)").
 */
fun subtitleLabels(tracks: List<SubtitleLabelInput>): List<String> {
    val labels = tracks.map { t ->
        val name = languageName(t.lang)
        val title = t.title?.trim().orEmpty()
        var label = name
        if (title.isNotEmpty() && !title.equals(name, ignoreCase = true) && !sameLanguageCode(title, t.lang)) {
            label = when {
                !hasLanguage(t.lang) -> title
                title.contains(name, ignoreCase = true) -> title
                else -> "$name · $title"
            }
        }
        if (t.forced && !label.contains("forced", ignoreCase = true)) label += " (forced)"
        label
    }
    return numbered(labels)
}

/** The codec a track's row shows: its codecs string ("mp4a.40.2"), else the
 *  one of its sample format. Media3 leaves a track's codecs unset where it
 *  cannot tell which of a variant's audio codecs is the track's: the 5.1
 *  E-AC-3 companions of a group that holds stereo AAC as well ("ec-3"). */
fun audioCodec(codecs: String?, sampleMimeType: String?): String? =
    codecs?.trim()?.takeIf { it.isNotEmpty() } ?: when (sampleMimeType) {
        "audio/eac3", "audio/eac3-joc" -> "ec-3"
        "audio/ac3" -> "ac-3"
        "audio/ac4" -> "ac-4"
        else -> null
    }

/** What an audio menu is told about one track. */
data class AudioLabelInput(
    /** The language tag as the track has it. */
    val lang: String?,
    /** What the track is called: the rendition's NAME. */
    val name: String? = null,
    /** What the menu shows beside the label ("Stereo · mp4a.40.2"). Two
     *  tracks that differ there are told apart there. */
    val detail: String? = null,
)

// A name that describes the source's audio format - a codec, a bitrate, a
// sample rate or depth ("AC3 5.1 @ 640 Kbps", "DTS-HD MA 5.1") - and so
// nothing of the track.
private val FORMAT_WORDS = Regex(
    """(^|[^A-Za-z0-9])(dts(-hd)?|truehd|atmos|dolby|e?-?ac-?3|ddp?\+?|aac|flac|l?pcm|opus|mp3|vorbis|lossless|master audio|\d+ ?k?hz|\d* ?[km]bps|kb/s|\d+[- ]?bit)(?![A-Za-z0-9])""",
    RegexOption.IGNORE_CASE,
)

// A channel layout, which goes from a name that names the track ("Commentary
// 5.1" is "Commentary"): the menu shows the channels beside it.
private val LAYOUT_WORDS = Regex(
    """(^|[^A-Za-z0-9.])(mono|stereo|surround|[1-9]\.[0-2]|\d{1,2} ?ch(annels?)?)(?![A-Za-z0-9.])""",
    RegexOption.IGNORE_CASE,
)

// A name that only numbers the track ("Track 2", "Audio Track 1", "2").
private val NUMBERED = Regex("""^(audio|sound|track|stream|[\s#])*\d*$""", RegexOption.IGNORE_CASE)

private val EMPTY_BRACKETS = Regex("""\(\s*\)|\[\s*]""")
private val SPACES = Regex("""\s+""")
private val EDGE_SEPARATORS = Regex("""^[\s\-–—:·,|/]+|[\s\-–—:·,|/]+$""")
private val LEADING_SEPARATORS = Regex("""^[\s\-–—:·,|]+""")
private val QUALIFIER_START = Regex("""^([\s\-–—:·,|(\[]|$)""")

/** What a track's name says about it, or "" when it says nothing: none, a
 *  format, a number, a language code. */
private fun trackName(name: String?, tag: String?): String {
    val raw = name?.trim().orEmpty()
    if (raw.isEmpty() || FORMAT_WORDS.containsMatchIn(raw)) return ""
    val t = raw.replace(LAYOUT_WORDS, "$1")
        .replace(EMPTY_BRACKETS, "")
        .replace(SPACES, " ")
        .replace(EDGE_SEPARATORS, "")
    if (t.isEmpty() || NUMBERED.matches(t) || sameLanguageCode(t, tag)) return ""
    return t
}

/** What a track's name adds to its label ("English Commentary" on an English
 *  track: "Commentary"); "" when nothing. */
private fun nameQualifier(name: String?, label: String, tag: String?): String {
    var t = trackName(name, tag)
    if (t.startsWith(label, ignoreCase = true) && QUALIFIER_START.containsMatchIn(t.substring(label.length))) {
        t = t.substring(label.length).replace(LEADING_SEPARATORS, "").trim()
        if ((t.startsWith("(") && t.endsWith(")")) || (t.startsWith("[") && t.endsWith("]"))) {
            t = t.substring(1, t.length - 1).trim()
        }
    }
    return if (t.isNotEmpty() && !NUMBERED.matches(t)) t else ""
}

/**
 * The audio menu's labels, one per track, in order: the language the track is
 * tagged with, by name ("German"; "No dialogue" for zxx). A track tagged with
 * none is called what its name says ("Commentary"), else "Unknown". Two that
 * would read the same, with the same detail, are told apart by their names
 * ("English · Commentary"), else numbered ("English (2)").
 */
fun audioLabels(tracks: List<AudioLabelInput>): List<String> {
    val bases = tracks.map { t ->
        if (hasLanguage(t.lang)) {
            val name = languageName(t.lang)
            // No name for the code: the track's own name before the code.
            if (name != t.lang.orEmpty().trim()) return@map name
        }
        trackName(t.name, t.lang).ifEmpty { if (hasLanguage(t.lang)) t.lang.orEmpty().trim() else UNKNOWN_LANGUAGE }
    }
    val key = { label: String, i: Int -> label + "\u0000" + tracks[i].detail.orEmpty() }
    val count = HashMap<String, Int>()
    bases.forEachIndexed { i, b -> count[key(b, i)] = (count[key(b, i)] ?: 0) + 1 }
    val labels = bases.mapIndexed { i, b ->
        if ((count[key(b, i)] ?: 0) < 2) return@mapIndexed b
        val q = nameQualifier(tracks[i].name, b, tracks[i].lang)
        if (q.isNotEmpty() && !q.equals(b, ignoreCase = true)) "$b · $q" else b
    }
    return numbered(labels, key)
}
