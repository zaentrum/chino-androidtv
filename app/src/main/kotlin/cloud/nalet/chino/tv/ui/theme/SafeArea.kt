package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme

/**
 * The TV's safe area (the design system's tv focus): the part of the picture
 * every TV shows in full - some cut a few percent off its edges. Everything
 * that takes focus and all text stays inside it, 48 dp from the left and the
 * right edge and 27 dp from the top and the bottom (the TV's dp), measured to
 * a focused item's ring at its growth.
 *
 * It is laid out once for the whole app: the shell puts every screen inside
 * it ([SafeAreaScreen]), and the player and Zap put their controls, menus and
 * messages inside it. An item at a screen's edge keeps the room its ring and
 * growth need inside that: the rail's and the top bar's padding, a shelf's
 * room ([shelfRoom]). What only paints - a backdrop, the band behind the
 * player's controls, a scrim - reaches the screen's edges
 * ([bleedToScreenEdges], [paintToScreenEdges], [scrim]). The safe area sets
 * where resting content starts, not a clip: a page that scrolls - Home, a
 * title's page - scrolls out to the screen's end and bottom edges, its rows
 * run to the screen's edge ([runToScreenEnd]), and a focused item is
 * brought into view inside the safe area ([ScrollsInSafeArea]).
 */
object SafeArea {
    /** From the left and from the right edge. */
    val Horizontal: Dp = tvDp(48f)

    /** From the top and from the bottom edge. */
    val Vertical: Dp = tvDp(27f)

    /** The safe area's inset from the screen's edges. */
    val Insets: PaddingValues = PaddingValues(horizontal = Horizontal, vertical = Vertical)
}

/**
 * A page of the shell, in [SafeAreaScreen]: its text in fg-2 on the canvas
 * the shell paints out to the screen's edges. Not clipped - as a tv-material
 * Surface would be - so a backdrop or a scrim on it reaches past the safe
 * area to the screen's edges.
 */
@Composable
fun ChinoPage(content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier = Modifier.fillMaxSize(), content = content)
    }
}

/** A screen of the shell, inside the safe area, on the canvas out to the
 *  screen's edges. */
@Composable
fun SafeAreaScreen(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChinoBg)
            .padding(SafeArea.Insets),
        content = content,
    )
}

/** What a full-screen picture - the player, Zap - shows over it, inside
 *  the safe area: its controls, menus and messages. */
@Composable
fun SafeAreaOverlay(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(SafeArea.Insets), content = content)
}

/**
 * Lays out an item that only paints - a backdrop - out past the safe area it
 * sits at the edge of, to the screen's [start], [top], [end] and [bottom]
 * edges, where its parent stays where it was. An unbounded side (a scrolling
 * column's height) is left as it is.
 */
fun Modifier.bleedToScreenEdges(
    start: Boolean = true,
    top: Boolean = true,
    end: Boolean = true,
    bottom: Boolean = true,
): Modifier = layout { measurable, constraints ->
    val h = SafeArea.Horizontal.roundToPx()
    val v = SafeArea.Vertical.roundToPx()
    val left = if (start) h else 0
    val right = if (end) h else 0
    val above = if (top && constraints.hasBoundedHeight) v else 0
    val below = if (bottom && constraints.hasBoundedHeight) v else 0
    val wide = if (constraints.hasBoundedWidth) left + right else 0
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + wide,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + wide else constraints.maxWidth,
            minHeight = constraints.minHeight + above + below,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + above + below else constraints.maxHeight,
        ),
    )
    val width = (placeable.width - wide).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = (placeable.height - above - below).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) { placeable.place(if (wide > 0) -left else 0, -above) }
}

/** Paints [color] behind an item and out past the safe area it sits at the
 *  edge of, to the screen's [start], [top], [end] and [bottom] edges: the
 *  band behind the player's controls. */
fun Modifier.paintToScreenEdges(
    color: Color,
    start: Boolean = true,
    top: Boolean = true,
    end: Boolean = true,
    bottom: Boolean = true,
): Modifier = paintToScreenEdges(SolidColor(color), start, top, end, bottom)

/** Paints [brush] - a gradient over the whole of what it paints - behind an
 *  item and out to the screen's edges, as [paintToScreenEdges] a colour. */
fun Modifier.paintToScreenEdges(
    brush: Brush,
    start: Boolean = true,
    top: Boolean = true,
    end: Boolean = true,
    bottom: Boolean = true,
): Modifier = drawBehind {
    val h = SafeArea.Horizontal.toPx()
    val v = SafeArea.Vertical.toPx()
    val left = if (start) h else 0f
    val above = if (top) v else 0f
    drawRect(
        brush = brush,
        topLeft = Offset(-left, -above),
        size = Size(size.width + left + (if (end) h else 0f), size.height + above + (if (bottom) v else 0f)),
    )
}

/**
 * A page that scrolls, laid out past the safe area to the screen's end and
 * bottom edges: what scrolls by shows out to them, never cut at the safe
 * line. Put it before the scroll; give the scrolled content [SafeArea]'s
 * end and bottom as padding, so it rests where it did, inside the safe
 * area, and [ScrollsInSafeArea] around it, so a focused item is brought into
 * view inside it.
 */
fun Modifier.scrollsToScreenEdges(): Modifier = bleedToScreenEdges(start = false, top = false)

/**
 * A scrolling row on a page that scrolls to the screen's edges: laid out
 * [by] past its parent's end - the page's padding there and the safe
 * area's - so its cards run to the screen's edge. Its content padding keeps
 * [by] after its last card, so that one rests where it did ([shelfPadding]).
 */
fun Modifier.runToScreenEnd(by: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = by.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout((placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth), placeable.height) {
        placeable.place(0, 0)
    }
}

/**
 * Brings a focused item into view inside the safe area of a list that runs
 * past it: no nearer the container's end than [trailing] px nor its start
 * than [leading] px, scrolling as little as that takes. The item a remote
 * moves to, its ring and its growth stay inside the safe area; one that is
 * there already stays where it is. One rule for the item and for its ring's
 * room around it (the focus style asks for both), so the two never pull the
 * list two ways.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class SafeAreaBringIntoView(
    private val leading: Float,
    private val trailing: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = leading
        val end = containerSize - trailing
        return when {
            // Bigger than the room: its start at the start.
            size > end - start -> offset - start
            offset < start -> offset - start
            offset + size > end -> offset + size - end
            else -> 0f
        }
    }
}

/**
 * What scrolls in [content] - the page's own scroll, or a row of it -
 * brings a focused item into view [trailing] clear of its end and
 * [leading] clear of its start ([SafeAreaBringIntoView]): for a page that
 * scrolls to the screen's bottom edge the safe area's bottom, for a row
 * that runs to the screen's edge the safe area's side and the room a ring
 * needs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollsInSafeArea(trailing: Dp, leading: Dp = 0.dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density, trailing, leading) {
        with(density) { SafeAreaBringIntoView(leading.toPx(), trailing.toPx()) }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
