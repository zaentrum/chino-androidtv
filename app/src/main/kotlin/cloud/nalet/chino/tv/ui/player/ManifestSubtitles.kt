package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.SidecarSubtitle

/*
 * A master's SUBTITLES renditions beside the sidecars. Pure:
 * ManifestSubtitlesTest runs it on the JVM.
 *
 * The player shows the sidecar subtitles of /v1/items/{id}/subtitles, which
 * Media3 merges in as tracks of their own (PGS and the other bitmap formats
 * among them). A packaged title's master can also carry a SUBTITLES group —
 * chino-stream passes on the packager's HLS_SUBTITLES renditions, one per
 * WebVTT sidecar — and Media3 lists those beside the sidecars: every
 * language twice in the menu, and a preferred language matched to whichever
 * came first (the master's). So the master is handed to Media3 without the
 * renditions the sidecars already have — same language, same kind (regular,
 * SDH or forced). A rendition the sidecars do not have stays: with the
 * sidecar list missing, the master's are the subtitles, and a FORCED one is
 * the forced subtitle of its language where no sidecar is (SubtitleChoices.kt
 * says when it comes on; Media3 switches none on by itself). chino-web leaves
 * a master's SUBTITLES alone the same way.
 */

/** What a subtitle track is for. */
enum class SubtitleKind { REGULAR, FORCED, SDH }

/** A sidecar's kind, from its label: the catalog names a track by its title
 *  ("Forced", "SDH"), which the packager's rendition NAMEs carry too. What
 *  an older chino-api's sidecars are known by; see [sidecarKinds]. */
fun sidecarKind(label: String): SubtitleKind = when {
    saysForced(label) -> SubtitleKind.FORCED
    isSdhSubtitle(label) -> SubtitleKind.SDH
    else -> SubtitleKind.REGULAR
}

/**
 * The kind of each of [sidecars]. chino-api says which are forced (forced:
 * true, the catalog's) and leaves the field out of the others; once one of
 * a list says it, that is what is forced there, and a label saying "forced"
 * makes no other one so - it is SDH or regular by its label. In a list where
 * none says it - an older chino-api's, or a title without a forced track -
 * a sidecar is forced where its label says so ([sidecarKind]). The player
 * flags a forced one FORCED.
 */
fun sidecarKinds(sidecars: List<SidecarSubtitle>): List<SubtitleKind> {
    val saysForced = sidecars.any { it.forced }
    return sidecars.map { s ->
        when {
            !saysForced -> sidecarKind(s.label)
            s.forced -> SubtitleKind.FORCED
            isSdhSubtitle(s.label) -> SubtitleKind.SDH
            else -> SubtitleKind.REGULAR
        }
    }
}

/** A SUBTITLES rendition's kind: FORCED=YES, the accessibility
 *  CHARACTERISTICS, else its NAME ("English (SDH)"). */
fun renditionKind(name: String?, forced: Boolean, characteristics: String?): SubtitleKind = when {
    forced -> SubtitleKind.FORCED
    characteristics.orEmpty().contains("public.accessibility.") -> SubtitleKind.SDH
    isSdhSubtitle(name) -> SubtitleKind.SDH
    else -> SubtitleKind.REGULAR
}

/** Whether a SUBTITLES rendition stays in the master beside [sidecars]: it
 *  goes where a sidecar is the same subtitle - its language, with the script
 *  or region either names ("zh-Hant" is no "zh-Hans"; [sameLanguage]), and
 *  its kind. */
fun keepRendition(
    language: String?,
    name: String?,
    forced: Boolean,
    characteristics: String?,
    sidecars: List<SidecarSubtitle>,
): Boolean {
    val kind = renditionKind(name, forced, characteristics)
    val tag = subtitleTag(language, name).tag
    val kinds = sidecarKinds(sidecars)
    return sidecars.indices.none { kinds[it] == kind && sameLanguage(tag, subtitleTag(sidecars[it].lang, sidecars[it].label).tag) }
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
