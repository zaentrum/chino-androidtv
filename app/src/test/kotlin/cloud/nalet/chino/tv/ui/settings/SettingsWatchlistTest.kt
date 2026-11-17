package cloud.nalet.chino.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.AccountSettings
import cloud.nalet.chino.tv.data.LastKnownInMemory
import cloud.nalet.chino.tv.data.LastKnownOnDevice
import cloud.nalet.chino.tv.data.SettingsStore
import cloud.nalet.chino.tv.data.api.AccountSettingsChange
import cloud.nalet.chino.tv.data.api.AccountSettingsResponse
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RequestAccount
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.feedback.BugReporter
import cloud.nalet.chino.tv.feedback.FilePendingReportStore
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.ShellScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.math.abs

/**
 * The watchlist group of settings (section 16.4.7 of the specification):
 * Separate movies and shows, after binge watching, only where the server
 * says features.watchlist_kinds; the account's choice on its switch, busy
 * while it is read or saved, the one last known on this device while it is
 * read or where that read failed, and a save that failed said in the banner
 * under it (section 16.8), never in the server's words. The choice is the
 * active account's alone (change P.C16).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SettingsWatchlistTest {
    @get:Rule val compose = createComposeRule()

    /** Each account's choice, as the server keeps it. */
    private val kept = mutableMapOf("parent" to true, "child" to false)
    private var active = "parent"
    private var changes = 0L

    /** What the next save does: answer as kept (null), or fail so. */
    private var saveFails: Throwable? = null

    /** Holds the saves until [answerSave]. */
    private var holdSaves = false
    private var waiting: Continuation<Any?>? = null
    private var waitingChange: Pair<String, Boolean>? = null

    /** The account each save went out under, and its value. */
    private val saves = mutableListOf<Pair<String, Boolean>>()

    /** What the next read does: answer as kept (null), or fail so. */
    private var readFails: Throwable? = null

    /** Holds the reads until [answerRead]. */
    private var holdReads = false
    private var waitingRead: Pair<Continuation<Any?>, String>? = null

    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "accountSettings" -> {
                val account = (args!![0] as RequestAccount).accountId
                if (holdReads) {
                    @Suppress("UNCHECKED_CAST")
                    waitingRead = (args.last() as Continuation<Any?>) to account
                    COROUTINE_SUSPENDED
                } else {
                    readFails?.let { throw it }
                    AccountSettingsResponse(kept.getValue(account))
                }
            }
            "changeAccountSettings" -> {
                val account = (args!![1] as RequestAccount).accountId
                val on = (args[0] as AccountSettingsChange).watchlistByKind
                saves += account to on
                if (holdSaves) {
                    @Suppress("UNCHECKED_CAST")
                    waiting = args.last() as Continuation<Any?>
                    waitingChange = account to on
                    COROUTINE_SUSPENDED
                } else {
                    saveFails?.let { throw it }
                    kept[account] = on
                    AccountSettingsResponse(on)
                }
            }
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw IllegalStateException("not asked here")
        }
    } as ChinoApi

    /** Each account's choice as last known on this device. */
    private val lastKnown = LastKnownInMemory()

    /** Where the saves run: the app's scope, as the container gives it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val accountSettings = AccountSettings(api, appScope, activeChanges = { changes }, lastKnown = lastKnown) {
        ActiveChoice(active, changes)
    }

    private lateinit var viewModel: SettingsViewModel

    /** The page's view model's first state, before any coroutine ran. */
    private lateinit var firstState: ListsByKindSetting

    /** The page's back stack entry: cleared as the page is left. */
    private val entry = ViewModelStore()
    private val onPage = mutableStateOf(true)

    /** Settings open, the server's features known as [offered] - and [asked],
     *  what asking the server answers; [wait]: until the group is drawn. */
    private fun show(offered: Boolean = true, asked: suspend () -> Boolean = { offered }, wait: Boolean = true) {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = ViewModelProvider(
            entry,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                    store = SettingsStore(ctx),
                    telemetry = Telemetry(ctx) { null },
                    bugReporter = BugReporter(api, emptyMap(), FilePendingReportStore(ctx.cacheDir)),
                    serverHost = "media.test",
                    takesReports = { false },
                    subtitleLanguages = { listOf("en", "de") },
                    kindsOffered = asked,
                    kindsOfferedNow = { offered },
                    accountSettings = accountSettings,
                ) as T
            },
        )[SettingsViewModel::class.java]
        firstState = viewModel.listsByKind.value
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ShellScreen {
                    if (onPage.value) SettingsScreen(viewModel = viewModel, serverHost = "media.test", onZapNav = {})
                }
            }
        }
        if (!wait) return
        compose.waitForIdle()
        if (offered) waitFor { exists("Separate movies and shows") }
    }

    /** Settings left: the page goes, and its view model is cleared with it. */
    private fun leave() {
        compose.runOnIdle {
            onPage.value = false
            entry.clear()
        }
        compose.waitForIdle()
    }

    /** Until [done], without a page on the screen. */
    private fun waitOff(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not done in 5 s" }
            idleMain()
            Thread.sleep(10)
        }
    }

    /** Runs the screen until [done]: a read or a save goes off the main
     *  thread for the account store. */
    private fun waitFor(done: () -> Boolean) = compose.waitUntil(timeoutMillis = 5_000) { idleMain(); done() }

    /** Runs what other threads handed back to the main thread: under
     *  Robolectric it runs only when the main thread is idled, and waiting
     *  alone doesn't. */
    private fun idleMain() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /** The held save answered: kept, or failed with [failure]. */
    private fun answerSave(failure: Throwable? = null) {
        val (account, on) = waitingChange!!
        val cont = waiting!!
        waiting = null
        compose.runOnIdle {
            if (failure != null) cont.resumeWith(Result.failure(failure))
            else {
                kept[account] = on
                cont.resumeWith(Result.success(AccountSettingsResponse(on)))
            }
        }
        compose.waitForIdle()
        // Done once the switch is no longer busy, or gone with its group.
        waitFor { !exists("Separate movies and shows") || !viewModel.listsByKind.value.busy }
    }

    /** The held read answered: as kept, or failed with [failure]; [settle]:
     *  until the switch is no longer busy. Which account it was for. */
    private fun answerRead(failure: Throwable? = null, settle: Boolean = true): String {
        waitFor { waitingRead != null }
        val (cont, account) = waitingRead!!
        waitingRead = null
        compose.runOnIdle {
            if (failure != null) cont.resumeWith(Result.failure(failure))
            else cont.resumeWith(Result.success(AccountSettingsResponse(kept.getValue(account))))
        }
        compose.waitForIdle()
        if (settle) waitFor { !viewModel.listsByKind.value.busy }
        return account
    }

    private fun bounds(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInRoot

    /** Separate movies and shows' switch: of the page's on/off buttons, the
     *  one on its line. */
    private fun kindsSwitch(): SemanticsNodeInteraction {
        val line = bounds(compose.onNodeWithText("Separate movies and shows")).center.y
        val labels = listOf("on", "off")
        val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { node -> textOf(node) in labels }
        val nearest = buttons.minBy { abs(it.boundsInRoot.center.y - line) }
        return compose.onNode(androidx.compose.ui.test.SemanticsMatcher("the switch") { it.id == nearest.id })
    }

    private fun textOf(node: SemanticsNode): String? =
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun switchLabel() = textOf(kindsSwitch().fetchSemanticsNode())

    private fun press(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `16_C25 a server that doesn't say features watchlist_kinds gets no watchlist group`() {
        show(offered = false)
        assertTrue(exists("binge watching"))
        assertTrue(!exists("Separate movies and shows"))
        assertTrue(!exists("watchlist"))
    }

    @Test
    fun `16_C25 the group comes after binge watching, the account's choice on its switch and its line under it`() {
        show()
        assertTrue(exists("watchlist"))
        assertTrue(exists(LISTS_BY_KIND_HINT))
        assertEquals("on", switchLabel())
        val binge = bounds(compose.onNodeWithText("binge watching")).top
        val group = bounds(compose.onNodeWithText("watchlist")).top
        val audio = bounds(compose.onNodeWithText("audio")).top
        assertTrue("binge watching $binge, watchlist $group, audio $audio", binge < group && group < audio)
    }

    @Test
    fun `16_C25 a press saves the choice for the account, the switch busy and showing it until it is kept`() {
        show()
        holdSaves = true
        press(kindsSwitch())
        // Busy: it shows the choice, and nothing else.
        assertTrue(viewModel.listsByKind.value.busy)
        assertEquals("off", switchLabel())
        // A press while it is saved does nothing.
        press(kindsSwitch())
        assertEquals(listOf("parent" to false), saves.toList())
        answerSave()
        assertEquals("off", switchLabel())
        assertEquals(false, kept["parent"])
    }

    @Test
    fun `16_8 a save that failed puts the switch back and says so under it until the switch is used again`() {
        show()
        holdSaves = true
        press(kindsSwitch())
        answerSave(failure = java.io.IOException("the server can't be reached"))
        assertEquals("on", switchLabel())
        assertTrue(exists("$LISTS_BY_KIND_SAVE_FAILED Can't reach the server."))
        // Used again: the banner goes, and the save goes out again.
        press(kindsSwitch())
        assertTrue(!exists(LISTS_BY_KIND_SAVE_FAILED, substring = true))
        answerSave()
        assertEquals("off", switchLabel())
    }

    @Test
    fun `16_4 a refusal is a save that failed, said by its status and never in the server's words`() {
        show()
        saveFails = HttpException(
            Response.error<Any>(
                400,
                """{"error":"watchlist_by_kind_invalid","detail":"watchlist_by_kind takes true, false or null"}""".toResponseBody(),
            ),
        )
        press(kindsSwitch())
        waitFor { exists(LISTS_BY_KIND_SAVE_FAILED, substring = true) }
        assertEquals("on", switchLabel())
        assertTrue(exists("$LISTS_BY_KIND_SAVE_FAILED The server didn't accept the request. Check for an app update."))
        assertTrue(!exists("watchlist_by_kind", substring = true))
    }

    @Test
    fun `P_C16 another account chosen takes the group away at once, and a save made before still goes to the account it was made on`() {
        show()
        holdSaves = true
        press(kindsSwitch())
        active = "child"
        changes += 1
        compose.runOnIdle { accountSettings.activeIs("child") }
        compose.waitForIdle()
        assertTrue(!exists("Separate movies and shows"))
        answerSave()
        assertEquals(listOf("parent" to false), saves.toList())
        assertEquals(false, kept["parent"])
        // The child's own choice is never shown as the parent's.
        assertTrue(!exists("Separate movies and shows"))
    }

    @Test
    fun `16_4_7 while the choice is read the switch is busy, shows the one last known on this device and takes no press`() {
        lastKnown.keep("parent", false)
        holdReads = true
        show()
        assertTrue(viewModel.listsByKind.value.busy)
        assertEquals("off", switchLabel())
        press(kindsSwitch())
        assertEquals(emptyList<Pair<String, Boolean>>(), saves.toList())
        // Read: the account's own choice, and the switch works.
        answerRead()
        assertEquals("on", switchLabel())
        assertEquals(true, lastKnown.of("parent"))
    }

    @Test
    fun `16_4_7 with no choice known on this device the switch shows off while it is read`() {
        holdReads = true
        show()
        assertEquals("off", switchLabel())
        answerRead()
        assertEquals("on", switchLabel())
    }

    @Test
    fun `16_4_7 where the read failed the switch shows the choice last known, says nothing and works`() {
        lastKnown.keep("parent", true)
        kept["parent"] = false
        readFails = java.io.IOException("the server can't be reached")
        show()
        waitFor { !viewModel.listsByKind.value.busy }
        assertEquals("on", switchLabel())
        assertTrue(!exists("Couldn't", substring = true))
        // A press saves, as always.
        press(kindsSwitch())
        waitFor { saves.isNotEmpty() && !viewModel.listsByKind.value.busy }
        assertEquals(listOf("parent" to false), saves.toList())
        assertEquals("off", switchLabel())
        assertEquals(false, lastKnown.of("parent"))
    }

    @Test
    fun `16_4_7 the choice last known is kept on the device by account, under a digest and never the account's id`() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = ctx.getSharedPreferences("account_settings_test", android.content.Context.MODE_PRIVATE)
        val onDevice = LastKnownOnDevice(prefs)
        onDevice.keep("parent", true)
        onDevice.keep("child", false)
        assertEquals(true, LastKnownOnDevice(prefs).of("parent"))
        assertEquals(false, LastKnownOnDevice(prefs).of("child"))
        assertNull(LastKnownOnDevice(prefs).of("guest"))
        assertTrue(prefs.all.keys.toString(), prefs.all.keys.none { "parent" in it || "child" in it })
        // An account that leaves the device takes its own with it.
        onDevice.forget("parent")
        assertNull(LastKnownOnDevice(prefs).of("parent"))
        assertEquals(false, LastKnownOnDevice(prefs).of("child"))
        assertEquals(1, prefs.all.size)
    }

    @Test
    fun `16_4_7 after home's warm the group is on settings' first frame with the account's choice, busy while settings read it again`() {
        // Home's warm: the server's features and the account's choice.
        runBlocking { accountSettings.watchlistByKind(fresh = false) }
        holdReads = true
        show(wait = false)
        // From what was known, before any coroutine of the page ran.
        assertEquals(ListsByKindSetting(shown = true, reading = true), firstState)
        // Nothing has answered since: what is drawn is that first state.
        assertTrue(exists("Separate movies and shows"))
        assertTrue(exists(LISTS_BY_KIND_HINT))
        assertEquals("on", switchLabel())
        assertTrue(viewModel.listsByKind.value.busy)
        press(kindsSwitch())
        assertEquals(emptyList<Pair<String, Boolean>>(), saves.toList())
        kept["parent"] = false
        answerRead()
        assertEquals("off", switchLabel())
    }

    @Test
    fun `16_4_7 a read for the account before still out keeps neither the group nor the next account's choice off the first frame`() {
        holdReads = true
        lastKnown.keep("child", true)
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // The parent's read, out.
        background.launch { accountSettings.watchlistByKind(fresh = true) }
        waitOff { waitingRead != null }
        // The child chosen; its home's warm asks for its choice.
        active = "child"
        changes += 1
        accountSettings.activeIs("child")
        background.launch { accountSettings.watchlistByKind(fresh = false) }
        waitOff { accountSettings.watchlistByKind.value == true }
        show(wait = false)
        assertEquals(true, firstState.shown)
        assertTrue(exists("Separate movies and shows"))
        assertEquals("on", switchLabel())
        // The parent's answer is not shown; the child's own come after it.
        assertEquals("parent", answerRead(settle = false))
        assertEquals("on", switchLabel())
        assertEquals("child", answerRead(settle = false))
        assertEquals("child", answerRead())
        assertEquals("off", switchLabel())
        assertEquals(true, lastKnown.of("parent"))
        assertEquals(false, lastKnown.of("child"))
    }

    @Test
    fun `16_4_7 known as offered, the choice is read at once and the switch works while the server is still asked`() {
        // The start: the choice last known on this TV held, asking nothing.
        lastKnown.keep("parent", true)
        runBlocking { accountSettings.holdActive() }
        holdReads = true
        val server = kotlinx.coroutines.CompletableDeferred<Boolean>()
        show(asked = { server.await() }, wait = false)
        assertEquals(ListsByKindSetting(shown = true, reading = true), firstState)
        // Read beside the server's answer, not after it.
        kept["parent"] = false
        answerRead()
        assertEquals("off", switchLabel())
        press(kindsSwitch())
        waitFor { saves.isNotEmpty() && !viewModel.listsByKind.value.busy }
        assertEquals(listOf("parent" to true), saves.toList())
        // The server says it doesn't fill lists by kind after all: the group goes.
        server.complete(false)
        waitFor { !exists("Separate movies and shows") }
    }

    @Test
    fun `16_4_7 a press, then settings left while it is saved - the save still lands`() {
        show()
        holdSaves = true
        press(kindsSwitch())
        assertEquals(listOf("parent" to false), saves.toList())
        leave()
        assertTrue(!exists("Separate movies and shows"))
        answerSave()
        waitOff { lastKnown.of("parent") == false }
        assertEquals(false, kept["parent"])
        assertEquals(false, accountSettings.watchlistByKind.value)
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
