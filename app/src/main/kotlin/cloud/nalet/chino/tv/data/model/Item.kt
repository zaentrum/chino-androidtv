package cloud.nalet.chino.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Catalogue item returned by chino-api (chino-api/internal/katalog/client.go
 * Item). Unknown fields are tolerated (Json{ignoreUnknownKeys=true}); a field
 * chino-api leaves out when empty takes the default here. Artwork isn't read
 * from the item: its poster_url / backdrop_url name chino-api's
 * /v1/items/{id}/poster and /backdrop routes, which the UI addresses directly
 * with the stream token.
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
    /** The title's links to online videos (detail only). A trailer this
     *  server plays is never here: it is one of [extras]. */
    val trailers: List<Trailer> = emptyList(),
    /** The title's extras that play from this server — its trailers,
     *  teasers, featurettes, … — in the order a viewer sees them (detail
     *  only; chino-api leaves it out when none plays). */
    val extras: List<Extra> = emptyList(),
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
    /** On a person's filmography only: their roles on this title, in
     *  katalog-api's credit order (["director", "writer"]). */
    val roles: List<String> = emptyList(),
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

/** One credit, as chino-api passes katalog-api's cast through: role by role
 *  (actor, creator, director, writer, producer, composer, cinematographer,
 *  editor, then any other role), billing order within a role, at most 20
 *  actors and 10 people of every other role. Optional fields are omitted
 *  when unknown. ui/detail/Credits.kt groups them for the detail page. */
@Serializable
data class CastMember(
    val name: String,
    /** An open role token ("actor", "director", "sound-designer"); blank
     *  or absent means an actor (catalogs from before roles were sent). */
    val role: String? = null,
    /** Stable katalog person id. Non-null once chino-api enriches cast with
     *  people rows; when present the Detail cast chip becomes a tap target into
     *  the Person surface. Older payloads (or unmatched names) leave it null —
     *  the chip then stays display-only. */
    @SerialName("person_id") val personId: String? = null,
    /** The job within the role ("Screenplay"). */
    val job: String? = null,
    /** The part an actor plays. */
    val character: String? = null,
    /** Billing order within the role, 0 first. */
    val order: Int? = null,
    /** How many episodes of a series the credit covers. */
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
data class Trailer(
    val url: String,
    val site: String? = null,
    val title: String? = null,
)

/**
 * One of a movie's or a series' extras, as chino-api lists it with the
 * title's detail: a trailer, a teaser, a featurette, … — a file of its own,
 * packaged for streaming apart from the title. Every field has a default, so
 * an extra short of one never fails the whole item; [playable] says whether
 * it can be played. An extra has no progress, watched, segments, trickplay
 * or play info of its own.
 */
@Serializable
data class Extra(
    val id: String = "",
    /** trailer, teaser, featurette, behind-the-scenes, making-of,
     *  deleted-scene, interview, gag-reel, short or other; a kind the app
     *  does not know is skipped. */
    val kind: String = "",
    val title: String = "",
    /** BCP 47, when known ("en"). */
    val language: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** Set on a series' extra of one season (0 the specials). */
    @SerialName("season_number") val seasonNumber: Int? = null,
    /** It plays from this server — chino-api always says so. */
    val local: Boolean = false,
    /** The extra's HLS master from the server root
     *  ("/api/v1/items/{id}/extras/{extraId}/play/master.m3u8"), asked for
     *  as a title's master is: `?stream=<token>&caps=<caps>`. */
    @SerialName("play_path") val playPath: String = "",
) {
    /** It can be played here: it has an id and a master, from this server. */
    val playable: Boolean get() = id.isNotBlank() && local && playPath.isNotBlank()
}

/** A list response: GET /v1/items, /v1/items/{id}/similar, /v1/me/watched.
 *  There is no cursor in it — chino-api pages by offset (see OffsetPager). */
@Serializable
data class ItemsPage(
    val items: List<Item> = emptyList(),
)
