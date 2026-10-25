package cloud.nalet.chino.tv.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced

/**
 * What a focused item brings into view along with itself, where the screen
 * has room for both: the top of the page above the controls at its head - a
 * title's name, its year and its meta, the top of its poster, the "settings"
 * heading ([PageTop]) - and a setting's text under its controls, the last
 * line of Settings among them ([PartBottom]).
 *
 * The focus style asks for it beside its own request for the item and its
 * ring ([focusStyle]): a page that scrolls shows the largest of the two that
 * fits, so where the two do not fit together the item alone comes into view,
 * never cut. Given to the items inside it by [LocalFocusAlong].
 */
@Stable
interface FocusAlong {
    /** The part to show along with [item], as its top and its bottom in the
     *  item's own pixels; null for nothing more. */
    fun span(item: LayoutCoordinates): Span?

    /** UP on the item asks for the part again, whatever the focus does: UP
     *  from a page's first control shows the page's top. */
    val onUp: Boolean get() = false
}

/** A vertical span in an item's pixels: [top] to [bottom]. */
data class Span(val top: Float, val bottom: Float)

/** What the focused items inside it show along with themselves. */
val LocalFocusAlong = staticCompositionLocalOf<FocusAlong?> { null }

/** Both [this] and [other]: the span over the two. */
infix fun FocusAlong?.and(other: FocusAlong?): FocusAlong? = when {
    this == null -> other
    other == null -> this
    else -> {
        val a = this
        object : FocusAlong {
            override fun span(item: LayoutCoordinates): Span? {
                val x = a.span(item)
                val y = other.span(item)
                return when {
                    x == null -> y
                    y == null -> x
                    else -> Span(minOf(x.top, y.top), maxOf(x.bottom, y.bottom))
                }
            }

            override val onUp: Boolean get() = a.onUp || other.onUp
        }
    }
}

/**
 * The top of a page that scrolls: the controls at its head bring it into
 * view along with themselves, so the focus coming back to them - UP from the
 * credits, from the episodes - shows the page's top again, never the
 * controls at the screen's top edge with the title above it scrolled away.
 * Put [Modifier.pageTop] on the page's scrolled content, after its scroll.
 */
@Stable
class PageTop : FocusAlong {
    internal var content: LayoutCoordinates? = null

    override fun span(item: LayoutCoordinates): Span? {
        val page = content?.takeIf { it.isAttached && item.isAttached } ?: return null
        // From the page's top down to the item.
        return Span(top = item.localPositionOf(page, Offset.Zero).y, bottom = 0f)
    }

    override val onUp: Boolean get() = true
}

/** The page [top] is the top of: its scrolled content. */
fun Modifier.pageTop(top: PageTop): Modifier = onPlaced { top.content = it }

/**
 * A part of a page - a setting with the text that explains it under its
 * controls - whose bottom a focused control inside it brings into view along
 * with itself: the text is read after the choice it explains, never cut at
 * the screen's bottom. Put [Modifier.partBottom] on the part.
 */
@Stable
class PartBottom : FocusAlong {
    internal var part: LayoutCoordinates? = null

    override fun span(item: LayoutCoordinates): Span? {
        val p = part?.takeIf { it.isAttached && item.isAttached } ?: return null
        // From the item down to the part's bottom.
        return Span(top = 0f, bottom = item.localPositionOf(p, Offset(0f, p.size.height.toFloat())).y)
    }
}

/** The part [bottom] is the bottom of. */
fun Modifier.partBottom(bottom: PartBottom): Modifier = onPlaced { bottom.part = it }
