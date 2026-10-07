package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoError
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.ChinoText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.ButtonDefaults
import cloud.nalet.chino.tv.KeyEventBus
import cloud.nalet.chino.tv.data.api.Segment
import cloud.nalet.chino.tv.data.model.Trailer
import cloud.nalet.chino.tv.ui.settings.BugReportState
import cloud.nalet.chino.tv.ui.trailer.launchTrailerLink
import cloud.nalet.chino.tv.ui.trailer.trailerLinkLabel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.Youtube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen playback. UX matches chino-web's PlayerPage:
 *  - Auto-resume: seeks to the saved progress on start, no dialog (chino-web b6a9437)
 *  - Auto-hide controls; remote activity re-shows them for 4s
 *  - Subtitle + quality pickers reached via the chrome's bottom-bar buttons.
 *  - Title overlay top-left
 *  - Progress reported every 10s + on dispose
 *  - Telemetry batched for chino-api log aggregator
 * A title's extra — its trailer — plays here too (PlayMode.Extra), with the
 * same chrome, menus and remote: from the start, with no progress, segments,
 * previews or up next, and it closes at its end (onClose).
 */

/** Witty rotating-message pool for the preparing/buffering overlay.
 *  Verbatim from chino-web/src/components/PlayerPage.tsx — keeping the
 *  same strings means web + TV share a brand voice during the
 *  intentionally-awkward "we're loading something" moments. Add new
 *  entries to chino-web first and port back here. */
private val LOADING_MESSAGES: List<String> = listOf(
    "Calling the projectionist…",
    "Threading the film reels…",
    "Dimming the house lights…",
    "Buttering the popcorn…",
    "Polishing the lens…",
    "Cueing up the opening credits…",
    "Warming up the decoder…",
    "Adjusting the focus…",
    "Sweeping the popcorn aisle…",
    "Final preparations…",
    "Almost ready, hold tight…",
    "Negotiating with the codec gods…",
    "Convincing HEVC to play nice…",
    "Buffering more cinema magic…",
)

/** How long a mid-stream rebuffer must persist before the player does something
 *  about it (PlaybackRecovery: an on-the-fly title drops a quality rung, a
 *  packaged one is retried in place). The playback buffer is huge (4 min), so
 *  entering STATE_BUFFERING means it fully drained; 12 s of continued stall past
 *  that is clearly the current rung out-running what nas001 can serve under
 *  contention — the BRAVIA 4K-high "stutters every few minutes but never
 *  errors" case. */
private const val REBUFFER_FALLBACK_MS = 12_000L

/** Kinds the manual "Skip …" pill handles. Post-credits previews are excluded —
 *  they get the "Next episode ▶" card instead (see [isPostCreditsPreview]). */
private val SKIPPABLE_MANUAL_KINDS = setOf("intro", "recap", "credits")

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onPlayNext: ((String) -> Unit)? = null,
    // Pops the player and lands on the library/home root. Full-screen playback
    // hides the persistent nav rail, so the chrome carries its own explicit
    // Home affordance next to Back (chino-web parity). Optional so existing
    // call sites / previews compile; when null the Home button is hidden.
    onHome: (() -> Unit)? = null,
    // Leaves the player, back to where it was opened from: what the end of
    // the media does where the mode closes at its end (an extra, back to its
    // title — PlayMode.closesAtEnd).
    onClose: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    when (val s = state) {
        PlayerUiState.Preparing -> Centered("Preparing…")
        is PlayerUiState.Error -> {
            val report by viewModel.errorReport.collectAsState()
            TerminalErrorScreen(
                message = s.message,
                report = report,
                onReport = viewModel::fileTerminalErrorBug,
            )
        }
        is PlayerUiState.NotAvailable -> NotAvailableScreen(
            link = s.link,
            onOpenLink = { link ->
                viewModel.reportLinkLaunch()
                launchTrailerLink(context, link)
            },
        )
        is PlayerUiState.Ready -> ExoPlayback(s, viewModel, onPlayNext, onHome, onClose)
    }
}

/**
 * An extra that is not there to play: "Trailer not available", and the
 * title's trailer link when it has one — opened in the YouTube app where it
 * is one of its. BACK leaves the player, as from the error screen.
 */
@Composable
private fun NotAvailableScreen(link: Trailer?, onOpenLink: (Trailer) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(link) { if (link != null) runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Trailer not available",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        if (link != null) {
            Button(
                onClick = { onOpenLink(link) },
                modifier = Modifier.focusRequester(focus),
                shape = ButtonDefaults.shape(shape = RectangleShape),
            ) {
                androidx.tv.material3.Icon(Lucide.Youtube, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = trailerLinkLabel(link))
            }
        }
    }
}

/**
 * Terminal playback failure — shown only after recovery has given up
 * (the on-the-fly ladder or a packaged title's retries spent / manifest
 * missing) and PlayerViewModel
 * flipped to [PlayerUiState.Error]. The retry/recovery logic is untouched;
 * this only ADDS chino-web's "Report a bug" affordance from PlayerPage's
 * fatal overlay: a focusable "Report this problem" row that files directly
 * via BugReporter.reportManual (title "Playback failed", description = the
 * technical error string the auto report shipped) and confirms inline with
 * "Filed bug #N". BACK keeps exiting the player exactly as before — no
 * BackHandler here.
 */
@Composable
private fun TerminalErrorScreen(
    message: String,
    report: BugReportState,
    onReport: () -> Unit,
) {
    val reportRow = remember { FocusRequester() }
    // Park focus on the report row — it's the only focusable on this screen,
    // so without this the first CENTER press would land in a void (same
    // BRAVIA last-focused-view trap the chrome's play/pause parking covers).
    LaunchedEffect(Unit) { runCatching { reportRow.requestFocus() } }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = message, color = MaterialTheme.colorScheme.error)
            when (report) {
                // Mirrors the Settings ReportProblemDialog confirmation
                // wording so the two manual surfaces read identically.
                is BugReportState.Filed -> {
                    Text(
                        text = if (report.duplicate) "Added to existing bug #${report.id}"
                        else "Filed bug #${report.id}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Thanks — the report landed on the dev backlog.",
                        color = ChinoMuted,
                        fontSize = 13.sp,
                    )
                }
                else -> {
                    if (report is BugReportState.Failed) {
                        Text(text = report.message, color = ChinoError, fontSize = 13.sp)
                    }
                    Button(
                        // Swallow repeat presses while a submit is in flight.
                        onClick = { if (report !is BugReportState.Sending) onReport() },
                        modifier = Modifier.focusRequester(reportRow),
                        shape = ButtonDefaults.shape(shape = RectangleShape),
                    ) {
                        Text(
                            if (report is BugReportState.Sending) "Sending the report…"
                            else "Report this problem",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExoPlayback(
    ready: PlayerUiState.Ready,
    viewModel: PlayerViewModel,
    onPlayNext: ((String) -> Unit)?,
    onHome: (() -> Unit)?,
    onClose: (() -> Unit)?,
) {
    val context = LocalContext.current
    // Key the ExoPlayer factory on (masterUrl + reloadKey) so switchQuality
    // tears down + rebuilds the player with the new ?q= URL. The ViewModel
    // bumps reloadKey on every prepare() that follows a switchQuality call.
    val player = remember(ready.masterUrl, ready.reloadKey) {
        // OkHttp over the AppContainer's stream client, shared with every
        // segment fetch (streamDataSourceFactory, StreamPlayer.kt).
        val appContainer = (context.applicationContext as cloud.nalet.chino.tv.ChinoTvApp).container
        val httpFactory = streamDataSourceFactory(appContainer)
        // Sidecar subs from /v1/items/{id}/subtitles attached as
        // side-loaded tracks. Format drives MIME so Media3 picks the
        // right decoder: TEXT_VTT goes through PgsDecoder etc via the
        // SubtitleDecoderFactory route; APPLICATION_PGS routes raw
        // .sup bytes into Media3's PgsDecoder (added pre-1.0,
        // RLE colour-index-0 fix in 1.4.0). Bitmap tracks (.sup)
        // surface in the text-track list and overlay alongside any
        // HLS-internal text tracks.
        val sideSubs = ready.sidecarSubtitles.map { sub ->
            val mime = when (sub.format?.lowercase()) {
                "pgs" -> MimeTypes.APPLICATION_PGS
                "vobsub" -> MimeTypes.APPLICATION_VOBSUB
                "dvb" -> MimeTypes.APPLICATION_DVBSUBS
                "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                else -> MimeTypes.TEXT_VTT
            }
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                .setMimeType(mime)
                // setLanguage is the only signal the track selector sees
                // for bitmap subs — .sup has no internal language tag.
                .setLanguage(sub.lang.takeIf { it.isNotBlank() })
                .setLabel(sub.label.takeIf { it.isNotBlank() })
                // DEFAULT as the catalog has it; FORCED where its label says
                // so (sidecarKind), which the forced subtitle's rule reads
                // (ForcedSubtitles.kt) and the menu shows ("(forced)").
                .setSelectionFlags(
                    (if (sub.default == true) C.SELECTION_FLAG_DEFAULT else 0) or
                        (if (sidecarKind(sub.label) == SubtitleKind.FORCED) C.SELECTION_FLAG_FORCED else 0),
                )
                .build()
        }
        // chino-stream's retry rules: a segment's 404 is retried with a
        // backoff, a master's fails at once (StreamLoadErrorPolicy).
        val retryPolicy = StreamLoadErrorPolicy()
        // The HLS master + the side-loaded subtitle tracks on one MediaItem.
        val mediaItem = MediaItem.Builder()
            .setUri(ready.masterUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setSubtitleConfigurations(sideSubs)
            .build()
        // Big playback buffer to ride out nas001 NFS read-latency spikes (the
        // "lags every ~5 min" rebuffering): chino-stream segment serves stall
        // up to ~17s under NFS contention, and the 50s ExoPlayer default drains
        // before the stall clears. Mirror chino-web's PlayerPage hls.js config
        // (maxBufferLength 300 / backBuffer 60) — buffer up to 4 min ahead, but
        // cap by BYTES (160 MiB) so a 4K HEVC stream on the BRAVIA can't OOM the
        // app (largeHeap is on; 160 MiB ≈ 50-60s of 4K, still 3x the worst stall).
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(
                30_000,   // minBufferMs — keep loading until ≥30s buffered
                240_000,  // maxBufferMs — up to 4 min ahead for low-bitrate streams
                2_500,    // bufferForPlaybackMs — start fast
                5_000,    // bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(160 * 1024 * 1024) // hard memory ceiling (4K guard)
            .setPrioritizeTimeOverSizeThresholds(false) // honour the byte ceiling
            .setBackBuffer(30_000, true)             // 30s back-buffer for instant rewind
            .build()
        // Use DefaultMediaSourceFactory (NOT a bare HlsMediaSource.Factory) so the
        // side-loaded subtitleConfigurations are actually MERGED into the timeline.
        // A bare HlsMediaSource silently ignores them — which is why side-loaded
        // subs (the existing WebVTT sidecars, and PGS) never appeared on TV. The
        // factory applies the same OkHttp data source + 404 retry policy to the
        // HLS source AND the per-subtitle SingleSampleMediaSources it creates.
        // The sidecars are the subtitles: the master reaches Media3 without the
        // SUBTITLES renditions they already have (withoutDuplicateSubtitles),
        // so no language is listed twice. Where the TV's output is stereo
        // a 5.1 default hands the DEFAULT to its stereo twin
        // (withStereoDefault): the player starts on the stereo track, the 5.1
        // one is in the audio menu.
        val sidecars = ready.sidecarSubtitles
        val surround = ready.surroundAudio
        val playDataSourceFactory = MasterRewritingDataSource.Factory(httpFactory) { master ->
            withoutDuplicateSubtitles(master, sidecars).let { if (surround) it else withStereoDefault(it) }
        }
        val mediaSourceFactory =
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(playDataSourceFactory)
                .setLoadErrorHandlingPolicy(retryPolicy)
        // A packaged ladder starts on its first variant, the one
        // chino-stream warmed when the master was fetched (streamPlayerBuilder).
        streamPlayerBuilder(context, mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().also {
            // Of a language's tracks Media3 picks the 5.1 one where the output
            // takes 5.1, else the stereo one; the other is the viewer's to
            // pick (SurroundAudio.kt). Without a DEFAULT among them (another
            // language than the master's default) Media3 would weigh a
            // hardware AAC decoder above E-AC-3 passed through before it
            // counts channels: the surround codecs are preferred instead.
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().apply {
                if (surround) {
                    setPreferredAudioMimeTypes(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3)
                } else {
                    setMaxAudioChannelCount(2)
                }
                // A forced track comes on by the player's rule alone
                // (ForcedSubtitles.kt): Media3 would switch on the one in the
                // audio's language wherever subtitles are on.
                setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED)
            }.build()
            it.setMediaItem(mediaItem)
            it.prepare()
            if (ready.resumePositionSec > 0) {
                it.seekTo(ready.resumePositionSec * 1000L)
            }
            it.playWhenReady = true
        }
    }

    // Apply the user's preferred subtitle language whenever Settings changes.
    // ExoPlayer's preferredTextLanguage trips its automatic track-selector to
    // pick the closest matching text track; "off" disables the text type
    // entirely. Anything the user selects manually via the side panel wins
    // because applySubtitleSelection sets a hard override.
    val settingsPref by viewModel.settings.collectAsState()
    // The forced subtitle's state (the rule is below, with the tracks): the
    // forced track it switched on, and whether the viewer picked in the menu.
    var forcedOn by remember(player) { mutableStateOf<String?>(null) }
    var subtitlePicked by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player, settingsPref.preferredSubLang) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            // A forced track switched on before is the rule's to weigh again
            // against the subtitles as set now.
            .apply { if (forcedOn != null) clearOverridesOfType(C.TRACK_TYPE_TEXT) }
            .subtitlesAsSet(settingsPref.preferredSubLang)
            .build()
        forcedOn = null
    }

    // Apply the user's preferred audio language on first load (and whenever the
    // Settings value changes). setPreferredAudioLanguage trips ExoPlayer's
    // automatic track selector to pick the closest-matching audio track —
    // Media3 normalises 2- and 3-letter ISO codes, so the picker's "en" matches
    // a track tagged "eng". "orig" leaves the source default in place: we clear
    // the preference AND any audio override so whatever the file marks as
    // default plays. Anything the user picks manually via AudioPanel sets a hard
    // override (setOverrideForType) which wins — matching the subtitle path and
    // chino-web (manual switches are session-only).
    LaunchedEffect(player, settingsPref.preferredAudioLang) {
        val params = player.trackSelectionParameters.buildUpon()
        if (settingsPref.preferredAudioLang == "orig" || settingsPref.preferredAudioLang.isBlank()) {
            params.setPreferredAudioLanguage(null)
            params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        } else {
            params.setPreferredAudioLanguage(settingsPref.preferredAudioLang)
        }
        player.trackSelectionParameters = params.build()
    }

    // bannerMessage is the shared transient confirmation toast — used by both
    // the subtitle panel and the audio panel after a track is picked.
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    var showSubtitlePanel by remember { mutableStateOf(false) }
    var showAudioPanel by remember { mutableStateOf(false) }
    var showQualityPanel by remember { mutableStateOf(false) }
    var showInfoOverlay by remember { mutableStateOf(false) }
    var showSpeedPanel by remember { mutableStateOf(false) }
    // Keyed on the same (masterUrl, reloadKey) as the player remember below so a
    // switchQuality reload rebuilds the player AND resets speed to 1.0x together
    // — no stale-speed-vs-fresh-player mismatch. Speed lives on the ExoPlayer
    // instance (mobile parity), so no PlayerViewModel state is needed.
    var playbackSpeed by remember(ready.masterUrl, ready.reloadKey) { mutableStateOf(1f) }
    // App-level volume + mute (web/mobile parity), driven into player.volume —
    // independent of the remote's system volume. Reset with the player.
    var volume by remember(ready.masterUrl, ready.reloadKey) { mutableStateOf(1f) }
    var muted by remember(ready.masterUrl, ready.reloadKey) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(player, volume, muted) {
        player.volume = if (muted) 0f else volume
    }
    // Re-apply playback speed to a freshly built player after a quality switch.
    // playbackSpeed resets to 1f on rebuild (keyed on masterUrl+reloadKey) and a
    // new ExoPlayer defaults to 1.0x, so this is normally a no-op — but keying
    // it on `player` AND `playbackSpeed` guarantees the engine matches the chip
    // even if the reset ever diverges (chino-mobile re-applies the same way).
    androidx.compose.runtime.LaunchedEffect(player, playbackSpeed) {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(playbackSpeed)
    }
    // "Skipped intro" undo pill — visible only on a fresh binge entry where
    // PlayerViewModel pre-skipped the intro. Self-clears after the pill's
    // timeout OR when the user presses BACK to undo. Keyed on masterUrl so a
    // mid-session quality reload doesn't re-pop the pill.
    var skippedIntroPill by remember(ready.masterUrl) {
        mutableStateOf(ready.preSkippedIntro != null)
    }
    var tracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var audioTracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    // The forced subtitle (ForcedSubtitles.kt): where the subtitles as set
    // put none on, the forced track in the language of the audio that plays
    // comes on, and goes with the audio into another language, until the
    // viewer picks in the menu; their Off holds for the rest of the playback
    // (the view model's, past a reload). Read off the player's own tracks
    // each time they change, so a rebuilt player's are the ones it acts on.
    LaunchedEffect(player, tracks, audioTracks) {
        val subtitles = collectSubtitleTracks(player.currentTracks)
        val change = forcedChange(
            tracks = subtitles.map { it.option },
            audioLanguage = collectAudioTracks(player.currentTracks).firstOrNull { it.selected }?.language,
            selected = subtitles.firstOrNull { it.selected }?.id,
            switchedOn = forcedOn,
            viewerChose = subtitlePicked || viewModel.subtitlesOff,
        )
        when (change) {
            ForcedChange.Keep -> Unit
            is ForcedChange.On -> {
                val track = subtitles.first { it.id == change.track.id }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
                    .build()
                forcedOn = track.id
                viewModel.reportTelemetry("subtitle_forced", mapOf("label" to track.label))
            }
            ForcedChange.Off -> {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .subtitlesAsSet(settingsPref.preferredSubLang)
                    .build()
                forcedOn = null
            }
        }
    }
    // The picture size of the video format Media3 plays (width to height) —
    // on Auto, the rung ExoPlayer has stepped to; null until the first frame.
    var playingSize by remember(player) { mutableStateOf<Pair<Int, Int>?>(null) }
    // The master's video variants as Media3 read them: the quality menu of a
    // mode without /play/info (an extra, variantMenu), and the one its menu
    // pinned (its name), AUTO while the player adapts.
    var videoVariants by remember(player) { mutableStateOf<List<VideoVariant>>(emptyList()) }
    var pinnedVariant by remember(player) { mutableStateOf(AUTO) }
    var controllerVisible by remember { mutableStateOf(true) }
    // Shared with the Player.Listener block below so the preparing/buffering
    // overlay reflects current state in real time. MutableState backing so
    // the listener (running off-main on ExoPlayer's handler) can set without
    // a separate snapshotFlow.
    val sawFirstFrameState = remember { mutableStateOf(false) }
    val bufferingState = remember { mutableStateOf(true) }
    // Timestamp a MID-STREAM rebuffer began (0 = not rebuffering). Distinct from
    // the listener's bufferingStartTs (telemetry only) because the sustained-
    // rebuffer watchdog below reads it from a Compose coroutine to start the
    // stall recovery. Reset when the player rebuilds for a new item/rung.
    val rebufferSinceMs = remember(player) { mutableStateOf(0L) }
    // Set by the listener on STATE_ENDED; a LaunchedEffect below auto-advances
    // to the next episode (series) or the top recommendation (movie). guarded
    // by autoAdvanced so the end-of-media path and the credits countdown can't
    // both fire onPlayNext. Both reset when the player rebuilds for a new item.
    val endedState = remember(player) { mutableStateOf(false) }
    var autoAdvanced by remember(player) { mutableStateOf(false) }

    // Track the player's tracks list so the side panel always has the latest set.
    DisposableEffect(player) {
        // Stamps so we can derive first_frame_ms / buffering durations.
        val mountTs = System.currentTimeMillis()
        var sawFirstFrame = false
        var bufferingStartTs = 0L
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.reportTelemetry(if (isPlaying) "play" else "pause")
                // An extra's one event, trailer_play.
                if (isPlaying) viewModel.reportStarted()
            }
            override fun onTracksChanged(t: Tracks) {
                tracks = collectSubtitleTracks(t)
                audioTracks = collectAudioTracks(t)
                videoVariants = collectVideoVariants(t)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // The selected format's size is the variant's RESOLUTION,
                // which /play/info's rungs carry; the decoded size else.
                val format = player.videoFormat
                playingSize = if (format != null && format.width > 0 && format.height > 0) {
                    format.width to format.height
                } else {
                    (videoSize.width to videoSize.height).takeIf { it.first > 0 && it.second > 0 }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        bufferingStartTs = System.currentTimeMillis()
                        bufferingState.value = true
                        // Arm the sustained-rebuffer watchdog only AFTER the
                        // first frame — initial startup buffering shouldn't drop
                        // quality (that's just the cold-transcode latency).
                        if (sawFirstFrame) rebufferSinceMs.value = System.currentTimeMillis()
                    }
                    Player.STATE_READY -> {
                        bufferingState.value = false
                        rebufferSinceMs.value = 0L
                        if (bufferingStartTs > 0) {
                            viewModel.reportTelemetry(
                                "buffering_end",
                                mapOf("duration_ms" to (System.currentTimeMillis() - bufferingStartTs).toString()),
                            )
                            bufferingStartTs = 0L
                        }
                        if (!sawFirstFrame) {
                            sawFirstFrame = true
                            sawFirstFrameState.value = true
                            viewModel.reportTelemetry(
                                "first_frame",
                                mapOf("startup_ms" to (System.currentTimeMillis() - mountTs).toString()),
                            )
                        }
                    }
                    Player.STATE_ENDED -> {
                        viewModel.reportTelemetry("playback_complete")
                        rebufferSinceMs.value = 0L
                        endedState.value = true
                    }
                    Player.STATE_IDLE -> rebufferSinceMs.value = 0L
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // Only count user-initiated seeks; auto-skip/auto-play-next
                // discontinuities have their own dedicated events.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    viewModel.reportTelemetry(
                        "seek",
                        mapOf(
                            "from_sec" to (oldPosition.positionMs / 1000L).toString(),
                            "to_sec" to (newPosition.positionMs / 1000L).toString(),
                        ),
                    )
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Auto bug report — fire-and-forget + silent (BugReporter
                // swallows every failure and session-throttles repeat
                // fingerprints). Position is read here on the player's
                // looper, before the report hops threads. Fires even when
                // the recovery below gets past it, so a flaky rung still
                // lands on the backlog.
                viewModel.reportPlayerErrorBug(
                    code = error.errorCodeName,
                    message = error.message,
                    stack = error.stackTraceToString(),
                    positionSec = player.currentPosition / 1000L,
                )
                // Recover before bothering the viewer with an error screen
                // (PlaybackRecovery): an on-the-fly title drops a rung of its
                // ladder — chino-stream's high rung is often the only one
                // still warming when medium/low have been cached for hours —
                // and a packaged one is tried again where it stands, then
                // rebuilt at its quality. The error UI only once that is
                // spent.
                val httpCause = error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                // A manifest 404 = the item has no playback asset. No retry or
                // rung can fix that (every rung 404s), so surface the "not
                // available" message immediately.
                val manifestMissing = httpCause?.responseCode == 404 &&
                    (httpCause.dataSpec.uri.toString().contains(".m3u8"))
                val recovery = if (manifestMissing) Recovery.GiveUp
                else viewModel.recoverFromError(player.currentPosition)
                when (recovery) {
                    // ExoPlayer keeps the playhead through an error: prepare()
                    // loads again from there.
                    Recovery.RetryInPlace -> player.prepare()
                    // The ViewModel is rebuilding the player at the position.
                    is Recovery.StepDown, is Recovery.Reload -> Unit
                    Recovery.GiveUp, Recovery.Wait -> {
                        // Pull HTTP status + failing URL off the cause when the
                        // root error is an HLS segment fetch failure. Mirrors the
                        // hls_fatal beacon in chino-web so a chino-stream 502 spike
                        // surfaces as a correlated client-side burst.
                        viewModel.reportPlaybackError(
                            code = error.errorCodeName,
                            message = error.message,
                            type = error.cause?.javaClass?.simpleName,
                            httpStatus = httpCause?.responseCode,
                            failedUrl = httpCause?.dataSpec?.uri?.toString(),
                        )
                    }
                }
            }
        }
        player.addListener(listener)
        viewModel.reportTelemetry("mount")
        onDispose {
            val pos = (player.currentPosition / 1000L).toInt()
            val dur = (player.duration.takeIf { it != C.TIME_UNSET } ?: 0).let { (it / 1000L).toInt() }
            if (pos > 0) viewModel.reportProgress(pos, dur)
            viewModel.reportTelemetry(
                "unmount",
                mapOf(
                    "position_sec" to pos.toString(),
                    "duration_sec" to dur.toString(),
                    "saw_first_frame" to sawFirstFrame.toString(),
                ),
            )
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(10_000)
            val pos = (player.currentPosition / 1000L).toInt()
            val dur = (player.duration.takeIf { it != C.TIME_UNSET } ?: 0).let { (it / 1000L).toInt() }
            if (pos > 0) viewModel.reportProgress(pos, dur)
        }
    }

    // Sustained-rebuffer watchdog. onPlayerError only covers a HARD failure
    // (decode error, segment retry exhausted); the BRAVIA's "stutters every few
    // minutes but keeps recovering" is a SOFT failure — the 4-min buffer
    // drains, playback stalls in STATE_BUFFERING, and the slow segment
    // eventually lands so no PlaybackException ever fires. When a mid-stream
    // rebuffer persists past REBUFFER_FALLBACK_MS the player recovers
    // (PlaybackRecovery): an on-the-fly title drops a rung — smaller segments
    // keep ahead of nas001 NFS contention — and a packaged one is left to load
    // while it is fetching, else nudged where it stands (a seek to the
    // playhead restarts its renderers on what is buffered) and rebuilt at its
    // quality after that. A drop or a rebuild makes a new player and with it a
    // new watchdog; otherwise it looks again REBUFFER_FALLBACK_MS later while
    // the stall lasts.
    LaunchedEffect(player) {
        while (true) {
            delay(1_000)
            val since = rebufferSinceMs.value
            if (since <= 0L || System.currentTimeMillis() - since < REBUFFER_FALLBACK_MS) continue
            val pos = player.currentPosition
            val recovery = viewModel.recoverFromStall(
                positionMs = pos,
                loading = player.isLoading,
                bufferedAheadMs = player.bufferedPosition - pos,
            )
            when (recovery) {
                Recovery.RetryInPlace -> player.seekTo(pos)
                is Recovery.StepDown -> viewModel.reportTelemetry(
                    "rebuffer_quality_fallback",
                    mapOf("stall_ms" to (System.currentTimeMillis() - since).toString()),
                )
                is Recovery.Reload, Recovery.Wait, Recovery.GiveUp -> Unit
            }
            // A seek within BUFFERING reports no state change: re-arm by hand.
            if (rebufferSinceMs.value > 0L) rebufferSinceMs.value = System.currentTimeMillis()
        }
    }

    // Binge-mode segment handling. Polls every 500ms; each segment is acted
    // on at most once per session via handledKeys. When a segment matches, we
    // arm `pendingSkip` and surface a countdown pill — that gives the user a
    // chance to BACK out before the seek/navigate actually fires.
    //
    // Same poll loop also handles "mark watched": fires reportWatched() the
    // first time the playhead enters a credits segment OR crosses 95 % of
    // duration, whichever comes first. Guarded by markedWatched so we only
    // POST once per playback session (chino-api is idempotent on the
    // server-side too, but no point burning round-trips).
    val settings by viewModel.settings.collectAsState()
    // Pre-mark any intro segment we already pre-skipped at prepare-time
    // (binge entry) so the segment poll loop doesn't fire a redundant
    // SkipIntro countdown for the same range — the user already opened
    // PAST it and saw the undo pill below.
    val handledKeys = remember(ready.masterUrl) {
        mutableSetOf<String>().apply {
            ready.preSkippedIntro?.let { add("${it.kind}:${it.startMs}") }
        }
    }
    var pendingSkip by remember(ready.masterUrl) { mutableStateOf<PendingSkip?>(null) }
    val markedWatched = remember(ready.masterUrl) { mutableSetOf<String>() }
    // Tracks which next-episode ids we've already pre-warmed this session so
    // the poll loop fires at most one fetch per chained-into next item. Keyed
    // on masterUrl so a new episode load gets a fresh set.
    val prewarmedNext = remember(ready.masterUrl) { mutableSetOf<String>() }

    LaunchedEffect(player, ready.segments, settings, onPlayNext) {
        while (true) {
            delay(500)
            // Watched-marking: fires regardless of whether the item has
            // segments. Two trigger conditions, whichever lands first per
            // session — entering a credits segment, or crossing the 95 %
            // duration line. The 95 % threshold handles items without
            // segment metadata (most movies, anything not packaged).
            if ("watched" !in markedWatched) {
                val posMs = player.currentPosition
                val durMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                val inCredits = ready.segments.any { seg ->
                    seg.kind.equals("credits", ignoreCase = true) && posMs in seg.startMs until seg.endMs
                }
                val pastThreshold = durMs > 0 && posMs >= (durMs * 95) / 100
                if (inCredits || pastThreshold) {
                    markedWatched += "watched"
                    viewModel.reportWatched()
                }
            }
            // Binge pre-warm — once per next-episode id per session. Fires
            // when the playhead enters a credits segment OR crosses into the
            // last 30 s of the file (fallback for items without segment
            // metadata). One async fetch warms chino-stream's transcoder for
            // the next id so the cut-over isn't a 0:00 freeze.
            if (onPlayNext != null && "prewarm" !in prewarmedNext) {
                val posMs = player.currentPosition
                val durMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                val PREWARM_WINDOW_MS = 30_000L
                val inOrNearCredits = ready.segments.any { seg ->
                    seg.kind.equals("credits", ignoreCase = true) &&
                        posMs >= seg.startMs - PREWARM_WINDOW_MS && posMs < seg.endMs
                } || (durMs > 0 && posMs >= durMs - PREWARM_WINDOW_MS)
                if (inOrNearCredits) {
                    prewarmedNext += "prewarm" // claim slot first; resolve is suspending
                    val next = viewModel.resolveNextUp()
                    if (next != null) {
                        viewModel.prewarmMaster(next)
                        viewModel.reportTelemetry("binge_prewarm", mapOf("next_item" to next))
                    }
                }
            }
            if (ready.segments.isEmpty()) continue
            if (pendingSkip != null) continue
            val posMs = player.currentPosition
            val active = ready.segments.firstOrNull { seg ->
                posMs in seg.startMs until seg.endMs &&
                    "${seg.kind}:${seg.startMs}" !in handledKeys
            } ?: continue
            val key = "${active.kind}:${active.startMs}"
            val cd = settings.countdownSec.coerceAtLeast(1)
            // A post-credits preview (a next-episode teaser mislabelled as a
            // recap, or an explicit `preview`) is NOT skippable content — it
            // plays with a "Next episode ▶" card over it. With auto-play-next
            // on, the card counts down and jumps; with it off, only the manual
            // card below appears (no auto-jump). Either way it must NOT trip the
            // recap-skip branch, hence the !isPreview guard there.
            val isPreview = isPostCreditsPreview(active, ready.segments)
            when {
                isPreview && settings.autoPlayNext && onPlayNext != null ->
                    pendingSkip = PendingSkip.PreviewNext(key, cd)
                active.kind.equals("intro", ignoreCase = true) && settings.autoSkipIntro ->
                    pendingSkip = PendingSkip.SkipIntro(key, active.endMs, cd)
                // Recap segments ("Previously on…") share the autoSkipIntro
                // setting because both are content the viewer has already
                // seen. Separate user setting can be added later if anyone
                // wants different behaviour for the two.
                active.kind.equals("recap", ignoreCase = true) && !isPreview && settings.autoSkipIntro ->
                    pendingSkip = PendingSkip.SkipRecap(key, active.endMs, cd)
                active.kind.equals("credits", ignoreCase = true) && settings.autoPlayNext && onPlayNext != null ->
                    pendingSkip = PendingSkip.PlayNext(key, cd)
                active.kind.equals("credits", ignoreCase = true) && settings.autoSkipCredits ->
                    pendingSkip = PendingSkip.SkipCredits(key, active.endMs, cd)
            }
        }
    }

    // Countdown ticker + action firing. KEY is pendingSkip?.key (stable for
    // the same segment-instance) so updates to secLeft don't cancel-and-
    // restart the coroutine on every tick — the previous LaunchedEffect-on-
    // pendingSkip version never reached the fire-the-action branch because
    // each setState reset the loop. A null key (skip cancelled / completed)
    // tears the coroutine down cleanly.
    LaunchedEffect(pendingSkip?.key) {
        val initial = pendingSkip ?: return@LaunchedEffect
        var remaining = initial.secLeft
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
            pendingSkip = pendingSkip?.withSecLeft(remaining) ?: return@LaunchedEffect
        }
        // Reached zero — fire the action.
        handledKeys += initial.key
        when (initial) {
            is PendingSkip.SkipIntro -> {
                player.seekTo(initial.endMs)
                viewModel.reportTelemetry("auto_skip_intro")
            }
            is PendingSkip.SkipRecap -> {
                player.seekTo(initial.endMs)
                viewModel.reportTelemetry("auto_skip_recap")
            }
            is PendingSkip.SkipCredits -> {
                player.seekTo(initial.endMs)
                viewModel.reportTelemetry("auto_skip_credits")
            }
            is PendingSkip.PlayNext -> {
                val next = viewModel.resolveNextUp()
                if (next != null && onPlayNext != null && !autoAdvanced) {
                    autoAdvanced = true
                    viewModel.reportTelemetry("auto_play_next", mapOf("next" to next))
                    onPlayNext(next)
                }
            }
            is PendingSkip.PreviewNext -> {
                val next = viewModel.resolveNextUp()
                if (next != null && onPlayNext != null && !autoAdvanced) {
                    autoAdvanced = true
                    viewModel.reportTelemetry("auto_play_next_preview", mapOf("next" to next))
                    onPlayNext(next)
                }
            }
        }
        pendingSkip = null
    }

    // End-of-media auto-advance. Covers items with no credits segment and the
    // case where the user skipped the credits (auto-skip or BACK) and the
    // playhead reaches the end: play the next episode, or — for a movie — the
    // top recommendation. Gated on the Auto-play-next setting and guarded so it
    // never double-fires with the credits countdown above. An extra closes
    // instead, back to its title.
    LaunchedEffect(endedState.value) {
        if (endedState.value && viewModel.mode.closesAtEnd) {
            onClose?.invoke()
            return@LaunchedEffect
        }
        if (endedState.value && !autoAdvanced && settings.autoPlayNext && onPlayNext != null) {
            val next = viewModel.resolveNextUp()
            if (next != null) {
                autoAdvanced = true
                viewModel.reportTelemetry("auto_play_next_on_end", mapOf("next" to next))
                onPlayNext(next)
            }
        }
    }

    // BACK cancels a pending skip — user explicitly opted out. Sits at lower
    // priority than the subtitle-panel BackHandler so the panel-close path
    // still wins when both are active.
    BackHandler(enabled = pendingSkip != null) {
        val p = pendingSkip
        if (p != null) {
            handledKeys += p.key
            viewModel.reportTelemetry("auto_skip_cancelled", mapOf("kind" to p.kind))
            pendingSkip = null
        }
    }

    // Closes a side panel when open; takes priority over the activity's
    // default back-press (which would otherwise finish PlayerScreen and exit
    // playback). Two separate BackHandlers so each only fires when its panel
    // is open — the LIFO dispatcher in Compose hits the most-recently-
    // composed enabled one first, but since the two are mutually exclusive
    // (open-one-at-a-time guard above), order doesn't matter.
    BackHandler(enabled = showSubtitlePanel) { showSubtitlePanel = false }
    BackHandler(enabled = showAudioPanel) { showAudioPanel = false }
    BackHandler(enabled = showQualityPanel) { showQualityPanel = false }
    BackHandler(enabled = showInfoOverlay) { showInfoOverlay = false }
    BackHandler(enabled = showSpeedPanel) { showSpeedPanel = false }
    // BACK while the "Skipped intro" pill is up undoes the pre-skip: seek back
    // to 0 and dismiss. Higher priority than the activity's exit-player BACK
    // because this is a transient, scoped affordance — the user's first BACK
    // press during the dwell window should obviously go to "watch the intro"
    // rather than "exit playback". After the pill dismisses, BACK exits as
    // usual.
    BackHandler(enabled = skippedIntroPill) {
        skippedIntroPill = false
        player.seekTo(0L)
        viewModel.reportTelemetry("binge_intro_undo")
    }

    // Activity-level key intercept via KeyEventBus — dispatchKeyEvent runs
    // before any View in the focused-window chain, so we see media keys + DPAD
    // regardless of which View (PlayerView, a Compose focusable, etc.) holds
    // focus. Routes around the focus race that broke onPreviewKeyEvent and
    // View.OnKeyListener attempts.
    // Chrome auto-hide timer — declared before the key handler so the handler
    // can wake the chrome on any key press. lastInteractionTs is bumped from
    // both the key handler (for any remote button) and the control buttons'
    // onClick handlers (for direct interaction).
    var lastInteractionTs by remember { mutableStateOf(System.currentTimeMillis()) }
    val noteInteraction: () -> Unit = { lastInteractionTs = System.currentTimeMillis() }
    // DEBUG: stream every remote button to logcat so we can diagnose remote
    // keycode quirks (Sony BRAVIA buttons can map to different codes than
    // emulator/dev kits). Tag is unique so `adb logcat -s ChinoPlayKey:V`
    // filters to just these lines.
    DisposableEffect(Unit) {
        val handler: (android.view.KeyEvent) -> Boolean = handler@{ e ->
            val phase = when (e.action) {
                android.view.KeyEvent.ACTION_DOWN -> "DOWN"
                android.view.KeyEvent.ACTION_UP -> "UP"
                else -> "MULTI"
            }
            val name = android.view.KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
            android.util.Log.d(
                "ChinoPlayKey",
                "$phase $name (${e.keyCode}) chrome=$controllerVisible repeat=${e.repeatCount}",
            )
            // Wake chrome on any key (matches web: any keypress reveals controls)
            if (e.action == android.view.KeyEvent.ACTION_DOWN) noteInteraction()
            if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                // Dedicated media keys always work — FF/REW skip ±10s, PLAY/
                // PAUSE/SPACE toggle playback regardless of where focus is.
                // Matches Android's standard media-key contract; the BRAVIA
                // remote's transport buttons (and Bluetooth keyboards) hit
                // these.
                when (e.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + 10_000L).coerceAtMost(dur))
                        viewModel.reportTelemetry("skip_forward", mapOf("source" to "ff_key"))
                        return@handler true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                        viewModel.reportTelemetry("skip_back", mapOf("source" to "rew_key"))
                        return@handler true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return@handler true }
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); return@handler true }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_SPACE,
                    -> {
                        if (player.isPlaying) player.pause() else player.play()
                        return@handler true
                    }
                    // Dedicated subtitle / audio-track buttons on TV remotes.
                    // Sony BRAVIA may eat KEYCODE_CAPTIONS in firmware before
                    // it reaches us — if that happens the ChinoPlayKey logcat
                    // tag will be silent for the press and we'll need to find
                    // a vendor-specific code. KEYCODE_MEDIA_AUDIO_TRACK is the
                    // standard android.media key for an audio-track switcher
                    // remote button. Toggle: pressing the same button while
                    // its panel is open dismisses it.
                    android.view.KeyEvent.KEYCODE_CAPTIONS -> {
                        showSubtitlePanel = !showSubtitlePanel
                        if (showSubtitlePanel) {
                            showAudioPanel = false
                            showQualityPanel = false
                        }
                        noteInteraction()
                        return@handler true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                        showAudioPanel = !showAudioPanel
                        if (showAudioPanel) {
                            showSubtitlePanel = false
                            showQualityPanel = false
                        }
                        noteInteraction()
                        return@handler true
                    }
                }
                // CENTER + ENTER when chrome is HIDDEN → toggle play/pause
                // (web-equivalent "click on video"). When chrome is visible
                // we let the focused button consume the event.
                if (!controllerVisible) {
                    when (e.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        -> {
                            if (player.isPlaying) player.pause() else player.play()
                            return@handler true
                        }
                    }
                }
                // DPAD_LEFT/RIGHT when chrome is HIDDEN → seek ±10s. With
                // chrome visible those keys navigate between focused buttons.
                if (!controllerVisible) {
                    when (e.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                            return@handler true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                            player.seekTo((player.currentPosition + 10_000L).coerceAtMost(dur))
                            return@handler true
                        }
                    }
                }
            }
            // Don't consume — let the focused control handle the key.
            false
        }
        KeyEventBus.register(handler)
        onDispose { KeyEventBus.clear(handler) }
    }

    // Live position polling — drives the custom scrubber + time labels. The
    // 250ms cadence is the same as chino-web's requestAnimationFrame-throttled
    // playhead update; finer would burn CPU, coarser would make scrubbing feel
    // laggy. positionMs / durationMs are remembered MutableStates so reads are
    // O(1) for the scrubber composable.
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var bufferedMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            bufferedMs = player.bufferedPosition
            isPlaying = player.isPlaying
            delay(250)
        }
    }
    // Chrome auto-hide: visible for 4s after the last interaction while
    // playing, 8s while paused. lastInteractionTs / noteInteraction are
    // declared earlier (above the key-event handler that needs them).
    LaunchedEffect(lastInteractionTs, isPlaying) {
        controllerVisible = true
        val gracePeriod = if (isPlaying) 4_000L else 8_000L
        delay(gracePeriod)
        controllerVisible = false
    }
    val playPauseFocusRequester = remember { FocusRequester() }
    val chromeCoroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    // When chrome reappears (visible flips false→true), park focus on the
    // play/pause button so DPAD_LEFT/RIGHT lands on a sensible default. Without
    // this the BRAVIA's last-focused-view (sometimes None) means the first
    // DPAD press goes into a void and the user thinks the remote is broken.
    LaunchedEffect(controllerVisible) {
        if (controllerVisible) {
            runCatching { playPauseFocusRequester.requestFocus() }
        }
    }
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // Default PlayerControlView is replaced by our custom
                    // Compose chrome below — disable it so we own the input
                    // model and visual styling completely. The PlayerView's
                    // SurfaceView is still in use; we just stop it from
                    // drawing its own control bar.
                    useController = false
                    // Hide PlayerView's built-in white buffering spinner —
                    // we render our own Compose spinner + text below so the
                    // two don't collide in the same screen-centre area.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // Keep the screen on while the player is mounted. Without
                    // this the BRAVIA's idle-detector kicks the screensaver
                    // in during long passive scenes (a 2 h film with quiet
                    // dialogue scenes triggers it well before the credits).
                    // Sets FLAG_KEEP_SCREEN_ON on the host window while this
                    // view is attached; clears automatically when the user
                    // backs out of the player. We bind to "mounted" not "is
                    // playing" deliberately — pausing for 30 s shouldn't make
                    // the screen blank either.
                    keepScreenOn = true
                }
            },
            // A quality switch rebuilds the ExoPlayer instance (player is
            // remember(masterUrl, reloadKey)); the factory only runs once, so
            // without this the PlayerView keeps the OLD, released player and
            // the surface goes black after switching quality. Re-bind whenever
            // the player instance changes — mirrors chino-mobile's
            // PlayerScreen.android.kt update block.
            update = { v -> if (v.player !== player) v.player = player },
        )
        // Preparing-spinner overlay — covers the gap between mount and the
        // first decoded frame, plus any re-buffering events mid-stream.
        // Stacked column: rotating arc spinner above, witty rotating
        // status text below. Text cycles through LOADING_MESSAGES every
        // 2 s (matches chino-web's pattern + verbatim string list) and
        // resets to a stable index per "preparing" episode so the user
        // doesn't see the same line every time they pause.
        val isPreparing = !sawFirstFrameState.value || bufferingState.value
        if (isPreparing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    PreparingSpinner()
                    var msgIdx by remember { mutableStateOf((0..LOADING_MESSAGES.lastIndex).random()) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(2_000)
                            msgIdx = (msgIdx + 1) % LOADING_MESSAGES.size
                        }
                    }
                    Text(
                        text = LOADING_MESSAGES[msgIdx],
                        color = ChinoText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        // The quality menu /play/info offers (null: nothing to pick) and the
        // rung that plays, by its menu label — Auto says it: "Auto · 720p".
        // A mode without /play/info (an extra) has its master's variants for
        // a menu, and the entry it is on is the variant pinned.
        val byPlayInfo = viewModel.requests.playInfo
        val menu = remember(ready.playInfo, videoVariants) {
            if (byPlayInfo) qualityMenu(ready.playInfo) else variantMenu(videoVariants)
        }
        val quality = if (byPlayInfo) ready.currentQuality else pinnedVariant
        val playing = playingSize?.let { (w, h) -> playingLabel(w, h, menu) }
        // Custom Compose chrome — replaces the default PlayerControlView.
        // Layout mirrors chino-web's PlayerPage: top bar (back + home + title),
        // bottom bar (scrubber row + button row). Auto-hides after the grace
        // period set in `lastInteractionTs` above. The mode badge was removed
        // from the chrome — the Mode/encoder detail now lives only in the
        // Playback-info overlay (Info button), matching chino-web.
        PlayerChromeOverlay(
            visible = controllerVisible,
            top = {
                PlayerTopChrome(
                    title = ready.title,
                    onBack = { backDispatcher?.onBackPressedDispatcher?.onBackPressed() },
                    onHome = onHome,
                    onUserInteraction = noteInteraction,
                )
            },
            bottom = {
                PlayerBottomChrome(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    segments = ready.segments,
                    trickplayCues = ready.trickplayCues,
                    trickplayBaseUrl = ready.trickplayBaseUrl,
                    streamToken = ready.streamToken,
                    muted = muted,
                    volume = volume,
                    onPlayPause = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    onToggleMute = { muted = !muted },
                    onVolumeChange = { v -> muted = false; volume = v },
                    onScrubberSeek = { ms -> player.seekTo(ms) },
                    onAudio = if (audioTracks.size > 1) {
                        { showAudioPanel = true }
                    } else null,
                    onSubtitles = { showSubtitlePanel = true },
                    captionsOn = tracks.any { it.selected },
                    onQuality = if (menu != null) {
                        { showQualityPanel = true }
                    } else null,
                    onSpeed = {
                        showSpeedPanel = true
                        showAudioPanel = false
                        showSubtitlePanel = false
                        showQualityPanel = false
                    },
                    speedActive = showSpeedPanel || playbackSpeed != 1f,
                    onInfo = { showInfoOverlay = true },
                    // Prev/next chevrons only for episodes (parentSeriesId != null),
                    // matching web which hides them on movies.
                    onPrevEpisode = if (onPlayNext != null && ready.parentSeriesId != null) {
                        { resolveAndPlayPrev(viewModel, onPlayNext, chromeCoroutineScope) }
                    } else null,
                    onNextEpisode = if (onPlayNext != null && ready.parentSeriesId != null) {
                        { resolveAndPlayNext(viewModel, onPlayNext, chromeCoroutineScope) }
                    } else null,
                    playPauseFocusRequester = playPauseFocusRequester,
                    onUserInteraction = noteInteraction,
                )
            },
        )
        bannerMessage?.let { label ->
            SubtitleBanner(label = label, onDismiss = { bannerMessage = null })
        }
        if (showSubtitlePanel) {
            SubtitlePanel(
                tracks = tracks,
                onSelect = { track ->
                    // The viewer's from now on: no forced subtitle comes on
                    // by itself any more, and after their Off none for the
                    // rest of the playback.
                    subtitlePicked = true
                    if (track == null) viewModel.turnSubtitlesOff()
                    val label = applySubtitleSelection(player, track)
                    bannerMessage = label
                    showSubtitlePanel = false
                    viewModel.reportTelemetry("subtitle_select", mapOf("label" to label))
                },
                onDismiss = { showSubtitlePanel = false },
            )
        }
        if (showAudioPanel) {
            AudioPanel(
                tracks = audioTracks,
                onSelect = { track ->
                    val label = applyAudioSelection(player, track)
                    bannerMessage = label
                    showAudioPanel = false
                    viewModel.reportTelemetry("audio_select", mapOf("label" to label))
                },
                onDismiss = { showAudioPanel = false },
            )
        }
        if (showQualityPanel && menu != null) {
            QualityPanel(
                rungs = menu,
                active = chosenQuality(menu, quality)?.name,
                playing = playing,
                onPick = { rung ->
                    showQualityPanel = false
                    if (!byPlayInfo) {
                        // A variant of the master: pinned in this player,
                        // where it plays on — no reload.
                        pinnedVariant = rung
                        applyVariantSelection(player, rung)
                    } else {
                        // The reload goes on at this playhead (prepare's atSec);
                        // the progress save is only the usual one, not what the
                        // reload reads.
                        val pos = (player.currentPosition / 1000L).toInt()
                        val dur = (player.duration.takeIf { it != C.TIME_UNSET } ?: 0).let { (it / 1000L).toInt() }
                        if (pos > 0) viewModel.reportProgress(pos, dur)
                        viewModel.switchQuality(rung, atSec = pos)
                    }
                },
                onDismiss = { showQualityPanel = false },
            )
        }
        if (showSpeedPanel) {
            SpeedPanel(
                current = playbackSpeed,
                onPick = { rate ->
                    showSpeedPanel = false
                    playbackSpeed = rate
                    // Apply on the in-scope ExoPlayer — same mechanism as mobile;
                    // no PlayerViewModel change. Fully-qualified to avoid a new import.
                    player.playbackParameters = androidx.media3.common.PlaybackParameters(rate)
                    viewModel.reportTelemetry("speed_select", mapOf("rate" to rate.toString()))
                },
                onDismiss = { showSpeedPanel = false },
            )
        }
        pendingSkip?.let { p ->
            SkipCountdownPill(prompt = p)
        }
        // Manual Skip Intro / Skip Recap / Skip Credits control — web parity
        // (PlayerPage skipSegment). Visible whenever the playhead is inside an
        // intro/recap/credits segment, independent of the binge auto-skip
        // setting. Seeks to the segment end (+250ms, like web) and reports the
        // skip. Hidden while an auto-skip countdown for that same segment is on
        // screen so the two affordances don't stack in the same corner (web
        // replaces the plain button with the countdown card the same way). It's
        // focusable so DPAD users can reach it, but never calls requestFocus —
        // so it can't steal focus from the playback controls.
        val activeManualSeg = if (pendingSkip == null) {
            ready.segments.firstOrNull { seg ->
                positionMs in seg.startMs until seg.endMs && when (seg.kind.lowercase()) {
                    "intro", "recap", "credits", "preview" -> true
                    else -> false
                }
            }
        } else null
        // A post-credits preview gets a "Next episode ▶" card (play the teaser,
        // jump on activate); intro/recap/credits keep the plain "Skip …" button.
        // The preview claims the segment first so a post-credits recap shows
        // "Next episode" rather than "Skip Recap".
        val previewSeg = activeManualSeg
            ?.takeIf { onPlayNext != null && isPostCreditsPreview(it, ready.segments) }
        val skipSeg = activeManualSeg
            ?.takeIf { it !== previewSeg && it.kind.lowercase() in SKIPPABLE_MANUAL_KINDS }
        if (previewSeg != null) {
            NextEpisodeButton(
                onPlay = {
                    noteInteraction()
                    chromeCoroutineScope.launch {
                        val next = viewModel.resolveNextUp()
                        if (next != null && onPlayNext != null) {
                            viewModel.reportTelemetry("play_next_preview", mapOf("next" to next))
                            onPlayNext(next)
                        }
                    }
                },
            )
        }
        if (skipSeg != null) {
            SkipSegmentButton(
                segment = skipSeg,
                onSkip = {
                    noteInteraction()
                    // +250ms past the boundary mirrors chino-web (end_ms/1000 +
                    // 0.25) so we land clear of the last segment frame.
                    player.seekTo(skipSeg.endMs + 250L)
                    viewModel.reportTelemetry(
                        "skip_segment",
                        mapOf(
                            "kind" to skipSeg.kind.lowercase(),
                            "from_sec" to (positionMs / 1000L).toString(),
                            "to_sec" to ((skipSeg.endMs + 250L) / 1000L).toString(),
                        ),
                    )
                },
            )
        }
        if (showInfoOverlay) {
            PlaybackInfoOverlay(
                info = ready.playInfo,
                quality = qualityText(menu, quality, playing),
                audio = audioTracks.firstOrNull { it.selected }?.label?.replace(" • ", " · "),
                onDismiss = { showInfoOverlay = false },
            )
        }
        if (skippedIntroPill) {
            SkippedIntroPill(onTimeout = { skippedIntroPill = false })
        }
    }
}

/**
 * Position-based reclassification of a next-episode preview. The analyzer
 * mislabels post-credits "next time on…" teasers as `recap` (a "Previously
 * on…" opener) — hundreds of them in the catalog — so a recap that STARTS at
 * or after the credits is really a post-roll preview, and an explicit `preview`
 * kind always is. Such segments get the "Next episode ▶" card treatment (play
 * the teaser, offer to jump) instead of the skip-recap treatment (seek past it,
 * which robbed the viewer of the teaser). A genuine opening recap starts near
 * 0:00 and keeps the skip behaviour.
 */
private fun isPostCreditsPreview(seg: Segment, all: List<Segment>): Boolean {
    when (seg.kind.lowercase()) {
        "preview" -> return true
        "recap" -> {
            val creditsStart = all
                .filter { it.kind.equals("credits", ignoreCase = true) }
                .minByOrNull { it.startMs }?.startMs ?: return false
            return seg.startMs >= creditsStart
        }
        else -> return false
    }
}

/* ─────────────────────────  web-style menu popovers  ─────────────────────
 * Subtitle / Audio / Speed / Quality pickers render as compact popovers
 * anchored bottom-right above the control bar — chino-web CaptionsMenuCard /
 * SpeedMenuCard, chino-mobile parity — NOT full-height drawers. They float
 * over the video (no scrim, like web); the first row auto-focuses, CENTER
 * selects, BACK closes (BackHandler at the call site). #161B22 / 8dp radius /
 * 1dp white@10% border; selected row = blue. */
@Composable
private fun MenuPopover(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(end = 24.dp, bottom = 92.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .heightIn(max = 460.dp)
                .clip(RectangleShape)
                .background(ChinoSurface)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RectangleShape)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun PlayerMenuHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = ChinoMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = ChinoMuted.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

@Composable
private fun PlayerMenuRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    secondary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val base = Modifier
        .fillMaxWidth()
        .background(if (focused) ChinoAccent.copy(alpha = 0.22f) else Color.Transparent)
        .padding(horizontal = 16.dp, vertical = 10.dp)
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) ChinoAccent else Color.White,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(text = secondary, color = ChinoMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MenuCheckbox(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RectangleShape)
            .background(if (selected) ChinoAccent else Color.Transparent)
            .border(1.dp, if (selected) ChinoAccent else Color.White.copy(alpha = 0.3f), RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Splits the TV audio label "primary • channels • codec" into
 *  (primary, "channels · codec") to match web/mobile's two-line row. */
private fun splitAudioLabel(label: String): Pair<String, String?> {
    val parts = label.split(" • ")
    if (parts.size <= 1) return label to null
    return parts[0] to parts.drop(1).joinToString(" · ").ifBlank { null }
}

/** The quality menu ([qualityMenu]): Auto and a packaged title's rungs, or
 *  an on-the-fly transcode's high / medium / low, by the server's labels.
 *  [active] is the entry the player is on; on Auto, its row names the rung
 *  that plays ([playing]): "Auto · 720p". */
@Composable
private fun QualityPanel(
    rungs: List<cloud.nalet.chino.tv.data.api.QualityRung>,
    active: String?,
    playing: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    MenuPopover(width = 200.dp) {
        PlayerMenuHeader("QUALITY")
        rungs.forEachIndexed { idx, rung ->
            PlayerMenuRow(
                label = if (rung.name == AUTO && active == AUTO) autoLabel(rung.label, playing) else rung.label,
                selected = rung.name == active,
                focusRequester = focusRequester.takeIf { idx == 0 },
                onClick = { onPick(rung.name) },
            )
        }
    }
}

/** Playback-speed picker — structural clone of [QualityPanel], reusing
 *  [QualityRow] so it inherits the same DPAD focus model (CENTER/ENTER select,
 *  2dp blue focus border, first-row auto-focus). Mirrors mobile's SpeedMenuCard
 *  rates; 1.0x renders as "Normal". */
@Composable
private fun SpeedPanel(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // Web rate ladder (7 steps incl. 1.75x); 1.0x renders "Normal (1x)".
    val rates = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    MenuPopover(width = 180.dp) {
        PlayerMenuHeader("SPEED")
        rates.forEachIndexed { idx, rate ->
            PlayerMenuRow(
                label = when {
                    rate == 1.0f -> "Normal (1x)"
                    rate == rate.toInt().toFloat() -> "${rate.toInt()}x"
                    else -> "${rate}x"
                },
                selected = rate == current,
                focusRequester = focusRequester.takeIf { idx == 0 },
                onClick = { onPick(rate) },
            )
        }
    }
}

sealed class PendingSkip {
    abstract val key: String
    abstract val secLeft: Int
    abstract val kind: String
    abstract fun withSecLeft(n: Int): PendingSkip

    data class SkipIntro(override val key: String, val endMs: Long, override val secLeft: Int) : PendingSkip() {
        override val kind = "intro"
        override fun withSecLeft(n: Int) = copy(secLeft = n)
    }
    data class SkipRecap(override val key: String, val endMs: Long, override val secLeft: Int) : PendingSkip() {
        override val kind = "recap"
        override fun withSecLeft(n: Int) = copy(secLeft = n)
    }
    data class SkipCredits(override val key: String, val endMs: Long, override val secLeft: Int) : PendingSkip() {
        override val kind = "credits"
        override fun withSecLeft(n: Int) = copy(secLeft = n)
    }
    data class PlayNext(override val key: String, override val secLeft: Int) : PendingSkip() {
        override val kind = "next"
        override fun withSecLeft(n: Int) = copy(secLeft = n)
    }
    /** Post-credits next-episode preview: the teaser plays while a "Next episode
     *  ▶ in Ns" card counts down to the jump (BACK cancels → keep watching the
     *  teaser). Unlike [PlayNext] it does NOT seek/skip — the preview stays on
     *  screen underneath the card. */
    data class PreviewNext(override val key: String, override val secLeft: Int) : PendingSkip() {
        override val kind = "preview"
        override fun withSecLeft(n: Int) = copy(secLeft = n)
    }
}

@Composable
private fun SkipCountdownPill(prompt: PendingSkip) {
    val message = when (prompt) {
        is PendingSkip.SkipIntro -> "Skipping intro in ${prompt.secLeft}s"
        is PendingSkip.SkipRecap -> "Skipping recap in ${prompt.secLeft}s"
        is PendingSkip.SkipCredits -> "Skipping credits in ${prompt.secLeft}s"
        is PendingSkip.PlayNext -> "Up next in ${prompt.secLeft}s"
        is PendingSkip.PreviewNext -> "Next episode ▶ in ${prompt.secLeft}s"
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xCC101010), shape = RectangleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = message, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Press BACK to cancel",
                color = ChinoMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Manual skip control — a focusable pill at the bottom-end that seeks past the
 *  current intro/recap/credits segment. Independent of the binge auto-skip
 *  setting (web parity: the plain "Skip Intro/Recap/Credits" button on
 *  PlayerPage). Wording matches web exactly. Focusable + DPAD-activatable
 *  (CENTER/ENTER), but the caller never requests focus onto it so it can't
 *  steal focus from the transport controls; the user DPADs over to it. */
@Composable
private fun SkipSegmentButton(
    segment: Segment,
    onSkip: () -> Unit,
) {
    // Web wording: "Skip Intro" / "Skip Recap" / "Skip Credits".
    val label = when (segment.kind.lowercase()) {
        "intro" -> "Skip Intro"
        "recap" -> "Skip Recap"
        "credits" -> "Skip Credits"
        else -> "Skip"
    }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Row(
            modifier = Modifier
                .clip(RectangleShape)
                // Web's button is white-on-black; on focus we lift to the brand
                // blue + ring so it reads at 10-ft like the other chrome.
                .background(if (focused) ChinoAccent else Color.White)
                .then(
                    if (focused) Modifier.border(3.dp, ChinoAccent, RectangleShape)
                    else Modifier,
                )
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown &&
                        (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                        onSkip(); true
                    } else false
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.tv.material3.Icon(
                imageVector = Lucide.SkipForward,
                contentDescription = null,
                tint = if (focused) Color.White else Color.Black,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                color = if (focused) Color.White else Color.Black,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** "Next episode ▶" card shown over a post-credits preview (a next-episode
 *  teaser the analyzer mislabelled as a recap, or an explicit `preview`). Plays
 *  the teaser underneath; activating jumps straight to the next episode. Styled
 *  as the brand-blue counterpart of [SkipSegmentButton] so it reads as the same
 *  "there's an action here" affordance family. Focusable + DPAD-activatable but
 *  never requests focus, so it can't steal focus from the transport controls. */
@Composable
private fun NextEpisodeButton(
    onPlay: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Row(
            modifier = Modifier
                .clip(RectangleShape)
                // Blue by default (this is the primary action, not a dismiss);
                // focus deepens it + adds the ring for 10-ft legibility.
                .background(ChinoAccent)
                .then(
                    if (focused) Modifier.border(3.dp, Color.White, RectangleShape)
                    else Modifier,
                )
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown &&
                        (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                        onPlay(); true
                    } else false
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.tv.material3.Icon(
                imageVector = Lucide.SkipForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Next episode",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Side panel listing each subtitle track plus an "Off" entry.
 * DPAD-navigable; CENTER selects, BACK dismisses. Slides in from the right.
 */
@Composable
private fun SubtitlePanel(
    tracks: List<SubtitleTrack>,
    onSelect: (SubtitleTrack?) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val anySelected = tracks.any { it.selected }
    MenuPopover(width = 300.dp) {
        PlayerMenuHeader("SUBTITLES", trailing = "${if (anySelected) 1 else 0}/${tracks.size}")
        // "None / Off" — always first; clears the text-track override. No
        // checkbox (web renders it as plain text, blue when active).
        PlayerMenuRow(
            label = "None / Off",
            selected = !anySelected,
            focusRequester = focusRequester,
            onClick = { onSelect(null) },
        )
        if (tracks.isEmpty()) {
            Text(
                text = "No subtitles available",
                color = ChinoMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            tracks.forEach { track ->
                PlayerMenuRow(
                    label = track.label,
                    selected = track.selected,
                    leading = { MenuCheckbox(track.selected) },
                    onClick = { onSelect(track) },
                )
            }
        }
    }
}

data class SubtitleTrack(
    val id: String,
    val label: String,
    val selected: Boolean,
    // Internal pointers so applySubtitleSelection can rebuild the override.
    val group: Tracks.Group,
    val trackIndex: Int,
    /** What the forced subtitle's rule reads of it: language, forced, image. */
    val option: SubtitleOption,
)

/** The subtitle panel's rows, each labelled by [subtitleLabels]: by its
 *  language ("German", "No dialogue" for zxx), with what its own label - the
 *  sidecar's, the rendition's NAME - says beyond that ("English · SDH"); a
 *  track in no language by its own label, else "Unknown". */
private fun collectSubtitleTracks(tracks: Tracks): List<SubtitleTrack> {
    val found = tracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { g -> (0 until g.length).map { i -> g to i } }
    val formats = found.map { (g, i) -> g.getTrackFormat(i) }
    val labels = subtitleLabels(
        formats.map { fmt ->
            SubtitleLabelInput(fmt.language, fmt.label, forced = (fmt.selectionFlags and C.SELECTION_FLAG_FORCED) != 0)
        },
    )
    return found.mapIndexed { k, (g, i) ->
        val id = "${g.mediaTrackGroup.id}#$i"
        val fmt = formats[k]
        SubtitleTrack(
            id = id,
            label = labels[k],
            selected = g.isTrackSelected(i),
            group = g,
            trackIndex = i,
            option = SubtitleOption(
                id = id,
                language = fmt.language,
                forced = (fmt.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                image = isImageSubtitle(fmt.sampleMimeType, fmt.codecs),
            ),
        )
    }
}

data class AudioTrack(
    val id: String,
    val label: String,
    val selected: Boolean,
    val group: Tracks.Group,
    val trackIndex: Int,
    /** Its language tag, which the forced subtitle follows. */
    val language: String? = null,
)

/** Builds the user-facing label from format metadata: the language first
 *  ("German", "No dialogue" for zxx), the NAME where there is none or where
 *  it tells two apart ([audioLabels]). Appends channel count + codec when
 *  present so "English • 5.1 • ec-3" disambiguates surround vs stereo — a
 *  package's 5.1 E-AC-3 companion beside its stereo twin, both of one
 *  language. Only the tracks the TV can play are listed: a pick of one it
 *  can neither decode nor pass through would fail the playback. */
private fun collectAudioTracks(tracks: Tracks): List<AudioTrack> {
    val found = tracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it, /* allowExceedsCapabilities= */ true) }.map { i -> g to i }
        }
    val formats = found.map { (g, i) -> g.getTrackFormat(i) }
    val details = formats.map { fmt ->
        listOfNotNull(
            fmt.channelCount.takeIf { it > 0 }?.let {
                when (it) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "${it}ch"
                }
            },
            audioCodec(fmt.codecs, fmt.sampleMimeType),
        )
    }
    val labels = audioLabels(
        formats.mapIndexed { k, fmt -> AudioLabelInput(fmt.language, fmt.label, details[k].joinToString(" • ")) },
    )
    return found.mapIndexed { k, (g, i) ->
        AudioTrack(
            id = "${g.mediaTrackGroup.id}#$i",
            label = (listOf(labels[k]) + details[k]).joinToString(" • "),
            selected = g.isTrackSelected(i),
            group = g,
            trackIndex = i,
            language = formats[k].language,
        )
    }
}

/** The master's video variants the device plays, as Media3 read them: each
 *  one's track in the video group, its picture size, codec and bitrate — what
 *  [variantMenu] makes a quality menu of. */
private fun collectVideoVariants(tracks: Tracks): List<VideoVariant> {
    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return emptyList()
    return (0 until group.length).filter { group.isTrackSupported(it) }.map { i ->
        val fmt = group.getTrackFormat(i)
        VideoVariant(track = i, width = fmt.width, height = fmt.height, codec = fmt.codecs, bitrate = fmt.bitrate)
    }
}

/** Pins the video variant a [variantMenu] entry names (its track), or, on
 *  Auto, lets the player adapt again. */
private fun applyVariantSelection(player: ExoPlayer, name: String) {
    val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
    val track = name.toIntOrNull()?.takeIf { group != null && it in 0 until group.length }
    val params = player.trackSelectionParameters.buildUpon()
    if (group == null || track == null) {
        params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    } else {
        params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
    }
    player.trackSelectionParameters = params.build()
}

private fun applyAudioSelection(player: ExoPlayer, track: AudioTrack): String {
    val params = player.trackSelectionParameters
    player.trackSelectionParameters = params.buildUpon()
        .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
        .build()
    return "Audio: ${track.label}"
}

/** Side panel listing each audio track. Mirrors SubtitlePanel structurally
 *  (could be refactored to a generic TrackPanel<T> once we hit a fourth panel
 *  variant, but two near-clones is not yet pulling weight). */
@Composable
private fun AudioPanel(
    tracks: List<AudioTrack>,
    onSelect: (AudioTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    MenuPopover(width = 300.dp) {
        PlayerMenuHeader("AUDIO")
        if (tracks.isEmpty()) {
            Text(
                text = "No audio tracks",
                color = ChinoMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            tracks.forEachIndexed { idx, track ->
                val (primary, suffix) = splitAudioLabel(track.label)
                PlayerMenuRow(
                    label = primary,
                    secondary = suffix,
                    selected = track.selected,
                    focusRequester = focusRequester.takeIf { idx == 0 },
                    onClick = { onSelect(track) },
                )
            }
        }
    }
}

/** The subtitles as set in Settings: Off (or nothing set) turns text off,
 *  and with it any track picked; a language has Media3 pick a track in it. */
private fun TrackSelectionParameters.Builder.subtitlesAsSet(pref: String): TrackSelectionParameters.Builder =
    if (pref == "off" || pref.isBlank()) {
        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
    } else {
        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(pref)
    }

private fun applySubtitleSelection(player: ExoPlayer, track: SubtitleTrack?): String {
    val params = player.trackSelectionParameters
    return if (track == null) {
        player.trackSelectionParameters = params.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        "Subtitles off"
    } else {
        player.trackSelectionParameters = params.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
            .build()
        "Subtitles: ${track.label}"
    }
}

@Composable
private fun PlaybackInfoOverlay(
    info: cloud.nalet.chino.tv.data.api.PlayInfo?,
    quality: String,
    /** The audio track that plays, as the audio menu names it ("English ·
     *  5.1 · ec-3"); null before the player has picked one. */
    audio: String?,
    onDismiss: () -> Unit,
) {
    // Auto-dismiss after 8s — same dwell as chino-web's info badge so the user
    // doesn't have to remember to BACK out of it. BACK also dismisses (handled
    // by the BackHandler at the caller).
    LaunchedEffect(Unit) {
        delay(8_000)
        onDismiss()
    }
    // Top-right card: filename, mode + encoder, WxH, codecs, quality rung.
    // Mirrors chino-web's "Playback info" tooltip — useful for diagnosing
    // whether a slow segment hit transcode-libx264 vs passthrough-NVENC.
    val rows = buildList {
        info?.filename?.takeIf { it.isNotBlank() }?.let { add("File" to it) }
        info?.mode?.takeIf { it.isNotBlank() }?.let { mode ->
            val encoder = info.encoder?.takeIf { it.isNotBlank() && mode == "transcode" }
            add("Mode" to if (encoder != null) "$mode ($encoder)" else mode)
        }
        info?.reason?.takeIf { it.isNotBlank() }?.let { add("Reason" to it) }
        info?.container?.takeIf { it.isNotBlank() }?.let { add("Container" to it) }
        val codec = info?.videoCodec?.takeIf { it.isNotBlank() }
        val resolution = if ((info?.width ?: 0) > 0 && (info?.height ?: 0) > 0) "${info?.width}x${info?.height}" else null
        listOfNotNull(codec, resolution).takeIf { it.isNotEmpty() }?.let { add("Video" to it.joinToString(" • ")) }
        // The track that plays, else the codec /play/info says it starts on:
        // a stereo output starts on the stereo twin of the 5.1 default.
        (audio ?: info?.audioCodec)?.takeIf { it.isNotBlank() }?.let { add("Audio" to it) }
        if (quality.isNotBlank()) add("Quality" to quality)
        if (isEmpty()) add("Info" to "No playback metadata available")
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xE6101010), shape = RectangleShape)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .width(360.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Playback info",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            rows.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "$label:",
                        color = ChinoMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(80.dp),
                    )
                    Text(
                        text = value,
                        color = ChinoText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleOverlay(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xCC000000), Color.Transparent),
                ),
            )
            // Match the top-chrome's 32dp horizontal inset so the title
            // doesn't shift left/right when the controller shows/hides.
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

/** Brief bottom-end pill shown right after a binge auto-play-next chain
 *  opened the player past the intro. Auto-dismisses after 6 s; BACK during
 *  that window is intercepted by the caller's BackHandler to undo the skip
 *  (seek back to 0:00). Mirrors the "Skipping intro in Ns" SkipCountdownPill
 *  styling so the user reads it as the same affordance family. */
@Composable
private fun SkippedIntroPill(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(6_000)
        onTimeout()
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xCC101010), shape = RectangleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "Skipped intro", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Press BACK to watch from the start",
                color = ChinoMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SubtitleBanner(label: String, onDismiss: () -> Unit) {
    LaunchedEffect(label) {
        delay(2_000)
        onDismiss()
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xE6202020), shape = RectangleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Centered(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Rotating arc spinner — matches chino-web's `animate-spin` ring icon. Hand
 *  rolled with Canvas + infiniteRepeatable so we don't pull in Material3 (TV
 *  Material doesn't expose CircularProgressIndicator and full Material3 would
 *  bloat the APK by ~700 KB for one widget). */
@Composable
private fun PreparingSpinner() {
    val infinite = rememberInfiniteTransition(label = "spinner")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
        ),
        label = "spinner_rotation",
    )
    Canvas(modifier = Modifier.size(56.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        // Background track — full circle at low opacity.
        drawArc(
            color = Color(0x33FFFFFF),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        // Foreground arc — 270° wedge that rotates. The leading 1/4 of the
        // ring is transparent which produces the chasing-comet look.
        drawArc(
            color = ChinoAccent,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter = false,
            style = stroke,
        )
    }
}

/** Resolves the next-episode id off the main thread, then jumps the player at it
 *  via the navigation callback. Fire-and-forget so the click handler isn't
 *  blocked while chino-api computes the next id. */
private fun resolveAndPlayNext(
    viewModel: PlayerViewModel,
    onPlayNext: (String) -> Unit,
    scope: CoroutineScope,
) {
    scope.launch {
        val next = viewModel.resolveNextEpisode()
        if (next != null) onPlayNext(next)
    }
}

/** Counterpart for the Prev chevron — same nav callback because navigating to
 *  another episode pops the current player off the stack either way. Returns
 *  silently when we're on the first episode of a series. */
private fun resolveAndPlayPrev(
    viewModel: PlayerViewModel,
    onPlay: (String) -> Unit,
    scope: CoroutineScope,
) {
    scope.launch {
        val prev = viewModel.resolvePrevEpisode()
        if (prev != null) onPlay(prev)
    }
}
