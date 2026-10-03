package cloud.nalet.chino.tv.ui.detail

import cloud.nalet.chino.tv.data.model.CastMember
import org.junit.Assert.assertEquals
import org.junit.Test

// The cases of chino-web's src/lib/credits.test.ts, so the two clients group
// and name credits alike.
class CreditsTest {
    private fun credit(name: String, role: String? = null, character: String? = null, order: Int? = null) =
        CastMember(name = name, role = role, personId = "id-$name", character = character, order = order)

    @Test
    fun `the crew is grouped by role, known roles in display order, others after them as they came`() {
        val (actors, crew) = groupCredits(
            listOf(
                credit("Esther Wouda", "writer"),
                credit("Halina Reijn", "actor", character = "Sintel (voice)", order = 0),
                credit("Jan Morgenstern", "composer"),
                credit("Rob Tuytel", "visual-effects"),
                credit("Colin Levy", "director"),
                credit("Thom Hoffman", "actor", character = "Shaman (voice)", order = 1),
                credit("Joram Letwory", "sound-designer"),
                credit("Ton Roosendaal", "producer"),
                credit("Josh Bernhard", "creator"),
            ),
        )
        assertEquals(listOf("Halina Reijn", "Thom Hoffman"), actors.map { it.name })
        assertEquals(
            listOf(
                "creator" to "Created by",
                "director" to "Director",
                "writer" to "Writer",
                "producer" to "Producer",
                "composer" to "Music",
                "visual-effects" to "Visual Effects",
                "sound-designer" to "Sound Designer",
            ),
            crew.map { it.role to it.label },
        )
    }

    @Test
    fun `the order within a role is the order the credits arrive in (billing order)`() {
        val leads = listOf("Alexandra Blatt", "Laura Graham", "James Rich", "Einar Gunn", "Jack Haley")
        val (actors, _) = groupCredits(leads.mapIndexed { i, n -> credit(n, "actor", order = i) })
        assertEquals(leads, actors.map { it.name })
    }

    @Test
    fun `a credit without a role is an actor, role tokens are matched case-insensitively`() {
        val (actors, crew) = groupCredits(
            listOf(credit("A"), credit("B", ""), credit("C", " Actor "), credit("D", "DIRECTOR")),
        )
        assertEquals(listOf("A" to "actor", "B" to "actor", "C" to "actor"), actors.map { it.name to it.role })
        assertEquals(listOf("director" to listOf("D")), crew.map { g -> g.role to g.people.map { it.name } })
    }

    @Test
    fun `a person credited twice in one role is listed once, with both characters`() {
        val (actors, crew) = groupCredits(
            listOf(
                credit("Ian Hubert", "director"),
                credit("Ian Hubert", "director"),
                credit("Ian Hubert", "writer"),
                credit("Derek de Lint", "actor", character = "Old Thom"),
                credit("Derek de Lint", "actor", character = "Narrator"),
                credit("Derek de Lint", "actor", character = "Old Thom"),
            ),
        )
        assertEquals(
            listOf("Director" to listOf("Ian Hubert"), "Writer" to listOf("Ian Hubert")),
            crew.map { g -> g.label to g.people.map { it.name } },
        )
        assertEquals(1, actors.size)
        assertEquals("Old Thom / Narrator", actors[0].character)
    }

    @Test
    fun `without a person id, the name tells credits apart, nameless credits are dropped`() {
        val (actors, _) = groupCredits(
            listOf(
                CastMember(name = "Same Name", role = "actor"),
                CastMember(name = "Same Name", role = "actor"),
                CastMember(name = "Other", role = "actor"),
                CastMember(name = "  ", role = "actor"),
            ),
        )
        assertEquals(listOf("Same Name", "Other"), actors.map { it.name })
    }

    @Test
    fun `every kept credit has a key unique in its row`() {
        val (actors, crew) = groupCredits(
            listOf(
                CastMember(name = "Same Name", role = "actor", personId = "p1"),
                CastMember(name = "Same Name", role = "actor", personId = "p2"),
                CastMember(name = "Same Name", role = "director", personId = "p1"),
            ),
        )
        assertEquals(listOf("actor:p1", "actor:p2"), actors.map(::creditKey))
        assertEquals(listOf("director:p1"), crew.single().people.map(::creditKey))
    }

    @Test
    fun `no credits, no groups`() {
        assertEquals(GroupedCredits(emptyList(), emptyList()), groupCredits(null))
        assertEquals(GroupedCredits(emptyList(), emptyList()), groupCredits(emptyList()))
    }

    @Test
    fun `labels - singular for one name, plural for more, the fixed ones fixed`() {
        assertEquals("Director", creditLabel("director", 1))
        assertEquals("Directors", creditLabel("director", 2))
        assertEquals("Writers", creditLabel("writer", 3))
        assertEquals("Producer", creditLabel("producer", 1))
        assertEquals("Editors", creditLabel("editor", 2))
        assertEquals("Music", creditLabel("composer", 1))
        assertEquals("Music", creditLabel("composer", 2))
        assertEquals("Cinematography", creditLabel("cinematographer", 2))
        assertEquals("Created by", creditLabel("creator", 2))
        assertEquals("Starring", creditLabel("actor", 5))
        // An unknown role is titleized and not pluralised: it can't be done right blind.
        assertEquals("Casting", creditLabel("casting", 2))
        assertEquals("Constructor", creditLabel("constructor", 1))
    }

    @Test
    fun `titleizeRole splits on hyphens, underscores and spaces`() {
        assertEquals("Sound Designer", titleizeRole("sound-designer"))
        assertEquals("Visual Effects", titleizeRole("visual_effects"))
        assertEquals("Art Direction", titleizeRole("art  direction"))
        assertEquals("", titleizeRole(""))
    }

    @Test
    fun `roleOf`() {
        assertEquals("actor", roleOf(CastMember(name = "X")))
        assertEquals("composer", roleOf(CastMember(name = "X", role = "Composer")))
    }

    @Test
    fun `a person's roles on a title are named once each, in the order given`() {
        assertEquals("Director", formatRoles(listOf("director")))
        assertEquals("Director · Writer", formatRoles(listOf("director", "writer")))
        assertEquals("Actor · Composer · Sound Designer", formatRoles(listOf("actor", "composer", "sound-designer")))
        assertEquals("Writer", formatRoles(listOf("writer", "WRITER")))
        assertEquals("", formatRoles(listOf("", " ")))
        assertEquals("", formatRoles(emptyList()))
        assertEquals("", formatRoles(null))
        assertEquals("Cinematographer", roleName("cinematographer"))
        assertEquals("Creator", roleName("creator"))
        assertEquals("Constructor", roleName("constructor"))
    }
}
