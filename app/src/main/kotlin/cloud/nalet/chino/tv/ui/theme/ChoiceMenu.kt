package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/*
 * A menu of one choice (docs/chino, playback: the player's menus): the
 * player's subtitles, audio, quality and speed, and Settings' languages. One
 * look for all of them:
 *
 * - the header pinned above the rows, lowercase, in the mono face;
 * - the rows scroll under it, each a row in the focus style, set in from the
 *   panel's sides so a focused row's growth stays inside the panel;
 * - where the list goes on past the panel's top or bottom edge, the rows fade
 *   out there instead of being cut through their letters, and the focused
 *   row is scrolled clear of the fade;
 * - the picked row in the accent blue, with a small filled square before it,
 *   square like every shape of the design system, and no checkboxes or check
 *   marks; every row keeps the square's column, picked or not, so the names
 *   line up;
 * - a row rests in fg-2 and turns fg on focus; its names stay in the body
 *   face, as they are names.
 *
 * The focus stays inside while the menu is open: DOWN on its last row, UP on
 * its first, stay there.
 */

/** How far the rows sit in from the panel's sides: a focused row grows by
 *  2 %, 1 % on each side, and its grown edge stays inside the panel. */
private val ROW_INSET = 6.dp

/** Where the list goes on past an edge, the rows fade out over this height,
 *  and the focused row is kept clear of it. */
private val FADE = 20.dp

/** The picked mark's side: small, beside a 15 sp name. */
private val PICKED_MARK = 8.dp

/**
 * A menu of one choice: [header] pinned above the rows [content] puts in,
 * [width] wide, at most [maxHeight] tall, the rows scrolling beyond that.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChoiceMenu(
    header: String,
    width: Dp,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .width(width)
            .heightIn(max = maxHeight)
            .background(ChinoSurface)
            .border(1.dp, ChinoBorder, RectangleShape),
    ) {
        Text(
            text = header,
            // The mono face, lowercase: chrome, not a name.
            style = MaterialTheme.typography.titleSmall,
            fontSize = 12.sp,
            color = ChinoMuted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        CompositionLocalProvider(LocalFocusScrollMargin provides FADE) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fadingEdges(scroll, FADE, ChinoSurface)
                    .verticalScroll(scroll)
                    .padding(horizontal = ROW_INSET)
                    .padding(bottom = 6.dp),
                content = content,
            )
        }
    }
}

/** Fades the content out over [height] at the top or the bottom where
 *  [scroll] has more beyond that edge, into the panel's [color]. */
private fun Modifier.fadingEdges(scroll: ScrollState, height: Dp, color: Color): Modifier = drawWithContent {
    drawContent()
    val h = height.toPx().coerceAtMost(size.height / 2f)
    if (scroll.value > 0) {
        drawRect(
            brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)), startY = 0f, endY = h),
            topLeft = Offset.Zero,
            size = Size(size.width, h),
        )
    }
    if (scroll.value < scroll.maxValue) {
        drawRect(
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0f), color), startY = size.height - h, endY = size.height),
            topLeft = Offset(0f, size.height - h),
            size = Size(size.width, h),
        )
    }
}

/**
 * A row of a [ChoiceMenu]: [label], picked where [selected] - in the accent
 * blue, the small filled square before it - with [secondary] under it and
 * [trailing] after it. OK picks, once per press: a held OK repeats its
 * key-down.
 */
@Composable
fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    secondary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    maxLines: Int = 1,
) {
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusStyle(focus, FocusKind.Row, surface = Color.Transparent)
            // One thing to a screen reader: its name, picked or not.
            .semantics(mergeDescendants = true) { this.selected = selected }
            .focusable()
            .onKeyEvent { e ->
                val ok = e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    onClick(); true
                } else ok
            }
            .padding(horizontal = 16.dp - ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PickedMark(selected)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) ChinoAccent else ChinoFocus.title(ChinoText, focus.focused),
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    color = ChinoFocus.text(ChinoMuted, focus.focused),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** A line of a [ChoiceMenu] that is no choice ("No audio tracks"), where
 *  the names stand. */
@Composable
fun ChoiceNote(text: String) {
    Text(
        text = text,
        color = ChinoMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 16.dp - ROW_INSET, vertical = 8.dp),
    )
}

/** The picked row's small filled square, in the accent blue; its column
 *  kept, empty, on every other row. */
@Composable
private fun PickedMark(selected: Boolean) {
    Box(modifier = Modifier.size(PICKED_MARK), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(PICKED_MARK).background(ChinoAccent, RectangleShape))
    }
}
