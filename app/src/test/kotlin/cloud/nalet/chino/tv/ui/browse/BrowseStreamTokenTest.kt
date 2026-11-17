package cloud.nalet.chino.tv.ui.browse

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.GenresResponse
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * Movies and Shows without a stream token - the first request for one
 * failed: the page loads, and the next load of it asks for the token again,
 * so the posters are not asked for without one for as long as the page stays.
 */
@RunWith(RobolectricTestRunner::class)
class BrowseStreamTokenTest {
    private val movies = (1..3).map { Item(id = "m$it", title = "Harbour Light $it", kind = "movie", year = 2010 + it) }

    /** A server whose first [failures] requests for a stream token fail. */
    private fun api(failures: Int, mints: AtomicInteger): ChinoApi = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "listItems" -> ItemsPage(movies)
            "listGenres" -> GenresResponse(listOf("Drama"))
            "mintStreamToken" ->
                if (mints.getAndIncrement() < failures) throw java.io.IOException("can't reach") else StreamTokenResponse(token = "t")
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw java.io.IOException("not asked here")
        }
    } as ChinoApi

    private fun page(api: ChinoApi): BrowseViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        return BrowseViewModel(
            api = api,
            streamTokens = StreamTokenManager(api) { ActiveChoice("viewer", 0) },
            telemetry = Telemetry(ctx) { null },
            userFlags = UserFlagsRepository(api, activeChanges = { 0 }) { ActiveChoice("viewer", 0) },
            openedFor = { "viewer" },
            baseUrl = "https://media.test/api",
            type = "movie",
        )
    }

    /** Runs the main thread's work until [check] holds. */
    private fun eventually(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            check(System.currentTimeMillis() < until) { "the page never got there" }
            Thread.sleep(5)
        }
    }

    private fun BrowseViewModel.readyWith(token: String) =
        eventually { (state.value as? BrowseUiState.Ready)?.let { !it.reloading && it.streamToken == token } == true }

    @Test
    fun `a chip pressed on a page that has no stream token asks for one again`() {
        val mints = AtomicInteger(0)
        val vm = page(api(failures = 1, mints))
        vm.readyWith("")
        vm.setFilters(BrowseFilters(genre = "Drama"))
        vm.readyWith("t")
        assertEquals(2, mints.get())
    }

    @Test
    fun `a page with its stream token asks for none again`() {
        val mints = AtomicInteger(0)
        val vm = page(api(failures = 0, mints))
        vm.readyWith("t")
        vm.setFilters(BrowseFilters(genre = "Drama"))
        vm.readyWith("t")
        eventually { (vm.state.value as? BrowseUiState.Ready)?.filters?.genre == "Drama" }
        assertEquals(1, mints.get())
    }
}
