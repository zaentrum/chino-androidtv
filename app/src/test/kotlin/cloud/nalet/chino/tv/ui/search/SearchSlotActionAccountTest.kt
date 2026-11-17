package cloud.nalet.chino.tv.ui.search

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.SlotActions
import cloud.nalet.chino.tv.data.SlotButton
import cloud.nalet.chino.tv.data.SlotKind
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.AccountBearers
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.slots.SlotActionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An addon's slot action pressed on Search goes out under the account the
 * screen was opened for, with its bearer, through the client the app sends
 * actions with, also where another account is active by the time it is sent
 * (change P.C16 of the specification).
 */
@RunWith(RobolectricTestRunner::class)
class SearchSlotActionAccountTest {
    private val tokens = mapOf("parent" to "parent-token", "child" to "child-token")

    private val bearers = object : AccountBearers {
        override fun tokenOf(accountId: String): String? = tokens[accountId]
        override fun forceRefreshOf(accountId: String): String? = null
    }

    private fun answer(request: Request, status: Int): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("fake")
        .body("".toResponseBody("application/json".toMediaType()))
        .build()

    /** A chino-api that has nothing to say here. The child is active by the
     *  time anything is sent. */
    private val api: ChinoApi = RetrofitFactory.retrofit(
        "https://media.example/api/",
        RetrofitFactory.httpClient(tokenProvider = { tokens["child"] }, forceRefresh = { null }, accounts = bearers)
            .addInterceptor { chain -> answer(chain.request(), 404) }
            .build(),
    ).create(ChinoApi::class.java)

    /** The actions sent to the addon, which takes each. */
    private val actions = CopyOnWriteArrayList<Request>()

    private val slotActions = SlotActions(
        RetrofitFactory.actionClient(tokenProvider = { tokens["child"] }, forceRefresh = { null }, accounts = bearers)
            .newBuilder()
            .addInterceptor { chain -> actions += chain.request(); answer(chain.request(), 204) }
            .build(),
    )

    private fun eventually(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            check(System.currentTimeMillis() < until) { "never got there" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `P_C16 a slot action pressed on a search opened for the parent goes out with the parent's bearer while the child is active`() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = SearchViewModel(
            api = api,
            streamTokens = StreamTokenManager(api) { ActiveChoice("child", 1) },
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.example/api",
            slotActions = slotActions,
            userFlags = UserFlagsRepository(api, activeChanges = { 0 }) { ActiveChoice("child", 1) },
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            openedFor = { "parent" },
        )
        vm.onSlotAction(SlotButton("notify", SlotKind.Action, "Notify me", "https://media.example/api/portal/apps/sample/notify"))
        eventually { vm.slotActionStates.value["notify"] == SlotActionState.Sent }
        assertEquals("Bearer parent-token", actions.single().header("Authorization"))
    }
}
