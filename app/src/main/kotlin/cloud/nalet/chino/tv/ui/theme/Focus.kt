package cloud.nalet.chino.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.launch

/*
 * The one focus style of the TV app: the design system's tv focus (10-foot),
 * https://nalet.github.io/design-system/patterns/tv-focus/. Every focusable
 * item takes it from here, a row, a card, a button or a chip, and no screen
 * has a focus style of its own: no screen passes a focused colour, border or
 * scale. When an item gets focus, four things change together:
 *
 * - a ring in the focus colour, 3 dp wide - it does most of the work, at
 *   least 3:1 against everything around it;
 * - the focused surface behind rows, plain buttons and chips;
 * - a little growth: 5 % for cards, buttons and chips, 2 % for rows;
 * - brighter text: the title in the brightest text colour, the text under
 *   it one step brighter ([ChinoFocus.title], [ChinoFocus.text]).
 *
 * Rows draw the ring inside their edge; cards, buttons and chips outside,
 * with a 2 dp gap where the background shows, so the ring has the dark
 * background on both sides on any poster or fill. The ring means focus only:
 * where a screen was opened from is a bar along an item's left edge in the
 * accent ([ChinoFocus.CameFrom]). The ring and the growth settle in 150 ms,
 * easing out; with the system's animations off they appear at once (Compose
 * scales its animations by the system's animator duration scale).
 *
 * Its colours are the tokens in Color.kt; the values here change in one place
 * when the design system's do. FocusContrastTest computes every pair from
 * them; the screenshot tests show each kind at rest and focused.
 */

/**
 * A length in the TV's own dp, the design system's unit: 1/960 of the
 * screen's width, so 2 px on a 1920 × 1080 screen. This app lays out at
 * [UI_SCALE] of that (ChinoTvTheme): the design system's 3 dp ring is 4 of
 * this app's dp.
 */
fun tvDp(value: Float): Dp = (value / UI_SCALE).dp

/** How the focus style sits on an item, by how it sits on the screen. */
enum class FocusKind(
    /** How much a focused item grows. */
    val growth: Float,
    /** The ring outside the item, with a gap; else inside its edge. */
    val ringOutside: Boolean,
    /** A plain item gets the focused surface; a card shows its artwork unchanged. */
    val lightsUp: Boolean,
) {
    /** An episode, a setting, a menu entry, a field: the ring inside the
     *  edge, no gap, the focused surface, 2 % growth. */
    Row(growth = 1.02f, ringOutside = false, lightsUp = true),

    /** A poster, a still, a person: the ring outside with a gap, the artwork
     *  unchanged, 5 % growth. */
    Card(growth = 1.05f, ringOutside = true, lightsUp = false),

    /** A button or a chip, an icon button, a toggle and a nav item among them:
     *  the ring outside with a gap, 5 % growth; a plain one gets the focused
     *  surface, a filled one keeps its fill. */
    Control(growth = 1.05f, ringOutside = true, lightsUp = true),
}

/** The focus style's values, in one place. */
object ChinoFocus {
    /** The ring, around every focused item. */
    val Ring: Color = ChinoFocusRing
    val RingWidth: Dp = tvDp(3f)

    /** Between a card, button or chip and its ring, where the background shows. */
    val RingGap: Dp = tvDp(2f)

    /** Behind a focused row, plain button or chip. It helps; it never shows
     *  focus on its own. */
    val Surface: Color = ChinoSurfaceFocus

    /** "Where you came from" - the episode a detail page opened on: a bar
     *  along the item's left edge in the accent, never a ring. */
    val CameFrom: Color = ChinoAccent
    val CameFromWidth: Dp = tvDp(4f)

    /** At least this much room between two cards, and between a list and the
     *  screen's edges, so a focused card never touches its neighbours and a
     *  growing row is never cut off. */
    val Room: Dp = tvDp(12f)

    /** The ring and the growth settle in this long, easing out (CSS ease-out). */
    const val SettleMillis: Int = 150
    val SettleEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** The focused item's title: the brightest text colour; a colour that is
     *  brighter already stays. */
    fun title(rest: Color, focused: Boolean): Color =
        if (focused && rest.luminance() < ChinoFg.luminance()) ChinoFg else rest

    /** Text under a focused item's title, one step brighter: fg-muted becomes
     *  fg-2 and fg-dim becomes fg-muted (fg-dim never sits on the focused
     *  surface). The error's red, which does not read on the focused surface
     *  (3.88:1), becomes fg; an icon beside it keeps the red, which is enough
     *  for an icon. Other colours stay. */
    fun text(rest: Color, focused: Boolean): Color = when {
        !focused -> rest
        rest == ChinoMuted -> ChinoText
        rest == ChinoDim -> ChinoMuted
        rest == ChinoError -> ChinoFg
        else -> rest
    }

    /** A plain tv-material button's colours at rest: the platform's. */
    val PlainContainer: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    val PlainContent: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
}

/** Whether an item has the focus: what its focus style and its text follow. */
@Stable
class ItemFocus(
    /** Drawn focused from the first frame, before the focus a screen asks for
     *  lands: the first item of a picker, so its ring does not flicker in.
     *  Until real focus has come, a report of none does not clear it. */
    preFocused: Boolean = false,
) {
    var focused: Boolean by mutableStateOf(preFocused)
        private set
    private var settled = !preFocused

    internal fun report(isFocused: Boolean) {
        if (isFocused) {
            focused = true
            settled = true
        } else if (settled) {
            focused = false
        }
    }
}

@Composable
fun rememberItemFocus(preFocused: Boolean = false): ItemFocus = remember { ItemFocus(preFocused) }

/**
 * The focus style on an item. Put it before the item's focus target
 * (clickable, focusable, a tv-material component's own), so it follows that
 * target's focus, and after the item's size.
 *
 * [surface] is the item's own fill at rest, painted here; leave it null where
 * the item paints its own (a tv-material button or card does). A [filled]
 * control keeps its fill on focus; a plain one, and every row, gets the
 * focused surface. [cameFrom] draws the "where you came from" bar.
 * [ringGap] paints the gap between the item and its ring, for an item that
 * touches another of a colour the ring would not stand out on (the parts of
 * add to list, both blue): the ring then lies on that colour all round.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusStyle(
    focus: ItemFocus,
    kind: FocusKind,
    surface: Color? = null,
    filled: Boolean = false,
    cameFrom: Boolean = false,
    ringGap: Color? = null,
): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (focus.focused) 1f else 0f,
        animationSpec = tween(durationMillis = ChinoFocus.SettleMillis, easing = ChinoFocus.SettleEasing),
        label = "focus",
    )
    val density = LocalDensity.current
    val ringPx = with(density) { ChinoFocus.RingWidth.toPx() }
    val gapPx = with(density) { ChinoFocus.RingGap.toPx() }
    val barPx = with(density) { ChinoFocus.CameFromWidth.toPx() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val fill = surface?.let { if (focus.focused && kind.lightsUp && !filled) ChinoFocus.Surface else it }
    return this
        .bringIntoViewRequester(bringIntoView)
        .onSizeChanged { measured = it }
        .onFocusChanged { state ->
            focus.report(state.isFocused)
            // Scroll the grown item and its ring into view, not only the
            // item: a scrolling list would stop with the ring past its edge.
            if (state.isFocused) {
                val room = focusRoom(measured, kind, ringPx, gapPx)
                scope.launch { bringIntoView.bringIntoView(room) }
            }
        }
        // A focused item draws over its neighbours, its ring included.
        .zIndex(if (focus.focused || progress > 0f) 1f else 0f)
        .graphicsLayer {
            val grown = 1f + (kind.growth - 1f) * progress
            scaleX = grown
            scaleY = grown
        }
        .drawWithContent {
            if (fill != null && fill.alpha > 0f) drawRect(fill)
            drawContent()
            // Lengths on the screen, whatever the growth.
            val grown = 1f + (kind.growth - 1f) * progress
            if (cameFrom) {
                // Inside the ring while the item has focus.
                val inset = if (kind.ringOutside) 0f else ringPx * progress / grown
                drawRect(
                    color = ChinoFocus.CameFrom,
                    topLeft = Offset(inset, 0f),
                    size = Size(barPx / grown, size.height),
                )
            }
            if (progress > 0f && ringGap != null && kind.ringOutside) {
                val gap = gapPx / grown
                drawRect(
                    color = ringGap,
                    alpha = progress,
                    topLeft = Offset(-gap / 2f, -gap / 2f),
                    size = Size(size.width + gap, size.height + gap),
                    style = Stroke(width = gap),
                )
            }
            if (progress > 0f) drawFocusRing(kind.ringOutside, ringPx / grown, gapPx / grown, progress)
        }
}

/** The ring: its stroke inside the edge, or outside it past [gap]. */
private fun DrawScope.drawFocusRing(outside: Boolean, ring: Float, gap: Float, alpha: Float) {
    val half = ring / 2f
    // Where the stroke's middle runs, past the edge (inside when negative).
    val out = if (outside) gap + half else -half
    drawRect(
        color = ChinoFocus.Ring,
        alpha = alpha,
        topLeft = Offset(-out, -out),
        size = Size(size.width + 2f * out, size.height + 2f * out),
        style = Stroke(width = ring),
    )
}

/** The item grown, with its ring: what a focused item needs to see of itself. */
private fun focusRoom(size: IntSize, kind: FocusKind, ring: Float, gap: Float): Rect {
    val growX = size.width * (kind.growth - 1f) / 2f
    val growY = size.height * (kind.growth - 1f) / 2f
    val ringOut = if (kind.ringOutside) (gap + ring) * kind.growth else 0f
    return Rect(
        left = -growX - ringOut,
        top = -growY - ringOut,
        right = size.width + growX + ringOut,
        bottom = size.height + growY + ringOut,
    )
}

/**
 * Room for the focus style at a horizontal shelf's ends: the shelf reaches
 * [ChinoFocus.Room] past its parent on both sides, so a focused first or last
 * card's growth and ring are not cut off by the shelf's edge, while its cards
 * stay where they were. Give the shelf [shelfPadding] as its content padding.
 */
fun Modifier.shelfRoom(): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val room = ChinoFocus.Room.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + 2 * room,
            maxWidth = constraints.maxWidth + 2 * room,
        ),
    )
    val width = (placeable.width - 2 * room).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-room, 0) }
}

/** A shelf's content padding with [Modifier.shelfRoom]: its cards where they
 *  were without it, [end] past the last one. */
fun shelfPadding(end: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues =
    PaddingValues(start = ChinoFocus.Room, end = ChinoFocus.Room + end, top = top, bottom = bottom)

/**
 * A tv-material button in the focus style. A plain one (the default) gets the
 * focused surface and its label the brightest text colour on focus; a
 * [filled] one keeps its fill and its label. [container] and [content] are
 * its colours at rest; on focus the style takes over, so a screen passes no
 * focused colour.
 */
@Composable
fun ChinoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = ChinoFocus.PlainContainer,
    content: Color = ChinoFocus.PlainContent,
    filled: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    focus: ItemFocus = rememberItemFocus(),
    /** The colour between the button and its ring ([focusStyle]'s ringGap). */
    ringGap: Color? = null,
    body: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.focusStyle(focus, FocusKind.Control, ringGap = ringGap),
        enabled = enabled,
        // The growth and the ring are the focus style's.
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        border = ButtonDefaults.border(),
        glow = ButtonDefaults.glow(),
        shape = ButtonDefaults.shape(shape = RectangleShape),
        colors = ButtonDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = if (filled) container else ChinoFocus.Surface,
            focusedContentColor = if (filled) content else ChinoFocus.title(content.copy(alpha = 1f), focused = true),
        ),
        contentPadding = contentPadding,
        content = body,
    )
}

/**
 * A tv-material card in the focus style: the ring outside with a gap, 5 %
 * growth, the artwork unchanged. [focus] tells its content when to brighten
 * its text.
 */
@Composable
fun ChinoCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focus: ItemFocus = rememberItemFocus(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.focusStyle(focus, FocusKind.Card),
        onLongClick = onLongClick,
        shape = CardDefaults.shape(shape = RectangleShape),
        // The growth and the ring are the focus style's; the artwork stays.
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
        glow = CardDefaults.glow(),
        content = content,
    )
}
