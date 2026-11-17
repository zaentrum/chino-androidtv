package cloud.nalet.chino.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AccountSettings
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.AppSettings
import cloud.nalet.chino.tv.data.SettingsStore
import cloud.nalet.chino.tv.data.api.LoadFailure
import cloud.nalet.chino.tv.data.api.loadFailureOf
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.feedback.BugReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

/**
 * Separate movies and shows as settings show it (sections 16.4.7 and 16.8 of
 * the specification): [shown] is the choice on the switch - the account's,
 * the one last known on this device while it is read or where that read
 * failed, or the one being saved - and null leaves the watchlist group out,
 * where the server doesn't fill lists by kind. The switch is busy while the
 * choice is [saving] or [reading]. [failure] says why the last save failed,
 * until the switch is used again.
 */
data class ListsByKindSetting(
    val shown: Boolean? = null,
    val saving: Boolean = false,
    val reading: Boolean = false,
    val failure: LoadFailure? = null,
) {
    /** Busy: the switch shows its choice and takes no press. */
    val busy: Boolean get() = saving || reading
}

/** The watchlist group as [offered], [held], [reading], [saving] and the
 *  last save's [failure] make it: left out where it isn't offered or nothing
 *  is held for the account. */
private fun listsByKindShown(
    offered: Boolean,
    held: Boolean?,
    reading: Boolean,
    saving: Boolean?,
    failure: LoadFailure?,
): ListsByKindSetting =
    if (!offered || held == null) ListsByKindSetting()
    else ListsByKindSetting(
        shown = saving ?: held,
        saving = saving != null,
        reading = reading && saving == null,
        failure = failure.takeIf { saving == null },
    )

class SettingsViewModel(
    private val store: SettingsStore,
    private val telemetry: Telemetry,
    private val bugReporter: BugReporter,
    /** Connected-server host stamped onto manual reports (the static context
     *  already carries the app version / flavor / device fields). */
    private val serverHost: String?,
    /** Whether the server takes bug reports ([cloud.nalet.chino.tv.data.ServerFeatures]). */
    private val takesReports: suspend () -> Boolean = { false },
    /** The server's subtitle languages ([cloud.nalet.chino.tv.data.SubtitleLanguages]). */
    private val subtitleLanguages: suspend () -> List<String> = { emptyList() },
    /** Whether the server keeps Separate movies and shows and fills a list by
     *  kind ([cloud.nalet.chino.tv.data.ServerFeatures.watchlistKinds]),
     *  asked where it hasn't answered yet; where that fails, as it said
     *  before. */
    private val kindsOffered: suspend () -> Boolean,
    /** The same as far as is known, asking nothing - the server's answer,
     *  or what it said before, kept on this device
     *  ([cloud.nalet.chino.tv.data.ServerFeatures.watchlistKindsNow]): what
     *  the page's first frame shows. */
    private val kindsOfferedNow: () -> Boolean,
    /** The settings the active account keeps on the server. */
    private val accountSettings: AccountSettings,
) : ViewModel() {
    init { telemetry.event("screen_view", extra = mapOf("screen" to "settings")) }

    /** Whether the watchlist group is offered: as far as is known when the
     *  page opens - the server's answer, or what it said before, kept on
     *  this device - then as it answers. */
    private val kindsAvailable = MutableStateFlow(kindsOfferedNow())

    /** The read the page makes as it opens is still out: the switch is busy
     *  from the first frame until it has answered. */
    private val kindsOpening = MutableStateFlow(true)

    /** The choice being saved, while it is. */
    private val kindsSaving = MutableStateFlow<Boolean?>(null)
    private val kindsFailure = MutableStateFlow<LoadFailure?>(null)

    /** Separate movies and shows, as the watchlist group shows it: from the
     *  first frame what is known already - whether the server fills lists
     *  by kind, as it answered or said before, and the choice the account
     *  settings hold - then as each answers. */
    val listsByKind: StateFlow<ListsByKindSetting> = combine(
        kindsAvailable,
        accountSettings.watchlistByKind,
        combine(accountSettings.reading, kindsOpening) { reading, opening -> reading || opening },
        kindsSaving,
        kindsFailure,
    ) { offered, held, reading, saving, failure ->
        listsByKindShown(offered, held, reading, saving, failure)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        listsByKindShown(kindsAvailable.value, accountSettings.watchlistByKind.value, true, null, null),
    )

    init {
        viewModelScope.launch {
            if (kindsAvailable.value) {
                // Known as offered: the choice is read again at once - it may
                // have been changed on another device - beside asking the
                // server where it hasn't answered.
                launch { kindsAvailable.value = kindsOffered() }
                try {
                    accountSettings.watchlistByKind(fresh = true)
                } finally {
                    kindsOpening.value = false
                }
            } else {
                try {
                    if (kindsOffered()) {
                        kindsAvailable.value = true
                        // Read again: it may have been changed on another device.
                        accountSettings.watchlistByKind(fresh = true)
                    }
                } finally {
                    kindsOpening.value = false
                }
            }
        }
    }

    /** The switch pressed: [on] saved for the account whose choice it shows.
     *  A press while the choice is saved or read does nothing; a press after
     *  another account was chosen sends nothing. The save runs on the
     *  account settings' own scope: leaving the page doesn't stop it. */
    fun setListsByKind(on: Boolean) {
        if (kindsSaving.value != null || kindsOpening.value) return
        val under = accountSettings.pressedUnder() ?: return
        telemetry.event("setting_change", extra = mapOf("key" to "watchlistByKind", "value" to on.toString()))
        // The banner of a save before goes as the switch is used again.
        kindsFailure.value = null
        kindsSaving.value = on
        val save = accountSettings.saveWatchlistByKind(on, under)
        viewModelScope.launch {
            kindsFailure.value = try {
                save.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadFailureOf(e)
            }
            kindsSaving.value = null
        }
    }

    private val _reportsTaken = MutableStateFlow(false)

    /** Whether the server takes bug reports: the Feedback section shows only
     *  then, as its line promises them a recipient. */
    val reportsTaken: StateFlow<Boolean> = _reportsTaken.asStateFlow()

    init { viewModelScope.launch { _reportsTaken.value = takesReports() } }

    private val _serverSubtitleLanguages = MutableStateFlow<List<String>>(emptyList())

    /** The subtitle languages the server names, its order: what the subtitle
     *  setting offers (LanguageChoices.kt); none - every language - until it
     *  has answered, and from a server that names none. */
    val serverSubtitleLanguages: StateFlow<List<String>> = _serverSubtitleLanguages.asStateFlow()

    init {
        viewModelScope.launch {
            _serverSubtitleLanguages.value = runCatching { subtitleLanguages() }.getOrDefault(emptyList())
        }
    }
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
                    takesReports = container.serverFeatures::feedback,
                    subtitleLanguages = container.subtitleLanguages::get,
                    kindsOffered = container.serverFeatures::watchlistKinds,
                    kindsOfferedNow = container.serverFeatures::watchlistKindsNow,
                    accountSettings = container.accountSettings,
                ) as T
        }
    }
}
