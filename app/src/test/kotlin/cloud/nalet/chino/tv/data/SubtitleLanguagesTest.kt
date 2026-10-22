package cloud.nalet.chino.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** The server's subtitle languages: asked once a session, none where it
 *  cannot say. */
class SubtitleLanguagesTest {
    private fun refused(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun `the first answer holds for the session`() = runBlocking {
        var asked = 0
        val languages = SubtitleLanguages { asked++; listOf("en", " de ", "zh-Hans", "", "en") }
        assertEquals(listOf("en", "de", "zh-Hans"), languages.get())
        assertEquals(listOf("en", "de", "zh-Hans"), languages.get())
        assertEquals(1, asked)
    }

    @Test
    fun `none set is an answer too`() = runBlocking {
        var asked = 0
        val languages = SubtitleLanguages { asked++; emptyList() }
        assertEquals(emptyList<String>(), languages.get())
        assertEquals(emptyList<String>(), languages.get())
        assertEquals(1, asked)
    }

    @Test
    fun `an older server's 404 is no languages, and is not asked again`() = runBlocking {
        var asked = 0
        val languages = SubtitleLanguages { asked++; throw refused(404) }
        assertEquals(emptyList<String>(), languages.get())
        assertEquals(emptyList<String>(), languages.get())
        assertEquals(1, asked)
    }

    @Test
    fun `a failure is no languages for now, and asked again next time`() = runBlocking {
        var asked = 0
        val languages = SubtitleLanguages {
            asked++
            when (asked) {
                1 -> throw IOException("no connection")
                2 -> throw refused(502)
                else -> listOf("de")
            }
        }
        assertEquals(emptyList<String>(), languages.get())
        assertEquals(emptyList<String>(), languages.get())
        assertEquals(listOf("de"), languages.get())
        assertEquals(listOf("de"), languages.get())
        assertEquals(3, asked)
    }
}
