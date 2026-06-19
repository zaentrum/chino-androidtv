package cloud.nalet.chino.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Catalogue item returned by chino-api. The beta /v1/items endpoint is still a stub
 * — fields here track what we expect once katalog is wired in (id, title, kind,
 * artwork URL). Unknown fields are tolerated (Json{ignoreUnknownKeys=true}).
 */
@Serializable
data class Item(
    val id: String,
    val title: String,
    /**
     * Catalogue type — "movie", "series", "episode", "album", "track" per
     * chino-api/internal/katalog/client.go. JSON field is `type`; we keep
     * the Kotlin property named `kind` so all the existing call sites
     * (Item.kind == "series" etc.) don't have to change.
     */
    @SerialName("type") val kind: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val year: Int? = null,
    // chino-api/katalog emits the synopsis as JSON `description` (see
    // katalog/client.go Item `json:"description"`), on BOTH list and detail
    // endpoints. A bare `overview` never bound and left the hero + detail
    // overview permanently null. chino-web reads `description` directly.
    // Kotlin property stays `overview` so call sites read naturally.
    @SerialName("description") val overview: String? = null,
    // Numeric (TMDB-style 0.0-10.0). chino-web renders as e.g. "7.4" in a
    // blue chip; null when chino-api hasn't populated it for the item.
    val rating: Double? = null,
    // RFC3339 timestamp; non-null when the current user has watched this
    // item end-to-end. Stamped by chino-api's enrich.go.
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val cast: List<CastMember> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    /** Free-form genre tags from katalog metadata, e.g.
     *  ["Action & Adventure", "Animation"]. Only inlined on the detail
     *  endpoint (GET v1/items/{id}); rendered as pill chips on Detail. */
    val genres: List<String> = emptyList(),
    /** Optional short marketing line under the title (detail only). */
    val tagline: String? = null,
    /** Available subtitle tracks (detail only). Rendered in the Detail
     *  footer as a comma-separated list of label/lang. */
    val subtitles: List<Subtitle> = emptyList(),
    /** Per-item segment summary (intro/credits/recap). Non-null + count>0
     *  drives the Detail footer "Analyzed" column. */
    val segments: SegSummary? = null,
    /** For episodes, the parent series id. Null for movies / series-level items. */
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)

@Serializable
data class Subtitle(
    val id: String? = null,
    val lang: String = "",
    val label: String? = null,
    val format: String? = null,
    val default: Boolean = false,
)

@Serializable
data class SegSummary(
    val count: Int = 0,
    @SerialName("has_intro") val hasIntro: Boolean = false,
    @SerialName("has_credits") val hasCredits: Boolean = false,
    @SerialName("has_recap") val hasRecap: Boolean = false,
)

@Serializable
data class CastMember(
    val name: String,
    /** "director", "actor", or null. */
    val role: String? = null,
    /** Stable katalog person id. Non-null once chino-api enriches cast with
     *  people rows; when present the Detail cast chip becomes a tap target into
     *  the Person surface. Older payloads (or unmatched names) leave it null —
     *  the chip then stays display-only. */
    @SerialName("person_id") val personId: String? = null,
)

@Serializable
data class Trailer(
    val url: String,
    val site: String? = null,
    val title: String? = null,
)

@Serializable
data class ItemsPage(
    val items: List<Item> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
)
