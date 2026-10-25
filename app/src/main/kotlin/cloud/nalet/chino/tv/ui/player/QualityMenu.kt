package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.PlayInfo
import cloud.nalet.chino.tv.data.api.QualityRung

/*
 * The quality menu: what /play/info offers, the entry the player is on, and
 * the name of the rung that plays. Pure: QualityMenuTest runs it on the JVM.
 *
 * chino-stream serves a packaged title as the ladder of rungs this device
 * decodes (its caps), or one rung of it. /play/info's qualities lists the
 * choice — Auto, the ladder, which ExoPlayer steps through as the network
 * allows, then the rungs the device may pick, tallest first — and is null
 * when there is nothing to choose: a package of one rendition. A pick
 * reloads the master with q=<name>, Auto with q=auto. An on-the-fly
 * transcode lists high, medium and low, each named by the frame it encodes,
 * so two can share a name - a 720p source's high and medium are both "Medium
 * (720p)": the menu shows one row per name, the first's, and a step down
 * goes to the next entry of another name (lowerOnTheFlyRung). A direct
 * stream lists none and plays the "Original".
 *
 * Each quality has the server's name, a name and its height by one table
 * ("Medium (720p)", docs/chino playback), shown as the server gives it; an
 * older server's height alone ("720p") is named by the same table here, its
 * "High (source resolution)" by the frame that high encodes for this device
 * ([highRungFrame]: a 4K original's is 1080p), and a master's variants that
 * come without a name are named by it as chino-stream names a frame
 * ([frameQualityName]). A step down compares the names shown, and a pick of
 * the row the player is on loads nothing ([pickReloads]).
 *
 * A stream with no /play/info — a title's extra — has the same menu made from
 * its master's own variants, as the player read them ([variantMenu]).
 */

const val AUTO = "auto"

/** The on-the-fly pipeline's rungs, the best first. */
internal val ON_THE_FLY = listOf("high", "medium", "low")

/** The entries of [info] as the menu names them ([shownQualityName]); one
 *  without a label by its name ("High": no height rather than a wrong one),
 *  one without a name left out. What a step down compares, too
 *  (lowerOnTheFlyRung): the names the menu shows. */
fun shownQualities(info: PlayInfo?): List<QualityRung> =
    info?.qualities.orEmpty()
        .filter { it.name.isNotBlank() }
        .map { it.copy(label = shownQualityName(it.label.ifBlank { it.name.replaceFirstChar(Char::uppercaseChar) }, it.name, info)) }

/**
 * [info] with its qualities named as this device's menu shows them, made
 * once as it arrives: an older server's names by the table, its "High
 * (source resolution)" by the frame its high encodes for this device
 * ([shownQualityName], [maxHeight] the tallest frame the caps say the
 * hardware decodes; 0 for none said). [original]: the title played as it
 * is at high before a step down (a direct stream, a stream copy), whose
 * /play/info at another q names high as a re-encode though q=high plays the
 * original again - so its high is the "Original" here, as the menu and
 * Playback info say of it there.
 */
fun namedQualities(info: PlayInfo, maxHeight: Int = 0, original: Boolean = false): PlayInfo {
    if (info.qualities.isNullOrEmpty()) return info
    return info.copy(
        qualities = info.qualities.map { q ->
            val label = when {
                original && q.name.equals("high", ignoreCase = true) && !playsOriginal(info.mode, q.name) -> ORIGINAL
                q.label.isBlank() -> q.label
                else -> shownQualityName(q.label, q.name, info, maxHeight)
            }
            q.copy(label = label)
        },
    )
}

/**
 * The menu for [info], or null when there is nothing to pick (fewer than two
 * names: a package of one rendition, a remux, a direct stream, the ladder of
 * a source too small for two). One row per name - of entries that read the
 * same, the first, the better encode. A packaged title's has Auto first,
 * put there should the server leave it out.
 */
fun qualityMenu(info: PlayInfo?): List<QualityRung>? {
    val entries = shownQualities(info).distinctBy { it.label.lowercase() }
    if (entries.size < 2) return null
    if (!isPackaged(info?.mode)) return entries
    val auto = entries.firstOrNull { it.name == AUTO } ?: QualityRung(AUTO, "Auto")
    return listOf(auto) + entries.filter { it.name != AUTO }
}

/** A video variant of a master as the player read it: [track], its place
 *  among the video tracks; its picture size, codec and bitrate (BANDWIDTH,
 *  0 when unknown). */
data class VideoVariant(
    val track: Int,
    val width: Int,
    val height: Int,
    val codec: String? = null,
    val bitrate: Int = 0,
)

/**
 * The quality menu of a master the player reads for itself, a stream with no
 * /play/info: Auto, then one entry per name, tallest first, each named as
 * chino-stream names a frame ([frameQualityName]) and known by the variant's
 * [VideoVariant.track] — a pick pins that variant in the player, Auto lets it
 * adapt again. Of two variants of one name the first in the master stays;
 * one without a size is left out. Null when there are fewer than two names:
 * nothing to pick.
 */
fun variantMenu(variants: List<VideoVariant>): List<QualityRung>? {
    val bySize = LinkedHashMap<String, VideoVariant>()
    for (v in variants) {
        val label = frameQualityName(v.width, v.height) ?: continue
        bySize.getOrPut(label) { v }
    }
    if (bySize.size < 2) return null
    // Tallest first, as chino-stream lists rungs; of one height, master order.
    val rungs = bySize.entries.sortedByDescending { it.value.height }.map { (label, v) ->
        QualityRung(
            name = v.track.toString(),
            label = label,
            width = v.width,
            height = v.height,
            codec = v.codec,
            bitrate = v.bitrate.takeIf { it > 0 }?.toLong(),
        )
    }
    return listOf(QualityRung(AUTO, "Auto")) + rungs
}

/**
 * The entry the player is on for the q it asked with: the row of that q, or
 * the row of its name where [info] lists it under the name of another (a
 * 720p source's medium is its high's "Medium (720p)"), else Auto — which is
 * what chino-stream serves a packaged title for any other q (auto, the high
 * an unknown mode starts with, a rung this device can no longer pick). Null
 * when the menu has none of them.
 */
fun chosenQuality(menu: List<QualityRung>, q: String, info: PlayInfo? = null): QualityRung? =
    menu.firstOrNull { it.name != AUTO && it.name.equals(q, ignoreCase = true) }
        ?: shownQualities(info).firstOrNull { it.name.equals(q, ignoreCase = true) }?.let { listed ->
            menu.firstOrNull { it.name != AUTO && it.label.equals(listed.label, ignoreCase = true) }
        }
        ?: menu.firstOrNull { it.name == AUTO }

/**
 * Whether a pick of the menu's [rung] loads another stream: not where it is
 * the row the player is on already ([chosenQuality] of the q it plays) -
 * OK on the picked row, after a step down to a q of the same name too,
 * leaves the stream as it is rather than reloading the one it left.
 */
fun pickReloads(menu: List<QualityRung>, q: String, info: PlayInfo?, rung: String): Boolean =
    chosenQuality(menu, q, info)?.name?.equals(rung, ignoreCase = true) != true

/** The standard picture heights (docs/chino, playback: quality names): the
 *  transcoder's ladder rungs and the boxes players and sources come in. */
private val SIZE_CLASSES = intArrayOf(240, 360, 480, 540, 576, 720, 1080, 1440, 2160, 4320)

/**
 * What plays, named by the menu: the label of the rung of that picture size
 * (Media3's selected video format), else the frame's name by the table
 * ([frameQualityName]). Null while no frame size is known.
 */
fun playingLabel(width: Int, height: Int, menu: List<QualityRung>?): String? {
    if (width <= 0 || height <= 0) return null
    val rung = menu.orEmpty().firstOrNull { it.name != AUTO && it.width == width && it.height == height }
    return rung?.label ?: frameQualityName(width, height)
}

/** Auto with the rung it plays, "Auto · Medium (720p)"; plain "Auto" until that is known. */
fun autoLabel(auto: String, playing: String?): String =
    if (playing.isNullOrBlank()) auto else "$auto · $playing"

/** The quality as the Playback info says it: the chosen entry's label, Auto
 *  with the rung it plays; without a menu, the q by its name ([qualityLabel]:
 *  "Original" for a direct stream). */
fun qualityText(menu: List<QualityRung>?, q: String, playing: String?, info: PlayInfo? = null): String {
    val chosen = menu?.let { chosenQuality(it, q, info) }
        ?: return if (q == AUTO) autoLabel("Auto", playing) else qualityLabel(q, info)
    return if (chosen.name == AUTO) autoLabel(chosen.label, playing) else chosen.label
}

/**
 * The q a prepare asks for. The first: /play/info's default_quality (auto for
 * a packaged title, high on the fly), else high. A reload's [requested] q as
 * it is — but a packaged one (auto, a rung's name) only while the title is
 * served packaged: of an on-the-fly title chino-stream reads any q but high
 * as a transcode, re-encoding a direct stream it would copy at high.
 */
fun playQuality(requested: String?, info: PlayInfo?): String {
    if (requested == null) return info?.defaultQuality?.takeIf { it.isNotBlank() } ?: "high"
    if (requested.lowercase() in ON_THE_FLY) return requested
    return if (info != null && !isPackaged(info.mode)) "high" else requested
}

/**
 * A quality's name by the server's table (docs/chino, playback): a name and
 * the height ("Medium (720p)") - 2160p and up "Ultra", 1080p "High", 720p
 * "Medium", 480p "Low", below "Lowest"; a height between two steps the
 * lower step's name with its own height ("Medium (937p)").
 */
fun qualityName(height: Int): String {
    val name = when {
        height >= 2160 -> "Ultra"
        height >= 1080 -> "High"
        height >= 720 -> "Medium"
        height >= 480 -> "Low"
        else -> "Lowest"
    }
    return "$name (${height}p)"
}

/**
 * The height a width x height frame is named by (docs/chino, playback:
 * quality names): its step - its own height, or the height its width stands
 * for at 16:9, whichever is larger, so a 2.39:1 film's 1920x800 is 1080p, as
 * full HD as 1920x1080. A step up to 10 % above a standard height
 * ([SIZE_CLASSES]) in a frame no taller than it, or up to 3 % below one,
 * counts as that height: 2048x858 and 1916x800 are 1080p, DCI 4096x2160 is
 * 2160p, a 16:9 frame of 1076 lines 1080p. Any other step is its own height:
 * 1666x900 is 937p, a scope film's 720-line encode, 1728x720, 972p, and
 * 1600x900 900p. A width of 0 leaves the height; 0 without a height.
 */
fun namedHeight(width: Int, height: Int): Int {
    if (height <= 0) return 0
    val step = if (width > 0) maxOf(height, width * 9 / 16) else height
    for (c in SIZE_CLASSES.reversed()) if (height <= c && step >= c && step * 10 <= c * 11) return c
    for (c in SIZE_CLASSES) if (step < c && step * 100 >= c * 97) return c
    return step
}

/** A frame's quality name ([qualityName] of its [namedHeight]): "High
 *  (1080p)" for 1920x800; null without a height. */
fun frameQualityName(width: Int, height: Int): String? =
    namedHeight(width, height).takeIf { it > 0 }?.let(::qualityName)

/**
 * The frame an on-the-fly title's high encodes from a [width] x [height]
 * original, as chino-stream's transcodeGeometry has it: the original's own
 * size up to a 1920 x 1080 box - or the box of [maxHeight], the tallest
 * frame this device's hardware decodes, where that is lower (0: none said)
 * - else the original fitted into that box ("Invalid Level" past it), its
 * sides even. A 3840 x 2160 original's high is 1920 x 1080, a 3840 x 1606
 * one's 1920 x 804, a 1080p one on a device of 720 lines 1280 x 720.
 */
fun highRungFrame(width: Int, height: Int, maxHeight: Int = 0): Pair<Int, Int> {
    val boxH = if (maxHeight in 1 until 1080) maxHeight else 1080
    val boxW = boxH * 16 / 9
    if (width <= 0 || height <= 0 || (width <= boxW && height <= boxH)) return width to height
    return if (width.toLong() * boxH > height.toLong() * boxW) boxW to ((height * boxW / width + 1) and 1.inv())
    else ((width * boxH / height + 1) and 1.inv()) to boxH
}

private val HEIGHT_ONLY = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
private val SOURCE_WORD = Regex("""(?<![A-Za-z])source(?![A-Za-z])""", RegexOption.IGNORE_CASE)

/**
 * What a quality is called where the server's [label] for it is shown: the
 * label as it is ("Medium (720p)", "Original", "Auto"), but an older
 * server's - a height alone ("720p") is named by the table ([qualityName]),
 * never with its height twice, and its "High (source resolution)" of the
 * quality [name] is "Original" where that plays the original as it is, else
 * named by the frame that high encodes ([highOnTheFly]): the original's up
 * to 1080 lines, a 4K original's fitted into 1080p - "High (1080p)", never
 * "Ultra (2160p)" for the 1080p that plays.
 */
fun shownQualityName(label: String, name: String? = null, info: PlayInfo? = null, maxHeight: Int = 0): String {
    val t = label.trim()
    HEIGHT_ONLY.matchEntire(t)?.let { return qualityName(it.groupValues[1].toInt()) }
    if (name != null && SOURCE_WORD.containsMatchIn(t)) {
        if (playsOriginal(info?.mode, name)) return ORIGINAL
        highOnTheFly(info, maxHeight)?.let { return it }
    }
    return t
}

/** The name of the frame an on-the-fly high encodes of [info]'s original
 *  ([highRungFrame]); null where its size is not known. */
private fun highOnTheFly(info: PlayInfo?, maxHeight: Int = 0): String? {
    val height = info?.height?.takeIf { it > 0 } ?: return null
    val (w, h) = highRungFrame(info.width ?: 0, height, maxHeight)
    return frameQualityName(w, h)
}

/** The name of the original played as it is: a direct stream, a stream copy. */
const val ORIGINAL = "Original"

/** The words of the on-the-fly rungs, where the info at hand names none of
 *  them: no height, rather than a guessed one. */
private val ON_THE_FLY_WORDS = mapOf("high" to "High", "medium" to "Medium", "low" to "Low")

/** The quality [q] by its name in the quality menu, a name and its height
 *  ("Medium (720p)"): /play/info's label for it; "Original" for the original
 *  played as it is; a high on the fly by the frame it encodes of the
 *  original; else the rung's word alone. */
fun qualityLabel(q: String, info: PlayInfo?): String {
    if (playsOriginal(info?.mode, q)) return ORIGINAL
    info?.qualities.orEmpty().firstOrNull { it.name.equals(q, ignoreCase = true) && it.label.isNotBlank() }
        ?.let { return shownQualityName(it.label, it.name, info) }
    if (q.equals("high", ignoreCase = true)) highOnTheFly(info)?.let { return it }
    return ON_THE_FLY_WORDS[q.lowercase()] ?: q
}

/** Whether a stream of [mode] at [q] plays the original as it is: a direct
 *  stream or a stream copy at high, which a step down leaves for the
 *  encoded ladder. */
fun playsOriginal(mode: String?, q: String): Boolean =
    (mode.equals("passthrough", ignoreCase = true) || mode.equals("remux", ignoreCase = true)) &&
        q.equals("high", ignoreCase = true)

/**
 * What the player says when it steps the quality down by itself
 * (docs/chino, playback), [label] the new quality as the menu names it: from
 * the original when the connection is too slow ([stalled]), or because the
 * original couldn't be streamed; else one step down between qualities, for a
 * connection too slow, or because the one it left ([previous], as the menu
 * names it) couldn't be streamed - an error, which says nothing of the
 * connection.
 */
fun stepDownNotice(fromOriginal: Boolean, stalled: Boolean, label: String, previous: String? = null): String = when {
    fromOriginal && stalled -> "Switched to $label quality: the connection can't keep up with the original."
    fromOriginal -> "Switched to $label quality: the original couldn't be streamed."
    stalled -> "Switched to $label quality: the connection can't keep up."
    // An error, not the connection: the quality it left couldn't be streamed.
    else -> "Switched to $label quality: ${previous ?: "the one before"} couldn't be streamed."
}

/** A step down the player makes by itself: the q it goes to, whether it
 *  leaves the original, whether a stall made it (else an error), and the
 *  quality it leaves as the menu names it. */
data class StepDown(val quality: String, val fromOriginal: Boolean, val stalled: Boolean, val previous: String)

/**
 * What a load of the quality [loaded] says: the notice of the step down
 * [pending] where this load is that step down ([stepDownNotice], the quality
 * named from [info]); nothing for any other - a pick in the quality menu, a
 * reload, the first load - so a notice still up goes with it.
 */
fun stepDownNoticeFor(pending: StepDown?, loaded: String, info: PlayInfo?): String? {
    if (pending == null || !pending.quality.equals(loaded, ignoreCase = true)) return null
    val label = qualityLabel(loaded, info)
    // A step to the same picture is none to tell of.
    if (label.equals(pending.previous, ignoreCase = true)) return null
    return stepDownNotice(pending.fromOriginal, pending.stalled, label, pending.previous)
}
