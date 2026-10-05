package cloud.nalet.chino.tv.ui.notices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.Notice
import cloud.nalet.chino.tv.data.NoticesRepository
import cloud.nalet.chino.tv.data.NoticesState
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the Notices screen: the person's notices as the bell holds them
 * ([NoticesRepository] — the screen's own top bar keeps asking), and the
 * changes. Each change runs on the app's scope, not this screen's: opening a
 * notice reads it as the detail screen opens over this one, and a delete is
 * not dropped should the screen be left right after.
 */
class NoticesViewModel(
    private val notices: NoticesRepository,
    private val appScope: CoroutineScope,
    telemetry: Telemetry,
) : ViewModel() {
    init { telemetry.event("screen_view", extra = mapOf("screen" to "notices")) }

    val state: StateFlow<NoticesState> = notices.state

    /** Opening a notice reads it; the screen opens its title, if it has one. */
    fun open(account: String, notice: Notice) {
        appScope.launch { notices.read(account, notice) }
    }

    fun readAll(account: String) {
        appScope.launch { notices.readAll(account) }
    }

    fun delete(account: String, notice: Notice) {
        appScope.launch { notices.delete(account, notice) }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoticesViewModel(
                    notices = container.notices,
                    appScope = container.appScope,
                    telemetry = container.telemetry,
                ) as T
        }
    }
}
