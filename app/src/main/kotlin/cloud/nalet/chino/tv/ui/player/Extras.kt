package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.model.Trailer
import cloud.nalet.chino.tv.data.streamArtworkUrl
import cloud.nalet.chino.tv.ui.trailer.pickTrailer
import retrofit2.HttpException

/*
 * An extra in the player (PlayMode.Extra): what it plays, read from its
 * title's detail — the one request it makes. ExtraLoadTest runs it against a
 * fake chino-api.
 */

/** What an extra's title's detail says of it. */
sealed interface ExtraLoad {
    /** It plays [masterUrl], called [title] in the player ([extraTitle]).
     *  [link] is the title's trailer link, offered should the master answer
     *  404 after all. */
    data class Found(val masterUrl: String, val title: String, val link: Trailer?) : ExtraLoad

    /** There is no such extra to play: the title or the extra is gone, or
     *  the viewer's rating cap hides the title. [link] is the title's
     *  trailer link, when it has one. */
    data class NotAvailable(val link: Trailer?) : ExtraLoad
}

/**
 * An extra's master URL: its [playPath], from the server root, against the
 * API base [baseUrl] with the stream token ([streamArtworkUrl]), and the
 * device's [caps] — as a title's master is asked for. No `q`: chino-stream
 * serves the ladder, the variant to start on first.
 */
fun extraMasterUrl(baseUrl: String, playPath: String, streamToken: String, caps: String): String? {
    val url = streamArtworkUrl(baseUrl, playPath, streamToken) ?: return null
    if (caps.isEmpty()) return url
    return url + (if ('?' in url) '&' else '?') + "caps=" + caps
}

/** An extra's title in the player: its title's and its own, "Sintel ·
 *  Trailer" — "Trailer" for one without a title. */
fun extraTitle(title: String, extraTitle: String): String =
    listOf(title, extraTitle.ifBlank { "Trailer" }).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * What the extra [extraId] of the title [itemId] plays, as the title's
 * detail lists it: the detail is the one request. A 404 for the title — gone,
 * or above the viewer's rating cap — or an extra the detail no longer lists
 * is [ExtraLoad.NotAvailable]; any other failure throws.
 */
internal suspend fun loadExtra(
    api: ChinoApi,
    baseUrl: String,
    streamToken: String,
    caps: String,
    itemId: String,
    extraId: String,
): ExtraLoad {
    val item = try {
        api.getItem(itemId)
    } catch (e: HttpException) {
        if (e.code() == 404) return ExtraLoad.NotAvailable(link = null)
        throw e
    }
    val link = pickTrailer(item.trailers)
    val extra = item.extras.firstOrNull { it.id == extraId && it.playable }
        ?: return ExtraLoad.NotAvailable(link)
    val url = extraMasterUrl(baseUrl, extra.playPath, streamToken, caps)
        ?: return ExtraLoad.NotAvailable(link)
    return ExtraLoad.Found(masterUrl = url, title = extraTitle(item.title, extra.title), link = link)
}
