package cloud.nalet.chino.tv.ui.notices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.Notice
import cloud.nalet.chino.tv.data.NoticeList
import cloud.nalet.chino.tv.data.ageText
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.fromText
import cloud.nalet.chino.tv.data.noticeItemId
import cloud.nalet.chino.tv.data.plainText
import cloud.nalet.chino.tv.ui.library.TvIconCell
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoBorderHi
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.ChinoText
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.delay

/** How often the ages ("now", "5m") move on while the screen is up. */
private const val AGE_TICK_MS = 30_000L

/** The rows' width, as on the profile's history. */
private const val ROW_WIDTH = 0.85f

/**
 * The Notices screen: what addons told the signed-in person, newest first —
 * whom each is from and when, its title and its body, as plain text with the
 * body's line breaks. Reached from the bell in the top bar, inside the app
 * shell (rail + top bar) like every other destination; its own bell keeps
 * asking, so a notice that comes in shows up here.
 *
 * D-pad: focus starts on the newest notice (back from a title, on the notice
 * that opened it); UP and DOWN move between notices, RIGHT reaches a notice's
 * Delete, UP from the first reaches Mark All Read. OK opens a notice: it is
 * read, and one about a title opens the title's detail screen. A TV shows no
 * web page, so a notice's link is not followed — the text alone is shown.
 */
@Composable
fun NoticesScreen(
    viewModel: NoticesViewModel,
    onItemSelected: (String) -> Unit,
    noticesBell: NoticesBell? = null,
    onHomeNav: () -> Unit = {},
    onMoviesNav: () -> Unit = {},
    onSeriesNav: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    activeAccount: Account? = null,
) {
    val state by viewModel.state.collectAsState()
    val account = activeAccount?.id
    // The ages move on while the screen is up: "now" becomes "1m".
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(AGE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            TvSideRail(
                activeType = "notices", // non-matching → no rail item highlighted
                onHome = onHomeNav,
                onMovies = onMoviesNav,
                onSeries = onSeriesNav,
                onWatchlist = onWatchlist,
                onSettings = onSettings,
            )
            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                TvTopBar(
                    onSearch = onSearch,
                    noticesBell = noticesBell,
                    activeAccount = activeAccount,
                    onAccountClick = onAccountClick,
                )
                // The signed-in person's notices only, once asked for them.
                NoticesList(
                    list = state.list.takeIf { account != null && state.isFor(account) },
                    now = now,
                    onOpen = { notice ->
                        if (account != null) viewModel.open(account, notice)
                        noticeItemId(notice)?.let(onItemSelected)
                    },
                    onReadAll = { if (account != null) viewModel.readAll(account) },
                    onDelete = { notice -> if (account != null) viewModel.delete(account, notice) },
                )
            }
        }
    }
}

@Composable
private fun NoticesList(
    list: NoticeList?,
    now: Long,
    onOpen: (Notice) -> Unit,
    onReadAll: () -> Unit,
    onDelete: (Notice) -> Unit,
) {
    val notices = list?.notices.orEmpty()
    val unread = list?.unread ?: 0
    val listState = rememberLazyListState()
    // One requester per notice, so focus can be put on any of them.
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun focusOf(id: String) = requesters.getOrPut(id) { FocusRequester() }
    suspend fun focusNotice(index: Int) {
        val target = notices.getOrNull(index) ?: return
        // The header is the list's first item.
        listState.scrollToItem(index + 1)
        withFrameNanos { }
        runCatching { focusOf(target.id).requestFocus() }
    }

    // On entry, focus the notice last opened from here (BACK from its title
    // lands on it), else the newest.
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(notices.isNotEmpty()) {
        if (entered || notices.isEmpty()) return@LaunchedEffect
        entered = true
        focusNotice(notices.indexOfFirst { it.id == lastOpened }.coerceAtLeast(0))
    }
    // A notice deleted from here: once it is gone, focus the one in its place.
    var deleted by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(notices) {
        val (id, index) = deleted ?: return@LaunchedEffect
        if (notices.any { it.id == id }) return@LaunchedEffect
        deleted = null
        focusNotice(minOf(index, notices.lastIndex))
    }
    // Mark All Read leaves with the last unread notice: focus the newest.
    var readAllPressed by remember { mutableStateOf(false) }
    LaunchedEffect(unread) {
        if (!readAllPressed || unread > 0) return@LaunchedEffect
        readAllPressed = false
        focusNotice(0)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(ROW_WIDTH).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Notices",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (unread > 0) {
                    Button(
                        onClick = {
                            readAllPressed = true
                            onReadAll()
                        },
                        shape = ButtonDefaults.shape(shape = RectangleShape),
                    ) {
                        Icon(Lucide.CheckCheck, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = "Mark All Read")
                    }
                }
            }
        }
        // Not asked for this person yet: nothing until the bell's answer.
        // portal-api not answering: what was listed stays, or nothing.
        if (list != null && list.available && notices.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "No notices. An addon you use can tell you something here.",
                    color = ChinoMuted,
                    fontSize = 15.sp,
                )
            }
        }
        itemsIndexed(notices, key = { _, notice -> notice.id }) { index, notice ->
            NoticeRow(
                notice = notice,
                now = now,
                focusRequester = focusOf(notice.id),
                // Either is the person's next step: a Mark All Read that did
                // not go through moves focus no more.
                onOpen = {
                    readAllPressed = false
                    lastOpened = notice.id
                    onOpen(notice)
                },
                onDelete = {
                    readAllPressed = false
                    deleted = notice.id to index
                    onDelete(notice)
                },
            )
        }
    }
}

/**
 * One notice: a focusable card — an accent mark while unread, whom it is
 * from and when, its title and body as plain text, and "Open" when it is
 * about a title — with its Delete to the right. OK opens it.
 */
@Composable
private fun NoticeRow(
    notice: Notice,
    now: Long,
    focusRequester: FocusRequester,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val unread = notice.isUnread
    val title = plainText(notice.title, lines = false)
    val body = plainText(notice.body, lines = true)
    val age = ageText(notice.createdAt, now)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(ROW_WIDTH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RectangleShape)
                .background(if (focused) ChinoBorderHi else ChinoSurface)
                .then(
                    if (focused) Modifier.border(2.dp, ChinoAccent, RectangleShape)
                    else Modifier,
                )
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown &&
                        (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                    ) {
                        onOpen(); true
                    } else false
                }
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (unread) {
                    Box(modifier = Modifier.size(8.dp).background(ChinoAccent))
                }
                Text(
                    text = plainText(fromText(notice), lines = false),
                    color = ChinoMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (age.isNotEmpty()) {
                    Text(text = "·", color = ChinoMuted, fontSize = 13.sp)
                    Text(text = age, color = ChinoMuted, fontSize = 13.sp, maxLines = 1)
                }
                if (noticeItemId(notice) != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(text = "Open", color = ChinoAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            Text(
                text = title,
                color = if (unread) Color.White else ChinoText,
                fontSize = 17.sp,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (body.isNotEmpty()) {
                Text(text = body, color = if (unread) ChinoText else ChinoMuted, fontSize = 14.sp)
            }
        }
        TvIconCell(icon = Lucide.Trash2, contentDescription = "Delete notice: $title", onClick = onDelete)
    }
}
