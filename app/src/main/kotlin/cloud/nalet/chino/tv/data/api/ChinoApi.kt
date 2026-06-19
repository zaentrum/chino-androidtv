package cloud.nalet.chino.tv.data.api

import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Mirrors chino-api/internal/http/router.go. Keep methods in lock-step with that file —
 * if the API gets codegen later, drop this interface for the generated one.
 */
interface ChinoApi {
    @GET("v1/items")
    suspend fun listItems(
        @Query("page_token") pageToken: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("q") q: String? = null,
        @Query("type") type: String? = null,
        @Query("genre") genre: String? = null,
        @Query("year_min") yearMin: Int? = null,
        @Query("year_max") yearMax: Int? = null,
        @Query("rating_min") ratingMin: Double? = null,
        @Query("sort") sort: String? = null,
        // When true, chino-api hides items the user finished and backfills from
        // later pages so a Home rail still returns up to `limit` fresh titles.
        // Set only by the Home surface's rails + hero pool — Browse and Search
        // leave it false so watched titles stay findable for a rewatch.
        @Query("unwatched") unwatched: Boolean = false,
    ): ItemsPage

    @GET("v1/genres")
    suspend fun listGenres(): GenresResponse

    @GET("v1/items/{id}")
    suspend fun getItem(@Path("id") id: String): Item

    /** "More like this" — katalog-scored recommendations for the detail page. */
    @GET("v1/items/{id}/similar")
    suspend fun similar(@Path("id") id: String, @Query("limit") limit: Int? = 12): ItemsPage

    /**
     * People search — accent/case-insensitive name match, server-ranked.
     * Powers the Search "Cast & crew" people row. `credits` is the number of
     * titles the person is credited on. Render the returned order as-is (no
     * client re-rank), same as the title search.
     */
    @GET("v1/people")
    suspend fun searchPeople(
        @Query("q") q: String,
        @Query("limit") limit: Int? = null,
    ): PeopleResponse

    /**
     * A person's filmography — header (name + credit count) plus the standard
     * Item objects they're credited on (each carries poster_url/backdrop_url/
     * watched_at, so the existing poster card renders them unchanged). 404 when
     * the id is unknown. Reached from the Search people row + tappable cast chips.
     */
    @GET("v1/people/{id}")
    suspend fun getPerson(
        @Path("id") id: String,
        @Query("limit") limit: Int? = null,
    ): PersonDetail

    @GET("v1/me")
    suspend fun me(): Me

    /**
     * Mints a 6-hour HMAC-signed token used as `?stream=<token>` on playback URLs.
     * The OIDC bearer rotates on silent renew; the stream token doesn't, so the
     * `<video src>` URL stays stable across renews and the HLS pipeline isn't
     * restarted mid-playback. See chino-api/internal/http/router.go postStreamToken.
     */
    @POST("v1/me/stream-token")
    suspend fun mintStreamToken(): StreamTokenResponse

    /** Returns `{ position_sec }`, 0 if never watched. */
    @GET("v1/items/{id}/progress")
    suspend fun getProgress(@Path("id") id: String): ProgressResponse

    /** Continue-watching shelf: ordered list of in-progress items for this user. */
    @GET("v1/me/continue-watching")
    suspend fun continueWatching(): ContinueWatchingResponse

    /** Upserts resume position. Called every ~10s + once on player dispose. */
    @POST("v1/items/{id}/progress")
    suspend fun postProgress(@Path("id") id: String, @Body body: ProgressBody)

    /** Marks the item as fully watched. Drives the green "watched" badge on
     *  posters + lets continue-watching substitute the next episode. Player
     *  calls this when the user enters credits OR crosses 95 % of duration,
     *  whichever fires first. Idempotent — re-firing just bumps watched_at. */
    @POST("v1/me/items/{id}/watched")
    suspend fun postWatched(@Path("id") id: String)

    /** Un-marks the item as watched — clears the server-side watched_history
     *  row so watched_at becomes null again. Mirrors web's useWatchedToggle
     *  DELETE branch. Drives the detail watched TOGGLE (un-watch) + the
     *  per-episode un-watch. Idempotent — DELETE on an already-unwatched item
     *  is a no-op server-side. */
    @DELETE("v1/me/items/{id}/watched")
    suspend fun deleteWatched(@Path("id") id: String)

    /** Batched playback telemetry — chino-api logs each event for observability. */
    @POST("v1/play/events")
    suspend fun postTelemetry(@Body body: TelemetryBatch)

    /** Flat list of item ids in the user's DEFAULT watchlist. Back-compat:
     *  unchanged from before named lists existed; == the default list's items. */
    @GET("v1/me/watchlist")
    suspend fun getWatchlist(): UserFlagList

    /** Adds to the DEFAULT list (back-compat). */
    @PUT("v1/me/watchlist/{id}")
    suspend fun addToWatchlist(@Path("id") id: String)

    /** Removes from the DEFAULT list (back-compat). */
    @DELETE("v1/me/watchlist/{id}")
    suspend fun removeFromWatchlist(@Path("id") id: String)

    // ── Named watchlists ────────────────────────────────────────────────
    // Mirrors chino-api's /me/watchlists routes. Every user has exactly one
    // default list named "Watchlist" (isDefault=true), created lazily server-
    // side on first access; the default list is always returned FIRST.

    /** All of the user's lists — default first, then others by createdAt asc. */
    @GET("v1/me/watchlists")
    suspend fun getWatchlists(): WatchlistsResponse

    /** Creates a new named list. 409 on duplicate name / too many lists,
     *  400 on empty name (trimmed; 1..60 chars). */
    @POST("v1/me/watchlists")
    suspend fun createWatchlist(@Body body: WatchlistNameBody): Watchlist

    /** Renames a list. Renaming the default keeps isDefault=true. */
    @PATCH("v1/me/watchlists/{listId}")
    suspend fun renameWatchlist(
        @Path("listId") listId: String,
        @Body body: WatchlistNameBody,
    ): Watchlist

    /** Deletes a list (cascades its items). 409 when it's the default list. */
    @DELETE("v1/me/watchlists/{listId}")
    suspend fun deleteWatchlist(@Path("listId") listId: String)

    /** A single list with its item ids (newest-added first). */
    @GET("v1/me/watchlists/{listId}")
    suspend fun getWatchlistDetail(@Path("listId") listId: String): WatchlistDetail

    /** Idempotent add of an item to a specific list. */
    @PUT("v1/me/watchlists/{listId}/items/{itemId}")
    suspend fun addItemToWatchlist(
        @Path("listId") listId: String,
        @Path("itemId") itemId: String,
    )

    /** Idempotent remove of an item from a specific list. */
    @DELETE("v1/me/watchlists/{listId}/items/{itemId}")
    suspend fun removeItemFromWatchlist(
        @Path("listId") listId: String,
        @Path("itemId") itemId: String,
    )

    /** Per-item → which of the caller's lists contain it. Powers the add-to-
     *  list picker checkmarks and the "saved" badge on cards. `ids` is a
     *  comma-joined item-id list. */
    @GET("v1/me/watchlists/memberships")
    suspend fun watchlistMemberships(@Query("ids") ids: String): MembershipsResponse

    /** Flat list of item ids the user has liked. */
    @GET("v1/me/likes")
    suspend fun getLikes(): UserFlagList

    @PUT("v1/me/likes/{id}")
    suspend fun addLike(@Path("id") id: String)

    @DELETE("v1/me/likes/{id}")
    suspend fun removeLike(@Path("id") id: String)

    /** Returns seasons + episodes for a series. Empty seasons list for non-series. */
    @GET("v1/series/{id}/episodes")
    suspend fun seriesEpisodes(@Path("id") id: String): SeriesEpisodes

    /** Picks the episode chino-api thinks should play next after `id`. */
    @GET("v1/series/{id}/next-episode")
    suspend fun nextEpisode(@Path("id") id: String): NextEpisode

    /** Analyzer-detected segments (intro / credits / etc.) keyed by start/end ms. */
    @GET("v1/items/{id}/segments")
    suspend fun itemSegments(@Path("id") id: String): SegmentsResponse

    /** Sidecar subtitle tracks (packaged-pipeline / PVC), separate from HLS embedded text tracks. */
    @GET("v1/items/{id}/subtitles")
    suspend fun itemSubtitles(@Path("id") id: String): SubtitlesResponse

    /**
     * Pre-playback probe: chino-stream's transcode decision + container/codec
     * metadata + the ladder of qualities the server can serve. Result is
     * dispatched off `caps=` (same vocabulary the master URL uses), so we
     * pass the same beacon.
     */
    @GET("v1/items/{id}/play/info")
    suspend fun playInfo(
        @Path("id") id: String,
        @Query("caps") caps: String? = null,
    ): PlayInfo

    /** Raw WebVTT trickplay (scrub-preview thumbnail) cue file for a packaged
     *  item. chino-api proxies this through chino-stream
     *  (router.go GET /items/&#123;id&#125;/play/trickplay/thumbnails.vtt), which
     *  authenticates via the same `?stream=&lt;token&gt;` the master.m3u8 uses.
     *  Returns the body verbatim (no JSON decode) so the caller can run the
     *  WebVTT parser on it; the caller swallows a 404 (non-packaged items have
     *  no sprites) and degrades to segment-stripes-only scrubbing. The sprite
     *  JPGs the cues reference live at the sibling
     *  `…/play/trickplay/&#123;sprite&#125;?stream=&lt;token&gt;` path, loaded by Coil. */
    @Streaming
    @GET("v1/items/{id}/play/trickplay/thumbnails.vtt")
    suspend fun trickplayVtt(
        @Path("id") id: String,
        @Query("stream") stream: String,
    ): okhttp3.ResponseBody

    /** Ids of items with a finished CMAF package (instant first segment, no
     *  cold transcode). Zap filters its candidate pool to these so the muted
     *  channel-surf teaser never stalls on a 0:00 ffmpeg cold-start.
     *  chino-api proxies this to chino-stream (router.go GET /v1/play/packaged-ids). */
    @GET("v1/play/packaged-ids")
    suspend fun packagedIds(): PackagedIdsResponse

    /** Fire-and-forget warm of the next Zap card's transcode pipeline. chino-
     *  stream returns 202 immediately and warms the requested window on its
     *  warm-only ffmpeg pool. Mirrors chino-web's ZapSection / chino-mobile's
     *  ChinoApi.prewarm POST; best-effort — a 404 (older chino-stream without
     *  the endpoint) is swallowed by the caller. `caps` + `q` must match the
     *  card's actual play URL so the warm primes the right rung, and `t`
     *  (seek seconds) hints the mid-scene segment Zap will start on rather than
     *  segment 0. Body is empty (params carry everything). */
    @POST("v1/items/{id}/play/prewarm")
    suspend fun prewarm(
        @Path("id") id: String,
        @Query("caps") caps: String? = null,
        @Query("q") quality: String? = null,
        @Query("t") seekSec: Int? = null,
    )

    /** Full Item objects the current user has watched end-to-end (each carries
     *  watched_at). Zap dedups its pool against these ids. Mirrors web
     *  GET /v1/me/watched ({ items: [Item] }); reuses ItemsPage. */
    @GET("v1/me/watched")
    suspend fun watched(@Query("limit") limit: Int? = null): ItemsPage

    /** Raw multipart POST behind [submitFeedback] — Retrofit needs the parts
     *  pre-built (the "report" model is a multipart part, not a request body,
     *  so the converter factory doesn't apply). Call the extension, not this. */
    @Multipart
    @POST("v1/feedback")
    suspend fun submitFeedbackParts(
        @Part("report") report: RequestBody,
        @Part screenshot: MultipartBody.Part?,
    ): FeedbackResponse
}

/**
 * Files a bug report — chino-api opens (or dedup-appends to) a ticket on the
 * connected server's issue tracker and answers 201 (new) / 200 (duplicate,
 * comment appended).
 * Multipart: "report" JSON part + optional "screenshot" image part (server
 * rejects > 3 MB). Non-2xx surfaces as retrofit2.HttpException so BugReporter
 * can swallow auto-report failures silently while the Settings picker maps
 * 429/503 onto plain-language errors.
 */
suspend fun ChinoApi.submitFeedback(
    report: FeedbackReport,
    screenshot: ByteArray? = null,
    screenshotMime: String = "image/jpeg",
): FeedbackResponse = submitFeedbackParts(
    report = feedbackJson.encodeToString(FeedbackReport.serializer(), report)
        .toRequestBody("application/json".toMediaType()),
    screenshot = screenshot?.let { bytes ->
        val ext = if (screenshotMime == "image/png") "png" else "jpg"
        MultipartBody.Part.createFormData(
            "screenshot",
            "screenshot.$ext",
            bytes.toRequestBody(screenshotMime.toMediaType()),
        )
    },
)

/** Serializes the feedback "report" part by hand (see [submitFeedbackParts]).
 *  explicitNulls=false omits the optional title/fingerprint instead of
 *  sending JSON nulls. */
private val feedbackJson = Json { explicitNulls = false }

@Serializable
data class Episode(
    val id: String,
    val title: String,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("parent_id") val parentId: String? = null,
    // RFC3339; non-null when the current user has watched this episode
    // end-to-end. chino-api stamps it in seriesEpisodes() per 4f693fe.
    @SerialName("watched_at") val watchedAt: String? = null,
    // katalog emits the episode synopsis as JSON `description` (same as the
    // main Item). A bare `overview` never bound; map it explicitly.
    @SerialName("description") val overview: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val year: Int? = null,
)

@Serializable
data class Season(
    val season: Int,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class SeriesEpisodes(
    val seasons: List<Season> = emptyList(),
)

@Serializable
data class NextEpisode(
    val id: String? = null,
    val title: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)

@Serializable
data class Segment(
    val kind: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val label: String? = null,
)

@Serializable
data class SegmentsResponse(
    val segments: List<Segment> = emptyList(),
)

@Serializable
data class SidecarSubtitle(
    val id: String,
    val label: String,
    val lang: String,
    val url: String,
    val default: Boolean? = null,
    // `format` selects the renderer. `webvtt`/`srt` flow through the
    // native text-track pipeline; `pgs`/`vobsub`/`dvb` bind a Media3
    // image-subtitle decoder (PgsDecoder for `pgs`). Optional for
    // backward compatibility with older manager-api builds that
    // didn't emit the field — those rows are assumed webvtt.
    val format: String? = null,
)

@Serializable
data class SubtitlesResponse(
    val subtitles: List<SidecarSubtitle> = emptyList(),
)

@Serializable
data class QualityRung(
    /** "high" / "medium" / "low" — matches chino-stream's ?q= parameter. */
    val name: String,
    val label: String,
)

@Serializable
data class PlayInfo(
    val filename: String? = null,
    val container: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** ffprobe-derived authoritative duration — beats item-level rounded-to-the-minute. */
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** "passthrough" / "remux" / "transcode" / "packaged". */
    val mode: String? = null,
    val reason: String? = null,
    /** "libx264" / "h264_nvenc" when mode=transcode; null otherwise. */
    val encoder: String? = null,
    val qualities: List<QualityRung> = emptyList(),
    @SerialName("default_quality") val defaultQuality: String? = null,
)

@Serializable
data class PackagedIdsResponse(val ids: List<String> = emptyList())

@Serializable
data class UserFlagList(val items: List<String> = emptyList())

/** One named watchlist. The default list (name "Watchlist", isDefault=true)
 *  always exists per user and is returned first. */
@Serializable
data class Watchlist(
    val id: String,
    val name: String,
    @SerialName("itemCount") val itemCount: Int = 0,
    @SerialName("isDefault") val isDefault: Boolean = false,
    @SerialName("createdAt") val createdAt: String? = null,
)

@Serializable
data class WatchlistsResponse(val lists: List<Watchlist> = emptyList())

@Serializable
data class WatchlistNameBody(val name: String)

/** A single list with its item ids, newest-added first. */
@Serializable
data class WatchlistDetail(
    val id: String,
    val name: String,
    @SerialName("isDefault") val isDefault: Boolean = false,
    val items: List<String> = emptyList(),
)

@Serializable
data class MembershipsResponse(
    val memberships: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class GenresResponse(val genres: List<String> = emptyList())

/** A person row in the Search "Cast & crew" results. `credits` is the count of
 *  titles they're credited on (rendered "· N titles"). No profile photo exists,
 *  so the UI shows an initials-avatar placeholder. */
@Serializable
data class Person(
    val id: String,
    val name: String,
    val credits: Int = 0,
)

@Serializable
data class PeopleResponse(
    val people: List<Person> = emptyList(),
    val total: Int = 0,
)

/** Person surface payload: header (name + credit count) + the standard catalog
 *  Items they're credited on, rendered with the existing poster card. */
@Serializable
data class PersonDetail(
    val id: String,
    val name: String,
    val items: List<Item> = emptyList(),
)

@Serializable
data class Me(
    val sub: String,
    val email: String? = null,
    val name: String? = null,
)

@Serializable
data class StreamTokenResponse(
    @SerialName("stream_token") val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class ProgressResponse(
    @SerialName("position_sec") val positionSec: Int = 0,
)

@Serializable
data class ProgressBody(
    @SerialName("position_sec") val positionSec: Int,
    @SerialName("duration_sec") val durationSec: Int,
)

@Serializable
data class TelemetryEvent(
    val ts: Long,
    val kind: String,
    val itemId: String? = null,
    val payload: Map<String, String> = emptyMap(),
)

@Serializable
data class TelemetryBatch(
    val sessionId: String,
    val events: List<TelemetryEvent>,
)

@Serializable
data class ContinueWatchingItem(
    val id: String,
    val title: String,
    @SerialName("position_sec") val positionSec: Int = 0,
    @SerialName("duration_sec") val durationSec: Int = 0,
    /** True when chino-api substituted the next episode after one finished. */
    @SerialName("up_next") val upNext: Boolean = false,
    @SerialName("series_title") val seriesTitle: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val type: String? = null,
    // The server embeds the full catalog item in each continue-watching row,
    // so `year` + `rating` are already in the JSON — parsed here so the card
    // can show web's movie meta line ("<year> • <rating>", rating in accent).
    val year: Int? = null,
    /** Numeric TMDB-style 0.0–10.0, rendered "%.1f" like web/PosterCard. */
    val rating: Double? = null,
    // RFC3339; non-null once the user finished this item. Web's
    // ContinueWatchingEntry carries it too. Parsed here so the home shelf can
    // reflect watched-ness — and so the "Remove from Continue Watching" action
    // (which POSTs watched) can optimistically drop a card without a refetch.
    @SerialName("watched_at") val watchedAt: String? = null,
)

@Serializable
data class ContinueWatchingResponse(
    val items: List<ContinueWatchingItem> = emptyList(),
)

/** The "report" JSON part of POST /v1/feedback. Shape matches the contract
 *  shared with chino-web/chino-mobile field-for-field: source is "tv" here,
 *  kind is "manual" | "error" | "crash" | "player", fingerprint is the
 *  lowercase sha-256 of the normalized error signature (omitted for manual
 *  reports), context is a flat string map. */
@Serializable
data class FeedbackReport(
    val source: String,
    val kind: String,
    val title: String? = null,
    val description: String,
    val fingerprint: String? = null,
    val context: Map<String, String> = emptyMap(),
)

@Serializable
data class FeedbackResponse(
    /** Server-side bug ticket id. */
    val id: Long,
    val url: String,
    /** True when the server deduplicated onto an existing ticket (HTTP 200)
     *  instead of opening a new one (HTTP 201). */
    val duplicate: Boolean = false,
)
