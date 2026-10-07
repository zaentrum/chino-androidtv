package cloud.nalet.chino.tv.ui.player

import androidx.media3.common.MimeTypes

/*
 * The forced subtitle: the one track that comes on by itself where no other
 * does. Pure: ForcedSubtitlesTest runs it on the JVM.
 *
 * A forced track holds only what a viewer of the audio's language must read
 * to follow - a sign, a letter, a line in another language. Where the
 * subtitles as set put none on - Settings' Off, the default, or no track in
 * the language chosen there, and also when the audio is in the viewer's own
 * language - the player switches on the forced track in the language of the
 * audio that plays: a text one before an image one (PGS and the other bitmap
 * formats), the first of each in the menu's order. When the audio goes to
 * another language the forced subtitle goes with it, or off where that
 * language has none. A pick in the subtitle menu is the viewer's from then
 * on, and their Off holds for the rest of the playback.
 *
 * Media3 would switch on a FORCED track in the audio's language by itself
 * wherever subtitles are on; the player has it ignore the flag
 * (ignoredTextSelectionFlags), so this is the one rule. A track is forced by
 * its FORCED flag: a master's FORCED=YES rendition, a sidecar whose label
 * says so (sidecarKind).
 */

/** What the rule is told about a subtitle track: its [id] in the menu, its
 *  language tag, whether it is a forced one and an image one. */
data class SubtitleOption(
    val id: String,
    val language: String?,
    val forced: Boolean,
    val image: Boolean = false,
)

/** The subtitle formats that are pictures rather than text. */
private val IMAGE_SUBTITLES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)

/** Whether a subtitle track of [sampleMimeType] is pictures, not text. One
 *  Media3 parsed as it loaded it (a sidecar) has its own format in [codecs]. */
fun isImageSubtitle(sampleMimeType: String?, codecs: String?): Boolean {
    val mime = if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) codecs else sampleMimeType
    return mime?.lowercase() in IMAGE_SUBTITLES
}

/**
 * The forced track for audio in [audioLanguage]: of the forced [tracks] in
 * its language (ISO 639-1, so "eng" is "en"), a text one before an image
 * one, the first of each. Null for audio in no language one could read
 * ("und", none) and where its language has no forced track.
 */
fun forcedSubtitle(tracks: List<SubtitleOption>, audioLanguage: String?): SubtitleOption? {
    val language = languageKey(audioLanguage) ?: return null
    val inLanguage = tracks.filter { it.forced && languageKey(it.language) == language }
    return inLanguage.firstOrNull { !it.image } ?: inLanguage.firstOrNull()
}

/** What the forced subtitle does now. */
sealed interface ForcedChange {
    /** Nothing: what is on stays on. */
    data object Keep : ForcedChange

    /** Switch [track] on. */
    data class On(val track: SubtitleOption) : ForcedChange

    /** Switch the forced track this rule turned on off again, back to the
     *  subtitles as set. */
    data object Off : ForcedChange
}

/**
 * What the forced subtitle does once the tracks or their selection changed.
 * [selected] is the id of the text track on, if one is; [switchedOn] the id
 * of the forced track this rule turned on, while it is the one on;
 * [viewerChose] whether the viewer picked in the subtitle menu (a track, or
 * Off) - the subtitles are theirs then. A track on that this rule did not
 * turn on is the subtitles as set: it stays. Otherwise the forced track of
 * the audio's language comes on, or the one this rule turned on goes where
 * the audio has none.
 */
fun forcedChange(
    tracks: List<SubtitleOption>,
    audioLanguage: String?,
    selected: String?,
    switchedOn: String?,
    viewerChose: Boolean,
): ForcedChange {
    if (viewerChose) return ForcedChange.Keep
    if (selected != null && selected != switchedOn) return ForcedChange.Keep
    val want = forcedSubtitle(tracks, audioLanguage)
    return when {
        want == null -> if (switchedOn != null) ForcedChange.Off else ForcedChange.Keep
        want.id == selected -> ForcedChange.Keep
        else -> ForcedChange.On(want)
    }
}
