package cloud.nalet.chino.tv.ui.search

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.SearchResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException

/*
 * What a search finds: one request, chino-api's v1/search, or — against a
 * chino-api older than it — the three requests the app made before.
 * CatalogSearchTest runs it against a fake chino-api.
 */

/**
 * The types of title a search asks chino-api for: films and series, which it
 * ranks together.
 *
 * The hook for episodes: to show episodes, add "episode" here. Each episode
 * hit then carries its `series` ([cloud.nalet.chino.tv.data.model.Item.series]:
 * id, title, poster_url, matched) and its season and episode numbers. The
 * agreed card — a later UI rework, not built yet — shows it as its series'
 * poster card with one line read from the data, like "S05E14 · Ozymandias",
 * opening the series on that episode; and one card per series when the series
 * also matched on its own title (`series.matched`). Until that card is built,
 * the TV shows no episode hit, so it asks for none.
 */
private const val SEARCH_TYPES = "movie,series"

/** How many titles a search asks for (v1/search takes at most 100). */
private const val PAGE_SIZE = 60

/** How many people: the "Cast & crew" row sits above the title grid, so a
 *  long list would push the titles below the fold (v1/search takes at most
 *  50). */
private const val PEOPLE_LIMIT = 12

/**
 * What a search for [q] finds: the titles in the order to show them and the
 * people for the "Cast & crew" row, both as chino-api's v1/search ranks them
 * — films and series together, best first. A chino-api older than v1/search
 * answers 404, and is searched as before ([searchAsBefore]). Any other
 * failure throws, as a failed search did before.
 */
internal suspend fun searchCatalog(api: ChinoApi, q: String): SearchResponse =
    try {
        api.search(q = q, types = SEARCH_TYPES, limit = PAGE_SIZE, people = PEOPLE_LIMIT)
    } catch (e: HttpException) {
        if (e.code() != 404) throw e
        searchAsBefore(api, q)
    }

/**
 * A search of a chino-api without v1/search, as the app searched before: its
 * films and its series, in parallel, each list in the server's order, films
 * first; and the people beside them, best effort — a chino-api without
 * v1/people either (404) leaves the row empty rather than failing the search.
 */
private suspend fun searchAsBefore(api: ChinoApi, q: String): SearchResponse = coroutineScope {
    val movies = async { api.listItems(q = q, limit = PAGE_SIZE, type = "movie") }
    val series = async { api.listItems(q = q, limit = PAGE_SIZE, type = "series") }
    val people = async {
        runCatching { api.searchPeople(q = q, limit = PEOPLE_LIMIT).people }
            .getOrDefault(emptyList())
    }
    SearchResponse(items = movies.await().items + series.await().items, people = people.await())
}
