package cloud.nalet.chino.tv.ui.notices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.NOTICE_POLL_MS
import cloud.nalet.chino.tv.data.NoticesRepository
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.badgeText
import cloud.nalet.chino.tv.data.bellLabel
import cloud.nalet.chino.tv.ui.library.TvIconCell
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.delay

/** What the bell in a top bar needs: the person's notices and the way to the
 *  Notices screen. The NavHost makes one and hands it to every screen with a
 *  top bar — all of which exist only once an account is signed in. */
class NoticesBell(
    val notices: NoticesRepository,
    val open: () -> Unit,
)

/**
 * The bell in the top bar: the signed-in person's unread notices, counted
 * where they look first, opening the Notices screen. While its screen is
 * shown it asks for their notices — now, every minute, and again when the app
 * comes back to the foreground — and it shows only once portal-api answered
 * for them: with `available` false there is nothing (no bell, no count, no
 * error). Nothing waits for it: the asking runs off the main thread, and a
 * failed ask changes nothing.
 */
@Composable
internal fun NoticesBellCell(bell: NoticesBell, account: Account) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(bell.notices, account.id, lifecycle) {
        // STARTED: while this screen is the one shown and the app is in
        // front; it starts over (and asks at once) when either comes back.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                bell.notices.refresh(account.id)
                delay(NOTICE_POLL_MS)
            }
        }
    }
    val state by bell.notices.state.collectAsState()
    if (!state.showsFor(account.id)) return
    val unread = state.list.unread
    Box {
        TvIconCell(icon = Lucide.Bell, contentDescription = bellLabel(unread), onClick = bell.open)
        val badge = badgeText(unread)
        if (badge.isNotEmpty()) {
            // The count over the bell's corner: read by the cell's label, so
            // not twice here.
            Text(
                text = badge,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-4).dp)
                    .clearAndSetSemantics {}
                    .background(ChinoAccent, RectangleShape)
                    .widthIn(min = 16.dp)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}
