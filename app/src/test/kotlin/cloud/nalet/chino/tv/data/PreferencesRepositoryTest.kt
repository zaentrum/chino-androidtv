package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.Preferences
import cloud.nalet.chino.tv.data.api.PreferencesPatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The account's settings: a change shows at once, and goes back with a line
 *  if it can't be saved. */
class PreferencesRepositoryTest {
    private val on = Preferences(watchlistByKind = true)
    private val off = Preferences(watchlistByKind = false)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun repository(
        load: suspend () -> Preferences = { off },
        save: suspend (PreferencesPatch) -> Preferences = { Preferences(it.watchlistByKind) },
    ) = PreferencesRepository(load, save, scope)

    @Test
    fun `nothing is known until the server answers, and no answer changes nothing`() = runBlocking {
        var fail = true
        val repo = repository(load = { if (fail) throw IOException("no connection") else on })
        assertNull(repo.preferences.value)
        repo.refresh()
        assertNull(repo.preferences.value)
        fail = false
        repo.refresh()
        assertEquals(on, repo.preferences.value)
        fail = true
        repo.refresh()
        assertEquals(on, repo.preferences.value)
    }

    @Test
    fun `a change shows before the server answers`() = runBlocking {
        val answer = CompletableDeferred<Preferences>()
        val sent = mutableListOf<PreferencesPatch>()
        val repo = repository(save = { sent += it; answer.await() })
        repo.refresh()
        repo.setWatchlistByKind(true)
        assertEquals(on, repo.preferences.value)
        answer.complete(on)
        assertEquals(on, repo.preferences.value)
        assertEquals(listOf(PreferencesPatch(watchlistByKind = true)), sent)
        assertFalse(repo.saveFailed.value)
    }

    @Test
    fun `a change that can't be saved goes back and says so, until the next change`() = runBlocking {
        var fail = true
        val repo = repository(save = { if (fail) throw IOException("no connection") else Preferences(it.watchlistByKind) })
        repo.refresh()
        repo.setWatchlistByKind(true)
        assertEquals(off, repo.preferences.value)
        assertTrue(repo.saveFailed.value)
        fail = false
        repo.setWatchlistByKind(true)
        assertEquals(on, repo.preferences.value)
        assertFalse(repo.saveFailed.value)
    }

    @Test
    fun `changes made quickly end with the last, sent in order`() = runBlocking {
        val first = CompletableDeferred<Preferences>()
        val sent = mutableListOf<Boolean>()
        val repo = repository(save = { patch ->
            sent += patch.watchlistByKind
            if (sent.size == 1) first.await() else Preferences(patch.watchlistByKind)
        })
        repo.refresh()
        repo.setWatchlistByKind(true)
        repo.setWatchlistByKind(false)
        // The first is on its way; the second waits its turn and shows.
        assertEquals(off, repo.preferences.value)
        first.complete(on)
        assertEquals(off, repo.preferences.value)
        assertEquals(listOf(true, false), sent)
    }

    @Test
    fun `a change replaced before its turn is never sent`() = runBlocking {
        val first = CompletableDeferred<Preferences>()
        val sent = mutableListOf<Boolean>()
        val repo = repository(save = { patch ->
            sent += patch.watchlistByKind
            if (sent.size == 1) first.await() else Preferences(patch.watchlistByKind)
        })
        repo.refresh()
        repo.setWatchlistByKind(true)
        repo.setWatchlistByKind(false)
        repo.setWatchlistByKind(true)
        first.complete(on)
        assertEquals(on, repo.preferences.value)
        assertEquals(listOf(true, true), sent)
    }

    @Test
    fun `an older change failing doesn't undo a newer one`() = runBlocking {
        val first = CompletableDeferred<Preferences>()
        var saves = 0
        val repo = repository(save = { patch ->
            if (++saves == 1) first.await() else Preferences(patch.watchlistByKind)
        })
        repo.refresh()
        repo.setWatchlistByKind(true)
        repo.setWatchlistByKind(false)
        first.completeExceptionally(IOException("no connection"))
        assertEquals(off, repo.preferences.value)
        assertFalse(repo.saveFailed.value)
    }

    @Test
    fun `an answer read before a change doesn't overwrite it`() = runBlocking {
        val answer = CompletableDeferred<Preferences>()
        val started = CompletableDeferred<Unit>()
        val repo = repository(load = { started.complete(Unit); answer.await() })
        val refresh = scope.launch { repo.refresh() }
        started.await()
        repo.setWatchlistByKind(true)
        answer.complete(off)
        refresh.join()
        assertEquals(on, repo.preferences.value)
    }

    @Test
    fun `signing out forgets the setting and the failure`() = runBlocking {
        val repo = repository(save = { throw IOException("no connection") }, load = { on })
        repo.refresh()
        repo.setWatchlistByKind(false)
        assertTrue(repo.saveFailed.value)
        repo.clear()
        assertNull(repo.preferences.value)
        assertFalse(repo.saveFailed.value)
    }
}
