package cloud.nalet.chino.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.AppSettings
import cloud.nalet.chino.tv.data.SettingsStore
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.feedback.BugReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lifecycle of the manual "Report a problem" submit — drives the inline
 *  panel body: category rows (Idle/Failed), a sending line, or the filed-
 *  ticket confirmation with the server-side ticket id. */
sealed interface BugReportState {
    data object Idle : BugReportState
    data object Sending : BugReportState
    data class Filed(val id: Long, val duplicate: Boolean) : BugReportState
    data class Failed(val message: String) : BugReportState
}

class SettingsViewModel(
    private val store: SettingsStore,
    private val telemetry: Telemetry,
    private val bugReporter: BugReporter,
    /** Connected-server host stamped onto manual reports (the static context
     *  already carries the app version / flavor / device fields). */
    private val serverHost: String?,
) : ViewModel() {
    init { telemetry.event("screen_view", extra = mapOf("screen" to "settings")) }
    val state: StateFlow<AppSettings> = store.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    private val _bugReport = MutableStateFlow<BugReportState>(BugReportState.Idle)
    val bugReport: StateFlow<BugReportState> = _bugReport.asStateFlow()

    /**
     * Files a canned-category manual report through BugReporter.reportManual
     * (errors PROPAGATE there, unlike auto reports) and maps the outcome onto
     * [bugReport]: Filed carries the ticket id + duplicate flag, Failed a
     * plain-language line (429 rate limit / 503 reporting unconfigured /
     * anything else → connection wording). No free text on TV — the category
     * doubles as the ticket title.
     */
    fun fileBugReport(category: String) {
        if (_bugReport.value is BugReportState.Sending) return
        telemetry.event("bug_report_manual", extra = mapOf("category" to category))
        _bugReport.value = BugReportState.Sending
        viewModelScope.launch {
            _bugReport.value = try {
                val resp = bugReporter.reportManual(
                    title = category,
                    description = "$category (reported from TV settings)",
                    context = buildMap {
                        put("screen", "settings")
                        serverHost?.let { put("server", it) }
                    },
                )
                BugReportState.Filed(id = resp.id, duplicate = resp.duplicate)
            } catch (e: Exception) {
                BugReportState.Failed(
                    when ((e as? retrofit2.HttpException)?.code()) {
                        429 -> "Too many reports right now — try again in a few minutes."
                        503 -> "Bug reporting isn't set up on this server."
                        else -> "Couldn't send the report. Check your connection and try again."
                    },
                )
            }
        }
    }

    /** Back to the category rows — called when the panel (re)opens so a stale
     *  Filed/Failed body from the previous report doesn't flash. */
    fun resetBugReport() { _bugReport.value = BugReportState.Idle }

    fun setAutoSkipIntro(v: Boolean) {
        telemetry.event("setting_change", extra = mapOf("key" to "autoSkipIntro", "value" to v.toString()))
        viewModelScope.launch { store.setAutoSkipIntro(v) }
    }
    fun setAutoSkipCredits(v: Boolean) {
        telemetry.event("setting_change", extra = mapOf("key" to "autoSkipCredits", "value" to v.toString()))
        viewModelScope.launch { store.setAutoSkipCredits(v) }
    }
    fun setAutoPlayNext(v: Boolean) {
        telemetry.event("setting_change", extra = mapOf("key" to "autoPlayNext", "value" to v.toString()))
        viewModelScope.launch { store.setAutoPlayNext(v) }
    }
    fun setCountdownSec(v: Int) {
        telemetry.event("setting_change", extra = mapOf("key" to "countdownSec", "value" to v.toString()))
        viewModelScope.launch { store.setCountdownSec(v) }
    }
    fun setPreferredSubLang(v: String) {
        telemetry.event("setting_change", extra = mapOf("key" to "preferredSubLang", "value" to v))
        viewModelScope.launch { store.setPreferredSubLang(v) }
    }
    fun setPreferredAudioLang(v: String) {
        telemetry.event("setting_change", extra = mapOf("key" to "preferredAudioLang", "value" to v))
        viewModelScope.launch { store.setPreferredAudioLang(v) }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    container.settings,
                    container.telemetry,
                    container.bugReporter,
                    // Same host derivation the NavHost uses for the Server row.
                    serverHost = container.serverConfig.baseUrl
                        .substringAfter("://")
                        .substringBefore("/")
                        .takeIf { it.isNotBlank() },
                ) as T
        }
    }
}
