package cloud.nalet.chino.tv.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.ui.browse.PosterGridCard
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar

/**
 * Person / Filmography surface. Rail + top-bar shell (parity with Browse /
 * Search), a header (initials avatar + name + "· N titles"), then a focusable
 * poster grid of the person's credited titles rendered with the SHARED
 * [PosterGridCard]. OK on a card opens that title's Detail; BACK returns to the
 * previous surface (search results or the originating Detail page).
 */
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
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
                activeType = "person", // non-matching → no rail item highlighted
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
                    PersonUiState.Loading -> Centered("Loading…")
                    is PersonUiState.Error -> Centered("Couldn't load person: ${s.message}", isError = true)
                    is PersonUiState.Ready -> Filmography(s = s, onItemSelected = onItemSelected)
                }
            }
        }
    }
}

@Composable
private fun Filmography(
    s: PersonUiState.Ready,
    onItemSelected: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PersonHeader(name = s.name, credits = s.credits)
        }
        if (s.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No titles for ${s.name}.",
                    color = Color(0xFF8B949E),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(s.items, key = { it.id }) { item ->
            PosterGridCard(
                item = item,
                posterUrl = "${s.baseUrl}/v1/items/${item.id}/poster?stream=${s.streamToken}",
                onClick = { onItemSelected(item.id) },
            )
        }
    }
}

/** Person header — initials avatar + name + "· N titles" credit count. */
@Composable
private fun PersonHeader(name: String, credits: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFF161B22)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialsOf(name),
                color = Color(0xFFC9D1D9),
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "· ${creditLabel(credits)}",
                color = Color(0xFF8B949E),
                fontSize = 16.sp,
            )
        }
    }
}

/** "· N titles" copy, singularising at 1. Matches the Search people-row label. */
internal fun creditLabel(credits: Int): String =
    if (credits == 1) "1 title" else "$credits titles"

/** Up to two initials from a person's name, e.g. "Greta Gerwig" -> "GG". */
internal fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    val last = if (parts.size > 1) parts.last().firstOrNull()?.uppercaseChar()?.toString().orEmpty() else ""
    return (first + last).ifBlank { "?" }
}

@Composable
private fun Centered(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize().padding(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else Color(0xFF8B949E),
            fontSize = 18.sp,
        )
    }
}
