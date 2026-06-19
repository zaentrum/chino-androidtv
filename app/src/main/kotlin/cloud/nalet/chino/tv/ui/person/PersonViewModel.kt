package cloud.nalet.chino.tv.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PersonUiState {
    data object Loading : PersonUiState
    data class Ready(
        val name: String,
        /** Number of credited titles — drives the header "· N titles" line. */
        val credits: Int,
        val items: List<Item>,
        val baseUrl: String,
        val streamToken: String,
    ) : PersonUiState
    data class Error(val message: String) : PersonUiState
}

/**
 * Person / Filmography surface VM. Fetches GET /v1/people/{id}; the returned
 * items are standard catalog Items (poster/backdrop/watched_at), rendered with
 * the existing poster card. Credit count = items.size (the filmography is the
 * full list of titles the person is credited on).
 */
class PersonViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val telemetry: Telemetry,
    private val baseUrl: String,
    val personId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<PersonUiState>(PersonUiState.Loading)
    val state: StateFlow<PersonUiState> = _state.asStateFlow()

    init {
        telemetry.event("screen_view", itemId = personId, extra = mapOf("screen" to "person"))
        load()
    }

    fun load() {
        _state.value = PersonUiState.Loading
        viewModelScope.launch {
            try {
                val detail = api.getPerson(personId)
                _state.value = PersonUiState.Ready(
                    name = detail.name,
                    credits = detail.items.size,
                    items = detail.items,
                    baseUrl = baseUrl,
                    streamToken = streamTokens.valid(),
                )
            } catch (e: Exception) {
                _state.value = PersonUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, personId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PersonViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    telemetry = container.telemetry,
                    baseUrl = container.baseUrl,
                    personId = personId,
                ) as T
        }
    }
}
