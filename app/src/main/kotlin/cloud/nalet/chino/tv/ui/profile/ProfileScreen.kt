package cloud.nalet.chino.tv.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.auth.Avatar
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
import coil.compose.AsyncImage

/**
 * The TV Profile surface — the watch-history view (chino-web's ProfilePage /
 * chino-mobile's ProfileScreen parity), reached from the Settings Account
 * section. Integrated into the standard app shell (rail + top bar) like every
 * other destination.
 *
 * Renders the active account's identity (avatar + name + email from the OIDC
 * userinfo claims persisted on the [Account]) above a COMPACT watch-history
 * ROW LIST (NOT a grid) read from `GET /v1/me/watched?limit=60`. Each row is a
 * small thumbnail (16:9 still for episodes, else 2:3 poster), the title (series
 * title for episodes), a meta line (`SxxExx · episode title` for episodes, else
 * `year • rating`), and a right-aligned watch date. Rows are DPAD-focusable and
 * open Detail on CENTER/ENTER. The page scrolls with focus.
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onItemSelected: (String) -> Unit,
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
    Surface(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            TvSideRail(
                activeType = null,
                settingsActive = true,
                onHome = onHomeNav,
                onMovies = onMoviesNav,
                onSeries = onSeriesNav,
                onWatchlist = onWatchlist,
                onSettings = onSettings,
            )
            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                TvTopBar(
                    onSearch = onSearch,
                    onWatchlist = onWatchlist,
                    activeAccount = activeAccount,
                    onAccountClick = onAccountClick,
                )
                when (val s = state) {
                    ProfileUiState.Loading -> Centered("Loading your profile…")
                    is ProfileUiState.Error -> Centered(s.message, isError = true)
                    is ProfileUiState.Ready -> ReadyContent(
                        state = s,
                        activeAccount = activeAccount,
                        onItemSelected = onItemSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: ProfileUiState.Ready,
    activeAccount: Account?,
    onItemSelected: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
    ) {
        item {
            Text(
                text = "Profile",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            IdentityCard(
                displayName = activeAccount?.displayName ?: "Account",
                email = activeAccount?.email.orEmpty(),
            )
            Text(
                text = "Watch history",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
            )
        }
        if (state.history.isEmpty()) {
            item {
                Text(
                    text = "Nothing watched yet. Watched items appear here once you finish a " +
                        "movie or episode (or mark one watched on a detail page).",
                    color = Color(0xFF8B949E),
                    fontSize = 14.sp,
                )
            }
        } else {
            items(state.history, key = { it.id }) { item ->
                HistoryRow(
                    item = item,
                    seriesTitle = item.parentId?.let { state.seriesTitles[it] },
                    baseUrl = state.baseUrl,
                    token = state.streamToken,
                    onClick = { onItemSelected(item.id) },
                )
            }
        }
    }
}

@Composable
private fun IdentityCard(displayName: String, email: String) {
    // Web/mobile identity card: #161B22 fill, #30363D hairline, avatar +
    // name/email. Read-only on TV — account switching lives in Settings.
    Row(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF161B22))
            .border(width = 1.dp, color = Color(0xFF30363D), shape = RoundedCornerShape(12.dp))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(displayName = displayName, email = email, size = 64.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (email.isNotBlank() && email != displayName) {
                Text(
                    text = email,
                    color = Color(0xFF8B949E),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One watch-history row — a COMPACT, DPAD-focusable row: a 64dp thumbnail
 * (16:9 still for episodes, 2:3 poster otherwise), the title + a meta line,
 * then a right-aligned watch date. Focus bumps the row to #21262D with a blue
 * ring (the watchlist shelf-header idiom); CENTER/ENTER opens Detail.
 */
@Composable
private fun HistoryRow(
    item: Item,
    seriesTitle: String?,
    baseUrl: String,
    token: String,
    onClick: () -> Unit,
) {
    val isEpisode = item.kind == "episode"
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color(0xFF21262D) else Color(0xFF161B22))
            .then(
                if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), RoundedCornerShape(10.dp))
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .height(64.dp)
                .aspectRatio(if (isEpisode) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0D1117)),
        ) {
            AsyncImage(
                model = if (isEpisode) {
                    "$baseUrl/v1/items/${item.id}/backdrop?stream=$token"
                } else {
                    "$baseUrl/v1/items/${item.id}/poster?stream=$token"
                },
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                // Episodes lead with the series title; the episode itself moves
                // to the SxxExx meta line below (web/mobile CW-card parity).
                text = if (isEpisode) (seriesTitle ?: item.title) else item.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isEpisode) {
                val epNum = listOfNotNull(
                    item.seasonNumber?.let { "S${it.toString().padStart(2, '0')}" },
                    item.episodeNumber?.let { "E${it.toString().padStart(2, '0')}" },
                ).joinToString("")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (epNum.isNotEmpty()) {
                        Text(text = epNum, color = Color(0xFF58A6FF), fontSize = 13.sp)
                        Text(text = "·", color = Color(0xFF8B949E), fontSize = 13.sp)
                    }
                    Text(
                        text = item.title,
                        color = Color(0xFF8B949E),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // year • rating, rating in accent blue — same meta line the
                // poster cards render across clients.
                val ratingText = item.rating?.let { ((it * 10).toInt() / 10.0).toString() }
                if (item.year != null || ratingText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.year?.let {
                            Text(it.toString(), color = Color(0xFF8B949E), fontSize = 13.sp)
                        }
                        ratingText?.let {
                            if (item.year != null) {
                                Text("•", color = Color(0xFF8B949E), fontSize = 13.sp)
                            }
                            Text(it, color = Color(0xFF58A6FF), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        watchedDateLabel(item.watchedAt)?.let {
            Text(text = it, color = Color(0xFF8B949E), fontSize = 13.sp, maxLines = 1)
        }
    }
}

private val MonthAbbrev =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** RFC3339 "2026-06-12T18:23:45Z" -> "Jun 12" (no datetime dep; the watched
 *  stamp's calendar date is all the row needs). Mirrors mobile's helper. */
private fun watchedDateLabel(rfc3339: String?): String? {
    if (rfc3339 == null || rfc3339.length < 10) return null
    val month = rfc3339.substring(5, 7).toIntOrNull() ?: return null
    val day = rfc3339.substring(8, 10).toIntOrNull() ?: return null
    if (month !in 1..12) return null
    return "${MonthAbbrev[month - 1]} $day"
}

@Composable
private fun Centered(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else Color(0xFF8B949E),
            fontSize = 18.sp,
        )
    }
}
