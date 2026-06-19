package cloud.nalet.chino.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.AppSettings
import cloud.nalet.chino.tv.data.CodecCaps
import cloud.nalet.chino.tv.data.SettingsStore
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.PlayInfo
import cloud.nalet.chino.tv.data.api.ProgressBody
import cloud.nalet.chino.tv.data.api.QualityRung
import cloud.nalet.chino.tv.data.api.Segment
import cloud.nalet.chino.tv.data.api.SidecarSubtitle
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.feedback.BugReporter
import cloud.nalet.chino.tv.feedback.bugFingerprint
import cloud.nalet.chino.tv.ui.settings.BugReportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface PlayerUiState {
    data object Preparing : PlayerUiState
    data class Ready(
        val masterUrl: String,
        val title: String,
        val resumePositionSec: Int,
        val segments: List<Segment>,
        // URLs have the stream token appended already so ExoPlayer can fetch
        // them without OIDC headers (matches the <track src> path on web).
        val sidecarSubtitles: List<SidecarSubtitle>,
        // Series parent id (the item the user originally entered from) so we
        // know where to ask for the next episode. Null for movies.
        val parentSeriesId: String?,
        /** chino-stream's transcode decision + ladder. Null on pre-probe failure. */
        val playInfo: PlayInfo?,
        /** Currently-selected rung name ("high" / "medium" / "low"). */
        val currentQuality: String,
        /** Scrub-preview thumbnail cues parsed from the trickplay VTT. Empty
         *  when the item isn't packaged (no sprite tree) or the fetch failed —
         *  the scrubber then degrades to segment-stripes-only. */
        val trickplayCues: List<TrickplayCue> = emptyList(),
        /** `…/play/trickplay` base — sprite filenames from the cues hang off
         *  this with `?stream=<token>` appended. Empty when no cues. */
        val trickplayBaseUrl: String = "",
        /** The bare `?stream=` token, appended to each sprite URL so Coil can
         *  fetch the sheet without OIDC headers (same auth the master uses). */
        val streamToken: String = "",
        /** Reload bump — PlayerScreen keys ExoPlayer factory on it so changing
         *  quality tears down + rebuilds with the new ?q= URL. */
        val reloadKey: Int,
        /** The intro segment we pre-skipped at prepare-time because the user
         *  arrived here via binge auto-play-next. Non-null only on the first
         *  prepare of a binge-chained episode; clears on switchQuality reload
         *  so the "Skipped intro" pill doesn't re-appear after the user picks
         *  a different quality. PlayerScreen renders an undo pill and primes
         *  handledKeys with this segment so the normal SkipIntro countdown
         *  doesn't re-fire on top of the pre-skip. */
        val preSkippedIntro: Segment? = null,
    ) : PlayerUiState
    data class Error(val message: String) : PlayerUiState
}

/**
 * Owns the lifecycle around an HLS play session:
 *  1. Mint a `?stream=` token (cached, shared with library posters).
 *  2. Fetch the previous resume position via /v1/items/{id}/progress and seek
 *     there silently on start (auto-resume always — matches chino-web b6a9437,
 *     which dropped the "Continue watching?" dialog). "Play from start" from
 *     the Detail screen sets [startFromZero] to skip the lookup entirely.
 *  3. While ExoPlayer is rolling, the PlayerScreen calls [reportProgress] every
 *     10 s and on dispose so chino-api's progress table stays current.
 *  4. Optional [reportTelemetry] for play/pause/seek/error events — chino-api
 *     forwards each batch to the cluster log aggregator.
 */
class PlayerViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val settingsStore: SettingsStore,
    private val telemetry: Telemetry,
    /** Bug-report funnel for fatal player errors — fire-and-forget, session-
     *  deduped by fingerprint, all failures swallowed (see [reportPlayerErrorBug]). */
    private val bugReporter: BugReporter,
    private val itemId: String,
    /** When true, prepare() ignores any saved resume position and starts at
     *  0:00. Wired from the Detail screen's "Play from start" button via
     *  the navigation route's `fromStart=true` query parameter. */
    private val startFromZero: Boolean = false,
    /** When true, prepare() pre-skips the intro segment (if one is detected
     *  near 0:00) so a binge auto-play-next chain doesn't dump the user back
     *  at 00:00 just to fire the SkipIntro countdown again. PlayerScreen
     *  shows a brief undo pill so the user can BACK to revisit. Only set by
     *  the auto-play-next nav path; manual prev/next/detail entry leaves it
     *  false so the intro plays normally. */
    private val fromBinge: Boolean = false,
    /** Application-lifetime scope for terminal POSTs (progress / watched) so
     *  the OkHttp call isn't cancelled when the user backs out of the player
     *  and viewModelScope dies. Without this the library refresh-on-resume
     *  reads stale data because the save lost the cancellation race. */
    private val appScope: kotlinx.coroutines.CoroutineScope,
    /** Shared with the ExoPlayer data source — used for binge pre-warm
     *  fetches that hit chino-stream's master.m3u8 (HMAC stream-token auth,
     *  no bearer required). */
    private val streamHttpClient: okhttp3.OkHttpClient,
    /** The connected server's base URL (already trailing-slash-trimmed),
     *  sourced from the runtime ServerConfig rather than BuildConfig so the
     *  player follows a user-configured server. */
    private val baseUrl: String,
    /** When >0, resume at this absolute second (Zap's ?resume= channel-surf
     *  handoff) and skip the server progress lookup. */
    private val resumeSec: Int = 0,
) : ViewModel() {

    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Preparing)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    // Per-play-session identifier — pinned to the parent process session so
    // telemetry rolls up to one "app launch" view even across multiple plays.
    private val sessionId: String = telemetry.sessionId

    /** Technical string of the most recent fatal player error — the exact
     *  description [reportPlayerErrorBug]'s auto report shipped. The terminal
     *  error UI's manual "Report this problem" row re-files it through
     *  [fileTerminalErrorBug] so the ticket carries the engine diagnostics,
     *  not the user-facing one-liner. */
    private var lastTechnicalError: String? = null

    /** Lifecycle of the terminal-error manual report — reuses the Settings
     *  panel's [BugReportState] so the inline body (sending line / "Filed bug
     *  #id" / plain-language failure) reads identically across the two
     *  manual-report surfaces. */
    private val _errorReport = MutableStateFlow<BugReportState>(BugReportState.Idle)
    val errorReport: StateFlow<BugReportState> = _errorReport.asStateFlow()

    init { prepare(quality = null) }

    /**
     * Loads everything we need for playback in parallel. `quality` overrides
     * the server's default rung; pass null on first prepare and "high" /
     * "medium" / "low" on a switchQuality reload.
     */
    fun prepare(quality: String?) {
        _state.value = PlayerUiState.Preparing
        viewModelScope.launch {
            _state.value = try {
                val token = withContext(Dispatchers.IO) { streamTokens.valid() }
                val capsParam = CodecCaps.queryParam
                // Fan-out all six prepare-time API calls in parallel — they're
                // independent and slow ones (segments, subtitles) used to gate
                // playback startup on the longest single call (~11s observed
                // on the BRAVIA). Now max(individual) ≈ 2-3s.
                val (item, resume, segments, rawSubs, info) = coroutineScope {
                    val itemDef = async { runCatching { api.getItem(itemId) }.getOrNull() }
                    // When the user clicked "Play from start", skip the
                    // resume-position lookup entirely — the saved progress
                    // is stale by intent. Saves a round-trip too.
                    val progressDef = async {
                        when {
                            resumeSec > 0 -> resumeSec        // Zap channel-surf handoff
                            startFromZero -> 0
                            else -> runCatching { api.getProgress(itemId).positionSec }.getOrDefault(0)
                        }
                    }
                    val segDef = async { runCatching { api.itemSegments(itemId).segments }.getOrDefault(emptyList()) }
                    val subDef = async { runCatching { api.itemSubtitles(itemId).subtitles }.getOrDefault(emptyList()) }
                    val infoDef = async {
                        runCatching { api.playInfo(itemId, caps = capsParam.ifEmpty { null }) }.getOrNull()
                    }
                    PrepareBundle(
                        item = itemDef.await(),
                        resume = progressDef.await(),
                        segments = segDef.await(),
                        rawSubs = subDef.await(),
                        info = infoDef.await(),
                    )
                }
                val title = item?.title.orEmpty()
                val parentSeriesId = item?.parentId
                val resolvedQuality = quality ?: info?.defaultQuality ?: "high"
                // Binge entry: pre-skip the intro/recap chain ONLY when it
                // begins right at the head (small tolerance for analyzer
                // drift — segmenters often stamp the start a few hundred ms
                // in). Shows with a cold open before the title sequence have
                // intro.startMs in the 20-60 s range; pre-skipping those
                // would also skip the cold open, robbing the viewer of actual
                // show content. In that case we leave the normal countdown
                // pills to handle the segments when the playhead reaches
                // them.
                //
                // Walks forward through adjacent intro/recap segments so a
                // "Previously on…" recap followed by the title intro skips
                // past BOTH in a single jump — without this the user lands
                // mid-show on the recap, plays for a frame, then sees a
                // second SkipIntro countdown. Adjacent = gap ≤ 2 s.
                //
                // Only applies on the very first prepare of a binge-chained
                // episode (quality == null) — switchQuality reloads shouldn't
                // re-skip because the user already saw the pill and decided.
                val preSkip: Segment? = if (fromBinge && quality == null) {
                    val sortedByStart = segments.sortedBy { it.startMs }
                    val head = sortedByStart.firstOrNull { seg ->
                        (seg.kind.equals("intro", ignoreCase = true) ||
                            seg.kind.equals("recap", ignoreCase = true)) &&
                            seg.startMs < 2_000L &&
                            (seg.endMs / 1000L).toInt() > resume
                    }
                    head?.let { first ->
                        var furthest = first
                        for (next in sortedByStart.dropWhile { it !== first }.drop(1)) {
                            val isSkippable = next.kind.equals("intro", ignoreCase = true) ||
                                next.kind.equals("recap", ignoreCase = true)
                            if (!isSkippable) break
                            if (next.startMs > furthest.endMs + 2_000L) break
                            if (next.endMs > furthest.endMs) furthest = next
                        }
                        furthest
                    }
                } else null
                val effectiveResume = preSkip?.let { (it.endMs / 1000L).toInt() } ?: resume
                // Append the stream token to each sidecar URL — chino-stream's
                // proxySidecarSubtitle handler authenticates via the same
                // `?stream=<token>` (or `?token=`) the master.m3u8 uses, so
                // ExoPlayer can fetch the VTT without OIDC headers.
                val base = baseUrl
                val tokenizedSubs = rawSubs.map { s ->
                    val absUrl = if (s.url.startsWith("http")) s.url else base + s.url.removePrefix("/api")
                    val sep = if ('?' in absUrl) '&' else '?'
                    // The sidecar /play/subs/{id}.vtt route is under the SAME
                    // StreamMiddleware as the segments — it authorises on the
                    // `?stream=` query param (NOT `?token=`). Sending `token=`
                    // here 401'd, so the side-sub track failed to load and the
                    // menu showed 0/0 even though the merge succeeded.
                    s.copy(url = "$absUrl${sep}stream=$token")
                }
                // Trickplay scrub-preview cues — only packaged items have the
                // sprite tree (the analyzer emits it alongside the CMAF). For
                // anything else the fetch would 404, so skip it and let the
                // scrubber degrade to segment-stripes-only. Body read on IO;
                // a 404 / parse failure just yields an empty cue list.
                val trickplayCues = if (info?.mode.equals("packaged", ignoreCase = true)) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            api.trickplayVtt(itemId, stream = token).use { body ->
                                parseTrickplayVtt(body.string())
                            }
                        }.getOrDefault(emptyList())
                    }
                } else emptyList()
                val trickplayBase =
                    if (trickplayCues.isNotEmpty()) "$base/v1/items/$itemId/play/trickplay" else ""
                val prev = _state.value as? PlayerUiState.Ready
                PlayerUiState.Ready(
                    masterUrl = buildString {
                        append("$base/v1/items/$itemId/play/master.m3u8?stream=$token")
                        if (capsParam.isNotEmpty()) append("&caps=$capsParam")
                        append("&q=$resolvedQuality")
                    },
                    title = title,
                    // Always auto-resume — matches chino-web b6a9437 which
                    // dropped the Continue-watching? dialog. The seek happens
                    // inside ExoPlayback.factory; if resume is 0 we start at
                    // the head, otherwise mid-stream.
                    resumePositionSec = effectiveResume,
                    preSkippedIntro = preSkip,
                    segments = segments,
                    sidecarSubtitles = tokenizedSubs,
                    parentSeriesId = parentSeriesId,
                    playInfo = info,
                    currentQuality = resolvedQuality,
                    trickplayCues = trickplayCues,
                    trickplayBaseUrl = trickplayBase,
                    streamToken = token,
                    reloadKey = (prev?.reloadKey ?: 0) + if (prev != null) 1 else 0,
                )
            } catch (e: Exception) {
                PlayerUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    private data class PrepareBundle(
        val item: cloud.nalet.chino.tv.data.model.Item?,
        val resume: Int,
        val segments: List<Segment>,
        val rawSubs: List<SidecarSubtitle>,
        val info: PlayInfo?,
    )

    /**
     * Reload the player at a different quality rung. PlayerScreen stashes the
     * current position so the reload resumes there instead of jumping to head.
     */
    fun switchQuality(rung: String) {
        val cur = _state.value as? PlayerUiState.Ready ?: return
        if (cur.currentQuality == rung) return
        reportTelemetry("quality_switch", mapOf("from" to cur.currentQuality, "to" to rung))
        prepare(quality = rung)
    }

    /**
     * Resolves the next episode id. For an episode, asks chino-api for the
     * next-episode after the parent series; for a series-level item, asks
     * for the next-episode directly. Returns null for movies (no series to
     * chain from) and when chino-api has nothing queued.
     *
     * Defensive on null parentSeriesId: if the current item is an episode
     * whose Episode record didn't carry parent_id, we ALSO try its series
     * episodes list (resolved through getItem's parent_id field on episodes
     * we've already loaded). Worst case returns null and auto-play-next
     * is a no-op.
     */
    suspend fun resolveNextEpisode(): String? {
        val ready = _state.value as? PlayerUiState.Ready ?: return null
        val lookupId = ready.parentSeriesId ?: itemId
        val viaApi = runCatching { api.nextEpisode(lookupId).id }.getOrNull()
        if (!viaApi.isNullOrBlank()) return viaApi
        // Fallback: when /next-episode returns nothing (e.g. season ended),
        // fetch episodes for the parent series and pick the next-index
        // candidate manually. Only meaningful when we know the parent — for
        // movies / standalone items there's no chain.
        val parent = ready.parentSeriesId ?: return null
        return runCatching {
            val seasons = api.seriesEpisodes(parent).seasons
            val flat = seasons.flatMap { it.episodes }
            val idx = flat.indexOfFirst { it.id == itemId }
            flat.getOrNull(idx + 1)?.id
        }.getOrNull()
    }

    /** Mirror of [resolveNextEpisode] for the previous-episode chevron. chino-api
     *  has no /previous-episode endpoint, so this is purely client-side via the
     *  series episode list. Returns null on the first episode and on standalone
     *  movies. */
    suspend fun resolvePrevEpisode(): String? {
        val ready = _state.value as? PlayerUiState.Ready ?: return null
        val parent = ready.parentSeriesId ?: return null
        return runCatching {
            val seasons = api.seriesEpisodes(parent).seasons
            val flat = seasons.flatMap { it.episodes }
            val idx = flat.indexOfFirst { it.id == itemId }
            if (idx > 0) flat[idx - 1].id else null
        }.getOrNull()
    }

    /** What to auto-play when the current item finishes. For a series item it's
     *  the next episode; for a movie / standalone item it's the top "more like
     *  this" recommendation (the player has no episode chain, so we continue
     *  into a related title — web parity). Null when nothing is queued. */
    suspend fun resolveNextUp(): String? {
        val ready = _state.value as? PlayerUiState.Ready ?: return null
        if (ready.parentSeriesId != null) return resolveNextEpisode()
        // Movie / standalone — chain into the first recommendation (skip self).
        return runCatching {
            api.similar(itemId, limit = 8).items.firstOrNull { it.id != itemId }?.id
        }.getOrNull()
    }

    /** Binge pre-warm — fire one GET against the next episode's master.m3u8
     *  while the current episode is still rolling. chino-stream's Master()
     *  handler kicks `warmTranscode` in a goroutine on every hit, so by the
     *  time the auto-play countdown elapses (or the user clicks Next), the
     *  first segment + init are already on disk. Eliminates the 0:00 freeze
     *  the user reported between episodes. Mirrors chino-web's
     *  PlayerPage.tsx:1146 — runs at most once per nextId per session,
     *  caller gates by tracking which ids have been warmed.
     *
     *  Uses streamHttpClient (no bearer; HMAC `?stream=` authorises the
     *  master path) and appScope so it survives backing out of the player
     *  before the response lands. We discard the body. */
    fun prewarmMaster(nextId: String) {
        appScope.launch {
            runCatching {
                val token = streamTokens.valid()
                val capsParam = CodecCaps.queryParam
                val base = baseUrl
                val url = buildString {
                    append("$base/v1/items/$nextId/play/master.m3u8?stream=$token")
                    if (capsParam.isNotEmpty()) append("&caps=$capsParam")
                    append("&q=high")
                }
                val req = okhttp3.Request.Builder().url(url).build()
                streamHttpClient.newCall(req).execute().use { /* discard body */ }
            }
        }
    }

    /**
     * Promotes a runtime ExoPlayer / HLS error to the Ready→Error transition
     * so PlayerScreen can render a recovery message instead of leaving the
     * surface black + frozen. Called from the Player.Listener.onPlayerError
     * path in PlayerScreen; idempotent on already-Error state.
     */
    fun reportPlaybackError(
        code: String,
        message: String?,
        type: String? = null,
        httpStatus: Int? = null,
        failedUrl: String? = null,
    ) {
        val extra = buildMap<String, String> {
            put("code", code)
            put("msg", message ?: "")
            type?.takeIf { it.isNotBlank() }?.let { put("type", it) }
            httpStatus?.let { put("http_status", it.toString()) }
            failedUrl?.takeIf { it.isNotBlank() }?.let { put("url", it) }
        }
        telemetry.event("playback_error", extra = extra)
        if (_state.value !is PlayerUiState.Error) {
            // A 404 on the manifest means chino-stream has no playable asset for
            // this item — show a plain-language message instead of the engine
            // error code. Everything else keeps the diagnostic code.
            val manifestMissing = httpStatus == 404 && (failedUrl?.contains(".m3u8") == true)
            val userMessage = if (manifestMissing) {
                "This title isn't available to stream yet."
            } else {
                "Playback failed: $code${message?.let { " — $it" } ?: ""}"
            }
            _state.value = PlayerUiState.Error(userMessage)
        }
    }

    /**
     * Auto bug report for a Media3 player error — separate from
     * [reportPlaybackError] (telemetry + error UI) on purpose: this fires on
     * EVERY onPlayerError, including ones the quality-ladder fallback
     * recovers from, so a flaky rung still lands on the backlog even when
     * the user never saw an error screen. BugReporter session-dedups by
     * fingerprint and hard-caps auto reports per process, so a retry storm
     * can't flood the tracker. Fire-and-forget + silent — never touches
     * playback state. No screenshot on TV: PixelCopy of the video
     * SurfaceView plane comes out black and the DPAD chrome adds nothing
     * the description doesn't already say.
     */
    fun reportPlayerErrorBug(code: String, message: String?, stack: String, positionSec: Long) {
        val description = buildString {
            append(code)
            message?.let { append(" — ").append(it) }
            append('\n')
            append(stack)
        }.take(8 * 1024)
        // Stash for the terminal error UI's manual "Report this problem" row.
        // This fires on EVERY onPlayerError, so by the time recovery gives up
        // and the Error screen renders, it holds the last fatal error.
        lastTechnicalError = description
        bugReporter.report(
            kind = "player",
            description = description,
            // codeName+message only (no frames): the same decoder failure
            // should dedupe regardless of which call path tripped it.
            fingerprint = bugFingerprint(name = code, message = message),
            context = mapOf(
                "itemId" to itemId,
                "positionSec" to positionSec.toString(),
                "screen" to "player",
            ),
        )
    }

    /**
     * Manual report from the terminal error UI's "Report this problem" row —
     * the user-visible counterpart of [reportPlayerErrorBug]'s silent auto
     * report (web parity: PlayerPage's "Report a bug" button on the fatal
     * overlay). Files directly through BugReporter.reportManual with the same
     * technical string the auto report shipped (falling back to the on-screen
     * message for prepare-time failures that never hit onPlayerError) and
     * maps the outcome onto [errorReport] exactly like the Settings picker,
     * so the error screen can show the inline "Filed bug #N" / failure line.
     * Recovery logic is untouched — this only runs once the state is already
     * terminally Error.
     */
    fun fileTerminalErrorBug() {
        if (_errorReport.value is BugReportState.Sending) return
        val description = lastTechnicalError
            ?: (state.value as? PlayerUiState.Error)?.message
            ?: return
        telemetry.event("bug_report_manual", extra = mapOf("screen" to "player"))
        _errorReport.value = BugReportState.Sending
        viewModelScope.launch {
            _errorReport.value = try {
                val resp = bugReporter.reportManual(
                    title = "Playback failed",
                    description = description,
                    context = mapOf(
                        "screen" to "player",
                        "itemId" to itemId,
                    ),
                )
                BugReportState.Filed(id = resp.id, duplicate = resp.duplicate)
            } catch (e: Exception) {
                BugReportState.Failed(
                    when ((e as? retrofit2.HttpException)?.code()) {
                        429 -> "Too many reports right now — try again in a few minutes."
                        503 -> "Bug reporting isn't set up on this server."
                        else -> "Couldn't send the report. Check your connection and try again."
                    },
                )
            }
        }
    }

    /** Persists the current position. Called periodically by PlayerScreen
     *  AND once more in onDispose when the user leaves the player. Uses
     *  appScope so the terminal save survives ViewModel.onCleared() — the
     *  in-flight POST would otherwise be cancelled before OkHttp dispatched
     *  it, leaving the resume position stale on the next library refresh. */
    fun reportProgress(positionSec: Int, durationSec: Int) {
        if (positionSec <= 0) return
        appScope.launch {
            runCatching {
                api.postProgress(itemId, ProgressBody(positionSec = positionSec, durationSec = durationSec))
            }
        }
    }

    /** Marks this item watched on chino-api so the green "watched" badge
     *  appears on its poster and continue-watching substitutes the next
     *  episode. Fire-and-forget; idempotent — chino-api just bumps the
     *  watched_at timestamp on a second call. Called from PlayerScreen
     *  when the user enters the credits segment OR crosses 95 % of the
     *  duration, whichever fires first. A `markedWatched` flag in
     *  PlayerScreen guards against the two paths firing twice in the
     *  same session. */
    fun reportWatched() {
        appScope.launch {
            runCatching { api.postWatched(itemId) }
        }
    }

    /** Fire-and-forget telemetry — never blocks playback. Auto-stamps device /
     *  app / network context via the shared Telemetry singleton. */
    fun reportTelemetry(kind: String, payload: Map<String, String> = emptyMap()) {
        telemetry.event(kind, itemId = itemId, extra = payload)
    }

    /**
     * Drop one rung on the quality ladder (high→medium→low) and reload
     * playback. Called from PlayerScreen's onPlayerError before surfacing
     * the error UI — covers the "this rendition has a missing segment but
     * the lower one is fine" case (transcode pipeline still warming the
     * high ladder while medium has been cached for hours). Returns true if
     * a fallback was kicked off; false when we're already on low and
     * there's nowhere lower to drop to.
     */
    fun attemptQualityFallback(): Boolean {
        val cur = _state.value as? PlayerUiState.Ready ?: return false
        val next = when (cur.currentQuality.lowercase()) {
            "high" -> "medium"
            "medium" -> "low"
            else -> return false
        }
        telemetry.event("quality_fallback", extra = mapOf("from" to cur.currentQuality, "to" to next))
        prepare(quality = next)
        return true
    }

    companion object {
        fun factory(
            container: AppContainer,
            itemId: String,
            startFromZero: Boolean = false,
            fromBinge: Boolean = false,
            resumeSec: Int = 0,
        ) = viewModelFactory {
            initializer {
                PlayerViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    settingsStore = container.settings,
                    telemetry = container.telemetry,
                    bugReporter = container.bugReporter,
                    itemId = itemId,
                    startFromZero = startFromZero,
                    fromBinge = fromBinge,
                    appScope = container.appScope,
                    streamHttpClient = container.streamHttpClient,
                    baseUrl = container.baseUrl,
                    resumeSec = resumeSec,
                )
            }
        }
    }
}
