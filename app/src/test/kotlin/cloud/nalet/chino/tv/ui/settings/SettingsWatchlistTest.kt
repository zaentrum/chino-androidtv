package cloud.nalet.chino.tv.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.PreferencesRepository
import cloud.nalet.chino.tv.data.SettingsStore
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.Preferences
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.feedback.BugReporter
import cloud.nalet.chino.tv.feedback.FilePendingReportStore
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.ShellScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The watchlist section (docs/chino, watchlist): only where the server can
 *  split a list, its words, and a change that can't be saved. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SettingsWatchlistTest {
    @get:Rule val compose = createComposeRule()

    private fun show(offered: Boolean, repo: PreferencesRepository) {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val api = java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, _, _ -> throw IllegalStateException("no server") } as ChinoApi
        val viewModel = SettingsViewModel(
            store = SettingsStore(ctx),
            telemetry = Telemetry(ctx) { null },
            bugReporter = BugReporter(api, emptyMap(), FilePendingReportStore(ctx.cacheDir)),
            serverHost = "media.test",
            offersWatchlistByKind = { offered },
            preferences = repo,
        )
        compose.setContent {
            ChinoTvTheme {
                ShellScreen { SettingsScreen(viewModel = viewModel, onZapNav = {}) }
            }
        }
        compose.waitForIdle()
    }

    private fun repo(save: suspend (Boolean) -> Preferences = { Preferences(it) }) = PreferencesRepository(
        load = { Preferences(watchlistByKind = false) },
        save = { save(it.watchlistByKind) },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `pressing the switch asks for the other state`() {
        val asked = mutableListOf<Boolean>()
        compose.setContent {
            ChinoTvTheme { WatchlistSection(byKind = false, saveFailed = false, onChange = { asked += it }) }
        }
        compose.onNode(hasText("off")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(true), asked)
    }

    @Test
    fun `a server that doesn't offer it has no section`() {
        show(offered = false, repo = repo())
        compose.onNode(hasText("Separate movies and shows")).assertDoesNotExist()
    }

    @Test
    fun `the section's words`() {
        show(offered = true, repo = repo())
        compose.onNode(hasText("watchlist")).assertExists()
        compose.onNode(hasText("Separate movies and shows")).assertExists()
        compose.onNode(hasText("Each list shows its movies in one row and its shows in another.")).assertExists()
    }

    @Test
    fun `a change that can't be saved goes back, and says so until the next`() {
        var fail = true
        val saved = mutableListOf<Boolean>()
        val repository = repo { value ->
            saved += value
            if (fail) throw IOException("no connection")
            Preferences(value)
        }
        show(offered = true, repo = repository)
        compose.onNode(hasText(WATCHLIST_NOT_SAVED)).assertDoesNotExist()
        compose.runOnIdle { repository.setWatchlistByKind(true) }
        compose.waitForIdle()
        compose.onNode(hasText(WATCHLIST_NOT_SAVED)).assertExists()
        assertEquals(false, repository.preferences.value?.watchlistByKind)
        fail = false
        compose.runOnIdle { repository.setWatchlistByKind(true) }
        compose.waitForIdle()
        compose.onNode(hasText(WATCHLIST_NOT_SAVED)).assertDoesNotExist()
        assertEquals(true, repository.preferences.value?.watchlistByKind)
        assertEquals(listOf(true, true), saved)
    }
}
