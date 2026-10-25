package cloud.nalet.chino.tv.ui.library

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.theme.ChinoMonoFamily
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A card's menu, as chino-web's (docs/chino, title actions): "play", or
 * "resume" where the card has a position; the watched toggle in the title
 * page's words, "mark as watched" and "watched"; "remove from continue
 * watching" - menu items, in the mono face and lowercase.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardMenuTest {
    @get:Rule val compose = createComposeRule()

    private val movie = Item(id = "m1", title = "Quiet Orchard", kind = "movie")
    private val show = Item(id = "s1", title = "Paper Station", kind = "series")

    @Test
    fun `the words - play or resume, the watched toggle's state, remove from continue watching`() {
        assertEquals("play", playCardAction(resumes = false) {}.label)
        assertEquals("resume", playCardAction(resumes = true) {}.label)
        assertEquals("mark as watched", watchedCardAction(watched = false) {}.label)
        assertEquals("watched", watchedCardAction(watched = true) {}.label)
        assertEquals("remove from continue watching", REMOVE_FROM_CONTINUE_WATCHING)
    }

    @Test
    fun `a movie's card plays from its menu, a show's names its watched toggle alone`() {
        var played: String? = null
        var marked: Boolean? = null
        val actions = cardActions(movie, onPlay = { played = it }, watched = false) { marked = it }
        assertEquals(listOf("play", "mark as watched"), actions.map { it.label })
        actions[0].onClick()
        actions[1].onClick()
        assertEquals("m1", played)
        assertEquals(true, marked)
        assertEquals(listOf("watched"), cardActions(show, onPlay = {}, watched = true) {}.map { it.label })
        // A surface without a player to open: the toggle alone.
        assertEquals(listOf("mark as watched"), cardActions(movie, onPlay = null, watched = false) {}.map { it.label })
        assertEquals(CARD_OPTIONS_HINT, cardMenuHint(movie))
        assertEquals("Hold OK to mark watched", cardMenuHint(show))
    }

    @Test
    fun `the menu's items are in the mono face`() {
        compose.setContent {
            ChinoTvTheme {
                CardActionsMenu(
                    title = "Quiet Orchard",
                    actions = listOf(
                        playCardAction(resumes = true) {},
                        watchedCardAction(watched = false) {},
                        CardAction(icon = Lucide.X, label = REMOVE_FROM_CONTINUE_WATCHING, destructive = true) {},
                    ),
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        for (label in listOf("resume", "mark as watched", "remove from continue watching")) {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label)).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            assertEquals(label, ChinoMonoFamily, results.single().layoutInput.style.fontFamily)
        }
    }

    @Test
    fun `no label is cut - the menu is as wide as its longest, a long title wraps`() {
        compose.setContent {
            ChinoTvTheme {
                CardActionsMenu(
                    title = "The Lighthouse Keeper's Very Long Winter Journey Home Across the Northern Sea and Back Again",
                    actions = listOf(
                        playCardAction(resumes = true) {},
                        watchedCardAction(watched = false) {},
                        CardAction(icon = Lucide.X, label = REMOVE_FROM_CONTINUE_WATCHING, destructive = true) {},
                    ),
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        for (label in listOf("resume", "mark as watched", REMOVE_FROM_CONTINUE_WATCHING)) {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label)).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val text = results.single()
            assertEquals("$label on one line", 1, text.lineCount)
            assertFalse("$label cut", text.hasVisualOverflow)
            assertFalse("$label cut", text.isLineEllipsized(0))
        }
        // The longest label is wider than the old fixed 320 dp left it.
        val row = compose.onNode(hasText(REMOVE_FROM_CONTINUE_WATCHING)).fetchSemanticsNode().boundsInRoot
        assertTrue("its row is ${row.width / 2f} dp", row.width / 2f > 300f && row.width / 2f <= CARD_MENU_MAX_WIDTH.value)
        val title = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("Lighthouse", substring = true)).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(title)
        assertEquals("the title wraps", 2, title.single().lineCount)
        assertFalse("the title cut", title.single().isLineEllipsized(1))
    }
}
