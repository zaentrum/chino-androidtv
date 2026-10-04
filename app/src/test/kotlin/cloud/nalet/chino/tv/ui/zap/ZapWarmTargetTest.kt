package cloud.nalet.chino.tv.ui.zap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZapWarmTargetTest {
    private val base = "https://media.example.org/api/v1/items/m1/play"
    private val q = "?stream=t&caps=hvc:2160,avc:2160,aac,ac3,eac3&q=medium"

    private fun url(path: String) = "$base/$path$q"

    // A ladder package as chino-stream serves it to a TV that decodes HEVC
    // and E-AC-3 (chino-stream's testdata 2e7c1add): the HEVC rungs, v0
    // first, once per audio group — the stereo AAC "audio" and the 5.1
    // "audio-surround" — and an I-frame playlist per rung. As Media3 parses
    // it: a variant URL listed twice is kept once, with its first group.
    private val ladderVariants = listOf(
        MasterVariant(url("v0/playlist.m3u8"), "audio"),
        MasterVariant(url("v1/playlist.m3u8"), "audio"),
        MasterVariant(url("v0/iframes.m3u8"), null, trickPlay = true),
        MasterVariant(url("v1/iframes.m3u8"), null, trickPlay = true),
    )
    private val ladderAudios = listOf(
        MasterAudio(url("a0/playlist.m3u8"), "audio", "en", isDefault = true),
        MasterAudio(url("a1/playlist.m3u8"), "audio", "de", isDefault = false),
        MasterAudio(url("a2/playlist.m3u8"), "audio-surround", "en", isDefault = true),
    )

    // A package from before the ladder: one rung, one group, and no
    // rendition marked DEFAULT (served as packaged).
    private val legacyVariants = listOf(MasterVariant(url("v0/playlist.m3u8"), "audio"))
    private val legacyAudios = listOf(
        MasterAudio(url("a0/playlist.m3u8"), "audio", "en", isDefault = false),
        MasterAudio(url("a1/playlist.m3u8"), "audio", "de", isDefault = false),
    )

    @Test
    fun `a card starts on the first variant and the DEFAULT audio of its group`() {
        val t = zapWarmTarget(ladderVariants, ladderAudios)!!
        assertEquals(url("v0/playlist.m3u8"), t.videoUrl)
        // Not German, and not the 5.1 group's DEFAULT the card does not play.
        assertEquals(url("a0/playlist.m3u8"), t.audioUrl)
    }

    @Test
    fun `the DEFAULT rendition wins over the device's language`() {
        val t = zapWarmTarget(ladderVariants, ladderAudios, deviceLanguages = listOf("de-ch", "en"))!!
        assertEquals(url("a0/playlist.m3u8"), t.audioUrl)
    }

    @Test
    fun `without a DEFAULT, the rendition in the first device language that has one`() {
        assertEquals(
            url("a1/playlist.m3u8"),
            zapWarmTarget(legacyVariants, legacyAudios, deviceLanguages = listOf("de-ch", "en"))!!.audioUrl,
        )
        assertEquals(
            url("a0/playlist.m3u8"),
            zapWarmTarget(legacyVariants, legacyAudios, deviceLanguages = listOf("fr", "en"))!!.audioUrl,
        )
    }

    @Test
    fun `without a DEFAULT or a language of the device, the first rendition`() {
        assertEquals(url("a0/playlist.m3u8"), zapWarmTarget(legacyVariants, legacyAudios)!!.audioUrl)
        assertEquals(
            url("a0/playlist.m3u8"),
            zapWarmTarget(legacyVariants, legacyAudios, deviceLanguages = listOf("fr"))!!.audioUrl,
        )
    }

    @Test
    fun `I-frame playlists are not what a card starts on`() {
        val iframesFirst = listOf(
            MasterVariant(url("v0/iframes.m3u8"), null, trickPlay = true),
            MasterVariant(url("v0/playlist.m3u8"), "audio"),
        )
        assertEquals(url("v0/playlist.m3u8"), zapWarmTarget(iframesFirst, ladderAudios)!!.videoUrl)
    }

    @Test
    fun `audio in the variant's own segments warms the variant alone`() {
        // An on-the-fly copy master: no audio group on the variant.
        val copy = listOf(MasterVariant(url("copy/index.m3u8"), null))
        val t = zapWarmTarget(copy, ladderAudios)!!
        assertEquals(url("copy/index.m3u8"), t.videoUrl)
        assertNull(t.audioUrl)
        // A group whose renditions have no playlist of their own.
        val muxed = listOf(MasterAudio(null, "audio", "en", isDefault = true))
        assertNull(zapWarmTarget(ladderVariants, muxed)!!.audioUrl)
    }

    @Test
    fun `a master without a variant to play warms nothing`() {
        assertNull(zapWarmTarget(emptyList(), ladderAudios))
        assertNull(zapWarmTarget(listOf(MasterVariant(url("v0/iframes.m3u8"), null, trickPlay = true)), ladderAudios))
    }
}
