package cloud.nalet.chino.tv.ui.person

import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.streamArtworkUrl
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed interface PersonUiState {
    data object Loading : PersonUiState
    data class Ready(
        val name: String,
        /** Number of credited titles — drives the header "N titles" line. */
        val credits: Int,
        val items: List<Item>,
        val baseUrl: String,
        val streamToken: String,
        /** The portrait, stream token on it; null when the catalog has none. */
        val portraitUrl: String? = null,
        /** Known for / Born / Died, in the device's locale. */
        val facts: List<PersonFact> = emptyList(),
        val biography: String? = null,
        /** The biography's language (a primary subtag), when the catalog said. */
        val biographyLang: String? = null,
    ) : PersonUiState
    data class Error(val message: String) : PersonUiState
}

/**
 * Person / Filmography surface VM. Fetches GET /v1/people/{id} with the
 * device's languages as Accept-Language, so the biography comes in one of
 * them when the catalog has it (else English, else any). The returned items
 * are standard catalog Items (poster/backdrop/watched_at, plus the person's
 * roles on each), rendered with the existing poster card. Credit count =
 * items.size (the filmography is the full list of titles the person is
 * credited on).
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
                val detail = api.getPerson(
                    personId,
                    acceptLanguage = acceptLanguage(deviceLanguageTags()).ifEmpty { null },
                )
                // The mint is a blocking OkHttp call when the cached token is
                // stale — off the main thread, as the Library does it.
                val token = withContext(Dispatchers.IO) { streamTokens.valid() }
                _state.value = PersonUiState.Ready(
                    name = detail.name,
                    credits = detail.items.size,
                    items = detail.items,
                    baseUrl = baseUrl,
                    streamToken = token,
                    portraitUrl = detail.profileUrl
                        ?.takeIf { detail.hasProfile }
                        ?.let { streamArtworkUrl(baseUrl, it, token) },
                    facts = personFacts(detail, Locale.getDefault(), todayCatalogDate()),
                    biography = detail.biography?.trim()?.takeIf { it.isNotEmpty() },
                    biographyLang = detail.biographyLang?.takeIf { it.isNotBlank() },
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

/** The device's languages, most wanted first (the system locale list on API
 *  24+, the default locale before it), as plain language tags. */
private fun deviceLanguageTags(): List<String> {
    val locales = LocaleListCompat.getAdjustedDefault()
    return (0 until locales.size()).mapNotNull { i -> locales.get(i)?.let(::languageTagOf) }
}
