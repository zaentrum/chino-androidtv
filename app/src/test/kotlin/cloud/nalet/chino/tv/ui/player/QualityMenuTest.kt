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
    fun `a packaged ladder offers Auto and its rungs, an older server's heights named by the table`() {
        val menu = qualityMenu(ladder)!!
        assertEquals(listOf("auto", "v0", "v1", "v2"), menu.map { it.name })
        assertEquals(listOf("Auto", "High (1080p)", "Medium (720p)", "Low (480p)"), menu.map { it.label })
        // The server's own names as it gives them: never a height twice.
        val named = ladder.copy(qualities = listOf(auto, r1080.copy(label = "High (1080p)"), r720.copy(label = "Medium (720p)")))
        assertEquals(listOf("Auto", "High (1080p)", "Medium (720p)"), qualityMenu(named)!!.map { it.label })
    }

    @Test
    fun `the server's table of names by height`() {
        assertEquals(
            listOf("Ultra (4320p)", "Ultra (2160p)", "High (1440p)", "High (1080p)", "Medium (937p)", "Medium (720p)", "Low (576p)", "Low (480p)", "Lowest (360p)", "Lowest (240p)"),
            listOf(4320, 2160, 1440, 1080, 937, 720, 576, 480, 360, 240).map(::qualityName),
        )
        assertEquals("Medium (720p)", shownQualityName("720p"))
        assertEquals("Medium (720p)", shownQualityName("Medium (720p)"))
        assertEquals("Original", shownQualityName("Original"))
        assertEquals("Lowest (360p)", shownQualityName("Lowest (360p)"))
        // An older server's on-the-fly high: the original where it plays as
        // it is, else a re-encode named by the frame it encodes - the
        // original's up to 1080 lines, a larger one fitted into 1080p, as
        // chino-stream's transcodeGeometry does.
        val old = { w: Int, h: Int -> PlayInfo(mode = "transcode", width = w, height = h) }
        assertEquals("Original", shownQualityName("High (source resolution)", "high", PlayInfo(mode = "remux", height = 1080)))
        assertEquals("High (1080p)", shownQualityName("High (source resolution)", "high", old(1920, 1080)))
        assertEquals("High (1080p)", shownQualityName("High (source resolution)", "high", old(3840, 1606)))
        assertEquals("High (1080p)", shownQualityName("High (source resolution)", "high", old(3840, 2160)))
        assertEquals("High (1080p)", shownQualityName("High (source resolution)", "high", old(2560, 1440)))
        assertEquals("Medium (720p)", shownQualityName("High (source resolution)", "high", old(1280, 720)))
        // A device whose hardware decodes 720 lines gets a 720p high.
        assertEquals("Medium (720p)", shownQualityName("High (source resolution)", "high", old(1920, 1080), maxHeight = 720))
        assertEquals("High (1080p)", shownQualityName("High (source resolution)", "high", old(3840, 2160), maxHeight = 2160))
        assertEquals("High (source resolution)", shownQualityName("High (source resolution)", "high", PlayInfo(mode = "transcode")))
        // A high with no ladder listed, by the same frame.
        assertEquals("High (1080p)", qualityLabel("high", old(3840, 2160)))
    }

    @Test
    fun `the frame an on-the-fly high encodes, as chino-stream fits it`() {
        assertEquals(1920 to 1080, highRungFrame(3840, 2160))
        assertEquals(1920 to 804, highRungFrame(3840, 1606))
        assertEquals(1920 to 1080, highRungFrame(2560, 1440))
        // A 4:3 one is fitted by its height.
        assertEquals(1440 to 1080, highRungFrame(2880, 2160))
        assertEquals(1280 to 720, highRungFrame(1920, 1080, maxHeight = 720))
        // Within the box: the original's own size; a size not known as it is.
        assertEquals(1920 to 1080, highRungFrame(1920, 1080))
        assertEquals(720 to 480, highRungFrame(720, 480))
        assertEquals(0 to 2160, highRungFrame(0, 2160))
    }

    @Test
    fun `an older server's names, made the menu's once as they arrive`() {
        // An older server's 720p transcode: its high is the same picture as
        // its medium, so one row, and a step down goes to low, by the names
        // shown - with a notice.
        val raw = onTheFly("high" to "High (source resolution)", "medium" to "Medium (720p)", "low" to "Low (480p)", width = 1280, height = 720)
        val named = namedQualities(raw)
        assertEquals(listOf("Medium (720p)", "Medium (720p)", "Low (480p)"), named.qualities!!.map { it.label })
        assertEquals(listOf("high" to "Medium (720p)", "low" to "Low (480p)"), qualityMenu(named)!!.map { it.name to it.label })
        assertEquals("low", lowerOnTheFlyRung("high", shownQualities(raw)))
        assertEquals("low", lowerOnTheFlyRung("high", shownQualities(named)))
        // A 4K original on a device whose hardware decodes 1080: High (1080p).
        val uhd = onTheFly("high" to "High (source resolution)", "medium" to "Medium (720p)", "low" to "Low (480p)", width = 3840, height = 2160)
        assertEquals("High (1080p)", namedQualities(uhd, maxHeight = 1080).qualities!!.first().label)
        // A label not given stays so: the menu names it by its word.
        assertEquals(listOf("High", "Medium (720p)", "Low (480p)"), qualityMenu(namedQualities(unknown))!!.map { it.label })
    }

    @Test
    fun `after a step down from the original its high is the Original again`() {
        // A direct stream stepped down to medium: /play/info at q=medium names
        // high as a re-encode, but q=high plays the original again.
        val named = namedQualities(hd, original = true)
        assertEquals(listOf("Original", "Medium (720p)", "Low (480p)"), qualityMenu(named)!!.map { it.label })
        assertEquals("Original", qualityLabel("high", named))
        // A 720p one: the Original and its medium are two streams, two rows.
        assertEquals(listOf("Original", "Medium (720p)", "Low (480p)"), qualityMenu(namedQualities(p720, original = true))!!.map { it.label })
        // A title that never played its original: as the server names it.
        assertEquals("High (1080p)", qualityMenu(namedQualities(hd))!!.first().label)
    }

    @Test
    fun `a pick of the row the player is on loads nothing`() {
        val menu = qualityMenu(p720)!!
        // After a step down to medium the one row is high's: OK on it stays.
        assertEquals(false, pickReloads(menu, "medium", p720, "high"))
        assertEquals(true, pickReloads(menu, "medium", p720, "low"))
        assertEquals(false, pickReloads(menu, "high", p720, "high"))
        val ladderMenu = qualityMenu(ladder)!!
        assertEquals(false, pickReloads(ladderMenu, "auto", ladder, "auto"))
        assertEquals(true, pickReloads(ladderMenu, "auto", ladder, "v1"))
    }

    @Test
    fun `Auto comes first even where the server left it out`() {
        val menu = qualityMenu(ladder.copy(qualities = listOf(r720, r480)))!!
        assertEquals(listOf("Auto", "Medium (720p)", "Low (480p)"), menu.map { it.label })
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
        assertEquals("Medium (720p)", playingLabel(1280, 720, menu))
        assertEquals("Auto · Medium (720p)", autoLabel("Auto", playingLabel(1280, 720, menu)))
        assertEquals("Auto", autoLabel("Auto", playingLabel(0, 0, menu)))
        assertEquals("Auto · Medium (720p)", qualityText(menu, "auto", "Medium (720p)"))
        assertEquals("Low (480p)", qualityText(menu, "v2", "Low (480p)"))
        assertEquals("High", qualityText(qualityMenu(transcode), "high", "1080p"))
        // No menu: the q by its name.
        assertEquals("High", qualityText(null, "high", "1080p"))
        assertEquals("Auto · Medium (720p)", qualityText(null, "auto", "Medium (720p)"))
    }

    @Test
    fun `a frame is named by its step, the standard heights with the server's tolerance`() {
        val menu = qualityMenu(ladder)!!
        // A 2.39:1 film's 720p rung.
        assertEquals("Medium (720p)", playingLabel(1280, 536, menu))
        // docs/chino playback, quality names: a frame's step is its height or
        // the height its width stands for at 16:9, whichever is larger; up to
        // 10 % above a standard height in a frame no taller than it, or up to
        // 3 % below one, is that height; any other step is its own.
        val frames = listOf(
            // The spec's own examples.
            Triple(1920, 818, "High (1080p)"), Triple(1920, 1080, "High (1080p)"),
            Triple(1916, 800, "High (1080p)"), Triple(2048, 858, "High (1080p)"),
            Triple(1024, 576, "Low (576p)"), Triple(2560, 1072, "High (1440p)"),
            Triple(1666, 900, "Medium (937p)"), Triple(1728, 720, "Medium (972p)"),
            // Scope and flat frames of the standard heights.
            Triple(3840, 1606, "Ultra (2160p)"), Triple(1920, 804, "High (1080p)"), Triple(1920, 800, "High (1080p)"),
            Triple(1280, 536, "Medium (720p)"), Triple(854, 358, "Low (480p)"), Triple(640, 268, "Lowest (360p)"),
            Triple(1920, 1038, "High (1080p)"), Triple(1280, 692, "Medium (720p)"),
            // Up to 10 % above, in a frame no taller: DCI and its scope.
            Triple(1998, 1080, "High (1080p)"), Triple(4096, 2160, "Ultra (2160p)"), Triple(4096, 1716, "Ultra (2160p)"),
            Triple(2048, 1080, "High (1080p)"), Triple(8192, 4320, "Ultra (4320p)"),
            // Up to 3 % below: a standard frame cropped at the sides.
            Triple(1904, 800, "High (1080p)"), Triple(1912, 1036, "High (1080p)"), Triple(1918, 1040, "High (1080p)"),
            Triple(3836, 1600, "Ultra (2160p)"), Triple(3816, 1600, "Ultra (2160p)"),
            Triple(1278, 534, "Medium (720p)"), Triple(1276, 716, "Medium (720p)"),
            // Any other step: the height its width stands for at 16:9.
            Triple(1856, 800, "Medium (1044p)"), Triple(1856, 1036, "Medium (1044p)"), Triple(1152, 480, "Low (648p)"),
            // A frame no wider than 16:9: its own height.
            Triple(1440, 1080, "High (1080p)"), Triple(960, 720, "Medium (720p)"), Triple(720, 576, "Low (576p)"),
            Triple(720, 480, "Low (480p)"), Triple(640, 480, "Low (480p)"), Triple(1600, 900, "Medium (900p)"),
            Triple(2560, 1080, "High (1440p)"), Triple(7680, 4320, "Ultra (4320p)"),
            // A taller frame than the standard one is not that height.
            Triple(1200, 1100, "High (1100p)"),
        )
        for ((w, h, name) in frames) assertEquals("${w}x$h", name, frameQualityName(w, h))
        // A 16:9 frame, and a height alone: the standard height within 3 %
        // below it, else its own.
        val sixteenNine = listOf(
            240 to "Lowest (240p)", 359 to "Lowest (360p)", 360 to "Lowest (360p)", 400 to "Lowest (400p)",
            479 to "Low (480p)", 480 to "Low (480p)", 576 to "Low (576p)", 700 to "Medium (720p)", 719 to "Medium (720p)",
            720 to "Medium (720p)", 900 to "Medium (900p)", 1050 to "High (1080p)", 1079 to "High (1080p)",
            1080 to "High (1080p)", 1440 to "High (1440p)", 2159 to "Ultra (2160p)", 2160 to "Ultra (2160p)", 4320 to "Ultra (4320p)",
        )
        for ((h, name) in sixteenNine) {
            assertEquals("16:9 of $h", name, frameQualityName(h * 16 / 9, h))
            assertEquals("$h alone", name, frameQualityName(0, h))
        }
        assertNull(frameQualityName(0, 0))
        assertNull(frameQualityName(1920, 0))
        assertNull(frameQualityName(1920, -1))
    }

    // /play/info of an on-the-fly transcode as chino-stream names it now: every
    // q listed, each by the frame it encodes, two of them maybe of one name.
    private fun onTheFly(vararg rungs: Pair<String, String>, width: Int? = null, height: Int? = null) =
        PlayInfo(mode = "transcode", qualities = rungs.map { (q, label) -> QualityRung(q, label) }, defaultQuality = "high", width = width, height = height)

    private val hd = onTheFly("high" to "High (1080p)", "medium" to "Medium (720p)", "low" to "Low (480p)", width = 1920, height = 1080)
    private val p720 = onTheFly("high" to "Medium (720p)", "medium" to "Medium (720p)", "low" to "Low (480p)", width = 1280, height = 720)
    private val pal = onTheFly("high" to "Low (576p)", "medium" to "Low (576p)", "low" to "Low (480p)", width = 720, height = 576)
    private val p360 = onTheFly("high" to "Lowest (360p)", "medium" to "Lowest (360p)", "low" to "Lowest (360p)", width = 640, height = 360)
    private val unknown = onTheFly("high" to "", "medium" to "Medium (720p)", "low" to "Low (480p)")

    @Test
    fun `one row per name, a q of another's name on that row`() {
        assertEquals(listOf("High (1080p)", "Medium (720p)", "Low (480p)"), qualityMenu(hd)!!.map { it.label })
        val menu = qualityMenu(p720)!!
        assertEquals(listOf("high" to "Medium (720p)", "low" to "Low (480p)"), menu.map { it.name to it.label })
        // After a step down to medium: the one row it plays.
        assertEquals("high", chosenQuality(menu, "medium", p720)?.name)
        assertEquals("Medium (720p)", qualityText(menu, "medium", null, p720))
        assertEquals(listOf("Low (576p)", "Low (480p)"), qualityMenu(pal)!!.map { it.label })
        // A source too small for two names: nothing to pick, the name it plays.
        assertNull(qualityMenu(p360))
        assertEquals("Lowest (360p)", qualityText(null, "high", null, p360))
        // A high of a size not known has no name: its word alone.
        assertEquals(listOf("High", "Medium (720p)", "Low (480p)"), qualityMenu(unknown)!!.map { it.label })
    }

    @Test
    fun `the original played as it is is the Original`() {
        for (mode in listOf("passthrough", "remux")) {
            assertEquals("Original", qualityText(null, "high", null, PlayInfo(mode = mode, width = 1920, height = 1080)))
            assertEquals("Original", qualityLabel("high", PlayInfo(mode = mode)))
        }
    }

    @Test
    fun `a step down goes to the next listed rung of another name`() {
        assertEquals("medium", lowerOnTheFlyRung("high", hd.qualities))
        assertEquals("low", lowerOnTheFlyRung("medium", hd.qualities))
        assertNull(lowerOnTheFlyRung("low", hd.qualities))
        // A 720p source: high and medium are one picture, so high steps to low.
        assertEquals("low", lowerOnTheFlyRung("high", p720.qualities))
        assertEquals("low", lowerOnTheFlyRung("medium", p720.qualities))
        assertEquals("low", lowerOnTheFlyRung("high", pal.qualities))
        // A 360p source has no smaller picture: on an error, the next rung
        // of its name anyway - another encode - but never on a stall.
        assertNull(lowerOnTheFlyRung("high", p360.qualities))
        assertEquals("medium", nextListedRung("high", p360.qualities))
        assertNull(nextListedRung("low", p360.qualities))
        assertNull(nextListedRung("v1", p360.qualities))
        // A high without a name is another than medium's.
        assertEquals("medium", lowerOnTheFlyRung("high", unknown.qualities))
        // No ladder listed - a direct stream's first step, an older server: the fixed one.
        assertEquals("medium", lowerOnTheFlyRung("high"))
        assertEquals("low", lowerOnTheFlyRung("medium", emptyList()))
        val at = 120_000L
        assertEquals(Recovery.StepDown("low"), PlaybackRecovery().onStall("transcode", "high", at, loading = true, bufferedAheadMs = 0, listed = p720.qualities))
        assertEquals(Recovery.Wait, PlaybackRecovery().onStall("transcode", "high", at, loading = true, bufferedAheadMs = 0, listed = p360.qualities))
        val r = PlaybackRecovery()
        assertEquals(
            listOf(
                Recovery.StepDown("medium"), Recovery.StepDown("low"),
                // At the last rung: tried in place, rebuilt, then the error.
                Recovery.RetryInPlace, Recovery.RetryInPlace, Recovery.RetryInPlace, Recovery.Reload("low"), Recovery.GiveUp,
            ),
            listOf("high", "medium", "low", "low", "low", "low", "low").map { q -> r.onError("transcode", q, at, listed = p360.qualities) },
        )
        // A 720p source's high steps to low on an error too, past the medium of its name.
        assertEquals(Recovery.StepDown("low"), PlaybackRecovery().onError("transcode", "high", at, listed = p720.qualities))
    }

    @Test
    fun `a step to the next rung of the same name says nothing`() {
        val same = StepDown(quality = "medium", fromOriginal = false, stalled = false, previous = qualityLabel("high", p360))
        assertNull(stepDownNoticeFor(same, "medium", p360))
    }

    @Test
    fun `a step down's notice names the picture it went to, never the one it left`() {
        val pending = StepDown(quality = "low", fromOriginal = false, stalled = true, previous = qualityLabel("high", p720))
        assertEquals("Switched to Low (480p) quality: the connection can't keep up.", stepDownNoticeFor(pending, "low", p720))
        // Were a step to keep the picture, it says nothing.
        val same = StepDown(quality = "medium", fromOriginal = false, stalled = true, previous = qualityLabel("high", p720))
        assertNull(stepDownNoticeFor(same, "medium", p720))
        // From the original: its next /play/info lists the ladder it is on.
        val off = StepDown(quality = "medium", fromOriginal = true, stalled = true, previous = qualityLabel("high", PlayInfo(mode = "passthrough")))
        assertEquals(
            "Switched to Medium (720p) quality: the connection can't keep up with the original.",
            stepDownNoticeFor(off, "medium", hd),
        )
    }

    @Test
    fun `a master's own variants make the menu where there is no play info`() {
        // An extra's ladder as Media3 reads it: 2.39:1 rungs, the 480p listed first.
        val menu = variantMenu(
            listOf(
                VideoVariant(track = 0, width = 854, height = 356, codec = "avc1.64001e", bitrate = 706_649),
                VideoVariant(track = 1, width = 1280, height = 536, codec = "avc1.64001f", bitrate = 1_505_267),
            ),
        )!!
        assertEquals(listOf("auto", "1", "0"), menu.map { it.name })
        assertEquals(listOf("Auto", "Medium (720p)", "Low (480p)"), menu.map { it.label })
        assertEquals(QualityRung("1", "Medium (720p)", width = 1280, height = 536, codec = "avc1.64001f", bitrate = 1_505_267), menu[1])
        // It reads as a title's menu does.
        assertEquals("Auto · Medium (720p)", qualityText(menu, "auto", playingLabel(1280, 536, menu)))
        assertEquals("Low (480p)", qualityText(menu, "0", playingLabel(854, 356, menu)))
        assertEquals("1", chosenQuality(menu, "1")?.name)
    }

    @Test
    fun `of a master's variants one per size, and none without a size`() {
        val menu = variantMenu(
            listOf(
                VideoVariant(track = 0, width = 1280, height = 720, bitrate = 3_000_000),
                VideoVariant(track = 1, width = 1280, height = 720, bitrate = 1_500_000),
                VideoVariant(track = 2, width = -1, height = -1),
                VideoVariant(track = 3, width = 640, height = 360),
            ),
        )!!
        assertEquals(listOf("auto", "0", "3"), menu.map { it.name })
        assertNull(menu[2].bitrate)
    }

    @Test
    fun `a master of one size has nothing to pick`() {
        assertNull(variantMenu(emptyList()))
        assertNull(variantMenu(listOf(VideoVariant(track = 0, width = 1280, height = 720))))
        assertNull(
            variantMenu(listOf(VideoVariant(track = 0, width = 1280, height = 720), VideoVariant(track = 1, width = 1280, height = 720))),
        )
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

    @Test
    fun `a quality is named by the menu's name, a name and its height`() {
        val onTheFly = PlayInfo(
            mode = "transcode",
            qualities = listOf(QualityRung("high", "High"), QualityRung("medium", "Medium (720p)"), QualityRung("low", "Low (480p)")),
        )
        assertEquals("Medium (720p)", qualityLabel("medium", onTheFly))
        assertEquals("Low (480p)", qualityLabel("LOW", onTheFly))
        assertEquals("Medium (720p)", qualityLabel("v1", ladder))
        // A server that sent no qualities: the original as it is, a high by
        // the original's frame, else the rung's word without a guessed height.
        assertEquals("Medium", qualityLabel("medium", null))
        assertEquals("Low", qualityLabel("low", PlayInfo(mode = "remux")))
        assertEquals("Original", qualityLabel("high", PlayInfo(mode = "remux")))
        assertEquals("High (1080p)", qualityLabel("high", PlayInfo(mode = "transcode", width = 1920, height = 1080)))
        assertEquals("High", qualityLabel("high", null))
        assertEquals("v9", qualityLabel("v9", null))
    }

    @Test
    fun `the original plays as it is only in a direct stream or a stream copy at high`() {
        assertEquals(true, playsOriginal("passthrough", "high"))
        assertEquals(true, playsOriginal("remux", "high"))
        assertEquals(false, playsOriginal("remux", "medium"))
        assertEquals(false, playsOriginal("transcode", "high"))
        assertEquals(false, playsOriginal(null, "high"))
    }

    @Test
    fun `a step down says what it switched to and why`() {
        assertEquals(
            "Switched to Medium (720p) quality: the connection can't keep up with the original.",
            stepDownNotice(fromOriginal = true, stalled = true, label = "Medium (720p)"),
        )
        assertEquals(
            "Switched to Medium (720p) quality: the original couldn't be streamed.",
            stepDownNotice(fromOriginal = true, stalled = false, label = "Medium (720p)"),
        )
        assertEquals(
            "Switched to Low (480p) quality: the connection can't keep up.",
            stepDownNotice(fromOriginal = false, stalled = true, label = "Low (480p)"),
        )
        // An error between qualities says that, not the connection.
        assertEquals(
            "Switched to Low (480p) quality: Medium (720p) couldn't be streamed.",
            stepDownNotice(fromOriginal = false, stalled = false, label = "Low (480p)", previous = "Medium (720p)"),
        )
    }

    @Test
    fun `a notice only for the load of its own step down, never after a pick in the menu`() {
        val info = PlayInfo(mode = "transcode", qualities = listOf(QualityRung("high", "High (1080p)"), QualityRung("medium", "Medium (720p)")))
        val stall = StepDown(quality = "medium", fromOriginal = false, stalled = true, previous = "High (1080p)")
        assertEquals("Switched to Medium (720p) quality: the connection can't keep up.", stepDownNoticeFor(stall, "medium", info))
        // The viewer picks High while the notice is up: that load says nothing.
        assertNull(stepDownNoticeFor(null, "high", info))
        // A load of another quality than the step down's: nothing either.
        assertNull(stepDownNoticeFor(stall, "high", info))
    }
}
