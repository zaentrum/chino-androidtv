package cloud.nalet.chino.tv.ui.person

import cloud.nalet.chino.tv.data.api.PersonDetail
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

// What the person screen says about a person: dates in the device's locale,
// their age, and the languages their biography is asked for in. A port of
// chino-web's src/lib/people.ts. Pure: PersonFactsTest runs it on the JVM.

/** A catalog date: YYYY-MM-DD as katalog-api sends them, or YYYY-MM / YYYY. */
data class CatalogDate(val year: Int, val month: Int? = null, val day: Int? = null)

private val CATALOG_DATE = Regex("""(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?""")

/** [iso] as a [CatalogDate]; null for anything else, an impossible day included. */
fun parseCatalogDate(iso: String?): CatalogDate? {
    val m = CATALOG_DATE.matchEntire(iso?.trim().orEmpty()) ?: return null
    val year = m.groupValues[1].toInt()
    val month = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
    val day = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
    if (month != null && month !in 1..12) return null
    if (month != null && day != null) {
        val last = GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH)
        if (day !in 1..last) return null
    }
    return CatalogDate(year, month, day)
}

/**
 * A catalog date in words, in [locale]: "March 3, 1957" (en-US), "3 March
 * 1957" (en-GB), "3. März 1957" (de-CH). A year-month or a year alone is
 * written as far as it goes. Anything that is not a date comes back as it was
 * rather than as nothing. Formatted in UTC, so the day is the calendar day
 * the catalog names whatever the device's time zone.
 */
fun formatCatalogDate(iso: String?, locale: Locale): String {
    val raw = iso?.trim().orEmpty()
    val d = parseCatalogDate(raw) ?: return raw
    val format = when {
        d.day != null -> DateFormat.getDateInstance(DateFormat.LONG, locale)
        d.month != null -> SimpleDateFormat("LLLL yyyy", locale)
        else -> return d.year.toString()
    }
    val utc = TimeZone.getTimeZone("UTC")
    format.timeZone = utc
    val at = GregorianCalendar(utc).apply {
        clear()
        set(d.year, (d.month ?: 1) - 1, d.day ?: 1)
    }
    return format.format(at.time)
}

/** Whole years from [born] to [on], both full catalog dates; null when either
 *  is not one, or [on] comes first. */
fun ageInYears(born: String?, on: String?): Int? {
    val b = parseCatalogDate(born) ?: return null
    val o = parseCatalogDate(on) ?: return null
    val bMonth = b.month ?: return null
    val bDay = b.day ?: return null
    val oMonth = o.month ?: return null
    val oDay = o.day ?: return null
    var age = o.year - b.year
    if (oMonth < bMonth || (oMonth == bMonth && oDay < bDay)) age -= 1
    return age.takeIf { it >= 0 }
}

/** [now] in the device's calendar, as a catalog date (YYYY-MM-DD). */
fun todayCatalogDate(now: Calendar = Calendar.getInstance()): String =
    "%04d-%02d-%02d".format(
        Locale.ROOT,
        now.get(Calendar.YEAR),
        now.get(Calendar.MONTH) + 1,
        now.get(Calendar.DAY_OF_MONTH),
    )

/** One labelled fact under the person's name: "Born" over its lines. */
data class PersonFact(val label: String, val lines: List<String>)

/**
 * The labelled facts under the name, as chino-web's person page lists them:
 * what they are known for, when (with the age) and where they were born,
 * when they died (with the age they reached). [today] is a catalog date.
 */
fun personFacts(person: PersonDetail, locale: Locale, today: String): List<PersonFact> {
    val facts = mutableListOf<PersonFact>()
    person.knownForDepartment?.trim()?.takeIf { it.isNotEmpty() }?.let {
        facts += PersonFact("Known for", listOf(it))
    }
    val born = formatCatalogDate(person.birthDate, locale)
    val died = formatCatalogDate(person.deathDate, locale)
    val age = ageInYears(person.birthDate, person.deathDate?.takeIf { it.isNotBlank() } ?: today)
    val birthplace = person.birthplace?.trim().orEmpty()
    if (born.isNotEmpty() || birthplace.isNotEmpty()) {
        facts += PersonFact(
            "Born",
            listOfNotNull(
                born.takeIf { it.isNotEmpty() }?.let { if (died.isEmpty() && age != null) "$it (age $age)" else it },
                birthplace.takeIf { it.isNotEmpty() },
            ),
        )
    }
    if (died.isNotEmpty()) {
        facts += PersonFact("Died", listOf(if (age != null) "$died (aged $age)" else died))
    }
    return facts
}

private val LANGUAGE_TAG = Regex("""[A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*""")

/**
 * An Accept-Language header for the device's languages, most wanted first:
 * "de-CH, de;q=0.9, en;q=0.8". katalog-api picks a person's biography from
 * it, falling back to English. Tags that are not language tags are left out;
 * at most ten. Empty when there are none.
 */
fun acceptLanguage(languages: List<String>?): String {
    val tags = mutableListOf<String>()
    for (raw in languages.orEmpty()) {
        val tag = raw.trim()
        if (!LANGUAGE_TAG.matches(tag)) continue
        if (tags.any { it.equals(tag, ignoreCase = true) }) continue
        tags += tag
        if (tags.size == 10) break
    }
    return tags.mapIndexed { i, tag ->
        if (i == 0) tag else "$tag;q=${"%.1f".format(Locale.ROOT, 1 - i / 10.0)}"
    }.joinToString(", ")
}

/**
 * A device locale as the language tag Accept-Language wants: [Locale.toLanguageTag]
 * (which also writes the modern codes, "he" not "iw") without its extensions —
 * Android 14's regional preferences put "-u-mu-celsius" and the like on the
 * default locale. Null for the root locale ("und").
 */
fun languageTagOf(locale: Locale): String? =
    locale.toLanguageTag()
        .split('-')
        .takeWhile { it.length > 1 }
        .joinToString("-")
        .takeIf { it.isNotEmpty() && it != "und" }
