package cloud.nalet.chino.tv.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.text.Layout
import android.text.SpannableStringBuilder
import android.view.View
import android.view.accessibility.CaptioningManager
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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
 * is ignored. Where the viewer has switched Android's caption settings on
 * (accessibility), their style and their text size win, box included.
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
}

/**
 * Where the last line of a subtitle ends, as a share of the picture's height
 * above its bottom edge: [SubtitleLook.BOTTOM_FRACTION], or more where the
 * picture's bottom edge lies inside the safe area so that 8 % would not clear
 * it. [pictureBottom] is the picture's bottom edge and [screenHeight] the
 * screen's, in pixels; [safeBottom] the safe area in pixels.
 *
 * The lines need no such care at the sides: centred within 80 % of the
 * picture's width, they keep a tenth of the picture's width clear on either
 * side, more than the safe area's 5 % of the screen's for any picture at
 * least half as wide as the screen; a narrower one is centred, far from the
 * edges.
 */
fun subtitleBottomFraction(pictureHeight: Int, pictureBottom: Int, screenHeight: Int, safeBottom: Float): Float {
    if (pictureHeight <= 0) return SubtitleLook.BOTTOM_FRACTION
    val below = (screenHeight - pictureBottom).coerceAtLeast(0)
    val inside = (safeBottom - below).coerceAtLeast(0f)
    return maxOf(SubtitleLook.BOTTOM_FRACTION, inside / pictureHeight)
}

/**
 * The cues as the look draws them. The text ones become one subtitle,
 * centred at the bottom within [SubtitleLook.LINE_WIDTH_FRACTION] of the
 * picture's width: what is shown at once, top to bottom as the file placed
 * it, each cue on lines of its own. The places a file gives its cues (a line
 * at the top, a position at the side, an alignment, vertical text) and its
 * text sizes go; its spans stay for the view, which keeps only those of the
 * language itself (ruby) once embedded styles are off. Picture cues (PGS)
 * keep their own place: a picture cannot be re-flowed.
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
    val subtitle = Cue.Builder()
        .setText(text)
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .setSize(SubtitleLook.LINE_WIDTH_FRACTION)
        .build()
    return cues.filter { it.bitmap != null } + subtitle
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
 * Gives [view] the look: embedded styles and sizes off, and [SubtitleLook]'s
 * style and size - or, where Android's caption settings are on, the viewer's
 * style and their text size (their scale of our 5.33 %). Again whenever those
 * settings change, while the view is attached; the bottom margin follows the
 * picture's place on the screen ([subtitleBottomFraction]).
 */
fun SubtitleView.applySubtitleLook() {
    setApplyEmbeddedStyles(false)
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
    var bottom = SubtitleLook.BOTTOM_FRACTION
    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        val safe = SubtitleLook.SAFE_BOTTOM_DP * v.resources.displayMetrics.density
        val fraction = subtitleBottomFraction(v.height, at[1] + v.height, v.rootView.height, safe)
        if (fraction != bottom) {
            bottom = fraction
            setBottomPaddingFraction(fraction)
        }
    }
}

/** The style and size: the viewer's where Android's caption settings are on,
 *  else ours. */
private fun SubtitleView.applyCaptionSettings() {
    val own = viewerCaptions(context)
    setStyle(own?.let { CaptionStyleCompat.createFromCaptionStyle(it.userStyle) } ?: SubtitleLook.captionStyle)
    setFractionalTextSize(SubtitleLook.TEXT_SIZE_FRACTION * (own?.fontScale ?: 1f))
}
