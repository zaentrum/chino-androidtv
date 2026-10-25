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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card's menu, as chino-web's (docs/chino, title actions): "play", or
 * "resume" where the card has a position; the watched toggle in the title
 * page's words, "mark as watched" and "watched"; "remove from continue
 * watching" - menu items, in the mono face and lowercase.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
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
}
