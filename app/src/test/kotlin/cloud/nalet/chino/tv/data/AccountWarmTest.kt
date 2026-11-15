package cloud.nalet.chino.tv.data

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app-start Zap warm is the active account's alone (change P.C16 of the
 * specification): once per account; stopped, with the downloads it handed on,
 * when another account is active; and nothing handed on for the account before
 * after another is chosen.
 */
class AccountWarmTest {
    @Volatile private var active: String? = "parent"

    /** What the warms handed on, by account. */
    private val handed = CopyOnWriteArrayList<String>()
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    @Volatile private var stops = 0
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e },
    )
    private val warm = AccountWarm(scope, activeAccount = { active }, stopHandedOn = { stops += 1 })

    /** Opens once the last warm started has begun its work. */
    private var started = CountDownLatch(1)

    /** A warm that hands on the account it ran for once [go] opens. */
    private fun startHandingOn(go: CountDownLatch? = null, result: (Boolean) -> Unit = {}): CountDownLatch {
        val done = CountDownLatch(1)
        val account = active
        started = CountDownLatch(1)
        val began = started
        warm.start { handOver ->
            began.countDown()
            // Not cancellable, as the feed's request is not: what stops it is
            // the hand-over refused.
            go?.await(5, TimeUnit.SECONDS)
            result(handOver { handed += account!! })
            done.countDown()
        }
        return done
    }

    @Test
    fun `P_C16 the warm runs once per account`() {
        assertTrue(startHandingOn().await(5, TimeUnit.SECONDS))
        startHandingOn()
        Thread.sleep(100)
        assertEquals(listOf("parent"), handed.toList())
        active = "child"
        assertTrue(startHandingOn().await(5, TimeUnit.SECONDS))
        assertEquals(listOf("parent", "child"), handed.toList())
    }

    @Test
    fun `P_C16 the next account's warm stops the downloads of the one before`() {
        assertTrue(startHandingOn().await(5, TimeUnit.SECONDS))
        assertEquals(0, stops)
        active = "child"
        assertTrue(startHandingOn().await(5, TimeUnit.SECONDS))
        // Stopping also forgets which titles were warmed: the child's first card
        // is warmed even where it is the same title as the parent's.
        assertEquals(1, stops)
        assertEquals(listOf("parent", "child"), handed.toList())
    }

    @Test
    fun `P_C16 another account active stops the warm of the one before, and nothing is handed on after`() {
        val go = CountDownLatch(1)
        var handedOver: Boolean? = null
        val done = startHandingOn(go) { handedOver = it }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        active = "child"
        warm.activeIs("child")
        assertEquals(1, stops)
        go.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(false, handedOver)
        assertEquals(emptyList<String>(), handed.toList())
    }

    @Test
    fun `P_C16 another account chosen while the warm read its card - nothing is handed on for the one before`() {
        val go = CountDownLatch(1)
        var handedOver: Boolean? = null
        val done = startHandingOn(go) { handedOver = it }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // Chosen, and the change not yet followed: the hand-over reads again.
        active = "child"
        go.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(false, handedOver)
        assertEquals(emptyList<String>(), handed.toList())
    }

    @Test
    fun `the same account active again stops nothing`() {
        assertTrue(startHandingOn().await(5, TimeUnit.SECONDS))
        warm.activeIs("parent")
        assertEquals(0, stops)
    }

    @Test
    fun `an account store that cannot be read starts nothing and breaks nothing`() {
        val failing = AccountWarm(scope, activeAccount = { error("the store can't be read") }, stopHandedOn = {})
        var ran = false
        failing.start { ran = true }
        Thread.sleep(100)
        assertFalse(ran)
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }
}
