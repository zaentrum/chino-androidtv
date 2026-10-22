package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide

/*
 * A card's status marks (docs/chino, title actions): the same icon and colour
 * as the title page's action in its on state - the bookmark dark on blue for
 * the Watchlist, the check dark on green for watched. Marks, not buttons: the
 * actions live on the title's page.
 */

/** A title in the Watchlist: the bookmark, dark on blue. */
@Composable
fun WatchlistMark(modifier: Modifier = Modifier) =
    StatusMark(StateIcons.BookmarkFilled, ChinoAccent, "in your Watchlist", modifier)

/** A title watched: the check, dark on green. */
@Composable
fun WatchedMark(modifier: Modifier = Modifier) =
    StatusMark(Lucide.Check, ChinoSignalGreen, "watched", modifier)

@Composable
private fun StatusMark(icon: ImageVector, fill: Color, name: String, modifier: Modifier) {
    Box(modifier = modifier.background(fill).padding(4.dp)) {
        Icon(imageVector = icon, contentDescription = name, tint = ChinoBg, modifier = Modifier.size(14.dp))
    }
}
