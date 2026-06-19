package cloud.nalet.chino.tv.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many watch-history rows to pull. Mirrors web/mobile's
 *  `GET /v1/me/watched?limit=60`. */
private const val WATCHED_LIMIT = 60

sealed interface ProfileUiState {
    data object Loading : ProfileUiState

    data class Ready(
        /** Watch history, newest first (as chino-api orders it). */
        val history: List<Item>,
        /** parent_id -> series title, resolved after the list lands so episode
         *  rows can lead with the series title. Missing entries fall back to
         *  the episode's own title. */
        val seriesTitles: Map<String, String>,
        val baseUrl: String,
        val streamToken: String,
    ) : ProfileUiState

    data class Error(val message: String) : ProfileUiState
}

/**
 * Backs the TV Profile surface — the watch-history view (web's ProfilePage /
 * mobile's ProfileScreen parity). Loads the same `GET /v1/me/watched?limit=60`
 * endpoint the web + mobile clients call ([ChinoApi.watched]); episode rows
 * resolve their parent series title with follow-up GET /v1/items/{parent_id}
 * calls (deduped, in parallel) after the list paints. An unwatch removes the
 * row optimistically and DELETEs the server-side watched row
 * ([ChinoApi.deleteWatched]), reverting on failure — the same revert pattern
 * the detail page's watched toggle uses.
 *
 * Identity (name/email) is rendered by the screen from the active [Account]
 * the NavHost already threads into every shell; this VM only owns the history.
 */
class ProfileViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val telemetry: Telemetry,
    private val baseUrl: String,
) : ViewModel() {
    private val _state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        telemetry.event("screen_view", extra = mapOf("screen" to "profile"))
        load()
    }

    fun load() {
        _state.value = ProfileUiState.Loading
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) {
                runCatching { streamTokens.valid() }.getOrDefault("")
            }
            val items = withContext(Dispatchers.IO) {
                runCatching { api.watched(limit = WATCHED_LIMIT).items }.getOrNull()
            }
            if (items == null) {
                _state.value = ProfileUiState.Error("Couldn't load your watch history.")
                return@launch
            }
            _state.value = ProfileUiState.Ready(
                history = items,
                seriesTitles = emptyMap(),
                baseUrl = baseUrl,
                streamToken = token,
            )
            // Resolve parent series titles for episode rows — deduped, in
            // parallel, each fetch individually fallible. The list is already
            // on screen at this point; rows upgrade in place as titles land.
            val parentIds = items
                .filter { it.kind == "episode" }
                .mapNotNull { it.parentId }
                .distinct()
            if (parentIds.isNotEmpty()) {
                val titles = withContext(Dispatchers.IO) {
                    coroutineScope {
                        parentIds.map { pid ->
                            async {
                                runCatching { api.getItem(pid) }.getOrNull()?.let { pid to it.title }
                            }
                        }.awaitAll().filterNotNull().toMap()
                    }
                }
                val cur = _state.value as? ProfileUiState.Ready ?: return@launch
                _state.value = cur.copy(seriesTitles = titles)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ProfileViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    telemetry = container.telemetry,
                    baseUrl = container.baseUrl,
                ) as T
        }
    }
}
