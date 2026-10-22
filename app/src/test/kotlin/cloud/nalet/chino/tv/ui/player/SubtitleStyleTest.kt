package cloud.nalet.chino.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.provider.Settings
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.text.Cue
import androidx.media3.common.text.RubySpan
import androidx.media3.common.text.TextAnnotation
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one look of a subtitle (SubtitleStyle.kt): its values, the view it is
 * set on, the viewer's own caption settings winning, and the layout of the
 * cues - centred at the bottom within 80 % of the picture, inside the safe
 * area. Under Robolectric for the framework's text, colour and caption types.
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleStyleTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `white text, a black outline, nothing behind it, the system's sans-serif`() {
        val style = SubtitleLook.captionStyle
        assertEquals(Color.WHITE, style.foregroundColor)
        assertEquals(Color.TRANSPARENT, style.backgroundColor)
        assertEquals(Color.TRANSPARENT, style.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.edgeType)
        assertEquals(Color.BLACK, style.edgeColor)
        assertSame(Typeface.SANS_SERIF, style.typeface)
    }

    @Test
    fun `the size follows the picture, the lines a share of its width, the safe area of a TV`() {
        assertEquals(0.0533f, SubtitleLook.TEXT_SIZE_FRACTION, 0f)
        assertEquals(0.8f, SubtitleLook.LINE_WIDTH_FRACTION, 0f)
        assertEquals(0.08f, SubtitleLook.BOTTOM_FRACTION, 0f)
        assertEquals(48f, SubtitleLook.SAFE_SIDE_DP, 0f)
        assertEquals(27f, SubtitleLook.SAFE_BOTTOM_DP, 0f)
    }

    private fun field(view: SubtitleView, name: String): Any? =
        SubtitleView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)

    @Test
    fun `the view draws the look, the file's own sizes off, its styles dropped as the cues are laid out`() {
        val view = SubtitleView(context)
        view.applySubtitleLook()
        // Media3's switch would drop italics too: layOutCues drops the rest.
        assertEquals(true, field(view, "applyEmbeddedStyles"))
        assertEquals(false, field(view, "applyEmbeddedFontSizes"))
        val style = field(view, "style") as CaptionStyleCompat
        assertEquals(Color.TRANSPARENT, style.backgroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.edgeType)
        assertEquals(Cue.TEXT_SIZE_TYPE_FRACTIONAL, field(view, "defaultTextSizeType"))
        assertEquals(0.0533f, field(view, "defaultTextSize") as Float, 1e-6f)
    }

    @Test
    fun `the viewer's own caption settings win, box and size included`() {
        val resolver = context.contentResolver
        // Android's caption settings switched on, its default preset (white on
        // black) and a larger text.
        Settings.Secure.putInt(resolver, "accessibility_captioning_enabled", 1)
        Settings.Secure.putFloat(resolver, "accessibility_captioning_font_scale", 1.5f)
        val view = SubtitleView(context)
        view.applySubtitleLook()
        val style = field(view, "style") as CaptionStyleCompat
        assertEquals(Color.WHITE, style.foregroundColor)
        assertEquals(Color.BLACK, style.backgroundColor)
        assertEquals(0.0533f * 1.5f, field(view, "defaultTextSize") as Float, 1e-6f)
        // The file's sizes stay off: the viewer's look, not the file's.
        assertEquals(false, field(view, "applyEmbeddedFontSizes"))
        Settings.Secure.putInt(resolver, "accessibility_captioning_enabled", 0)
    }

    private fun text(s: CharSequence, line: Float = Cue.DIMEN_UNSET, lineType: Int = Cue.TYPE_UNSET) = Cue.Builder()
        .setText(s)
        .setLine(line, lineType)
        .build()

    @Test
    fun `what is shown at once is one subtitle, centred at the bottom within 80 percent, top line first`() {
        val top = Cue.Builder()
            .setText("[door creaks]")
            .setLine(0f, Cue.LINE_TYPE_NUMBER)
            .setPosition(0.1f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setTextAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setSize(0.3f)
            .setTextSize(0.2f, Cue.TEXT_SIZE_TYPE_FRACTIONAL)
            .setWindowColor(Color.BLACK)
            .build()
        val bottom = text("Hold the rope.", line = -1f, lineType = Cue.LINE_TYPE_NUMBER)
        val laidOut = layOutCues(listOf(bottom, top))
        val cue = laidOut.single()
        assertEquals("[door creaks]\nHold the rope.", cue.text.toString())
        assertEquals(Cue.DIMEN_UNSET, cue.line, 0f)
        assertEquals(0.5f, cue.position, 0f)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, cue.positionAnchor)
        assertEquals(0.8f, cue.size, 0f)
        assertEquals(Layout.Alignment.ALIGN_CENTER, cue.textAlignment)
        assertEquals(Cue.DIMEN_UNSET, cue.textSize, 0f)
        assertFalse(cue.windowColorSet)
        assertEquals(Cue.TYPE_UNSET, cue.verticalType)
    }

    @Test
    fun `two lines of one cue stay two lines, two cues at the bottom stay in the file's order`() {
        val first = text("The ferry leaves at seven,\nso we have the whole evening.")
        assertEquals("The ferry leaves at seven,\nso we have the whole evening.", layOutCues(listOf(first)).single().text.toString())
        val earlier = text("one", line = -1f, lineType = Cue.LINE_TYPE_NUMBER)
        val later = text("two", line = -2f, lineType = Cue.LINE_TYPE_NUMBER)
        // The later one sits above the earlier in the file: on top here too.
        assertEquals("two\none", layOutCues(listOf(earlier, later)).single().text.toString())
        assertEquals("a\nb", layOutCues(listOf(text("a", 0.2f, Cue.LINE_TYPE_FRACTION), text("b", 0.9f, Cue.LINE_TYPE_FRACTION))).single().text.toString())
    }

    @Test
    fun `the language's own spans stay with the text, each ruby reading drawn`() {
        val ruby = SpannableString("漢字")
        ruby.setSpan(RubySpan("かんじ", TextAnnotation.POSITION_BEFORE), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val laidOut = layOutCues(listOf(text(ruby))).single().text as Spanned
        assertEquals(1, laidOut.getSpans(0, laidOut.length, RubySpan::class.java).size)
        val reading = laidOut.getSpans(0, laidOut.length, RubyReading::class.java).single()
        assertEquals("かんじ", reading.reading)
        assertFalse(reading.under)
        assertEquals(0 to 2, laidOut.getSpanStart(reading) to laidOut.getSpanEnd(reading))
    }

    @Test
    fun `italics stay, every other style a file brings goes`() {
        val styled = SpannableString("a song, a thought, a shout")
        val all = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        styled.setSpan(StyleSpan(Typeface.ITALIC), 0, 6, all)
        styled.setSpan(StyleSpan(Typeface.BOLD_ITALIC), 8, 17, all)
        styled.setSpan(StyleSpan(Typeface.BOLD), 19, 26, all)
        styled.setSpan(ForegroundColorSpan(Color.YELLOW), 0, 26, all)
        styled.setSpan(BackgroundColorSpan(Color.BLUE), 0, 26, all)
        styled.setSpan(RelativeSizeSpan(1.5f), 0, 26, all)
        styled.setSpan(TypefaceSpan("serif"), 0, 26, all)
        styled.setSpan(UnderlineSpan(), 0, 26, all)
        val laidOut = layOutCues(listOf(text(styled))).single().text as Spanned
        val left = laidOut.getSpans(0, laidOut.length, Any::class.java).toList()
        // The italic one and the bold italic one, now italic: nothing else.
        assertEquals(2, left.size)
        assertTrue(left.all { it is StyleSpan && it.style == Typeface.ITALIC })
        assertEquals(listOf(0 to 6, 8 to 17), left.map { laidOut.getSpanStart(it) to laidOut.getSpanEnd(it) }.sortedBy { it.first })
        assertEquals("a song, a thought, a shout", laidOut.toString())
    }

    @Test
    fun `a picture subtitle keeps its own place, empty cues go`() {
        val picture = Cue.Builder()
            .setBitmap(Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888))
            .setPosition(0.2f)
            .setLine(0.85f, Cue.LINE_TYPE_FRACTION)
            .setSize(0.6f)
            .build()
        assertSame(picture, layOutCues(listOf(picture)).single())
        assertSame(picture, layOutCues(listOf(text(" "), picture)).single())
        assertEquals(emptyList<Cue>(), layOutCues(emptyList()))
        val both = layOutCues(listOf(picture, text("Hello")))
        assertEquals(2, both.size)
        assertSame(picture, both[0])
        assertNull(both[1].bitmap)
    }

    @Test
    fun `the bottom margin clears the safe area wherever the picture ends`() {
        val safe = 27f * 2 // 27 dp on a 1080p TV at density 2
        // A 16:9 picture filling the screen: Media3's 8 % (86 px) clears 54 px.
        assertEquals(0.08f, subtitleBottomFraction(1080, 1080, 1080, safe), 1e-6f)
        // A scope picture, letterboxed: its bottom edge is far from the screen's.
        assertEquals(0.08f, subtitleBottomFraction(804, 942, 1080, safe), 1e-6f)
        // A picture ending 10 px above the screen's edge, 300 px high: 44 px inside.
        assertEquals(44f / 300f, subtitleBottomFraction(300, 1070, 1080, safe), 1e-6f)
        assertEquals(0.08f, subtitleBottomFraction(0, 0, 1080, safe), 1e-6f)
    }

    @Test
    fun `while the controls show, the line ends above them, coming and going with them`() {
        val safe = 27f * 2
        val gap = 12f * 2
        // A full-screen picture, the controls' top 200 px above its bottom.
        val shown = subtitleBottomFraction(1080, 1080, 1080, safe, controlsTop = 880f, aboveControls = gap, lift = 1f)
        assertEquals((200f + 24f) / 1080f, shown, 1e-6f)
        // Hidden: where it rests; halfway in: halfway up.
        assertEquals(0.08f, subtitleBottomFraction(1080, 1080, 1080, safe, controlsTop = 880f, aboveControls = gap, lift = 0f), 1e-6f)
        assertEquals(0.08f + (shown - 0.08f) / 2f, subtitleBottomFraction(1080, 1080, 1080, safe, controlsTop = 880f, aboveControls = gap, lift = 0.5f), 1e-6f)
        // A scope picture whose bottom edge is above the controls: it stays.
        assertEquals(0.08f, subtitleBottomFraction(804, 942, 1080, safe, controlsTop = 960f, aboveControls = gap, lift = 1f), 1e-6f)
        // Controls not laid out yet: where it rests.
        assertEquals(0.08f, subtitleBottomFraction(1080, 1080, 1080, safe, controlsTop = null, lift = 1f), 1e-6f)
    }

    @Test
    fun `lines within 80 percent of the picture's width stay inside the side safe area`() {
        val screen = 1920
        val safe = 48f * 2
        for (pictureWidth in listOf(1920, 1440, 1080, 960, 720, 480)) {
            // Centred on the screen, as the player fits it.
            val left = (screen - pictureWidth) / 2f
            val textLeft = left + pictureWidth * (1f - SubtitleLook.LINE_WIDTH_FRACTION) / 2f
            assertTrue("$pictureWidth px wide", textLeft >= safe)
            assertTrue("$pictureWidth px wide", screen - (textLeft + pictureWidth * SubtitleLook.LINE_WIDTH_FRACTION) >= safe)
        }
    }
}
