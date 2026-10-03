package cloud.nalet.chino.tv.data.api

import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DTOs against JSON shaped as chino-api writes it (internal/http and
 * internal/katalog: writeJSON over the handlers' maps and structs, with
 * omitempty fields left out), decoded exactly as the app decodes responses.
 */
class ApiContractTest {
    private fun <T> decode(serializer: KSerializer<T>, body: String): T =
        RetrofitFactory.json.decodeFromString(serializer, body)

    @Test
    fun `a list is a bare page of items - no cursor`() {
        val page = decode(
            ItemsPage.serializer(),
            """{"product":"chino","items":[{"id":"m1","type":"movie","title":"Sintel","year":2010,
               "poster_url":"/api/v1/items/m1/poster","backdrop_url":"/api/v1/items/m1/backdrop"}],
               "source":"katalog"}""",
        )
        assertEquals(listOf("m1"), page.items.map { it.id })
        assertEquals("movie", page.items[0].kind)
    }

    @Test
    fun `next-episode wraps the episode in next`() {
        val after = decode(
            NextEpisodeResponse.serializer(),
            """{"next":{"id":"e2","type":"episode","title":"Two","season_number":1,"episode_number":2,
               "parent_id":"s"},"anchor":"e1"}""",
        )
        assertEquals("e2", after.next?.id)
        assertEquals(2, after.next?.episodeNumber)
        assertEquals("e1", after.anchor)

        val end = decode(NextEpisodeResponse.serializer(), """{"next":null,"reason":"end_of_series"}""")
        assertNull(end.next)
        assertEquals("end_of_series", end.reason)
    }

    @Test
    fun `a continue-watching row names its series`() {
        val cw = decode(
            ContinueWatchingResponse.serializer(),
            """{"items":[{"id":"e5","type":"episode","title":"Five","season_number":2,"episode_number":5,
               "parent_id":"s","position_sec":0,"duration_sec":0,"series_title":"Show","up_next":true}]}""",
        )
        val row = cw.items.single()
        assertEquals("s", row.parentId)
        assertTrue(row.upNext)
    }

    @Test
    fun `an unlabelled subtitle no longer fails the whole list`() {
        val subs = decode(
            SubtitlesResponse.serializer(),
            """{"subtitles":[{"id":"a","lang":"en","label":"English","url":"/api/v1/play/subs/a.vtt"},
               {"id":"b","lang":"de","url":"/api/v1/play/subs/b.vtt"}]}""",
        )
        assertEquals(listOf("English", ""), subs.subtitles.map { it.label })
    }

    @Test
    fun `the cast carries character, job, order and episode count`() {
        val item = decode(
            Item.serializer(),
            """{"id":"s","type":"series","title":"Show","cast":[
               {"person_id":"p1","name":"Halina Reijn","role":"actor","character":"Sintel","order":0,"episode_count":8},
               {"person_id":"p2","name":"Esther Wouda","role":"writer","job":"Screenplay"},
               {"name":"Uncredited","role":""}]}""",
        )
        val (actor, writer, bare) = item.cast
        assertEquals("Sintel", actor.character)
        assertEquals(0, actor.order)
        assertEquals(8, actor.episodeCount)
        assertEquals("Screenplay", writer.job)
        assertNull(bare.personId)
    }

    @Test
    fun `a person carries portrait, dates, biography and roles per title`() {
        val person = decode(
            PersonDetail.serializer(),
            """{"id":"p1","name":"Colin Levy","has_profile":true,"profile_url":"/api/v1/people/p1/profile",
               "birth_date":"1980-11-20","birthplace":"Bern","known_for_department":"Directing",
               "biography":"Text.","biography_lang":"en",
               "items":[{"id":"m1","type":"movie","title":"Sintel","roles":["director","writer"]}]}""",
        )
        assertTrue(person.hasProfile)
        assertEquals("/api/v1/people/p1/profile", person.profileUrl)
        assertEquals("1980-11-20", person.birthDate)
        assertNull(person.deathDate)
        assertEquals("Directing", person.knownForDepartment)
        assertEquals("en", person.biographyLang)
        assertEquals(listOf("director", "writer"), person.items.single().roles)

        val searched = decode(
            PeopleResponse.serializer(),
            """{"people":[{"id":"p1","name":"Colin Levy","credits":3,"has_profile":false}],"total":1}""",
        )
        assertFalse(searched.people.single().hasProfile)
        assertNull(searched.people.single().profileUrl)
    }

    @Test
    fun `a series without episodes answers seasons null`() {
        val eps = decode(SeriesEpisodes.serializer(), """{"series_id":"s","seasons":null,"count":0}""")
        assertEquals(emptyList<Season>(), eps.seasons)
    }

    @Test
    fun `me is the subject alone`() {
        assertEquals("u-1", decode(Me.serializer(), """{"sub":"u-1"}""").sub)
    }
}
