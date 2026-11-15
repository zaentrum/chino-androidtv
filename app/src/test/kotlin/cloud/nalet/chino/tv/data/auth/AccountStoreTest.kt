package cloud.nalet.chino.tv.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.concurrent.thread

/**
 * The account store's choices and its lock (change P.C16 of the
 * specification), over plain preferences: the app's are encrypted, which
 * Robolectric has no key store for.
 */
@RunWith(RobolectricTestRunner::class)
class AccountStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("account-store-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }
    private val store = AccountStore(prefs)

    private fun account(id: String, token: String = "access-of-$id") = Account(
        id = id, displayName = id, email = "", accessToken = token, refreshToken = "refresh-of-$id",
        expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0,
    )

    private fun signedIn(vararg ids: String) = runBlocking {
        ids.forEach { store.addOrUpdate(account(it)) }
        store.setActive(ids.first())
    }

    @Test
    fun `P_C16 each change of the active account is another choice, one that is undone again too`() {
        signedIn("parent", "child")
        val first = store.activeChoiceBlocking()
        assertEquals("parent", first.accountId)
        runBlocking {
            store.setActive("child")
            store.setActive("parent")
        }
        val again = store.activeChoiceBlocking()
        assertEquals("parent", again.accountId)
        assertNotEquals(first, again)
    }

    @Test
    fun `the same account chosen again, a token renewed, another account added - the same choice`() {
        signedIn("parent", "child")
        val first = store.activeChoiceBlocking()
        runBlocking {
            store.setActive("parent")
            store.addOrUpdate(account("guest"))
        }
        store.updateTokensForBlocking("parent", Tokens(accessToken = "renewed", refreshToken = null, expiresAtEpochMillis = 1))
        assertEquals(first, store.activeChoiceBlocking())
    }

    @Test
    fun `P_C16 the active account removed is another choice, another one removed is not`() {
        signedIn("parent", "child", "guest")
        val first = store.activeChoiceBlocking()
        runBlocking { store.remove("guest") }
        assertEquals(first, store.activeChoiceBlocking())
        runBlocking { store.remove("parent") }
        val after = store.activeChoiceBlocking()
        assertEquals("child", after.accountId)
        assertNotEquals(first.changes, after.changes)
    }

    @Test
    fun `P_C16 a token renewed while another account is chosen never makes the one before the active one again`() {
        signedIn("parent", "child")
        var last = "parent"
        val choosing = thread {
            runBlocking {
                repeat(300) { i ->
                    last = if (i % 2 == 0) "child" else "parent"
                    store.setActive(last)
                }
            }
        }
        val renewing = thread {
            repeat(300) { i ->
                store.updateTokensForBlocking(
                    "parent",
                    Tokens(accessToken = "renewed-$i", refreshToken = null, expiresAtEpochMillis = Long.MAX_VALUE),
                )
            }
        }
        choosing.join()
        renewing.join()
        val now = store.snapshotBlocking()
        assertEquals(last, now.activeAccount?.id)
        assertEquals("renewed-299", now.accounts.first { it.id == "parent" }.accessToken)
        assertEquals(2, now.accounts.size)
    }
}
