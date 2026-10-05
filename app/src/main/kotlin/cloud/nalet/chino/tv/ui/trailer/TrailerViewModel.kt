package cloud.nalet.chino.tv.ui.trailer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.CodecCaps
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Trailer
import cloud.nalet.chino.tv.data.streamArtworkUrl
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

sealed interface TrailerUiState {
    data object Loading : TrailerUiState

    /** Plays [masterUrl]: the trailer [label] ("Trailer") of the title
     *  [title]. [link] is the title's trailer link, offered should the
     *  master answer 404 after all. */
    data class Ready(val masterUrl: String, val title: String, val label: String, val link: Trailer?) : TrailerUiState

    /** There is no such trailer to play: the title or the extra is gone, the
     *  viewer's rating cap hides the title, or the master answered 404.
     *  [link] is the title's trailer link, when it has one. */
    data class NotAvailable(val link: Trailer?) : TrailerUiState

    /** It did not load or play for another reason; Try again loads again. */
    data class Failed(val message: String) : TrailerUiState
}

/**
 * An extra's master URL: its [playPath], from the server root, against the
 * API base [baseUrl] with the stream token ([streamArtworkUrl]), and the
 * device's [caps] — as a title's master is asked for. No `q`: chino-stream
 * serves the ladder, the variant to start on first.
 */
fun trailerMasterUrl(baseUrl: String, playPath: String, streamToken: String, caps: String): String? {
    val url = streamArtworkUrl(baseUrl, playPath, streamToken) ?: return null
    if (caps.isEmpty()) return url
    return url + (if ('?' in url) '&' else '?') + "caps=" + caps
}

/**
 * What the trailer screen plays: the extra [extraId] of the title [itemId],
 * as the title's detail lists it. The detail is the one request: no
 * progress, watched, segments, trickplay, subtitles, next episode or
 * prewarm, so Continue Watching never hears of a trailer. A 404 for the
 * title — gone, or above the viewer's rating cap — or an extra the detail no
 * longer lists is [TrailerUiState.NotAvailable]; any other failure throws.
 */
internal suspend fun loadTrailer(
    api: ChinoApi,
    baseUrl: String,
    streamToken: suspend () -> String,
    caps: String,
    itemId: String,
    extraId: String,
): TrailerUiState {
    val item = try {
        api.getItem(itemId)
    } catch (e: HttpException) {
        if (e.code() == 404) return TrailerUiState.NotAvailable(link = null)
        throw e
    }
    val link = pickTrailer(item.trailers)
    val extra = item.extras.firstOrNull { it.id == extraId && it.playable }
        ?: return TrailerUiState.NotAvailable(link)
    val url = trailerMasterUrl(baseUrl, extra.playPath, streamToken(), caps)
        ?: return TrailerUiState.NotAvailable(link)
    return TrailerUiState.Ready(
        masterUrl = url,
        title = item.title,
        label = extra.title.ifBlank { "Trailer" },
        link = link,
    )
}

/**
 * The trailer screen's state: a title's trailer from this server, played
 * from the start with sound, then closed. It reaches the API only from the
 * trailer route, which Detail opens — so only once a server is connected and
 * someone is signed in; building the route table builds no API client.
 */
class TrailerViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val telemetry: Telemetry,
    private val baseUrl: String,
    val itemId: String,
    val extraId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<TrailerUiState>(TrailerUiState.Loading)
    val state: StateFlow<TrailerUiState> = _state.asStateFlow()

    /** The screen's trailer_play has gone out. */
    private var played = false

    init { load() }

    fun load() {
        _state.value = TrailerUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                loadTrailer(
                    api = api,
                    baseUrl = baseUrl,
                    // The token manager blocks while it mints: off the main thread.
                    streamToken = { withContext(Dispatchers.IO) { streamTokens.valid() } },
                    caps = CodecCaps.queryParam,
                    itemId = itemId,
                    extraId = extraId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrailerUiState.Failed(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** Playback has begun: the screen's one trailer_play. */
    fun reportStarted() {
        if (played) return
        played = true
        telemetry.event("trailer_play", itemId = itemId, extra = mapOf("extra_id" to extraId, "local" to "true"))
    }

    /** The master answered 404: the trailer is gone, or under the viewer's
     *  rating cap. The title's link stays on offer. */
    fun notFound() {
        val link = (_state.value as? TrailerUiState.Ready)?.link
        _state.value = TrailerUiState.NotAvailable(link)
    }

    /** The player gave up for another reason. */
    fun failed(message: String) {
        _state.value = TrailerUiState.Failed(message)
    }

    /** The title's link opened instead, as Detail reports it. */
    fun reportLinkLaunch() = telemetry.event("trailer_launch", itemId = itemId)

    companion object {
        fun factory(container: AppContainer, itemId: String, extraId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TrailerViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    telemetry = container.telemetry,
                    baseUrl = container.baseUrl,
                    itemId = itemId,
                    extraId = extraId,
                ) as T
        }
    }
}
