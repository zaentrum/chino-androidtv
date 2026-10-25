package cloud.nalet.chino.tv.ui.library

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.ProgressSaves
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import cloud.nalet.chino.tv.data.api.ContinueWatchingResponse
import cloud.nalet.chino.tv.data.api.GenresResponse
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Home back from the player reads continue watching once the player's last
 * progress save is answered, as the title page does: the shelf shows the
 * position just saved - a movie finished off it - never the one the server
 * held a moment before. So does Home built anew by the player's Home button.
 */
@RunWith(RobolectricTestRunner::class)
class ContinueWatchingRefreshTest {
    /** What the server's continue watching says now: one movie, part way. */
    @Volatile private var feed = listOf(row(600))

    /** How many times the shelf was read. */
    @Volatile private var reads = 0

    /** How many times a rail or the hero was read. */
    @Volatile private var itemReads = 0

    private fun row(positionSec: Int) =
        ContinueWatchingItem(id = "m1", title = "Quiet Orchard", positionSec = positionSec, durationSec = 5400, type = "movie")

    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "listItems" -> { itemReads++; ItemsPage(listOf(Item(id = "m2", title = "Paper Station", kind = "movie", year = 2019))) }
            "listGenres" -> GenresResponse()
            "getItem" -> Item(id = "m2", title = "Paper Station", kind = "movie", year = 2019)
            "continueWatching" -> { reads++; ContinueWatchingResponse(feed) }
            "mintStreamToken" -> StreamTokenResponse(token = "t")
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw java.io.IOException("not asked here")
        }
    } as ChinoApi

    /** Home built anew, its first load on its way. */
    private fun built(saves: ProgressSaves): LibraryViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        return LibraryViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            userFlags = UserFlagsRepository(api),
            progressSaves = saves,
        )
    }

    private fun home(saves: ProgressSaves): LibraryViewModel {
        val vm = built(saves)
        waitUntil { vm.state.value is LibraryUiState.Ready }
        // The screen's first resume, which the first load covers.
        vm.onScreenResumed()
        return vm
    }

    private fun shelf(vm: LibraryViewModel) = (vm.state.value as LibraryUiState.Ready).continueWatching

    /** Runs the main thread's queue until [done], 5 s at most. */
    private fun waitUntil(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "never came" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Runs the main thread's queue for [ms]. */
    private fun runFor(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `back from the player Home reads continue watching once the player's last save is answered`() {
        val saves = ProgressSaves()
        val vm = home(saves)
        assertEquals(600, shelf(vm).single().positionSec)
        val readsBefore = reads
        // The player is up; Home resumes as it goes, before its last save
        // has left.
        val player = saves.hold()
        vm.onScreenResumed()
        runFor(200)
        assertEquals("no read while the player's last save is on its way", readsBefore, reads)
        assertEquals(600, shelf(vm).single().positionSec)
        // The player's last save, at 4:10 after starting over, is answered:
        // Home reads it.
        feed = listOf(row(250))
        player.closeAfter(null)
        waitUntil { shelf(vm).single().positionSec == 250 }
        assertEquals(readsBefore + 1, reads)
    }

    @Test
    fun `a movie the player finished leaves the shelf once its last save is answered`() {
        val saves = ProgressSaves()
        val vm = home(saves)
        val player = saves.hold()
        vm.onScreenResumed()
        runFor(100)
        assertEquals(1, shelf(vm).size)
        // Finished: the server drops it from continue watching.
        feed = emptyList()
        player.closeAfter(null)
        waitUntil { shelf(vm).isEmpty() }
    }

    @Test
    fun `with no player up Home reads continue watching at once`() {
        val vm = home(ProgressSaves())
        val readsBefore = reads
        feed = listOf(row(900))
        vm.onScreenResumed()
        waitUntil { shelf(vm).single().positionSec == 900 }
        assertEquals(readsBefore + 1, reads)
    }

    @Test
    fun `Home built anew as the player goes shows the player's last save, not the position before it`() {
        val saves = ProgressSaves()
        // The player is up; its Home button builds Home anew before the
        // player's last save has left.
        val player = saves.hold()
        val vm = built(saves)
        runFor(200)
        assertEquals("no shelf read while the player's last save is on its way", 0, reads)
        assertTrue("the rails are fetched meanwhile", itemReads > 0)
        assertTrue(vm.state.value is LibraryUiState.Loading)
        // The player goes: its last save, at 0:51 after rewinding, leaves,
        // and Home reads once it is answered.
        val answered = CompletableDeferred<Unit>()
        val last = saves.send(CoroutineScope(Dispatchers.Default)) {
            answered.await()
            feed = listOf(row(51))
        }
        player.closeAfter(last)
        runFor(100)
        assertEquals("no shelf read until the last save is answered", 0, reads)
        answered.complete(Unit)
        waitUntil { vm.state.value is LibraryUiState.Ready }
        assertEquals(51, shelf(vm).single().positionSec)
        assertEquals(1, reads)
    }

    @Test
    fun `a movie finished and left with the player's Home button is off the shelf`() {
        val saves = ProgressSaves()
        val player = saves.hold()
        val vm = built(saves)
        runFor(100)
        // Finished: once the last save is answered the server drops it.
        val last = saves.send(CoroutineScope(Dispatchers.Default)) { feed = emptyList() }
        player.closeAfter(last)
        waitUntil { vm.state.value is LibraryUiState.Ready }
        assertTrue(shelf(vm).isEmpty())
    }
}
