package cloud.nalet.chino.tv.ui.theme

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.nalet.chino.tv.ui.player.BufferingIndicator
import cloud.nalet.chino.tv.ui.settings.REPORT_SENT
import cloud.nalet.chino.tv.ui.zap.ZAP_LOADING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The app's words say what is happening in plain words, or nothing (the
 * design system's writing UI text): no jokes, no cute status lines, no
 * "please", no thanks. Searched in every string the app's code and
 * resources carry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class PlainWordsTest {
    @get:Rule val compose = createComposeRule()

    /** Words of jokes and padding - each found in this app once. */
    private val unplain = listOf(
        "codec gods", "projectionist", "popcorn", "film reels", "house lights", "cinema magic",
        "hold tight", "play nice", "tuning in", "snappier", "please", "thanks", "oops", "whoops",
    )

    /** Every string literal in the app's Kotlin, and every string resource. */
    private fun appStrings(): List<Pair<String, String>> {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        assertTrue("the app's sources at ${root.absolutePath}", root.isDirectory)
        val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val out = ArrayList<Pair<String, String>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                literal.findAll(code.substringBefore(" // ")).forEach { out += "${file.name}:${i + 1}" to it.groupValues[1] }
            }
        }
        root.walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }.forEach { file ->
            Regex("<string[^>]*>([^<]*)</string>").findAll(file.readText()).forEach { out += file.name to it.groupValues[1] }
        }
        assertTrue(out.size > 500)
        return out
    }

    @Test
    fun `no string the app shows jokes, pads or thanks`() {
        val found = appStrings().filter { (_, text) -> unplain.any { text.contains(it, ignoreCase = true) } }
        assertEquals(emptyList<Pair<String, String>>(), found)
    }

    @Test
    fun `the picture on its way shows the loader and nothing to read`() {
        compose.setContent { ChinoTvTheme { BufferingIndicator() } }
        compose.waitForIdle()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun `status lines in plain words`() {
        assertEquals("Loading…", ZAP_LOADING)
        assertEquals("Sent to the people who run this server.", REPORT_SENT)
    }
}
