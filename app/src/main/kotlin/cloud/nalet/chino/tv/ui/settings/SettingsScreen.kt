package cloud.nalet.chino.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSwitchAccount: (() -> Unit)? = null,
    /** Opens the Profile + watch-history surface (web/mobile parity). Null
     *  hides the row. */
    onProfile: (() -> Unit)? = null,
    /** Opens the prefilled Add-Server flow (clears accounts + restarts on
     *  connect to a different server). Null hides the row. */
    onChangeServer: (() -> Unit)? = null,
    /** Current connected-server host, shown as the row subtitle. */
    serverHost: String? = null,
    onHomeNav: () -> Unit = {},
    onMoviesNav: () -> Unit = {},
    onSeriesNav: () -> Unit = {},
    onSearch: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    activeAccount: cloud.nalet.chino.tv.data.auth.Account? = null,
) {
    val s by viewModel.state.collectAsState()
    val bugReport by viewModel.bugReport.collectAsState()
    var showReportPanel by remember { mutableStateOf(false) }
    // Integrated into the same shell as the rest of the app — rail (Settings
    // active) + top bar — instead of a standalone full-screen page.
    Surface(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            cloud.nalet.chino.tv.ui.library.TvSideRail(
                activeType = null,
                settingsActive = true,
                onHome = onHomeNav,
                onMovies = onMoviesNav,
                onSeries = onSeriesNav,
                onWatchlist = onWatchlist,
                onSettings = {},
            )
            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                cloud.nalet.chino.tv.ui.library.TvTopBar(
                    onSearch = onSearch,
                    onWatchlist = onWatchlist,
                    activeAccount = activeAccount,
                    onAccountClick = onAccountClick,
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Section(
                title = "Binge watching",
                subtitle = "Skip recurring parts back-to-back without leaving the player. " +
                    "A short countdown gives you a chance to cancel before each skip fires.",
            ) {
                ToggleRow(
                    label = "Auto-skip intros & recaps",
                    help = "Skips the title sequence and any \"Previously on…\" recap at the top of an episode.",
                    value = s.autoSkipIntro,
                    onChange = viewModel::setAutoSkipIntro,
                )
                ToggleRow(
                    label = "Auto-skip credits",
                    help = "Skips the closing credits at the end of an episode.",
                    value = s.autoSkipCredits,
                    onChange = viewModel::setAutoSkipCredits,
                )
                ToggleRow(
                    label = "Auto-play next episode",
                    help = "Starts the next episode automatically when credits roll.",
                    value = s.autoPlayNext,
                    onChange = viewModel::setAutoPlayNext,
                )
                StepperRow(
                    label = "Countdown before auto-skip",
                    help = "Seconds the countdown stays on screen before the player skips. " +
                        "Shorter feels snappier; longer gives more time to cancel.",
                    value = s.countdownSec,
                    suffix = "s",
                    min = 1,
                    max = 15,
                    onChange = viewModel::setCountdownSec,
                )
            }
            Section(
                title = "Audio",
                subtitle = "Default audio language. Picks the closest matching track on each item. " +
                    "Original keeps whatever the source marks as the default track.",
            ) {
                LangPickerRow(
                    label = "Preferred language",
                    value = s.preferredAudioLang,
                    options = AUDIO_LANGS,
                    onChange = viewModel::setPreferredAudioLang,
                )
            }
            Section(
                title = "Subtitles",
                subtitle = "Default subtitle language. Picks the closest matching track on each item. " +
                    "Off keeps subtitles disabled by default.",
            ) {
                LangPickerRow(
                    label = "Preferred language",
                    value = s.preferredSubLang,
                    options = SUB_LANGS,
                    onChange = viewModel::setPreferredSubLang,
                )
            }
            if (onSwitchAccount != null || onProfile != null) {
                Section(
                    title = "Account",
                    subtitle = "View your profile and watch history, sign in as a different " +
                        "user, or add a new profile.",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Profile first (mirrors chino-mobile's Settings Account
                        // ordering): identity + watch history.
                        if (onProfile != null) {
                            Button(onClick = onProfile) {
                                Text("Profile")
                            }
                        }
                        if (onSwitchAccount != null) {
                            Button(onClick = onSwitchAccount) {
                                Text("Switch account")
                            }
                        }
                    }
                }
            }
            // Change-server entry — re-opens Add-Server prefilled with the
            // current server. Connecting to a DIFFERENT server clears the
            // existing accounts (their tokens are for the old issuer) and
            // restarts the process, then routes back to sign-in. Mirrors
            // chino-mobile's Settings "Change server" row.
            if (onChangeServer != null) {
                Section(
                    title = "Server",
                    subtitle = serverHost?.let { "Connected to $it." }
                        ?: "Connect to a different Chino server.",
                ) {
                    Button(onClick = onChangeServer) {
                        Text("Change server")
                    }
                }
            }
            // Manual bug-report entry — DPAD-only, so no free text: OK opens
            // a canned-category picker (panel overlay below) and one more OK
            // files the report through BugReporter.reportManual. Mirrors
            // chino-mobile's Settings "Report a bug" dialog minus the
            // keyboard.
            Section(
                title = "Feedback",
                subtitle = "Something not working? File a bug straight to the dev backlog — " +
                    "no typing needed, device and app details ride along automatically.",
            ) {
                Button(onClick = {
                    // Reset BEFORE showing so a stale Filed/Failed body from
                    // the previous report doesn't flash.
                    viewModel.resetBugReport()
                    showReportPanel = true
                }) {
                    Text("Report a problem")
                }
            }
                }
            }
        }
        if (showReportPanel) {
            ReportProblemDialog(
                state = bugReport,
                onPick = viewModel::fileBugReport,
                onDismiss = { showReportPanel = false },
            )
        }
    }
}

/** Canned bug-report categories — DPAD-only, so the manual path offers a
 *  pick-one list instead of free text (no on-screen-IME round-trip in this
 *  iteration). The category doubles as the ticket title; "Something else"
 *  exists so nothing is unreportable. */
private val REPORT_CATEGORIES = listOf(
    "Playback won't start",
    "Video stutters or freezes",
    "Audio out of sync",
    "Subtitles wrong or missing",
    "App is slow",
    "Something else",
)

/**
 * Manual bug-report picker — full-screen scrim + #161B22 card, the player
 * MenuPopover idiom (focusable rows, CENTER/ENTER selects, BACK closes, first
 * row auto-focuses). Selecting a category files the report through
 * SettingsViewModel.fileBugReport: success swaps the card body for
 * "Filed bug #id" / "Added to existing bug #id" (server deduplicated),
 * failure shows a plain-language line above the rows so the user can retry.
 */
@Composable
private fun ReportProblemDialog(
    state: BugReportState,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val firstRow = remember { FocusRequester() }
    val closeRow = remember { FocusRequester() }
    val filed = state as? BugReportState.Filed
    // First category row grabs focus on open; the Close row takes over once
    // the report is filed (the category rows it replaced are gone).
    LaunchedEffect(filed != null) {
        runCatching { (if (filed != null) closeRow else firstRow).requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161B22))
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (filed != null) {
                Text(
                    text = if (filed.duplicate) "Added to existing bug #${filed.id}"
                    else "Filed bug #${filed.id}",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    text = "Thanks — the report landed on the dev backlog.",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                ReportDialogRow(
                    label = "Close",
                    focusRequester = closeRow,
                    onClick = onDismiss,
                )
            } else {
                Text(
                    text = "REPORT A PROBLEM",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Text(
                    text = when (state) {
                        // Manual reports DO surface failures (auto reports
                        // never do) — inline, with the rows kept for a retry.
                        is BugReportState.Failed -> state.message
                        is BugReportState.Sending -> "Sending the report…"
                        else -> "What's going wrong? Pick the closest match."
                    },
                    color = if (state is BugReportState.Failed) Color(0xFFF85149) else Color(0xFF8B949E),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
                )
                REPORT_CATEGORIES.forEachIndexed { idx, category ->
                    ReportDialogRow(
                        label = category,
                        focusRequester = firstRow.takeIf { idx == 0 },
                        // Swallow repeat presses while a submit is in flight.
                        onClick = { if (state !is BugReportState.Sending) onPick(category) },
                    )
                }
            }
        }
    }
}

/** Focusable dialog row — structural clone of the player's PlayerMenuRow
 *  (CENTER/ENTER select, blue focus tint) so the picker inherits the same
 *  DPAD model as every other panel. */
@Composable
private fun ReportDialogRow(
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val base = Modifier
        .fillMaxWidth()
        .background(if (focused) Color(0xFF58A6FF).copy(alpha = 0.22f) else Color.Transparent)
        .padding(horizontal = 20.dp, vertical = 10.dp)
    val m = if (focusRequester != null) base.focusRequester(focusRequester) else base
    Row(
        modifier = m
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                    onClick(); true
                } else false
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color.White, fontSize = 15.sp)
    }
}

// Shared ISO-639-1 language list offered by both pickers. The leading sentinel
// row differs: subtitles offer "Off" (disable), audio offers "Original" (keep
// the source's default track) — mirroring chino-web's subtitles.preferredLang
// ('…'|'off') vs audio.preferredLang ('…'|'orig').
private val LANG_CHOICES = listOf(
    "en" to "English",
    "de" to "Deutsch",
    "fr" to "Français",
    "es" to "Español",
    "it" to "Italiano",
    "ja" to "日本語",
    "pt" to "Português",
    "nl" to "Nederlands",
)
private val SUB_LANGS = listOf("off" to "Off") + LANG_CHOICES
private val AUDIO_LANGS = listOf("orig" to "Original") + LANG_CHOICES

@Composable
private fun LangPickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (code, name) ->
                val on = value == code
                Button(
                    onClick = { onChange(code) },
                    colors = if (on) ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        focusedContainerColor = MaterialTheme.colorScheme.primary,
                        focusedContentColor = Color.White,
                    ) else ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(text = name)
                }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(0.7f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 8.dp),
        ) { content() }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    help: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(
                text = help,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // A two-state pill: ON/OFF. Inactive uses surface tint, active uses
        // primary fill so the chip clearly reads as enabled.
        Button(
            onClick = { onChange(!value) },
            colors = if (value) {
                ButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    focusedContainerColor = MaterialTheme.colorScheme.primary,
                    focusedContentColor = Color.White,
                )
            } else {
                ButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            },
        ) {
            Text(text = if (value) "ON" else "OFF", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    help: String,
    value: Int,
    suffix: String,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(
                text = help,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { if (value > min) onChange(value - 1) }) { Text("−") }
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = "$value$suffix",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Button(onClick = { if (value < max) onChange(value + 1) }) { Text("+") }
        }
    }
}
