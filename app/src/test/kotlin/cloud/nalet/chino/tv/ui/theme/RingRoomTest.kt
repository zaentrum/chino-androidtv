package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.NoticesRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.detail.DetailActions
import cloud.nalet.chino.tv.ui.library.TvTopBar
import cloud.nalet.chino.tv.ui.notices.NoticesBell
import cloud.nalet.chino.tv.ui.player.PlayerTopChrome
import cloud.nalet.chino.tv.ui.settings.SettingButtons
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ring room (the design system's tv focus): 12 dp between a focused
 * control's GROWN ring - its growth and the ring outside its 2 dp gap - and
 * the next control, measured in the TV's dp (2 px each on 1920 × 1080): the
 * title page's row, Settings' buttons, the player's Back and Home, the top
 * bar's bell and account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class RingRoomTest {
    @get:Rule val compose = createComposeRule()

    /** A control's bounds in the TV's dp. */
    private fun bounds(matcher: SemanticsMatcher): Rect {
        val px = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        return Rect(px.left / 2f, px.top / 2f, px.right / 2f, px.bottom / 2f)
    }

    /** How far past its edge a focused control's style reaches: half its
     *  growth, and a ring outside its gap; a part of add to list, a row,
     *  draws its ring inside. */
    private fun reach(r: Rect, ringOutside: Boolean): Float {
        val growth = if (ringOutside) 1.05f else 1.02f
        return r.width * (growth - 1f) / 2f + if (ringOutside) (2f + 3f) * growth else 0f
    }

    private fun assertRingRoom(controls: List<Pair<String, Pair<SemanticsMatcher, Boolean>>>) {
        val placed = controls.map { (name, m) -> Triple(name, bounds(m.first), m.second) }.sortedBy { it.second.left }
        for ((a, b) in placed.zipWithNext()) {
            val gap = b.second.left - a.second.right
            val room = gap - maxOf(reach(a.second, a.third), reach(b.second, b.third))
            assertTrue("${a.first} to ${b.first}: $gap dp apart, $room dp past a grown ring", room >= 12f - 0.01f)
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { ChinoTvTheme { content() } }
        compose.waitForIdle()
    }

    @Test
    fun `the title row - 12 dp from a grown ring to the next control`() {
        show {
            DetailActions(
                item = Item(id = "p1", title = "Quiet Orchard", kind = "movie", year = 2021),
                primaryLabel = "resume 1:43",
                canResume = true,
                inWatchlist = false,
                liked = false,
                onPlay = {},
                onToggleWatchlist = {},
                onOpenListPicker = {},
                onToggleLike = {},
                onToggleWatched = {},
                onTrailerLaunch = {},
                onPlayTrailer = {},
            )
        }
        assertRingRoom(
            listOf(
                "resume" to (hasText("resume 1:43") to true),
                "start over" to (hasText("start over") to true),
                "add to Watchlist" to (hasContentDescription("add to Watchlist") to false),
                "mark as watched" to (hasContentDescription("mark as watched") to true),
                "like" to (hasContentDescription("like") to true),
            ),
        )
    }

    @Test
    fun `Settings' buttons - 12 dp from a grown ring to the next`() {
        show {
            androidx.compose.foundation.layout.Box(Modifier.width(800.dp)) {
                SettingButtons {
                    ChinoButton(onClick = {}) { Text("see your account") }
                    ChinoButton(onClick = {}) { Text("switch account") }
                    ChinoButton(onClick = {}) { Text("delete account") }
                }
            }
        }
        assertRingRoom(
            listOf("see your account", "switch account", "delete account").map { it to (hasText(it) to true) },
        )
    }

    @Test
    fun `the player's Back and Home - 12 dp from a grown ring to the next`() {
        show {
            androidx.compose.foundation.layout.Box(Modifier.width(900.dp)) {
                PlayerTopChrome(title = "Quiet Orchard", onBack = {}, onUserInteraction = {}, onHome = {})
            }
        }
        assertRingRoom(listOf("Back", "Home").map { it to ((hasContentDescription(it) and hasClickAction()) to true) })
    }

    @Test
    fun `the top bar's bell and account - 12 dp from a grown ring to the next`() {
        // The bell shows once the server answers for the account's notices.
        val api = java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "notices" -> Json.parseToJsonElement("""{"notices":[],"unread":0,"available":true}""")
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "the server"
                else -> throw java.io.IOException("not asked here")
            }
        } as ChinoApi
        val account = Account(
            id = "u-1", displayName = "Robin Vale", email = "", accessToken = "t", refreshToken = null,
            expiresAtEpochMillis = 0L, lastUsedAt = 0L,
        )
        val bell = NoticesBell(NoticesRepository(api = { api }, activeAccount = { "u-1" }), open = {})
        show {
            androidx.compose.foundation.layout.Box(Modifier.width(900.dp)) {
                TvTopBar(noticesBell = bell, activeAccount = account, onAccountClick = {})
            }
        }
        val bellNode = hasContentDescription("Notices") and hasClickAction()
        compose.waitUntil(5_000) { compose.onAllNodes(bellNode).fetchSemanticsNodes().isNotEmpty() }
        assertRingRoom(listOf("bell" to (bellNode to true), "account" to ((hasText("RV") and hasClickAction()) to true)))
    }
}
