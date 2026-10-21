package cloud.nalet.chino.tv.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import cloud.nalet.chino.tv.data.api.Person
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search screen's focus order from its field: DOWN to the first row of
 * what was found - the cast & crew where they show, else the first title -
 * and only LEFT to the side rail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class SearchFocusTest {
    @get:Rule val compose = createComposeRule()

    private val rail = setOf("Home", "Movies", "Series", "Watchlist", "Zap", "Settings")

    private fun results(people: List<Person> = emptyList(), items: List<Item> = emptyList()) =
        SearchUiState.Results(items = items, people = people, baseUrl = "https://server.test/api", streamToken = "t")

    private fun show(state: SearchUiState, query: String = "pioneer") {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                SearchContent(
                    query = query,
                    state = state,
                    emptySlot = emptyList(),
                    slotActionStates = emptyMap(),
                    onQueryChange = {},
                    onSlotAction = {},
                    onItemSelected = {},
                )
            }
        }
        compose.waitForIdle()
        // The field has the focus first.
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** What has the focus: "the field", or the texts and content
     *  descriptions of the focused item and what it holds (a chip's name, a
     *  card's title, a rail item's label). */
    private fun focused(): Set<String> {
        val node = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()
        if (node.config.contains(SemanticsProperties.EditableText)) return setOf("the field")
        val out = mutableSetOf<String>()
        fun collect(n: SemanticsNode) {
            n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.forEach { out += it.text }
            n.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.forEach { out += it }
            n.children.forEach { collect(it) }
        }
        collect(node)
        return out
    }

    private fun assertFocusOn(what: String, label: String) {
        val now = focused()
        assertTrue("$what: the focus is on $now, not $label", label in now)
    }

    private fun assertFocusOnRail(what: String, expected: Boolean) {
        val now = focused()
        assertEquals("$what: the focus is on $now", expected, now.any { it in rail })
    }

    @Test
    fun `DOWN goes to the cast and crew where they show`() {
        show(results(people = listOf(Person(id = "p1", name = "Jack Haley")), items = listOf(Item(id = "i1", title = "Pioneer One"))))
        press(Key.DirectionDown)
        assertFocusOn("DOWN", "Jack Haley")
    }

    @Test
    fun `DOWN goes to the first title where no cast and crew show`() {
        show(results(items = listOf(Item(id = "i1", title = "Pioneer One"), Item(id = "i2", title = "Pioneer Two"))))
        press(Key.DirectionDown)
        assertFocusOn("DOWN", "Pioneer One")
    }

    // With nothing to go to below, DOWN and UP stay in the field, and RIGHT
    // does not reach the rail: no query yet, a search under way, no match
    // (no addon buttons), and results of nothing.
    @Test fun `no query yet - the field holds the focus`() = staysIn(SearchUiState.Empty)

    @Test fun `a search under way - the field holds the focus`() = staysIn(SearchUiState.Searching)

    @Test fun `no match - the field holds the focus`() = staysIn(SearchUiState.NoMatches)

    @Test fun `results of nothing - the field holds the focus`() = staysIn(results())

    private fun staysIn(state: SearchUiState) {
        show(state)
        press(Key.DirectionDown)
        assertFocusOn("DOWN", "the field")
        press(Key.DirectionUp)
        assertFocusOn("UP", "the field")
        press(Key.DirectionRight)
        assertFocusOnRail("RIGHT", expected = false)
    }

    @Test
    fun `LEFT reaches the rail`() {
        // With the cursor at the field's start: LEFT before it moves the cursor.
        show(SearchUiState.Empty, query = "")
        press(Key.DirectionLeft)
        assertFocusOnRail("LEFT", expected = true)
    }

    @Test
    fun `with results UP and RIGHT do not reach the rail either`() {
        show(results(items = listOf(Item(id = "i1", title = "Pioneer One"))))
        press(Key.DirectionUp)
        assertFocusOn("UP", "the field")
        press(Key.DirectionRight)
        assertFocusOnRail("RIGHT", expected = false)
    }

    /** The remote's input: a TV is in keyboard mode (Robolectric starts in
     *  touch mode). */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
