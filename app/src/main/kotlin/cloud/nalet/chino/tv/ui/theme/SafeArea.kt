package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.SurfaceDefaults

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
 * ([bleedToScreenEdges], [paintToScreenEdges], [scrim]).
 */
object SafeArea {
    /** From the left and from the right edge. */
    val Horizontal: Dp = tvDp(48f)

    /** From the top and from the bottom edge. */
    val Vertical: Dp = tvDp(27f)

    /** The safe area's inset from the screen's edges. */
    val Insets: PaddingValues = PaddingValues(horizontal = Horizontal, vertical = Vertical)
}

/** A page's own background: the canvas, --bg, out to the screen's edges. */
val PageColors @Composable get() = SurfaceDefaults.colors(containerColor = ChinoBg)

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
