package cloud.nalet.chino.tv.ui.settings

import cloud.nalet.chino.tv.ui.theme.ChinoBorder
import cloud.nalet.chino.tv.ui.theme.ChinoError
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import cloud.nalet.chino.tv.ui.theme.ChinoButton
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.FocusKind
import cloud.nalet.chino.tv.ui.theme.focusStyle
import cloud.nalet.chino.tv.ui.theme.rememberItemFocus
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.semantics
import cloud.nalet.chino.tv.ui.theme.ChinoText
import cloud.nalet.chino.tv.ui.theme.ChoiceMenu
import cloud.nalet.chino.tv.ui.theme.ChoiceRow
import cloud.nalet.chino.tv.ui.theme.scrim
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSwitchAccount: (() -> Unit)? = null,
    /** Opens the Profile + watch-history surface (web/mobile parity). Null
     *  hides the row. */
    onProfile: (() -> Unit)? = null,
    /** Opens Delete Account (the signed-in account and what the server keeps
     *  of it, asked first). Null hides the button. */
    onDeleteAccount: (() -> Unit)? = null,
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
    noticesBell: cloud.nalet.chino.tv.ui.notices.NoticesBell? = null,
    activeAccount: cloud.nalet.chino.tv.data.auth.Account? = null,
) {
    val s by viewModel.state.collectAsState()
    val reportsTaken by viewModel.reportsTaken.collectAsState()
    val bugReport by viewModel.bugReport.collectAsState()
    var showReportPanel by remember { mutableStateOf(false) }
    // The languages each setting offers (LanguageChoices.kt): the server's
    // subtitle languages, every language for the audio.
    val serverLanguages by viewModel.serverSubtitleLanguages.collectAsState()
    val subtitleChoices = remember(serverLanguages, s.preferredSubLang) {
        subtitleLanguageChoices(serverLanguages, s.preferredSubLang)
    }
    val audioChoices = remember(s.preferredAudioLang) { audioLanguageChoices(s.preferredAudioLang) }
    // The language menu open, if one is.
    var languageMenu by remember { mutableStateOf<LanguageMenuFor?>(null) }
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
                    noticesBell = noticesBell,
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
                // The same text in every app (docs/chino, playback: the audio
                // setting).
                subtitle = "Titles play with audio in the language you pick here, where they have it. " +
                    "With Original, each title plays in the language it was made in. " +
                    "A choice in the player applies only to what's playing now.",
            ) {
                LanguageSettingRow(
                    choices = audioChoices,
                    value = s.preferredAudioLang,
                    onOpen = { languageMenu = LanguageMenuFor.Audio },
                )
            }
            Section(
                title = "Subtitles",
                // The same text in every app (docs/chino, subtitles).
                subtitle = "Subtitles in the language you pick here come on when a title's audio is in another " +
                    "language. Otherwise, only the foreign-language parts get subtitles, where a title has them. " +
                    "A choice in the player applies only to what's playing now.",
            ) {
                LanguageSettingRow(
                    choices = subtitleChoices,
                    value = s.preferredSubLang,
                    onOpen = { languageMenu = LanguageMenuFor.Subtitles },
                )
            }
            if (onSwitchAccount != null || onProfile != null || onDeleteAccount != null) {
                Section(
                    title = "Account",
                    // "account", the word for who is watching (docs/chino words).
                    subtitle = "See your account and its watch history, sign in to another " +
                        "account or add one, or delete your account.",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Profile first (mirrors chino-mobile's Settings Account
                        // ordering): identity + watch history.
                        if (onProfile != null) {
                            ChinoButton(onClick = onProfile) {
                                Text("Your account")
                            }
                        }
                        if (onSwitchAccount != null) {
                            ChinoButton(onClick = onSwitchAccount) {
                                Text("Switch account")
                            }
                        }
                        // Last in the row: a screen of its own asks before
                        // anything is deleted.
                        if (onDeleteAccount != null) {
                            ChinoButton(onClick = onDeleteAccount) {
                                Text("Delete Account")
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
                    ChinoButton(onClick = onChangeServer) {
                        Text("Change server")
                    }
                }
            }
            // Manual bug-report entry — DPAD-only, so no free text: OK opens
            // a canned-category picker (panel overlay below) and one more OK
            // files the report through BugReporter.reportManual. Mirrors
            // chino-mobile's Settings "Report a bug" dialog minus the
            // keyboard.
            // Only where the server takes reports: the line promises them a
            // recipient (features.feedback in /api/config).
            if (reportsTaken) Section(
                title = "Feedback",
                subtitle = "Tell the people who run this server. The app's and the device's details go with your report.",
            ) {
                ChinoButton(
                    onClick = {
                        // Reset BEFORE showing so a stale Filed/Failed body from
                        // the previous report doesn't flash.
                        viewModel.resetBugReport()
                        showReportPanel = true
                    },
                ) {
                    Text("Report a problem")
                }
            }
                }
            }
        }
        when (languageMenu) {
            LanguageMenuFor.Audio -> LanguageMenu(
                header = "audio language",
                choices = audioChoices,
                value = s.preferredAudioLang,
                onPick = { code -> viewModel.setPreferredAudioLang(code); languageMenu = null },
                onDismiss = { languageMenu = null },
            )
            LanguageMenuFor.Subtitles -> LanguageMenu(
                header = "subtitle language",
                choices = subtitleChoices,
                value = s.preferredSubLang,
                onPick = { code -> viewModel.setPreferredSubLang(code); languageMenu = null },
                onDismiss = { languageMenu = null },
            )
            null -> Unit
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
        // Not clipped: a focused row grows past the panel's edge.
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(ChinoSurface)
                .border(1.dp, ChinoBorder, RectangleShape)
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
                    text = "Thanks — the report reached the people who run this server.",
                    color = ChinoMuted,
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
                    text = "report a problem",
                    color = ChinoMuted,
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
                    color = if (state is BugReportState.Failed) ChinoError else ChinoMuted,
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
 *  (CENTER/ENTER select, a row in the focus style) so the picker inherits the
 *  same DPAD model as every other panel. */
@Composable
private fun ReportDialogRow(
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusStyle(focus, FocusKind.Row, surface = Color.Transparent)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color.White, fontSize = 15.sp)
    }
}

/** Which language setting's menu is open. */
private enum class LanguageMenuFor { Audio, Subtitles }

/**
 * A language setting: a row in the focus style with the language set ("Off",
 * "Original", "German"), which opens its menu of one choice ([LanguageMenu]),
 * as the languages can be many - every language, where the server names none.
 */
@Composable
internal fun LanguageSettingRow(
    choices: List<LanguageChoice>,
    value: String,
    onOpen: () -> Unit,
    focus: cloud.nalet.chino.tv.ui.theme.ItemFocus = rememberItemFocus(),
) {
    val name = chosenLanguage(choices, value)?.name ?: choices.first().name
    Row(
        modifier = Modifier
            // Set in from the section's sides by more than a focused row
            // grows (1 % each side), its text where the other rows' is.
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .focusStyle(focus, FocusKind.Row, surface = Color.Transparent)
            .semantics(mergeDescendants = true) {}
            .focusable()
            .onKeyEvent { e ->
                val ok = e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    onOpen(); true
                } else ok
            }
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Language",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(text = name, color = ChinoFocus.title(ChinoText, focus.focused))
        Icon(
            imageVector = Lucide.ChevronDown,
            contentDescription = null,
            tint = ChinoFocus.text(ChinoMuted, focus.focused),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A language setting's menu of one choice (ChoiceMenu, the player's menus'
 * look): [choices] by name, the one set picked and focused first; OK picks
 * and closes, BACK closes.
 */
@Composable
internal fun LanguageMenu(
    header: String,
    choices: List<LanguageChoice>,
    value: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val picked = chosenLanguage(choices, value)?.code ?: choices.first().code
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Box(
        modifier = Modifier.fillMaxSize().scrim(),
        contentAlignment = Alignment.Center,
    ) {
        ChoiceMenu(header = header, width = 360.dp, maxHeight = 440.dp) {
            choices.forEach { choice ->
                ChoiceRow(
                    label = choice.name,
                    selected = choice.code == picked,
                    focusRequester = focusRequester.takeIf { choice.code == picked },
                    onClick = { onPick(choice.code) },
                )
            }
        }
    }
}

@Composable
internal fun Section(
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
        // Not clipped: a focused row grows past the section's edge.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 8.dp),
        ) { content() }
        // What the setting does, under it: read after the choice it explains.
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        // primary fill so the chip clearly reads as enabled; a filled one keeps
        // its fill on focus.
        ChinoButton(
            onClick = { onChange(!value) },
            container = if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            content = if (value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            filled = value,
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
            ChinoButton(onClick = { if (value > min) onChange(value - 1) }) { Text("−") }
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .clip(RectangleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RectangleShape,
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
            ChinoButton(onClick = { if (value < max) onChange(value + 1) }) { Text("+") }
        }
    }
}
