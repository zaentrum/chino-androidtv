package cloud.nalet.chino.tv.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.accessibility.CaptioningManager
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.LanguageFeatureSpan
import androidx.media3.common.text.RubySpan
import androidx.media3.common.text.TextAnnotation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/*
 * How a subtitle looks: one look for every title, set here and nowhere else
 * (the design system's subtitles pattern). SubtitleStyleTest checks the
 * values and the layout.
 *
 * White text with a black outline and nothing behind it - no box, no window -
 * in the system's sans-serif, which has the letters of every language
 * (Chinese, Japanese and Korean included). Its size follows the picture:
 * 5.33 % of the picture's height, Media3's own default. Centred at the
 * bottom, the lines wrap within 80 % of the picture's width, inside the TV's
 * safe area (48 dp left and right, 27 dp at the bottom), so no line is cut at
 * an edge. What a file brings - its colours, boxes, fonts, sizes and places -
 * is ignored, but for its italics (a voice off screen, a song, a thought) and
 * its ruby (the readings above Japanese and Chinese characters), which mean
 * something. While the player's controls show, the line moves up above them,
 * and back down when they hide, at the controls' own pace. While one of the
 * player's menus is open beside it - subtitles, audio, quality, speed, at
 * the bottom-right above the controls - the line moves out of its way: it is
 * centred in the part of the picture left of the menu, within 80 % of that,
 * so the menu never hides the line it changes. Where the viewer
 * has switched Android's caption settings on (accessibility), their style and
 * their text size win, box included.
 *
 * The subtitles are drawn by one view, the PlayerView's own SubtitleView,
 * from the one text renderer the player has: one subtitle at a time.
 */

/** The one look of a subtitle, and where it sits. */
object SubtitleLook {
    /** White text, a black outline, no background and no window; the
     *  system's sans-serif. */
    val captionStyle: CaptionStyleCompat
        get() = CaptionStyleCompat(
            /* foregroundColor = */ Color.WHITE,
            /* backgroundColor = */ Color.TRANSPARENT,
            /* windowColor = */ Color.TRANSPARENT,
            /* edgeType = */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            /* edgeColor = */ Color.BLACK,
            /* typeface = */ Typeface.SANS_SERIF,
        )

    /** The text's size as a share of the picture's height: Media3's default,
     *  5.33 %. */
    const val TEXT_SIZE_FRACTION = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION

    /** The width the lines wrap within, as a share of the picture's width,
     *  centred: 80 %. */
    const val LINE_WIDTH_FRACTION = 0.8f

    /** How far above the picture's bottom edge the last line ends, as a share
     *  of the picture's height: Media3's default, 8 %. Raised where the
     *  picture reaches into the safe area by more ([subtitleBottomFraction]). */
    const val BOTTOM_FRACTION = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION

    /** The TV's safe area, left and right. */
    const val SAFE_SIDE_DP = 48f

    /** The TV's safe area, at the bottom. */
    const val SAFE_BOTTOM_DP = 27f

    /** How far above the player's controls the last line ends while they show. */
    const val ABOVE_CONTROLS_DP = 12f

    /** How far left of an open menu the line's box ends. */
    const val BESIDE_MENU_DP = 12f

    /** At most this share of the picture's width goes to a menu: the line
     *  keeps the rest. */
    const val MAX_MENU_SHARE = 0.5f

    /** A ruby reading's size, as a share of the text's. */
    const val RUBY_SIZE = 0.5f
}

/**
 * Where the last line of a subtitle ends, as a share of the picture's height
 * above its bottom edge: [SubtitleLook.BOTTOM_FRACTION], or more where the
 * picture's bottom edge lies inside the safe area so that 8 % would not clear
 * it. [pictureBottom] is the picture's bottom edge and [screenHeight] the
 * screen's, in pixels; [safeBottom] the safe area in pixels. While the
 * player's controls show, the line ends [SubtitleLook.ABOVE_CONTROLS_DP] above
 * their top edge [controlsTop] (screen pixels, [aboveControls] the gap in
 * pixels) where that is higher: by [lift] of the way, 0 with the controls
 * hidden, 1 with them shown, as they come and go.
 *
 * The lines need no such care at the sides: centred within 80 % of the
 * picture's width, they keep a tenth of the picture's width clear on either
 * side, more than the safe area's 5 % of the screen's for any picture at
 * least half as wide as the screen; a narrower one is centred, far from the
 * edges.
 */
fun subtitleBottomFraction(
    pictureHeight: Int,
    pictureBottom: Int,
    screenHeight: Int,
    safeBottom: Float,
    controlsTop: Float? = null,
    aboveControls: Float = 0f,
    lift: Float = 0f,
): Float {
    if (pictureHeight <= 0) return SubtitleLook.BOTTOM_FRACTION
    val below = (screenHeight - pictureBottom).coerceAtLeast(0)
    val inside = (safeBottom - below).coerceAtLeast(0f)
    val resting = maxOf(SubtitleLook.BOTTOM_FRACTION, inside / pictureHeight)
    if (controlsTop == null || lift <= 0f) return resting
    val clear = (pictureBottom - controlsTop + aboveControls) / pictureHeight
    val raised = clear.coerceAtMost(1f - SubtitleLook.TEXT_SIZE_FRACTION)
    if (raised <= resting) return resting
    return resting + (raised - resting) * lift.coerceAtMost(1f)
}

/**
 * How far before the picture's right edge the subtitle's box ends, in
 * pixels, so its line clears an open menu of the player: the menu's left
 * edge [menuLeft] (window pixels; null while none is open) and [gap] before
 * it, from the picture that starts at [pictureLeft] and is [pictureWidth]
 * wide - never more than [SubtitleLook.MAX_MENU_SHARE] of it - by [aside] of
 * the way, 0 with no menu, 1 with one open, as it comes and goes. 0 where the
 * menu lies past the picture.
 */
fun subtitleEndInset(pictureLeft: Int, pictureWidth: Int, menuLeft: Float?, gap: Float, aside: Float = 1f): Int {
    if (menuLeft == null || pictureWidth <= 0 || aside <= 0f) return 0
    val inset = (pictureLeft + pictureWidth - menuLeft + gap).coerceIn(0f, pictureWidth * SubtitleLook.MAX_MENU_SHARE)
    return kotlin.math.round(inset * aside.coerceAtMost(1f)).toInt()
}

/**
 * The cues as the look draws them. The text ones become one subtitle,
 * centred at the bottom within [SubtitleLook.LINE_WIDTH_FRACTION] of the
 * picture's width: what is shown at once, top to bottom as the file placed
 * it, each cue on lines of its own. The places a file gives its cues (a line
 * at the top, a position at the side, an alignment, vertical text), its box
 * and its text sizes go, and of its styles only italics and the language's
 * own spans stay ([keepItalicsAndRuby]); a ruby reading is drawn above its
 * characters. Picture cues (PGS) keep their own place: a picture cannot be
 * re-flowed.
 */
fun layOutCues(cues: List<Cue>): List<Cue> {
    val texts = cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }
    if (texts.isEmpty()) return cues.filter { it.bitmap != null }
    val text = SpannableStringBuilder()
    texts.withIndex()
        .sortedWith(compareBy<IndexedValue<Cue>> { heightOnScreen(it.value) }.thenBy { it.index })
        .forEachIndexed { i, (_, cue) ->
            if (i > 0) text.append('\n')
            text.append(cue.text)
        }
    keepItalicsAndRuby(text)
    val subtitle = Cue.Builder()
        .setText(text)
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .setSize(SubtitleLook.LINE_WIDTH_FRACTION)
        .build()
    return cues.filter { it.bitmap != null } + subtitle
}

/**
 * Drops every style a file gives its text but italics and the spans of the
 * language itself (ruby, emphasis marks): bold, colours, backgrounds, sizes,
 * fonts, underlines. A bold italic stays italic. Each ruby reading gets a
 * [RubyReading] to draw it: Media3's own drawing shows the characters
 * without it.
 */
internal fun keepItalicsAndRuby(text: Spannable) {
    for (span in text.getSpans(0, text.length, Any::class.java)) {
        if (span is RubyReading) continue
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        val flags = text.getSpanFlags(span)
        when {
            span is StyleSpan && span.style == Typeface.ITALIC -> Unit
            span is StyleSpan && span.style == Typeface.BOLD_ITALIC -> {
                text.removeSpan(span)
                text.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
            }
            span is RubySpan -> text.setSpan(RubyReading(span.rubyText, under = span.position == TextAnnotation.POSITION_AFTER), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span is LanguageFeatureSpan -> Unit
            else -> text.removeSpan(span)
        }
    }
}

/**
 * A ruby reading drawn with its characters: the reading at half their size,
 * centred over them (under them where the file puts it after), in the
 * subtitle's own paint, so the outline goes round it as round the text. The
 * line grows by the reading's height.
 */
class RubyReading(val reading: String, val under: Boolean) : ReplacementSpan() {
    private fun small(paint: Paint) = TextPaint(paint).apply { textSize = paint.textSize * SubtitleLook.RUBY_SIZE }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val small = small(paint)
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val extra = kotlin.math.ceil(small.descent() - small.ascent()).toInt()
            if (under) {
                fm.descent += extra
                fm.bottom += extra
            } else {
                fm.ascent -= extra
                fm.top -= extra
            }
        }
        return kotlin.math.ceil(maxOf(paint.measureText(text, start, end), small.measureText(reading))).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val small = small(paint)
        val base = paint.measureText(text, start, end)
        val above = small.measureText(reading)
        val width = maxOf(base, above)
        canvas.drawText(text, start, end, x + (width - base) / 2f, y.toFloat(), paint)
        val readingY = if (under) y + paint.descent() - small.ascent() else y + paint.ascent() - small.descent()
        canvas.drawText(reading, x + (width - above) / 2f, readingY, small)
    }
}

/** How high a file placed a cue, from 0 (the top) to 1 (the bottom): what
 *  orders two cues shown at once. One with no line is at the bottom. */
private fun heightOnScreen(cue: Cue): Float {
    if (cue.line == Cue.DIMEN_UNSET) return 1f
    return when (cue.lineType) {
        Cue.LINE_TYPE_FRACTION -> cue.line.coerceIn(0f, 1f)
        Cue.LINE_TYPE_NUMBER -> if (cue.line >= 0) (cue.line / 15f).coerceIn(0f, 1f) else (1f + (cue.line + 1f) / 15f).coerceIn(0f, 1f)
        else -> 1f
    }
}

/** What a text renderer hands on, laid out by [layOutCues]: every cue the
 *  player shows passes here. */
class LaidOutCues(private val output: TextOutput) : TextOutput {
    @Deprecated("Use onCues(CueGroup)")
    @Suppress("DEPRECATION")
    override fun onCues(cues: List<Cue>) = output.onCues(layOutCues(cues))

    override fun onCues(cueGroup: CueGroup) = output.onCues(CueGroup(layOutCues(cueGroup.cues), cueGroup.presentationTimeUs))
}

/**
 * The player's renderers: Media3's, with one text renderer whose cues are
 * laid out by [layOutCues]. It decodes the side-loaded subtitles as they
 * come (legacy decoding): Media3 fetches a side-loaded subtitle only once it
 * is on when it decodes it here, rather than parsing every one as the
 * playback starts.
 */
class StreamRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out += TextRenderer(LaidOutCues(output), outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) }
    }
}

/** Android's caption settings where the viewer has switched them on
 *  (accessibility), else null. */
private fun viewerCaptions(context: Context): CaptioningManager? =
    (context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager)?.takeIf { it.isEnabled }

/**
 * Where a subtitle sits: above the bottom of the picture and the safe area,
 * and above the player's controls while they show ([subtitleBottomFraction]);
 * left of a menu of the player while one is open ([subtitleEndInset]). The
 * player tells it where the controls' top edge is and how far they have come
 * in, and where an open menu's left edge is.
 */
class SubtitlePlacement internal constructor(private val view: SubtitleView) {
    private var bottom = SubtitleLook.BOTTOM_FRACTION
    private var end = 0

    /** The left edge of the player's open menu, in window pixels; null while
     *  none is open. */
    var menuLeft: Float? = null
        set(value) {
            field = value
            update()
        }

    /** How far the line has moved out of a menu's way: 0 none open, 1 one
     *  open. */
    var aside: Float = 0f
        set(value) {
            field = value
            update()
        }

    /** The top edge of the player's controls, in window pixels; null before
     *  they have been laid out. */
    var controlsTop: Float? = null
        set(value) {
            field = value
            update()
        }

    /** How far the controls have come in: 0 hidden, 1 shown. */
    var lift: Float = 0f
        set(value) {
            field = value
            update()
        }

    internal fun update() {
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val density = view.resources.displayMetrics.density
        val fraction = subtitleBottomFraction(
            pictureHeight = view.height,
            pictureBottom = at[1] + view.height,
            screenHeight = view.rootView.height,
            safeBottom = SubtitleLook.SAFE_BOTTOM_DP * density,
            controlsTop = controlsTop,
            aboveControls = SubtitleLook.ABOVE_CONTROLS_DP * density,
            lift = lift,
        )
        // Set only when it changed: each set asks for a new drawing.
        if (fraction != bottom) {
            bottom = fraction
            view.setBottomPaddingFraction(fraction)
        }
        // Beside an open menu: the box the line is centred in ends left of
        // it - a padding of the view at its right, which leaves the text's
        // size, a share of the picture's height, as it is.
        val inset = subtitleEndInset(at[0], view.width, menuLeft, SubtitleLook.BESIDE_MENU_DP * density, aside)
        if (inset != end) {
            end = inset
            view.setPadding(view.paddingLeft, view.paddingTop, inset, view.paddingBottom)
        }
    }
}

/**
 * Gives [view] the look: embedded sizes off, the file's styles but italics
 * and ruby dropped as the cues are laid out ([layOutCues]), and
 * [SubtitleLook]'s style and size - or, where Android's caption settings are
 * on, the viewer's style and their text size (their scale of our 5.33 %).
 * Again whenever those settings change, while the view is attached. The
 * bottom margin follows the picture's place on the screen and the player's
 * controls: the [SubtitlePlacement] it returns.
 */
fun SubtitleView.applySubtitleLook(): SubtitlePlacement {
    // Media3's switch for embedded styles would drop italics with the rest:
    // layOutCues keeps them, and only them and ruby.
    setApplyEmbeddedStyles(true)
    setApplyEmbeddedFontSizes(false)
    applyCaptionSettings()
    val captioning = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
    val listener = object : CaptioningManager.CaptioningChangeListener() {
        override fun onEnabledChanged(enabled: Boolean) = applyCaptionSettings()
        override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) = applyCaptionSettings()
        override fun onFontScaleChanged(fontScale: Float) = applyCaptionSettings()
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            captioning?.addCaptioningChangeListener(listener)
            applyCaptionSettings()
        }

        override fun onViewDetachedFromWindow(v: View) {
            captioning?.removeCaptioningChangeListener(listener)
        }
    })
    if (isAttachedToWindow) captioning?.addCaptioningChangeListener(listener)
    // The bottom margin set last: a layout pass sets it only when it changed.
    val placement = SubtitlePlacement(this)
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placement.update() }
    return placement
}

/** The style and size: the viewer's where Android's caption settings are on,
 *  else ours. */
private fun SubtitleView.applyCaptionSettings() {
    val own = viewerCaptions(context)
    setStyle(own?.let { CaptionStyleCompat.createFromCaptionStyle(it.userStyle) } ?: SubtitleLook.captionStyle)
    setFractionalTextSize(SubtitleLook.TEXT_SIZE_FRACTION * (own?.fontScale ?: 1f))
}
