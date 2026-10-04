package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.PlayInfo
import cloud.nalet.chino.tv.data.api.QualityRung
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityMenuTest {
    private val auto = QualityRung("auto", "Auto")
    private val r1080 = QualityRung("v0", "1080p", id = "v0", width = 1920, height = 1080, codec = "hvc1.1.6.L120.90", bitrate = 7_436_688)
    private val r720 = QualityRung("v1", "720p", id = "v1", width = 1280, height = 720, codec = "avc1.64001f", bitrate = 1_505_267)
    private val r480 = QualityRung("v2", "480p", id = "v2", width = 854, height = 480, codec = "avc1.64001e", bitrate = 706_649)

    /** /play/info of a packaged ladder for a TV: Auto, then its rungs. */
    private val ladder = PlayInfo(mode = "packaged", qualities = listOf(auto, r1080, r720, r480), defaultQuality = "auto")

    private val transcode = PlayInfo(
        mode = "transcode",
        qualities = listOf(QualityRung("high", "High"), QualityRung("medium", "Medium"), QualityRung("low", "Low")),
        defaultQuality = "high",
    )

    @Test
    fun `a packaged ladder offers Auto and its rungs, by the server's labels`() {
        val menu = qualityMenu(ladder)!!
        assertEquals(listOf("auto", "v0", "v1", "v2"), menu.map { it.name })
        assertEquals(listOf("Auto", "1080p", "720p", "480p"), menu.map { it.label })
    }

    @Test
    fun `Auto comes first even where the server left it out`() {
        val menu = qualityMenu(ladder.copy(qualities = listOf(r720, r480)))!!
        assertEquals(listOf("Auto", "720p", "480p"), menu.map { it.label })
        // And is not listed twice when it is not first.
        assertEquals(listOf("auto", "v1", "v2"), qualityMenu(ladder.copy(qualities = listOf(r720, auto, r480)))!!.map { it.name })
    }

    @Test
    fun `nothing to pick, no menu`() {
        // A package of one rendition: qualities null, decoded as empty.
        assertNull(qualityMenu(PlayInfo(mode = "packaged", defaultQuality = "auto")))
        assertNull(qualityMenu(PlayInfo(mode = "packaged", qualities = listOf(auto))))
        // A remux or a direct stream offers none either.
        assertNull(qualityMenu(PlayInfo(mode = "remux", defaultQuality = "high")))
        assertNull(qualityMenu(null))
    }

    @Test
    fun `an on-the-fly transcode offers its ladder as it is`() {
        assertEquals(listOf("High", "Medium", "Low"), qualityMenu(transcode)!!.map { it.label })
        // An entry with no label is named by its name; one with no name is dropped.
        val unlabelled = transcode.copy(qualities = listOf(QualityRung("high", ""), QualityRung("", "X"), QualityRung("low", "Low")))
        assertEquals(listOf("High", "Low"), qualityMenu(unlabelled)!!.map { it.label })
    }

    @Test
    fun `the entry the player is on`() {
        val menu = qualityMenu(ladder)!!
        assertEquals("auto", chosenQuality(menu, "auto")?.name)
        assertEquals("v1", chosenQuality(menu, "v1")?.name)
        // chino-stream serves Auto for any other q of a packaged title.
        assertEquals("auto", chosenQuality(menu, "high")?.name)
        assertEquals("auto", chosenQuality(menu, "v9")?.name)
        val ladderMenu = qualityMenu(transcode)!!
        assertEquals("medium", chosenQuality(ladderMenu, "medium")?.name)
        assertNull(chosenQuality(ladderMenu, "v1"))
    }

    @Test
    fun `Auto says which rung plays`() {
        val menu = qualityMenu(ladder)!!
        assertEquals("720p", playingLabel(1280, 720, menu))
        assertEquals("Auto · 720p", autoLabel("Auto", playingLabel(1280, 720, menu)))
        assertEquals("Auto", autoLabel("Auto", playingLabel(0, 0, menu)))
        assertEquals("Auto · 720p", qualityText(menu, "auto", "720p"))
        assertEquals("480p", qualityText(menu, "v2", "480p"))
        assertEquals("High", qualityText(qualityMenu(transcode), "high", "1080p"))
        // No menu: the q as it is.
        assertEquals("high", qualityText(null, "high", "1080p"))
    }

    @Test
    fun `a size the menu lacks is named as chino-stream names rungs`() {
        val menu = qualityMenu(ladder)!!
        // A 2.39:1 film's 720p rung.
        assertEquals("720p", playingLabel(1280, 536, menu))
        assertEquals("1080p", sizeLabel(1920, 1080))
        assertEquals("480p", sizeLabel(854, 480))
        assertEquals("2160p", sizeLabel(4096, 2160))
        assertEquals("2160p", sizeLabel(3840, 1606))
        assertEquals("1080p", sizeLabel(1440, 1080))
        assertNull(sizeLabel(1280, 0))
    }

    @Test
    fun `the q a prepare asks for`() {
        assertEquals("auto", playQuality(null, ladder))
        assertEquals("high", playQuality(null, transcode))
        assertEquals("high", playQuality(null, null))
        // A pick of a packaged title is asked for as it is.
        assertEquals("v1", playQuality("v1", ladder))
        assertEquals("auto", playQuality("auto", ladder))
        assertEquals("v1", playQuality("v1", null))
        // A title no longer served packaged: high, not a rung or Auto.
        assertEquals("high", playQuality("v1", transcode))
        assertEquals("high", playQuality("auto", PlayInfo(mode = "passthrough")))
        // The on-the-fly ladder's own rungs stay.
        assertEquals("medium", playQuality("medium", transcode))
        assertEquals("low", playQuality("low", ladder))
    }
}
