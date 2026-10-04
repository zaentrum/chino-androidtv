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
 * allows, then the rungs the device may pick, tallest first, each named by
 * its picture size ("720p") — and is null when there is nothing to choose: a
 * package of one rendition. A pick reloads the master with q=<name>, Auto
 * with q=auto. An on-the-fly transcode offers high, medium and low. chino-web
 * builds its menu the same way (lib/qualities.ts).
 */

const val AUTO = "auto"

/** The on-the-fly pipeline's rungs. */
private val ON_THE_FLY = setOf("high", "medium", "low")

/**
 * The menu for [info], or null when there is nothing to pick (fewer than two
 * entries: a package of one rendition, a remux, a direct stream). A packaged
 * title's has Auto first, put there should the server leave it out. An entry
 * without a name is left out; one without a label is named by its name.
 */
fun qualityMenu(info: PlayInfo?): List<QualityRung>? {
    val entries = info?.qualities.orEmpty()
        .filter { it.name.isNotBlank() }
        .map { if (it.label.isBlank()) it.copy(label = it.name.replaceFirstChar(Char::uppercaseChar)) else it }
    if (entries.size < 2) return null
    if (!isPackaged(info?.mode)) return entries
    val auto = entries.firstOrNull { it.name == AUTO } ?: QualityRung(AUTO, "Auto")
    return listOf(auto) + entries.filter { it.name != AUTO }
}

/**
 * The entry the player is on for the q it asked with: the rung of that name,
 * else Auto — which is what chino-stream serves a packaged title for any
 * other q (auto, the high an unknown mode starts with, a rung this device can
 * no longer pick). Null when the menu has neither.
 */
fun chosenQuality(menu: List<QualityRung>, q: String): QualityRung? =
    menu.firstOrNull { it.name != AUTO && it.name.equals(q, ignoreCase = true) }
        ?: menu.firstOrNull { it.name == AUTO }

/** The heights quality labels name, as chino-stream's rungLabel does. */
private val SIZE_CLASSES = intArrayOf(240, 360, 480, 540, 576, 720, 1080, 1440, 2160, 4320)

/**
 * A picture size named as chino-stream names a rung: the smallest class
 * whose 16:9 box holds the frame, with 10 % of the width to spare for DCI
 * frames — a 2.39:1 film's 1280x536 is 720p, 4096x2160 is 2160p. Null
 * without a height.
 */
fun sizeLabel(width: Int, height: Int): String? {
    if (height <= 0) return null
    val w = width.coerceAtLeast(0)
    for (c in SIZE_CLASSES) {
        val boxW = (c * 16 / 9 + 1) and 1.inv()
        if (height <= c && w * 10 <= boxW * 11) return "${c}p"
    }
    return "${height}p"
}

/**
 * What plays, named by the menu: the label of the rung of that picture size
 * (Media3's selected video format), else the size's class. Null while no
 * frame size is known.
 */
fun playingLabel(width: Int, height: Int, menu: List<QualityRung>?): String? {
    if (width <= 0 || height <= 0) return null
    val rung = menu.orEmpty().firstOrNull { it.name != AUTO && it.width == width && it.height == height }
    return rung?.label ?: sizeLabel(width, height)
}

/** Auto with the rung it plays, "Auto · 720p"; plain "Auto" until that is known. */
fun autoLabel(auto: String, playing: String?): String =
    if (playing.isNullOrBlank()) auto else "$auto · $playing"

/** The quality as the Playback info says it: the chosen entry's label, Auto
 *  with the rung it plays; the bare q without a menu. */
fun qualityText(menu: List<QualityRung>?, q: String, playing: String?): String {
    val chosen = menu?.let { chosenQuality(it, q) } ?: return q
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
