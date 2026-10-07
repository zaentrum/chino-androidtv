package cloud.nalet.chino.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForcedSubtitlesTest {
    // A film in English with a German dub, as the menu lists its subtitles:
    // the sidecars in the catalog's ISO 639-2 codes, a forced one of each
    // language, the English one also as PGS, listed before its text twin.
    private val german = SubtitleOption("de", "ger", forced = false)
    private val englishForcedPgs = SubtitleOption("en-pgs", "eng", forced = true, image = true)
    private val englishForced = SubtitleOption("en-forced", "eng", forced = true)
    private val english = SubtitleOption("en", "eng", forced = false)
    private val germanForced = SubtitleOption("de-forced", "ger", forced = true)
    private val tracks = listOf(german, englishForcedPgs, englishForced, english, germanForced)

    @Test
    fun `the forced track of the audio's language, text before image`() {
        assertEquals(englishForced, forcedSubtitle(tracks, "en"))
        assertEquals(germanForced, forcedSubtitle(tracks, "de"))
        // Languages compare by their ISO 639-1 code, however tagged.
        assertEquals(englishForced, forcedSubtitle(tracks, "eng"))
        assertEquals(germanForced, forcedSubtitle(tracks, "deu"))
        // An image one where its language has no text one.
        assertEquals(englishForcedPgs, forcedSubtitle(tracks - englishForced, "en"))
        // The first of a kind.
        val second = SubtitleOption("en-forced-2", "en", forced = true)
        assertEquals(englishForced, forcedSubtitle(tracks + second, "en"))
    }

    @Test
    fun `no forced track for a language without one, nor for audio in none`() {
        assertNull(forcedSubtitle(tracks, "fr"))
        assertNull(forcedSubtitle(listOf(german, english), "en"))
        assertNull(forcedSubtitle(tracks, "und"))
        assertNull(forcedSubtitle(tracks, null))
        // A film without dialogue.
        assertNull(forcedSubtitle(tracks, "zxx"))
    }

    @Test
    fun `where no subtitle is on, the forced one of the audio's language comes on`() {
        // Settings' Off, or English audio for a viewer who reads English.
        assertEquals(ForcedChange.On(englishForced), forcedChange(tracks, "en", selected = null, switchedOn = null, viewerChose = false))
        // Nothing to switch on.
        assertEquals(ForcedChange.Keep, forcedChange(tracks, "fr", selected = null, switchedOn = null, viewerChose = false))
        assertEquals(ForcedChange.Keep, forcedChange(emptyList(), "en", selected = null, switchedOn = null, viewerChose = false))
    }

    @Test
    fun `a subtitle the settings put on stays`() {
        // German subtitles chosen in Settings, over English audio.
        assertEquals(ForcedChange.Keep, forcedChange(tracks, "en", selected = german.id, switchedOn = null, viewerChose = false))
        // Its own forced track that is on stays as it is.
        assertEquals(ForcedChange.Keep, forcedChange(tracks, "en", selected = englishForced.id, switchedOn = englishForced.id, viewerChose = false))
    }

    @Test
    fun `the forced subtitle goes with the audio into another language`() {
        // From English to the German dub: the German forced track.
        assertEquals(ForcedChange.On(germanForced), forcedChange(tracks, "de", selected = englishForced.id, switchedOn = englishForced.id, viewerChose = false))
        // To a language without one: off, back to the subtitles as set.
        assertEquals(ForcedChange.Off, forcedChange(tracks, "fr", selected = englishForced.id, switchedOn = englishForced.id, viewerChose = false))
        // Turned off meanwhile, it comes on again.
        assertEquals(ForcedChange.On(englishForced), forcedChange(tracks, "en", selected = null, switchedOn = englishForced.id, viewerChose = false))
    }

    @Test
    fun `the viewer's pick is theirs`() {
        // Their Off, or a track: nothing comes on, nothing goes.
        assertEquals(ForcedChange.Keep, forcedChange(tracks, "en", selected = null, switchedOn = null, viewerChose = true))
        assertEquals(ForcedChange.Keep, forcedChange(tracks, "de", selected = englishForced.id, switchedOn = englishForced.id, viewerChose = true))
        assertEquals(ForcedChange.Keep, forcedChange(tracks, "fr", selected = english.id, switchedOn = null, viewerChose = true))
    }

    @Test
    fun `an image subtitle is told by its format, a parsed sidecar by its codecs`() {
        assertTrue(isImageSubtitle("application/pgs", null))
        assertTrue(isImageSubtitle("application/vobsub", null))
        assertTrue(isImageSubtitle("application/dvbsubs", null))
        // Media3 parses a sidecar as it loads it: its own format is the codecs.
        assertTrue(isImageSubtitle("application/x-media3-cues", "application/pgs"))
        assertFalse(isImageSubtitle("application/x-media3-cues", "text/vtt"))
        assertFalse(isImageSubtitle("text/vtt", null))
        assertFalse(isImageSubtitle("application/x-subrip", null))
        assertFalse(isImageSubtitle(null, null))
    }
}
