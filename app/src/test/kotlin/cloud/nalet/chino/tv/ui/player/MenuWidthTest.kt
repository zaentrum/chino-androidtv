package cloud.nalet.chino.tv.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import cloud.nalet.chino.tv.data.api.QualityRung
import cloud.nalet.chino.tv.ui.settings.LanguageMenu
import cloud.nalet.chino.tv.ui.settings.subtitleLanguageChoices
import cloud.nalet.chino.tv.ui.theme.CHOICE_MENU_MAX_WIDTH
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.UI_SCALE
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
 * Every menu of one choice is as wide as its widest row (docs/chino,
 * playback: the player's menus): the quality menu's picked "Auto · Medium
 * (720p)" whole beside its mark, never cut to "Auto · Medium (72…"; a menu
 * of short names keeps its narrowest. Measured in the app's own faces on a
 * 1920 × 1080 TV.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuWidthTest {
    @get:Rule val compose = createComposeRule()

    private val rungs = listOf(
        QualityRung(name = AUTO, label = "Auto"),
        QualityRung(name = "v1", label = "High (1080p)"),
        QualityRung(name = "v2", label = "Medium (720p)"),
        QualityRung(name = "v3", label = "Lowest (360p)"),
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { ChinoTvTheme { Box(Modifier.size(960.dp, 540.dp)) { content() } } }
        compose.waitForIdle()
    }

    private fun layoutOf(label: String): TextLayoutResult =
        mutableListOf<TextLayoutResult>().also { results ->
            compose.onNode(hasText(label)).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        }.single()

    private fun assertWhole(label: String) {
        val text = layoutOf(label)
        assertEquals("$label on one line", 1, text.lineCount)
        assertFalse("$label cut", text.isLineEllipsized(0))
        assertFalse("$label cut", text.hasVisualOverflow)
    }

    /** The menu's width in the app's dp, by its row [label]: a row spans
     *  the panel but for its inset on either side, a focused row's growth's
     *  room. 2 px to the TV's dp, the app's dp [UI_SCALE] of that. */
    private fun menuWidthDp(label: String): Float =
        compose.onNode(hasText(label)).fetchSemanticsNode().boundsInRoot.width / (2f * UI_SCALE) + 2 * 6f

    @Test
    fun `the quality menu holds its picked Auto with the rung it plays, whole beside its mark`() {
        show { QualityPanel(rungs = rungs, active = AUTO, playing = "Medium (720p)", onPick = {}, onDismiss = {}) }
        assertWhole("Auto · Medium (720p)")
        for (r in rungs.drop(1)) assertWhole(r.label)
        val width = menuWidthDp("Auto · Medium (720p)")
        assertTrue("the menu is $width dp", width > 200f && width <= CHOICE_MENU_MAX_WIDTH.value)
    }

    @Test
    fun `a picked rung of its own, and a menu of short names at its narrowest`() {
        show { QualityPanel(rungs = rungs, active = "v1", playing = null, onPick = {}, onDismiss = {}) }
        assertWhole("High (1080p)")
        assertWhole("Lowest (360p)")
    }

    @Test
    fun `the speed menu keeps its narrowest`() {
        show { SpeedPanel(current = 1.25f, onPick = {}, onDismiss = {}) }
        assertEquals(180f, menuWidthDp("normal"), 0.5f)
    }

    @Test
    fun `Settings' language menu holds its longest name whole`() {
        show {
            LanguageMenu(
                header = "subtitle language",
                choices = subtitleLanguageChoices(listOf("en", "zh-Hant-TW", "es-419"), "zh-Hant-TW"),
                value = "zh-Hant-TW",
                onPick = {},
                onDismiss = {},
            )
        }
        assertWhole("Chinese (Traditional, Taiwan)")
        assertWhole("Spanish (Latin America)")
    }
}
