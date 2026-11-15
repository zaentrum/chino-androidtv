package cloud.nalet.chino.tv.data.auth

import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A stream token carries its account's rights, an age cap among them, for six
 * hours. It is kept for the account it was minted for alone (change P.C16 of
 * the specification): when another account is chosen on who's watching, the
 * next streams and pictures are asked for with a token of that account.
 *
 * The fake chino-api mints for the account whose bearer the request carries,
 * [bearer], read apart from the choice the manager labels the token with, as
 * the HTTP client reads it on its own thread.
 */
class StreamTokenManagerTest {
    /** Who is active, and how often the active account changed: [choose]
     *  counts as the account store does. */
    private var active: String? = "parent"
    private var changes = 0L

    private fun choose(account: String?) {
        if (account != active) changes += 1
        active = account
    }

    private val choice = { ActiveChoice(active, changes) }
    private var minted = 0
    private var lifetimeSeconds = 6L * 60 * 60

    /** Whose bearer the next request carries: read when it goes out. */
    private var bearer: () -> String? = { active }

    private fun mintFor(account: String?): StreamTokenResponse {
        minted += 1
        return StreamTokenResponse(
            token = "token-of-$account-$minted",
            expiresAt = Instant.now().plusSeconds(lifetimeSeconds).toString(),
        )
    }

    private val manager = StreamTokenManager(activeChoice = choice, mint = { mintFor(bearer()) })

    @Test
    fun `P_C16 a token is minted once for an account and kept while it is active`() {
        assertEquals("token-of-parent-1", manager.valid())
        assertEquals("token-of-parent-1", manager.valid())
        assertEquals(1, minted)
    }

    @Test
    fun `P_C16 the next account chosen gets a token of its own, not the one before`() {
        assertEquals("token-of-parent-1", manager.valid())
        choose("child")
        assertEquals("token-of-child-2", manager.valid())
        choose("parent")
        assertEquals("token-of-parent-3", manager.valid())
    }

    @Test
    fun `P_C16 a token asked for while another account is chosen is neither kept nor handed out`() {
        // The account is read as the parent; the request then goes out with the
        // child's bearer: the switch lands in between.
        var switchOnce = true
        bearer = {
            if (switchOnce) {
                switchOnce = false
                choose("child")
            }
            active
        }
        // The token minted with the child's bearer under the parent's name is
        // dropped; the child gets one asked for as the child.
        assertEquals("token-of-child-2", manager.valid())
        assertEquals("token-of-child-2", manager.valid())
        choose("parent")
        assertEquals("token-of-parent-3", manager.valid())
        assertEquals(3, minted)
    }

    @Test
    fun `P_C16 an account that changes on every request gets no token at all`() {
        bearer = {
            choose(if (active == "parent") "child" else "parent")
            active
        }
        assertThrows(IllegalStateException::class.java) { manager.valid() }
        // Nothing was kept: the next request, with nobody switching, mints anew.
        bearer = { active }
        val before = minted
        manager.valid()
        assertEquals(before + 1, minted)
    }

    @Test
    fun `P_C16 another account chosen and left again while a token is asked for, it is asked for again`() {
        // The parent's request goes out with the child's bearer, and the parent
        // is chosen again before it comes back: the same account, another choice.
        var switchOnce = true
        bearer = {
            if (switchOnce) {
                switchOnce = false
                choose("child")
                val was = active
                choose("parent")
                was
            } else {
                active
            }
        }
        assertEquals("token-of-parent-2", manager.valid())
        assertEquals("token-of-parent-2", manager.valid())
        assertEquals(2, minted)
    }

    @Test
    fun `P_C16 two threads, one asking while another account is chosen, both get the new account's token`() {
        val inRequest = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val waiting = StreamTokenManager(
            activeChoice = choice,
            mint = {
                inRequest.countDown()
                // The bearer is read once the request goes out: after the switch.
                answer.await(5, TimeUnit.SECONDS)
                synchronized(this) { mintFor(active) }
            },
        )
        var first: String? = null
        var second: String? = null
        val t1 = thread { first = waiting.valid() }
        assertTrue(inRequest.await(5, TimeUnit.SECONDS))
        choose("child")
        val t2 = thread { second = waiting.valid() }
        answer.countDown()
        t1.join(5_000)
        t2.join(5_000)
        assertFalse(t1.isAlive || t2.isAlive)
        // The parent's request came back with the child's token: it was dropped,
        // and the child's own one is what both threads got.
        assertEquals("token-of-child-2", first)
        assertEquals("token-of-child-2", second)
        choose("parent")
        assertEquals("token-of-parent-3", waiting.valid())
    }

    @Test
    fun `P_C16 a request that fails after a switch leaves nobody's token behind`() {
        var fail = false
        val failing = StreamTokenManager(
            activeChoice = choice,
            mint = {
                if (fail) throw IOException("the server can't be reached")
                mintFor(active)
            },
        )
        assertEquals("token-of-parent-1", failing.valid())
        choose("child")
        fail = true
        assertThrows(IOException::class.java) { failing.valid() }
        // Back to the parent: its token was dropped before the failed request.
        choose("parent")
        fail = false
        assertEquals("token-of-parent-2", failing.valid())
    }

    @Test
    fun `P_C16 clearing forgets the token, and the next one is minted anew`() {
        assertEquals("token-of-parent-1", manager.valid())
        runBlocking { manager.clear() }
        assertEquals("token-of-parent-2", manager.valid())
    }

    @Test
    fun `a token is minted anew within five minutes of running out`() {
        lifetimeSeconds = 4 * 60
        assertEquals("token-of-parent-1", manager.valid())
        assertEquals("token-of-parent-2", manager.valid())
    }

    @Test
    fun `a token minted while nobody is active is nobody's`() {
        choose(null)
        assertEquals("token-of-null-1", manager.valid())
        assertEquals("token-of-null-1", manager.valid())
        choose("parent")
        assertEquals("token-of-parent-2", manager.valid())
    }
}
