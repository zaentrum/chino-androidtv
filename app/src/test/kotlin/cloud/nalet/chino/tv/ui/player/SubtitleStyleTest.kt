package cloud.nalet.chino.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.provider.Settings
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
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
    fun `the view draws the look, the file's own styles and sizes off`() {
        val view = SubtitleView(context)
        view.applySubtitleLook()
        assertEquals(false, field(view, "applyEmbeddedStyles"))
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
        // Embedded styles stay off: the viewer's look, not the file's.
        assertEquals(false, field(view, "applyEmbeddedStyles"))
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
    fun `the language's own spans stay with the text`() {
        val ruby = SpannableString("漢字")
        ruby.setSpan(RubySpan("かんじ", TextAnnotation.POSITION_BEFORE), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val laidOut = layOutCues(listOf(text(ruby))).single().text as Spanned
        assertEquals(1, laidOut.getSpans(0, laidOut.length, RubySpan::class.java).size)
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
