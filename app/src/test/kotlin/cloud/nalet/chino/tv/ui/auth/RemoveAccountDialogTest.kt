package cloud.nalet.chino.tv.ui.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "remove account" dialog on who's watching, worked with the remote: it
 * takes the focus on "cancel" when it opens, the D-pad moves between its two
 * buttons and never out of it, OK never reaches a tile behind it, and BACK
 * closes it with the focus back on the tile that opened it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class RemoveAccountDialogTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val store = AccountStore(
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("remove-account-dialog-test", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() },
    )

    private var picked: Account? = null
    private var added = false
    private val forgotten = mutableListOf<String>()

    private fun account(id: String) = Account(
        id = id, displayName = id.replaceFirstChar(Char::uppercase), email = "", accessToken = "access-of-$id",
        refreshToken = "refresh-of-$id", expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0,
    )

    private fun host(vararg ids: String) {
        runBlocking { ids.forEach { store.addOrUpdate(account(it)) } }
        compose.setContent {
            ChinoTvTheme {
                Remote()
                AccountPickerScreen(
                    accountStore = store,
                    onPicked = { picked = it },
                    onAddAccount = { added = true },
                    forgetAccount = { forgotten += it },
                    onNoneLeft = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The remote's BACK, as a TV sends it: down and up, through the activity. */
    private fun remoteBack() {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    private fun tile(name: String) = compose.onNode(hasText(name) and hasClickAction())
    private fun cancelButton() = compose.onNode(hasText("cancel") and hasClickAction())
    private fun removeButton() = compose.onNode(hasText("remove account") and hasClickAction())

    /** The long press that opens the dialog on [name]'s tile, the focus on it. */
    private fun openOn(name: String) {
        tile(name).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        tile(name).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    @Test
    fun `the dialog takes the focus on cancel, the D-pad stays inside it, and OK on it picks no account`() {
        host("parent", "child")
        openOn("Child")
        cancelButton().assertIsFocused()

        press(Key.DirectionRight)
        removeButton().assertIsFocused()
        // Every way out is closed while it is open: the tiles and the add
        // tile are around and beside it.
        for (key in listOf(Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) {
            press(key)
            removeButton().assertIsFocused()
        }
        press(Key.DirectionLeft)
        cancelButton().assertIsFocused()
        for (key in listOf(Key.DirectionLeft, Key.DirectionUp, Key.DirectionDown)) {
            press(key)
            cancelButton().assertIsFocused()
        }

        // OK presses cancel: the dialog closes, no account is picked or
        // removed, no sign-in starts, the tile that opened it has the focus.
        press(Key.DirectionCenter)
        removeButton().assertDoesNotExist()
        assertNull(picked)
        assertEquals(false, added)
        assertEquals(listOf("child", "parent"), store.snapshotBlocking().accounts.map { it.id }.sorted())
        tile("Child").assertIsFocused()
    }

    @Test
    fun `BACK closes the dialog and leaves the tile that opened it focused`() {
        host("parent", "child")
        openOn("Child")
        cancelButton().assertIsFocused()
        remoteBack()
        removeButton().assertDoesNotExist()
        tile("Child").assertIsFocused()
        assertNull(picked)
    }

    @Test
    fun `remove account, reached with the D-pad, removes the account and focuses the first tile left`() {
        host("parent", "child")
        openOn("Child")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { store.snapshotBlocking().accounts.size == 1 }
        compose.waitForIdle()
        assertEquals(listOf("parent"), store.snapshotBlocking().accounts.map { it.id })
        assertEquals(listOf("child"), forgotten)
        assertNull(picked)
        tile("Parent").assertIsFocused()
    }
}
