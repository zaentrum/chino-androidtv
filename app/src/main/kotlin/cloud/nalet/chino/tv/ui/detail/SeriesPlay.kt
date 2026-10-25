package cloud.nalet.chino.tv.ui.detail

import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import cloud.nalet.chino.tv.data.api.Episode
import cloud.nalet.chino.tv.data.api.NextEpisodeResponse
import cloud.nalet.chino.tv.data.api.Season
import cloud.nalet.chino.tv.data.model.Item

// What Play on a series' detail page starts: the episode the viewer is in the
// middle of, else the one after the last they finished, else the first. The
// same signals the web client reads — the continue-watching feed (its rows
// carry each episode's position, and the up-next episode the server put in
// for a finished one) and /series/{id}/next-episode — so both clients pick
// the same episode. Pure: SeriesPlayTest runs it on the JVM.

/** The episode Play starts on a series, and where in it. */
data class SeriesPlayTarget(
    val episodeId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** The saved position the player picks up; 0 starts the episode over. */
    val positionSec: Int = 0,
) {
    /** True when Play resumes the episode rather than starting it. */
    val resumes: Boolean get() = positionSec > 0

    /** "S02E03", or null for an episode without a number. */
    val code: String?
        get() = episodeNumber?.let { "S%02dE%02d".format(seasonNumber ?: 0, it) }
}

/** The episode of a series the viewer last touched and where they left it. */
data class LastTouched(
    val episodeId: String,
    val positionSec: Int,
    /** chino-api says the episode is finished (its last 5 % or 2 minutes):
     *  it has no resume point, and the next one is played. */
    val finished: Boolean = false,
)

/**
 * The canonical cross-client "mid-watch" test: more than 30 s in and not
 * within the last minute (the server's own finished cutoff). An unknown
 * duration (0) does not count as finished.
 */
fun isMidWatch(positionSec: Int, durationSec: Int): Boolean =
    positionSec > 30 && (durationSec <= 0 || positionSec < durationSec - 60)

/**
 * The series' newest row in the continue-watching feed (newest first), if it
 * has one: an episode in progress resumes; an up-next row — the server's
 * first unwatched episode after one the viewer finished — starts from the top.
 */
fun continueWatchingTarget(seriesId: String, rows: List<ContinueWatchingItem>): SeriesPlayTarget? {
    val row = rows.firstOrNull { it.parentId == seriesId } ?: return null
    val resumeAt = if (!row.upNext && isMidWatch(row.positionSec, row.durationSec)) row.positionSec else 0
    return SeriesPlayTarget(row.id, row.seasonNumber, row.episodeNumber, resumeAt)
}

/**
 * The episode a next-episode answer went from: its `anchor`, or — on
 * end_of_series, where the server leaves the id out — the series' last
 * episode. Null when the viewer touched no episode of the series.
 */
fun lastTouchedEpisodeId(response: NextEpisodeResponse?, seasons: List<Season>): String? {
    response ?: return null
    response.anchor?.takeIf { it.isNotBlank() }?.let { return it }
    if (response.reason == "end_of_series") return seasons.flatMap { it.episodes }.lastOrNull()?.id
    return null
}

/**
 * Play's episode when the continue-watching feed has none of the series
 * (it holds the 20 newest titles only):
 *  - the last episode touched, when it isn't finished (not marked watched,
 *    not finished as chino-api says - its last 5 % or 2 minutes - not in its
 *    last minute) — resumed when mid-watch, else from the top;
 *  - else the first unwatched episode after it;
 *  - else (nothing touched, or nothing unwatched after it) the first
 *    unwatched episode, then the first episode — of the regular seasons, as
 *    the episode list hides the specials when there are any;
 *  - with no episode list, the server's [serverNext].
 * Episodes are in the server's order: season by season, specials first.
 */
fun nextTarget(seasons: List<Season>, lastTouched: LastTouched?, serverNext: Item?): SeriesPlayTarget? {
    val all = seasons.flatMap { season -> season.episodes.map { season.season to it } }
    if (lastTouched != null) {
        val at = all.indexOfFirst { it.second.id == lastTouched.episodeId }
        if (at >= 0) {
            val (season, ep) = all[at]
            val durationSec = ((ep.durationMs ?: 0L) / 1000L).toInt()
            val finished = ep.watchedAt != null || lastTouched.finished ||
                (durationSec > 0 && lastTouched.positionSec >= durationSec - 60)
            if (!finished) {
                val resumeAt = lastTouched.positionSec.takeIf { isMidWatch(it, durationSec) } ?: 0
                return target(season, ep, resumeAt)
            }
            all.drop(at + 1).firstOrNull { it.second.watchedAt == null }
                ?.let { (s, e) -> return target(s, e) }
        }
    }
    val regular = all.filter { it.first > 0 }.ifEmpty { all }
    (regular.firstOrNull { it.second.watchedAt == null } ?: regular.firstOrNull())
        ?.let { (s, e) -> return target(s, e) }
    return serverNext?.let { SeriesPlayTarget(it.id, it.seasonNumber, it.episodeNumber) }
}

/**
 * What the first button of a series opened on one of its episodes plays - a
 * continue-watching card's, a search hit's: that [episode], named by its
 * code as on a series opened as a series ("resume S01E03"), from its own
 * saved position ([positionSec]) where that is mid-watch, else from its
 * beginning ("play S01E03"). Its code from the series' episode list where
 * the episode is in it, else from the episode itself.
 */
fun entryTarget(episode: Item, seasons: List<Season>, positionSec: Int): SeriesPlayTarget {
    val listed = seasons.firstNotNullOfOrNull { season ->
        season.episodes.firstOrNull { it.id == episode.id }?.let { season.season to it }
    }
    val seasonNumber = listed?.let { (season, ep) -> ep.seasonNumber ?: season } ?: episode.seasonNumber
    val episodeNumber = listed?.second?.episodeNumber ?: episode.episodeNumber
    val durationSec = ((listed?.second?.durationMs ?: episode.durationMs ?: 0L) / 1000L).toInt()
    val resumeAt = positionSec.takeIf { isMidWatch(it, durationSec) } ?: 0
    return SeriesPlayTarget(episode.id, seasonNumber, episodeNumber, resumeAt)
}

private fun target(season: Int, ep: Episode, positionSec: Int = 0) =
    SeriesPlayTarget(ep.id, ep.seasonNumber ?: season, ep.episodeNumber, positionSec)
