package cloud.nalet.chino.tv.ui.person

import cloud.nalet.chino.tv.data.api.PersonDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.GregorianCalendar
import java.util.Locale

// The cases of chino-web's src/lib/people.test.ts (dates, age,
// Accept-Language), plus the facts list and device language tags.
class PersonFactsTest {
    @Test
    fun `a birth date in the device's locale`() {
        assertEquals("March 3, 1957", formatCatalogDate("1957-03-03", Locale.US))
        assertEquals("3 March 1957", formatCatalogDate("1957-03-03", Locale.UK))
        assertEquals("3. März 1957", formatCatalogDate("1957-03-03", Locale("de", "CH")))
        assertEquals("3 mars 1957", formatCatalogDate("1957-03-03", Locale("fr", "CH")))
    }

    @Test
    fun `no day is lost to a time zone - the date is the calendar day the catalog names`() {
        val zone = java.util.TimeZone.getDefault()
        try {
            // Midnight UTC is still the previous evening west of Greenwich.
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("January 1, 2000", formatCatalogDate("2000-01-01", Locale.US))
            assertEquals("December 31, 1999", formatCatalogDate("1999-12-31", Locale.US))
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `a year-month or a year is written as far as it goes, anything else as it was`() {
        assertEquals("March 1957", formatCatalogDate("1957-03", Locale.US))
        assertEquals("1957", formatCatalogDate("1957", Locale.US))
        assertEquals("circa 1957", formatCatalogDate("circa 1957", Locale.US))
        assertEquals("1957-02-30", formatCatalogDate("1957-02-30", Locale.US))
        assertEquals("", formatCatalogDate(null, Locale.US))
        assertEquals("March 3, 1957", formatCatalogDate(" 1957-03-03 ", Locale.US))
    }

    @Test
    fun `parseCatalogDate`() {
        assertEquals(CatalogDate(1957, 3, 3), parseCatalogDate("1957-03-03"))
        assertEquals(CatalogDate(2024, 2, 29), parseCatalogDate("2024-02-29"))
        assertNull(parseCatalogDate("2023-02-29"))
        assertNull(parseCatalogDate("1957-13-01"))
        assertNull(parseCatalogDate("03.03.1957"))
    }

    @Test
    fun `the age turns on the birthday, not before`() {
        assertEquals(68, ageInYears("1957-03-03", "2026-03-02"))
        assertEquals(69, ageInYears("1957-03-03", "2026-03-03"))
        assertEquals(69, ageInYears("1957-03-03", "2026-10-03"))
        assertEquals(43, ageInYears("1980-11-20", "2024-02-10"))
        assertEquals(0, ageInYears("2000-02-29", "2001-02-28"))
        assertEquals(1, ageInYears("2000-02-29", "2001-03-01"))
    }

    @Test
    fun `no age without two full dates, nor before the birth`() {
        assertNull(ageInYears("1957", "2026-10-03"))
        assertNull(ageInYears("1957-03-03", null))
        assertNull(ageInYears(null, "2026-10-03"))
        assertNull(ageInYears("1957-03-03", "1956-01-01"))
    }

    @Test
    fun `today, in the local calendar`() {
        assertEquals("2026-10-03", todayCatalogDate(GregorianCalendar(2026, 9, 3, 23, 59)))
        assertEquals("2026-01-01", todayCatalogDate(GregorianCalendar(2026, 0, 1, 0, 0)))
    }

    @Test
    fun `the facts - known for, born with the age and the place, died with the age reached`() {
        val alive = PersonDetail(
            id = "p1",
            name = "A",
            knownForDepartment = "Acting",
            birthDate = "1957-03-03",
            birthplace = "Lyon, France",
        )
        assertEquals(
            listOf(
                PersonFact("Known for", listOf("Acting")),
                PersonFact("Born", listOf("March 3, 1957 (age 69)", "Lyon, France")),
            ),
            personFacts(alive, Locale.US, today = "2026-10-03"),
        )

        val dead = PersonDetail(id = "p2", name = "B", birthDate = "1911-02-06", deathDate = "2004-06-05")
        assertEquals(
            listOf(
                PersonFact("Born", listOf("February 6, 1911")),
                PersonFact("Died", listOf("June 5, 2004 (aged 93)")),
            ),
            personFacts(dead, Locale.US, today = "2026-10-03"),
        )

        val placeOnly = PersonDetail(id = "p3", name = "C", birthplace = "Bern")
        assertEquals(listOf(PersonFact("Born", listOf("Bern"))), personFacts(placeOnly, Locale.US, "2026-10-03"))
        assertEquals(emptyList<PersonFact>(), personFacts(PersonDetail(id = "p4", name = "D"), Locale.US, "2026-10-03"))
    }

    @Test
    fun `Accept-Language - the device's languages, most wanted first`() {
        assertEquals("en-US", acceptLanguage(listOf("en-US")))
        assertEquals("de-CH, de;q=0.9, en;q=0.8", acceptLanguage(listOf("de-CH", "de", "en")))
        assertEquals("fr, it;q=0.9", acceptLanguage(listOf("fr", "FR", " it ")))
        assertEquals("de", acceptLanguage(listOf("de", "x y", "en;q=1", "")))
        assertEquals("", acceptLanguage(emptyList()))
        assertEquals("", acceptLanguage(null))
        val many = acceptLanguage((0 until 12).map { "l" + ('a' + it) })
        assertEquals(10, many.split(", ").size)
        assertEquals(true, many.endsWith("lj;q=0.1"))
    }

    @Test
    fun `a device locale as a plain language tag`() {
        assertEquals("en-US", languageTagOf(Locale.forLanguageTag("en-US-u-mu-celsius")))
        assertEquals("he-IL", languageTagOf(Locale("iw", "IL")))
        assertEquals("zh-Hant-TW", languageTagOf(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("de-CH", languageTagOf(Locale("de", "CH")))
        assertNull(languageTagOf(Locale.ROOT))
    }
}
