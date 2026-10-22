package cloud.nalet.chino.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoBg
import cloud.nalet.chino.tv.ui.theme.ChinoBorder
import cloud.nalet.chino.tv.ui.theme.ChinoButton
import cloud.nalet.chino.tv.ui.theme.ChinoError
import cloud.nalet.chino.tv.ui.theme.ChinoSignalGreen
import cloud.nalet.chino.tv.ui.theme.ChinoSurfaceHi
import cloud.nalet.chino.tv.ui.theme.ChinoText
import cloud.nalet.chino.tv.ui.theme.StateIcons
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide

/*
 * The title actions every chino app has (docs/chino, title actions): add to
 * list, watched and liked, one size and one look on a title's page and on its
 * episode rows. Each meaning has one icon and one colour: the bookmark on blue
 * for the Watchlist, the check on green for watched, the heart on red for
 * liked; off, the icon is outlined in fg-2 on surface-2. The TV keeps its own
 * control height and its focus style.
 */

/** The look of the title actions, in one place. */
internal object TitleActionLook {
    /** The TV's control height - every control of the title page's row has
     *  it, Play, Start over and Trailer too - and an icon button's width. */
    val Size = 44.dp

    /** The "⌄" part of add to list, at the full height. */
    val CaretWidth = 40.dp
    val IconSize = 20.dp
    val CaretIconSize = 16.dp

    /** Off: the plain surface, the icon outlined in fg-2. */
    val OffFill: Color = ChinoSurfaceHi
    val OffContent: Color = ChinoText

    /** On: the icon dark on the meaning's colour. */
    val OnContent: Color = ChinoBg
    val Watchlist: Color = ChinoAccent
    val Watched: Color = ChinoSignalGreen
    val Liked: Color = ChinoError
}

/** What screen readers call the main part of add to list: its state. */
fun addToListName(inWatchlist: Boolean): String = if (inWatchlist) "in your Watchlist" else "add to Watchlist"

/** What screen readers call the watched toggle. */
fun watchedName(watched: Boolean): String = if (watched) "watched" else "mark as watched"

/** What screen readers call the liked toggle. */
fun likedName(liked: Boolean): String = if (liked) "liked" else "like"

/** What screen readers call the "⌄" part of add to list. */
const val CHOOSE_LISTS = "choose lists"

/**
 * Add to list: one control in two parts, in one frame and one fill. The main
 * part shows and toggles the Watchlist only - an outlined bookmark, or a dark
 * one on blue - and never touches other lists; "⌄" opens the list picker,
 * where a title leaves them. A 1 dp divider between them; two focus stops on
 * a TV, each with its own ring - the page's colour between a part and its
 * ring, so the ring stands out where it lies on the other part's blue.
 */
@Composable
internal fun AddToListControl(
    inWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onChooseLists: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = if (inWatchlist) TitleActionLook.Watchlist else TitleActionLook.OffFill
    val content = if (inWatchlist) TitleActionLook.OnContent else TitleActionLook.OffContent
    Row(modifier = modifier.height(TitleActionLook.Size), verticalAlignment = Alignment.CenterVertically) {
        ChinoButton(
            onClick = onToggleWatchlist,
            modifier = Modifier.size(TitleActionLook.Size),
            container = fill,
            content = content,
            filled = inWatchlist,
            contentPadding = PaddingValues(0.dp),
            ringGap = ChinoBg,
        ) {
            Icon(
                imageVector = if (inWatchlist) StateIcons.BookmarkFilled else Lucide.Bookmark,
                contentDescription = addToListName(inWatchlist),
                modifier = Modifier.size(TitleActionLook.IconSize),
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(TitleActionLook.Size)
                .background(if (inWatchlist) TitleActionLook.OnContent.copy(alpha = 0.35f) else ChinoBorder),
        )
        ChinoButton(
            onClick = onChooseLists,
            modifier = Modifier.width(TitleActionLook.CaretWidth).height(TitleActionLook.Size),
            container = fill,
            content = content,
            filled = inWatchlist,
            contentPadding = PaddingValues(0.dp),
            ringGap = ChinoBg,
        ) {
            Icon(
                imageVector = Lucide.ChevronDown,
                contentDescription = CHOOSE_LISTS,
                modifier = Modifier.size(TitleActionLook.CaretIconSize),
            )
        }
    }
}

/** An icon toggle of the title actions: off, the outlined icon on the plain
 *  surface; on, [onIcon] dark on [onFill]. */
@Composable
internal fun TitleToggle(
    on: Boolean,
    icon: ImageVector,
    onIcon: ImageVector,
    onFill: Color,
    name: String,
    onClick: () -> Unit,
) {
    ChinoButton(
        onClick = onClick,
        modifier = Modifier.size(TitleActionLook.Size),
        container = if (on) onFill else TitleActionLook.OffFill,
        content = if (on) TitleActionLook.OnContent else TitleActionLook.OffContent,
        filled = on,
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(if (on) onIcon else icon, contentDescription = name, modifier = Modifier.size(TitleActionLook.IconSize))
    }
}

/** Watched: a check, outlined, or dark on green. */
@Composable
internal fun WatchedToggle(watched: Boolean, onToggle: () -> Unit) =
    TitleToggle(watched, Lucide.Check, Lucide.Check, TitleActionLook.Watched, watchedName(watched), onToggle)

/** Liked: a heart, outlined, or dark on the design system's red. */
@Composable
internal fun LikedToggle(liked: Boolean, onToggle: () -> Unit) =
    TitleToggle(liked, Lucide.Heart, StateIcons.HeartFilled, TitleActionLook.Liked, likedName(liked), onToggle)
