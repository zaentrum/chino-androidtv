package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.SidecarSubtitle
import java.util.Locale

/*
 * A master's SUBTITLES renditions beside the sidecars. Pure:
 * ManifestSubtitlesTest runs it on the JVM.
 *
 * The player shows the sidecar subtitles of /v1/items/{id}/subtitles, which
 * Media3 merges in as tracks of their own (PGS and the other bitmap formats
 * among them). A packaged title's master can also carry a SUBTITLES group —
 * chino-stream passes on the packager's HLS_SUBTITLES renditions, one per
 * WebVTT sidecar — and Media3 lists those beside the sidecars: every
 * language twice in the menu, a preferred language matched to whichever came
 * first (the master's), and a FORCED rendition in the language of the audio
 * switching itself on. So the master is handed to Media3 without the
 * renditions the sidecars already have — same language, same kind (regular
 * or SDH) — and without the forced ones. A rendition the sidecars do not
 * have stays: with the sidecar list missing, the master's are the subtitles.
 * chino-web leaves a master's SUBTITLES alone the same way.
 */

/** What a subtitle track is for. */
enum class SubtitleKind { REGULAR, FORCED, SDH }

private val FORCED_WORD = Regex("""\bforced\b""", RegexOption.IGNORE_CASE)
private val SDH_WORD = Regex("""\b(sdh|cc|hearing impaired)\b""", RegexOption.IGNORE_CASE)

/** A sidecar's kind, from its label: the catalog names a track by its title
 *  ("Forced", "SDH"), which the packager's rendition NAMEs carry too. */
fun sidecarKind(label: String): SubtitleKind = when {
    FORCED_WORD.containsMatchIn(label) -> SubtitleKind.FORCED
    SDH_WORD.containsMatchIn(label) -> SubtitleKind.SDH
    else -> SubtitleKind.REGULAR
}

/** A SUBTITLES rendition's kind: FORCED=YES, the accessibility
 *  CHARACTERISTICS, else its NAME ("English (SDH)"). */
fun renditionKind(name: String?, forced: Boolean, characteristics: String?): SubtitleKind = when {
    forced -> SubtitleKind.FORCED
    characteristics.orEmpty().contains("public.accessibility.") -> SubtitleKind.SDH
    SDH_WORD.containsMatchIn(name.orEmpty()) -> SubtitleKind.SDH
    else -> SubtitleKind.REGULAR
}

/** Whether a SUBTITLES rendition stays in the master beside [sidecars]. */
fun keepRendition(
    language: String?,
    name: String?,
    forced: Boolean,
    characteristics: String?,
    sidecars: List<SidecarSubtitle>,
): Boolean {
    val kind = renditionKind(name, forced, characteristics)
    if (kind == SubtitleKind.FORCED) return false
    val lang = languageKey(language)
    return sidecars.none { languageKey(it.lang) == lang && sidecarKind(it.label) == kind }
}

/**
 * [master] without the SUBTITLES renditions [keepRendition] drops; a
 * variant's SUBTITLES attribute goes with a group left empty. A master with
 * nothing to drop comes back as it is.
 */
fun withoutDuplicateSubtitles(master: String, sidecars: List<SidecarSubtitle>): String {
    val lines = master.split('\n')
    val groups = HashSet<String>()
    val keptGroups = HashSet<String>()
    var dropped = false
    val out = ArrayList<String>(lines.size)
    for (line in lines) {
        if (line.startsWith("#EXT-X-MEDIA:")) {
            val a = hlsAttributes(line).associate { it.key to it.value }
            if (a["TYPE"] == "SUBTITLES") {
                val group = a["GROUP-ID"].orEmpty()
                groups += group
                val keep = keepRendition(a["LANGUAGE"], a["NAME"], a["FORCED"] == "YES", a["CHARACTERISTICS"], sidecars)
                if (!keep) {
                    dropped = true
                    continue
                }
                keptGroups += group
            }
        }
        out += line
    }
    if (!dropped) return master
    val emptied = groups - keptGroups
    return out.joinToString("\n") { line ->
        val gone = if (line.startsWith("#EXT-X-STREAM-INF:")) {
            hlsAttributes(line).firstOrNull { it.key == "SUBTITLES" && it.value in emptied }
        } else null
        if (gone == null) line else line.withoutAttribute(gone)
    }
}

/** The ISO 639-1 code of a language tag — "en" of "eng", "en-US", "EN" —
 *  or its primary subtag where there is none; null for none or "und". */
fun languageKey(tag: String?): String? {
    val primary = tag?.trim()?.lowercase()?.split('-', '_')?.firstOrNull()
        ?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
    if (primary.length != 3) return primary
    return BIBLIOGRAPHIC[primary] ?: ISO_639_2[primary] ?: primary
}

/** ISO 639-2/T codes ("deu") to ISO 639-1 ("de"). */
private val ISO_639_2: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { code ->
        runCatching { Locale(code).isO3Language }.getOrDefault(code)
    }
}

/** ISO 639-2/B codes, which Locale does not know ("ger", "fre"). */
private val BIBLIOGRAPHIC = mapOf(
    "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
    "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
    "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
    "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
)
