package cloud.nalet.chino.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page that reads the position as the player goes reads it after the
 *  player's last save: it waits while a player is up and while a save is on
 *  its way, and reads at once with neither. */
class ProgressSavesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `with no player up and nothing on its way a read goes at once`() = runBlocking {
        val saves = ProgressSaves(waitAtMostMs = 60_000)
        val started = System.nanoTime()
        saves.settled()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun `a read waits for the player to go and its last save to be answered`() = runBlocking {
        val saves = ProgressSaves(waitAtMostMs = 60_000)
        val hold = saves.hold()
        val read = async(Dispatchers.Default) { saves.settled(); "read" }
        yield()
        assertFalse("the player is still up", read.isCompleted)
        // The player goes; its last save is on its way.
        val answer = CompletableDeferred<Unit>()
        val last = saves.send(scope) { answer.await() }
        hold.closeAfter(last)
        Thread.sleep(50)
        assertFalse("the last save is not answered yet", read.isCompleted)
        answer.complete(Unit)
        assertEquals("read", read.await())
    }

    @Test
    fun `a player that saved nothing lets the read go as it goes`() = runBlocking {
        val saves = ProgressSaves(waitAtMostMs = 60_000)
        val hold = saves.hold()
        hold.closeAfter(null)
        saves.settled()
    }

    @Test
    fun `a save never answered keeps a read waiting no longer than the wait`() = runBlocking {
        val saves = ProgressSaves(waitAtMostMs = 100)
        saves.send(scope) { CompletableDeferred<Unit>().await() }
        val started = System.nanoTime()
        saves.settled()
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $waited ms", waited in 100..5_000)
    }

    @Test
    fun `a last save sent after the page's navigation still has the whole wait for its answer`() = runBlocking {
        val saves = ProgressSaves(waitAtMostMs = 1_000)
        val hold = saves.hold()
        val read = async(Dispatchers.Default) { saves.settled(); "read" }
        // Home built anew: the player sends its last save once the
        // navigation has ended, 800 ms on.
        Thread.sleep(800)
        val answer = CompletableDeferred<Unit>()
        val last = saves.send(scope) { answer.await() }
        hold.closeAfter(last)
        // 1.3 s after the read began, past one wait, the save is still on its
        // way for 500 ms: the read waits for it.
        Thread.sleep(500)
        assertFalse("the last save is not answered yet", read.isCompleted)
        answer.complete(Unit)
        assertEquals("read", read.await())
    }

    @Test
    fun `a player that never sends its last save keeps a read waiting no longer than the two waits`() = runBlocking {
        val saves = ProgressSaves(waitAtMostMs = 100)
        saves.hold()
        val started = System.nanoTime()
        saves.settled()
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $waited ms", waited in 200..5_000)
    }
}
