package cloud.nalet.chino.tv.ui.zap

/** A variant of a master as the prefetch reads it: an EXT-X-STREAM-INF, or
 *  an EXT-X-I-FRAME-STREAM-INF ([trickPlay]). */
data class MasterVariant(val url: String, val audioGroup: String?, val trickPlay: Boolean = false)

/** An EXT-X-MEDIA TYPE=AUDIO rendition: its playlist (null when the audio is
 *  in the variant's own segments), GROUP-ID, LANGUAGE and DEFAULT=YES. */
data class MasterAudio(
    val url: String?,
    val group: String?,
    val language: String?,
    val isDefault: Boolean,
)

/** The playlists a Zap card starts on: its video variant's, and its audio
 *  rendition's (null when the variant's segments carry the audio). */
data class ZapWarmTarget(val videoUrl: String, val audioUrl: String?)

/**
 * What a Zap card starts on, from its master as chino-stream serves it to
 * this device's caps. Pure: ZapWarmTargetTest runs it on the JVM.
 *
 * Video: the master's first variant. chino-stream lists first the one a
 * client is to start on (a packaged ladder filtered to the rungs the caps
 * decode), and the card's player starts there (FirstVariantTrackSelection).
 *
 * Audio: a rendition of that variant's group, the one the card's player
 * takes — the DEFAULT one, as chino-stream marks exactly one per group of a
 * ladder; in a group without one (every package from before the ladder),
 * the one in the first of the device's [deviceLanguages] that has one, which
 * Media3 prefers among equals; else the first. Not the other renditions:
 * other languages, and the 5.1 group of a TV that decodes E-AC-3 (ZapScreen
 * keeps a card in stereo), are bytes no card plays.
 *
 * Null when the master has no variant to play.
 */
fun zapWarmTarget(
    variants: List<MasterVariant>,
    audios: List<MasterAudio>,
    deviceLanguages: List<String> = emptyList(),
): ZapWarmTarget? {
    val first = variants.firstOrNull { !it.trickPlay && it.url.isNotBlank() } ?: return null
    val group = first.audioGroup
    val inGroup = if (group == null) emptyList() else audios.filter { it.group == group && !it.url.isNullOrBlank() }
    val audio = inGroup.firstOrNull { it.isDefault }
        ?: deviceLanguages.firstNotNullOfOrNull { lang ->
            inGroup.firstOrNull { a -> primaryLanguage(a.language)?.let { it == primaryLanguage(lang) } == true }
        }
        ?: inGroup.firstOrNull()
    return ZapWarmTarget(first.url, audio?.url)
}

/** "de" of "de-CH" / "DE_ch"; null for none or "und". Media3 hands both
 *  sides over already normalised to ISO 639-1 where there is one. */
private fun primaryLanguage(tag: String?): String? =
    tag?.trim()?.lowercase()?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotEmpty() && it != "und" }
