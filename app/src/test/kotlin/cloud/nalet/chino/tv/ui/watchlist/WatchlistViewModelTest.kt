package cloud.nalet.chino.tv.ui.watchlist

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.PreferencesRepository
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.Preferences
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.api.WatchlistCounts
import cloud.nalet.chino.tv.data.api.WatchlistDetail
import cloud.nalet.chino.tv.data.api.WatchlistsResponse
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.lang.reflect.Proxy

/** What the hub asks the server for, the setting on and off, and that it
 *  follows the setting while it is kept behind Settings. */
@RunWith(RobolectricTestRunner::class)
class WatchlistViewModelTest {
    private val includes = mutableListOf<String?>()
    private val kindsAsked = mutableListOf<String?>()
    private val itemsAsked = mutableListOf<String>()

    private val api = Proxy.newProxyInstance(ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java)) { _, method, args ->
        when (method.name) {
            "getWatchlists" -> {
                val include = args[0] as String?
                includes += include
                WatchlistsResponse(
                    listOf(
                        Watchlist(
                            id = "l1", name = "Watchlist", itemCount = 3, isDefault = true,
                            counts = WatchlistCounts(movie = 1, series = 2).takeIf { include == "counts" },
                        ),
                    ),
                )
            }
            "getWatchlistDetail" -> {
                val kind = args[1] as String?
                kindsAsked += kind
                val ids = when (kind) {
                    "movie" -> listOf("m1")
                    "series" -> listOf("s1", "e1")
                    else -> listOf("m1", "s1", "e1")
                }
                WatchlistDetail(id = "l1", name = "Watchlist", items = ids)
            }
            "getItem" -> {
                val id = args[0] as String
                itemsAsked += id
                Item(id = id, title = id, kind = if (id.startsWith("m")) "movie" else "series")
            }
            "mintStreamToken" -> StreamTokenResponse(token = "t")
            else -> throw IOException("no ${method.name} here")
        }
    } as ChinoApi

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun viewModel(offered: Boolean, preferences: PreferencesRepository): WatchlistViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        return WatchlistViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            userFlags = UserFlagsRepository(api),
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test",
            offersByKind = { offered },
            preferences = preferences,
        ).also { idle() }
    }

    /** A server that answers with what was last saved. */
    private fun preferences(byKind: Boolean): PreferencesRepository {
        var stored = Preferences(watchlistByKind = byKind)
        return PreferencesRepository(
            load = { stored },
            save = { patch -> Preferences(patch.watchlistByKind).also { stored = it } },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun ready(vm: WatchlistViewModel) = vm.state.value as WatchlistUiState.Ready

    @Test
    fun `off, the hub asks as it did, without counts or kinds`() {
        val vm = viewModel(offered = true, preferences = preferences(byKind = false))
        val s = ready(vm)
        assertFalse(s.byKind)
        assertEquals(listOf("m1", "s1", "e1"), s.shelves.getValue("l1").map { it.id })
        // The bookmarks' cache asks for the lists too, never with counts.
        assertFalse("counts" in includes)
        assertTrue(kindsAsked.all { it == null })
    }

    @Test
    fun `on, each list's rows are asked for by kind, with its counts`() {
        val vm = viewModel(offered = true, preferences = preferences(byKind = true))
        val s = ready(vm)
        assertTrue(s.byKind)
        assertEquals(listOf("m1"), s.kindRows.getValue("l1").movies.map { it.id })
        assertEquals(listOf("s1", "e1"), s.kindRows.getValue("l1").shows.map { it.id })
        assertEquals(WatchlistCounts(movie = 1, series = 2), s.lists.single().counts)
        assertEquals("counts", includes.last())
        assertTrue("movie" in kindsAsked && "series" in kindsAsked)
    }

    @Test
    fun `a server that doesn't offer it draws the hub as off, and isn't asked`() {
        var asked = 0
        val repo = PreferencesRepository(
            load = { asked++; Preferences(watchlistByKind = true) },
            save = { Preferences(it.watchlistByKind) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val s = ready(viewModel(offered = false, preferences = repo))
        assertFalse(s.byKind)
        assertEquals(0, asked)
        assertFalse("counts" in includes)
    }

    @Test
    fun `the setting changed in Settings redraws the hub kept behind it`() {
        val repo = preferences(byKind = false)
        val vm = viewModel(offered = true, preferences = repo)
        assertFalse(ready(vm).byKind)
        repo.setWatchlistByKind(true)
        idle()
        assertTrue(ready(vm).byKind)
        assertEquals(listOf("m1"), ready(vm).kindRows.getValue("l1").movies.map { it.id })
        repo.setWatchlistByKind(false)
        idle()
        assertFalse(ready(vm).byKind)
    }

    @Test
    fun `see all opens a row, and going back forgets it`() {
        val vm = viewModel(offered = true, preferences = preferences(byKind = true))
        vm.openList("l1", KindRow.Shows)
        idle()
        assertEquals(KindRow.Shows, vm.openRow.value)
        assertEquals(listOf("m1", "s1", "e1"), vm.openItems.value.map { it.id })
        vm.closeList()
        idle()
        assertNull(vm.openRow.value)
    }
}
