package cloud.nalet.chino.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.AppSettings
import cloud.nalet.chino.tv.data.PlayCaps
import cloud.nalet.chino.tv.data.SettingsStore
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.PlayInfo
import cloud.nalet.chino.tv.data.api.ProgressBody
import cloud.nalet.chino.tv.data.api.QualityRung
import cloud.nalet.chino.tv.data.api.Segment
import cloud.nalet.chino.tv.data.api.SidecarSubtitle
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Trailer
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
        // know where to ask for the next episode. Null for movies and extras.
        val parentSeriesId: String?,
        /** chino-stream's transcode decision + ladder. Null on pre-probe
         *  failure, and for an extra, which has none. */
        val playInfo: PlayInfo?,
        /** The q the master is asked with: "auto" or a rung's name ("v1") for
         *  a packaged title, "high" / "medium" / "low" on the fly. "auto" for
         *  an extra, whose master is asked with no q: its whole ladder. */
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
        /** The TV's audio output takes 5.1 as this prepare found it
         *  ([PlayCaps.surround]): a language's 5.1 track starts where the
         *  master has one. Else its stereo one does (SurroundAudio.kt). */
        val surroundAudio: Boolean = false,
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

    /** An extra that is not there to play: its title or the extra is gone,
     *  the viewer's rating cap hides the title, or the master answered 404.
     *  [link] is the title's trailer link, when it has one. */
    data class NotAvailable(val link: Trailer?) : PlayerUiState
}

/**
 * Owns the lifecycle around an HLS play session:
 *  1. Mint a `?stream=` token (cached, shared with library posters).
 *  2. Fetch the previous resume position via /v1/items/{id}/progress and seek
 *     there silently on start (auto-resume always — matches chino-web b6a9437,
 *     which dropped the "Continue watching?" dialog). "Play from start" from
 *     the Detail screen sets [PlayMode.Title.fromStart] to skip the lookup
 *     entirely.
 *  3. While ExoPlayer is rolling, the PlayerScreen calls [reportProgress] every
 *     10 s and on dispose so chino-api's progress table stays current.
 *  4. Optional [reportTelemetry] for play/pause/seek/error events — chino-api
 *     forwards each batch to the cluster log aggregator.
 * What it plays is its [mode], and each of those requests is made only when
 * the mode asks for it ([PlayMode.requests]). An extra asks for none: it
 * plays the master its title's detail names (loadExtra) from the start, with
 * one trailer_play ([reportStarted]) for all its telemetry.
 */
class PlayerViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val settingsStore: SettingsStore,
    private val telemetry: Telemetry,
    /** Bug-report funnel for fatal player errors — fire-and-forget, session-
     *  deduped by fingerprint, all failures swallowed (see [reportPlayerErrorBug]). */
    private val bugReporter: BugReporter,
    /** What plays. A title's [PlayMode.Title.fromStart] (Detail's "Play from
     *  start", the route's `fromStart=true`) has prepare() ignore any saved
     *  resume position and start at 0:00; its [PlayMode.Title.resumeSec]
     *  (Zap's ?resume= channel-surf handoff), when >0, resumes at that second
     *  without the server progress lookup. Its [PlayMode.Title.fromBinge] has
     *  prepare() pre-skip the intro segment (if one is detected near 0:00) so
     *  a binge auto-play-next chain doesn't dump the user back at 00:00 just
     *  to fire the SkipIntro countdown again; PlayerScreen shows a brief undo
     *  pill so the user can BACK to revisit. Only the auto-play-next nav path
     *  sets it; manual prev/next/detail entry leaves it false so the intro
     *  plays normally. */
    val mode: PlayMode,
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
    /** The caps a play sends and whether the audio output takes 5.1, as it
     *  is routed when asked ([AppContainer.playCaps]): read at every
     *  prepare, off the main thread. */
    private val playCaps: () -> PlayCaps,
) : ViewModel() {

    private val itemId: String = mode.itemId

    /** What the mode asks the server for: nothing is asked that it does not. */
    val requests: PlayRequests = mode.requests

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

    /** An extra's title's trailer link, offered should its master answer 404. */
    private var link: Trailer? = null

    /** An extra's trailer_play has gone out. */
    private var started = false

    /** The viewer turned the subtitles Off in the menu: no forced subtitle
     *  comes on by itself for the rest of this playback, its reloads
     *  included (ForcedSubtitles.kt). */
    var subtitlesOff: Boolean = false
        private set

    fun turnSubtitlesOff() {
        subtitlesOff = true
    }

    init { prepare(quality = null) }

    /**
     * Loads everything we need for playback in parallel. `quality` overrides
     * the server's default rung; pass null on first prepare and the q to
     * reload with on a quality switch or a fallback. [atSec] is where a
     * reload goes on — the playhead of the player it replaces. Without it the
     * start is looked up ([startSec]): the Zap handoff, Play from start, else
     * the saved progress (a reload must not ask for that: the save it would
     * read is still in flight, and Play from start or a Zap scene would apply
     * again).
     */
    fun prepare(quality: String?, atSec: Int? = null) {
        _state.value = PlayerUiState.Preparing
        viewModelScope.launch {
            _state.value = try {
                val token = withContext(Dispatchers.IO) { streamTokens.valid() }
                // What the TV decodes and where its audio goes now: eac3 where
                // it plays E-AC-3, decoded or passed through (CodecCaps).
                val caps = withContext(Dispatchers.IO) { playCaps() }
                val capsParam = caps.query
                // An extra plays the master its title's detail names: the
                // detail is read first, the one request an extra makes
                // (loadExtra). One the detail does not name says so, and
                // nothing more is asked.
                val extra = (mode as? PlayMode.Extra)?.let { m ->
                    when (val loaded = loadExtra(api, baseUrl, token, capsParam, m.itemId, m.extraId)) {
                        is ExtraLoad.Found -> loaded
                        is ExtraLoad.NotAvailable -> {
                            link = loaded.link
                            _state.value = PlayerUiState.NotAvailable(loaded.link)
                            return@launch
                        }
                    }
                }
                link = extra?.link
                // Fan-out all six prepare-time API calls in parallel — they're
                // independent and slow ones (segments, subtitles) used to gate
                // playback startup on the longest single call (~11s observed
                // on the BRAVIA). Now max(individual) ≈ 2-3s. Each only as the
                // mode asks for it (requests): an extra asks for none of them.
                val (item, resume, segments, rawSubs, info) = coroutineScope {
                    // An extra's detail is read above.
                    val itemDef = async { if (extra != null) null else runCatching { api.getItem(itemId) }.getOrNull() }
                    // When the user clicked "Play from start", skip the
                    // resume-position lookup entirely — the saved progress
                    // is stale by intent. Saves a round-trip too.
                    val progressDef = async {
                        startSec(mode, atSec)
                            ?: runCatching { api.getProgress(itemId).positionSec }.getOrDefault(0)
                    }
                    val segDef = async {
                        if (!requests.segments) emptyList()
                        else runCatching { api.itemSegments(itemId).segments }.getOrDefault(emptyList())
                    }
                    val subDef = async {
                        if (!requests.subtitles) emptyList()
                        else runCatching { api.itemSubtitles(itemId).subtitles }.getOrDefault(emptyList())
                    }
                    val infoDef = async {
                        if (!requests.playInfo) null
                        else runCatching {
                            api.playInfo(itemId, caps = capsParam.ifEmpty { null }, quality = quality)
                        }.getOrNull()
                    }
                    PrepareBundle(
                        item = itemDef.await(),
                        resume = progressDef.await(),
                        segments = segDef.await(),
                        rawSubs = subDef.await(),
                        info = infoDef.await(),
                    )
                }
                val parentSeriesId = item?.parentId
                // Episode title compose (web parity): "{Series} — S01E02 · {Episode}".
                // Only for episodes (parentId present + a season/episode number).
                // The series title isn't on the episode payload, so fetch the
                // parent series item for it; while unknown, fall back to
                // "S01E02 · {Episode}". Movies / non-episodes: the bare title.
                // An extra: its title's and its own, "Sintel · Trailer".
                val title = extra?.title ?: composeTitle(item, parentSeriesId)
                // The server's default on the first prepare; a reload's own q,
                // but high for a packaged q the title is no longer served for.
                // An extra's master is its whole ladder: its quality menu pins a
                // variant in the player (variantMenu), with no reload.
                val resolvedQuality = if (extra != null) AUTO else playQuality(quality, info)
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
                val fromBinge = (mode as? PlayMode.Title)?.fromBinge == true
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
                val trickplayCues = if (requests.trickplay && info?.mode.equals("packaged", ignoreCase = true)) {
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
                    masterUrl = extra?.masterUrl ?: buildString {
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
                    surroundAudio = caps.surround,
                )
            } catch (e: Exception) {
                PlayerUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /**
     * Builds the player title. For an episode (has a parentId AND a
     * season/episode number) compose "{Series} — S01E02 · {Episode}" with
     * 2-digit zero-padded season/episode, matching chino-web. The series
     * title isn't carried on the episode payload, so resolve it by fetching
     * the parent series item; while that's unknown (fetch failed / blank) fall
     * back to "S01E02 · {Episode}". Movies and non-episode items keep their
     * plain title.
     */
    private suspend fun composeTitle(
        item: cloud.nalet.chino.tv.data.model.Item?,
        parentSeriesId: String?,
    ): String {
        val epTitle = item?.title.orEmpty()
        val season = item?.seasonNumber
        val episode = item?.episodeNumber
        if (parentSeriesId == null || season == null || episode == null) return epTitle
        val code = "S%02dE%02d".format(season, episode)
        val prefix = if (epTitle.isNotBlank()) "$code · $epTitle" else code
        val seriesTitle = runCatching { api.getItem(parentSeriesId).title }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return prefix
        return "$seriesTitle — $prefix"
    }

    private data class PrepareBundle(
        val item: cloud.nalet.chino.tv.data.model.Item?,
        val resume: Int,
        val segments: List<Segment>,
        val rawSubs: List<SidecarSubtitle>,
        val info: PlayInfo?,
    )

    /**
     * Reload the player at a different quality rung, at [atSec] — the
     * playhead PlayerScreen reads off the player it is about to replace.
     */
    fun switchQuality(rung: String, atSec: Int) {
        val cur = _state.value as? PlayerUiState.Ready ?: return
        if (cur.currentQuality == rung) return
        reportTelemetry("quality_switch", mapOf("from" to cur.currentQuality, "to" to rung))
        prepare(quality = rung, atSec = atSec)
    }

    /**
     * Resolves the next episode id: chino-api's next-episode of the parent
     * series after THIS episode (`?after=`, as chino-web's player asks — not
     * the episode the user last touched, which can be another one). Returns
     * null for movies (no series to chain from) and at the end of a series.
     *
     * Defensive on null parentSeriesId: if the current item is an episode
     * whose Episode record didn't carry parent_id, we ALSO try its series
     * episodes list (resolved through getItem's parent_id field on episodes
     * we've already loaded). Worst case returns null and auto-play-next
     * is a no-op. Null without asking where the mode has no up next.
     */
    suspend fun resolveNextEpisode(): String? {
        if (!requests.nextUp) return null
        val ready = _state.value as? PlayerUiState.Ready ?: return null
        val lookupId = ready.parentSeriesId ?: itemId
        val viaApi = runCatching { api.nextEpisode(lookupId, after = itemId).next?.id }.getOrNull()
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
     *  movies, and without asking where the mode has no up next. */
    suspend fun resolvePrevEpisode(): String? {
        if (!requests.nextUp) return null
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
     *  into a related title — web parity). Null when nothing is queued, and
     *  without asking where the mode has no up next. */
    suspend fun resolveNextUp(): String? {
        if (!requests.nextUp) return null
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
     *  before the response lands. We discard the body. Nothing where the
     *  mode does not prewarm. */
    fun prewarmMaster(nextId: String) {
        if (!requests.prewarm) return
        appScope.launch {
            runCatching {
                val token = streamTokens.valid()
                val capsParam = playCaps().query
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
        if (requests.telemetry) telemetry.event("playback_error", extra = extra)
        if (_state.value !is PlayerUiState.Error && _state.value !is PlayerUiState.NotAvailable) {
            // A 404 on the manifest means chino-stream has no playable asset for
            // this item — show a plain-language message instead of the engine
            // error code. Everything else keeps the diagnostic code.
            val manifestMissing = httpStatus == 404 && (failedUrl?.contains(".m3u8") == true)
            // An extra's: gone, or under the viewer's rating cap — not
            // available, as when its detail no longer names it.
            if (manifestMissing && mode is PlayMode.Extra) {
                _state.value = PlayerUiState.NotAvailable(link)
                return
            }
            val userMessage = if (manifestMissing) {
                "This title isn't available to stream yet."
            } else {
                "Playback failed: $code${message?.let { " — $it" } ?: ""}"
            }
            _state.value = PlayerUiState.Error(userMessage)
        }
    }

    /** What a bug report says it is about: the title, and the extra played. */
    private val reportContext: Map<String, String>
        get() = buildMap {
            put("itemId", itemId)
            (mode as? PlayMode.Extra)?.let { put("extraId", it.extraId) }
        }

    /**
     * Auto bug report for a Media3 player error — separate from
     * [reportPlaybackError] (telemetry + error UI) on purpose: this fires on
     * EVERY onPlayerError, including ones [recoverFromError] gets past, so a
     * flaky rung still lands on the backlog even when
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
            context = reportContext + mapOf(
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
        if (requests.telemetry) telemetry.event("bug_report_manual", extra = mapOf("screen" to "player"))
        _errorReport.value = BugReportState.Sending
        viewModelScope.launch {
            _errorReport.value = try {
                val resp = bugReporter.reportManual(
                    title = "Playback failed",
                    description = description,
                    context = mapOf("screen" to "player") + reportContext,
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
     *  it, leaving the resume position stale on the next library refresh.
     *  Nothing where the mode keeps no progress. */
    fun reportProgress(positionSec: Int, durationSec: Int) {
        if (!requests.progress || positionSec <= 0) return
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
     *  same session. Nothing where the mode marks nothing watched. */
    fun reportWatched() {
        if (!requests.watched) return
        appScope.launch {
            runCatching { api.postWatched(itemId) }
        }
    }

    /** Fire-and-forget telemetry — never blocks playback. Auto-stamps device /
     *  app / network context via the shared Telemetry singleton. Nothing
     *  where the mode sends none of the player's events. */
    fun reportTelemetry(kind: String, payload: Map<String, String> = emptyMap()) {
        if (!requests.telemetry) return
        telemetry.event(kind, itemId = itemId, extra = payload)
    }

    /** Playback has begun. An extra's one event, once however often it plays:
     *  trailer_play with the extra's id and local, as chino-web reports a
     *  trailer this server plays. A title's play is one of the player's own
     *  events. */
    fun reportStarted() {
        val extra = mode as? PlayMode.Extra ?: return
        if (started) return
        started = true
        telemetry.event("trailer_play", itemId = extra.itemId, extra = mapOf("extra_id" to extra.extraId, "local" to "true"))
    }

    /** The title's trailer link opened instead of an extra not there, as
     *  Detail reports its link. */
    fun reportLinkLaunch() = telemetry.event("trailer_launch", itemId = itemId)

    /** What has been tried about trouble at the current spot of the title —
     *  kept across the reloads it starts (see [PlaybackRecovery]). */
    private val recovery = PlaybackRecovery()

    /**
     * A player error at [positionMs]: what to do about it, by how the title
     * is served (PlaybackRecovery; an extra is packaged, [streamMode]). An
     * on-the-fly title steps down its ladder (high→medium→low: the transcode
     * still warming the high rung while medium has been cached for hours); a
     * packaged one is tried again in place, then rebuilt at its quality —
     * never put on the on-the-fly ladder. A step down or a reload is started
     * here and goes on at the position; retrying in place and giving up are
     * the screen's to do.
     */
    fun recoverFromError(positionMs: Long): Recovery {
        val cur = _state.value as? PlayerUiState.Ready ?: return Recovery.GiveUp
        val r = recovery.onError(streamMode(mode, cur.playInfo), cur.currentQuality, positionMs)
        carryOut(r, cur, positionMs, cause = "error")
        return r
    }

    /**
     * A mid-stream stall at [positionMs] — buffering for a while without an
     * error (the BRAVIA's "stutters every few minutes but never errors").
     * An on-the-fly title steps down as on an error; a packaged one that is
     * fetching with nothing buffered is left to it, else it is nudged in
     * place and rebuilt at its quality after that.
     */
    fun recoverFromStall(positionMs: Long, loading: Boolean, bufferedAheadMs: Long): Recovery {
        val cur = _state.value as? PlayerUiState.Ready ?: return Recovery.Wait
        val r = recovery.onStall(streamMode(mode, cur.playInfo), cur.currentQuality, positionMs, loading, bufferedAheadMs)
        carryOut(r, cur, positionMs, cause = "stall")
        return r
    }

    private fun carryOut(r: Recovery, cur: PlayerUiState.Ready, positionMs: Long, cause: String) {
        val atSec = (positionMs / 1000L).toInt()
        when (r) {
            is Recovery.StepDown -> {
                if (requests.telemetry) {
                    telemetry.event(
                        "quality_fallback",
                        extra = mapOf("from" to cur.currentQuality, "to" to r.quality, "cause" to cause),
                    )
                }
                prepare(quality = r.quality, atSec = atSec)
            }
            is Recovery.Reload -> {
                reportTelemetry(
                    "playback_reload",
                    mapOf("quality" to r.quality, "cause" to cause, "position_sec" to atSec.toString()),
                )
                prepare(quality = r.quality, atSec = atSec)
            }
            Recovery.RetryInPlace ->
                reportTelemetry("playback_retry", mapOf("cause" to cause, "position_sec" to atSec.toString()))
            Recovery.Wait, Recovery.GiveUp -> Unit
        }
    }

    companion object {
        fun factory(container: AppContainer, mode: PlayMode) = viewModelFactory {
            initializer {
                PlayerViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    settingsStore = container.settings,
                    telemetry = container.telemetry,
                    bugReporter = container.bugReporter,
                    mode = mode,
                    appScope = container.appScope,
                    streamHttpClient = container.streamHttpClient,
                    baseUrl = container.baseUrl,
                    playCaps = container::playCaps,
                )
            }
        }
    }
}
