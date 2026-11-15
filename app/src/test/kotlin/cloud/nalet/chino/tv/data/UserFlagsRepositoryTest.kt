package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.UserFlagList
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.api.WatchlistDetail
import cloud.nalet.chino.tv.data.api.WatchlistsResponse
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The lists and likes the app shows are the active account's, and nobody
 * else's (change P.C16 of the specification): another account chosen during a
 * warm or after it, a list read again across a change, a toggle that fails
 * after one.
 *
 * The fake chino-api answers for the account whose bearer the request
 * carries: the one active when the request goes out.
 */
class UserFlagsRepositoryTest {
    @Volatile private var active: String? = "parent"
    @Volatile private var changes = 0L

    private fun choose(account: String?) {
        if (account != active) changes += 1
        active = account
    }

    /** Runs as a request of [name] goes out, before it is answered. */
    private var onRequest: (name: String) -> Unit = {}

    /** Holds the like requests until released; they fail then where [likeFails]. */
    private var likeGate: CountDownLatch? = null
    private var likeFails = false

    private val api: ChinoApi = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, args ->
        onRequest(method.name)
        val who = active
        when (method.name) {
            "getWatchlists" -> WatchlistsResponse(listOf(Watchlist(id = "list-of-$who", name = "Watchlist", isDefault = true)))
            "getWatchlistDetail" -> WatchlistDetail(
                id = args!![0] as String, name = "Watchlist", isDefault = true,
                items = listOf("saved-by-${(args[0] as String).removePrefix("list-of-")}"),
            )
            "getLikes" -> UserFlagList(listOf("liked-by-$who"))
            "addLike", "removeLike" -> {
                likeGate?.await(5, TimeUnit.SECONDS)
                if (likeFails) throw IOException("the server can't be reached")
                Unit
            }
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "chino-api"
            else -> throw IOException("not asked here")
        }
    } as ChinoApi

    private val job = SupervisorJob()
    private val repo = UserFlagsRepository(api, CoroutineScope(job + Dispatchers.IO)) { ActiveChoice(active, changes) }

    private fun warm() = runBlocking { repo.warm() }

    /** Waits for the toggles and reloads started so far, their undoing included. */
    private fun settle() = runBlocking { job.children.toList().joinAll() }

    private fun assertShowsNothing() {
        assertEquals(emptyList<Watchlist>(), repo.lists.value)
        assertEquals(emptySet<String>(), repo.watchlist.value)
        assertEquals(emptySet<String>(), repo.savedItems.value)
        assertEquals(emptyMap<String, Set<String>>(), repo.memberships.value)
        assertEquals(emptySet<String>(), repo.likes.value)
    }

    @Test
    fun `P_C16 the lists and likes shown are the active account's`() {
        warm()
        assertEquals(setOf("saved-by-parent"), repo.watchlist.value)
        assertEquals(setOf("liked-by-parent"), repo.likes.value)
        assertEquals(listOf("list-of-parent"), repo.lists.value.map { it.id })
    }

    @Test
    fun `P_C16 another account chosen after a warm - the one before's go at once, and the next warm loads its own`() {
        warm()
        choose("child")
        repo.activeIs("child")
        assertShowsNothing()
        warm()
        assertEquals(setOf("saved-by-child"), repo.watchlist.value)
        assertEquals(setOf("liked-by-child"), repo.likes.value)
    }

    @Test
    fun `the account chosen again is the same one - what it has is kept`() {
        warm()
        repo.activeIs("parent")
        assertEquals(setOf("liked-by-parent"), repo.likes.value)
    }

    @Test
    fun `P_C16 another account chosen during a warm - nothing read is shown, not even before the warm ends`() {
        var shownDuringWarm: List<Any> = emptyList()
        onRequest = { name ->
            if (name == "getLikes") {
                // The lists have been read by now: none of them is shown yet.
                shownDuringWarm = listOf(repo.lists.value, repo.watchlist.value, repo.savedItems.value)
                choose("child")
            }
        }
        warm()
        assertEquals(listOf(emptyList<Watchlist>(), emptySet<String>(), emptySet<String>()), shownDuringWarm)
        assertShowsNothing()
        onRequest = {}
        warm()
        assertEquals(setOf("saved-by-child"), repo.watchlist.value)
        assertEquals(setOf("liked-by-child"), repo.likes.value)
    }

    @Test
    fun `P_C16 another account chosen and the first chosen again during a warm - nothing read is shown`() {
        var once = true
        onRequest = { name ->
            if (name == "getWatchlistDetail" && once) {
                once = false
                choose("child")
                choose("parent")
            }
        }
        warm()
        assertShowsNothing()
        warm()
        assertEquals(setOf("saved-by-parent"), repo.watchlist.value)
        assertEquals(setOf("liked-by-parent"), repo.likes.value)
    }

    @Test
    fun `P_C16 another account chosen while a read for the one before holds the cache - its lists go at once`() {
        warm()
        // A reload for the parent waits in the server, holding the cache.
        val inRead = CountDownLatch(1)
        val answer = CountDownLatch(1)
        onRequest = { name ->
            if (name == "getWatchlists") {
                inRead.countDown()
                answer.await(5, TimeUnit.SECONDS)
            }
        }
        repo.refreshLists()
        assertTrue(inRead.await(5, TimeUnit.SECONDS))
        choose("child")
        // Waits for nothing: the screens show nothing of the parent while the read goes on.
        repo.activeIs("child")
        assertShowsNothing()
        answer.countDown()
        settle()
        assertShowsNothing()
        onRequest = {}
        warm()
        assertEquals(setOf("liked-by-child"), repo.likes.value)
    }

    @Test
    fun `P_C16 lists read again across another account chosen are not shown`() {
        warm()
        onRequest = { name -> if (name == "getWatchlists") choose("child") }
        repo.refreshLists()
        settle()
        assertEquals(listOf("list-of-parent"), repo.lists.value.map { it.id })
        assertTrue(repo.watchlist.value.none { it.endsWith("child") })
    }

    @Test
    fun `P_C16 an unlike that fails after another account is chosen is not undone into the new account's likes`() {
        warm()
        likeGate = CountDownLatch(1)
        likeFails = true
        repo.setLike("liked-by-parent", present = false)
        choose("child")
        repo.activeIs("child")
        warm()
        likeGate!!.countDown()
        settle()
        assertEquals(setOf("liked-by-child"), repo.likes.value)
    }

    @Test
    fun `a like that fails while the account stays is undone`() {
        warm()
        likeFails = true
        repo.setLike("liked-on-parent", present = true)
        settle()
        assertEquals(setOf("liked-by-parent"), repo.likes.value)
    }
}
