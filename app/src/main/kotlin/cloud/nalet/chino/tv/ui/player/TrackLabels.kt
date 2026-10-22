package cloud.nalet.chino.tv.ui.player

import java.util.Locale

/*
 * What the audio and subtitle menus call a track, and the languages behind
 * the names. Pure: TrackLabelsTest runs it on the JVM.
 *
 * Languages come as the catalog, the media files and the settings spell them
 * - ISO 639-2/B ("ger", "fre"), 639-2/T ("deu", "fra"), 639-1 ("de"), BCP 47
 * tags ("pt-BR", "zh-Hans") - and are named in English from a table, so the
 * TV and the phone label a track the same way; a language the table lacks is
 * named by the platform, else shown as its code. A script or a region goes in
 * brackets after the language ("Chinese (Simplified)", "Portuguese
 * (Brazil)"), and where a track's tag carries neither, the words of its name
 * that say one do ("Chinese · Simplified", "Portuguese · Brazilian").
 *
 * A subtitle is named by its language first, then what its own label - the
 * sidecar's, the rendition's NAME, the name /play/info gives an embedded
 * track - says besides ("English · Commentary", "Chinese (Simplified) ·
 * AI"), then its kind in words: "· foreign-language parts only" for a forced
 * track, "· for deaf and hard of hearing" for one that describes the sound. A
 * label that says the kind itself ("English SDH", "German (forced)") says it
 * once, in these words; one that only names the language again ("Deutsch",
 * "中文") says nothing more. chino-mobile's rules (Languages.kt), which are
 * chino-web's lib/languages.ts, as docs/chino (subtitles, words) has them.
 */

// ISO 639-2/B (bibliographic) codes and the /T (terminology) code each stands
// for; files tagged by older tools carry the /B ones.
private val B_TO_T = mapOf(
    "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
    "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
    "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
    "tib" to "bod", "wel" to "cym",
)

// ISO 639-2/T -> 639-1, for the languages a library is likely to carry.
// Anything else is looked up in the platform's table, else compared as its
// three letters.
private val T_TO_1 = mapOf(
    "afr" to "af", "amh" to "am", "ara" to "ar", "aze" to "az", "bel" to "be", "ben" to "bn",
    "bod" to "bo", "bos" to "bs", "bul" to "bg", "cat" to "ca", "ces" to "cs", "cym" to "cy",
    "dan" to "da", "deu" to "de", "ell" to "el", "eng" to "en", "est" to "et", "eus" to "eu",
    "fas" to "fa", "fin" to "fi", "fra" to "fr", "gle" to "ga", "glg" to "gl", "guj" to "gu",
    "heb" to "he", "hin" to "hi", "hrv" to "hr", "hun" to "hu", "hye" to "hy", "ind" to "id",
    "isl" to "is", "ita" to "it", "jpn" to "ja", "kan" to "kn", "kat" to "ka", "kaz" to "kk",
    "khm" to "km", "kor" to "ko", "lao" to "lo", "lat" to "la", "lav" to "lv", "lit" to "lt",
    "ltz" to "lb", "mal" to "ml", "mar" to "mr", "mkd" to "mk", "mlt" to "mt", "mon" to "mn",
    "mri" to "mi", "msa" to "ms", "mya" to "my", "nep" to "ne", "nld" to "nl", "nob" to "nb",
    "nno" to "nn", "nor" to "no", "pan" to "pa", "pol" to "pl", "por" to "pt", "pus" to "ps",
    "ron" to "ro", "rus" to "ru", "sin" to "si", "slk" to "sk", "slv" to "sl", "som" to "so",
    "spa" to "es", "sqi" to "sq", "srp" to "sr", "swa" to "sw", "swe" to "sv", "tam" to "ta",
    "tel" to "te", "tgl" to "tl", "tha" to "th", "tur" to "tr", "ukr" to "uk", "urd" to "ur",
    "uzb" to "uz", "vie" to "vi", "yid" to "yi", "zho" to "zh", "zul" to "zu",
)

/** The platform's ISO 639-2/T codes ("kin") to ISO 639-1 ("rw"), for what the
 *  table above lacks. */
private val PLATFORM_T_TO_1: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { code ->
        runCatching { Locale(code).isO3Language }.getOrDefault(code)
    }
}

// Withdrawn 639-1 codes still found in the wild.
private val OLD_1 = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

/** Codes that name no language one could read or listen to: undetermined, no
 *  linguistic content, several, one with no code. */
private val NO_LANGUAGE = setOf("und", "zxx", "mul", "mis")

/** What a track tagged "zxx" (no linguistic content) is called: the audio of a
 *  film without dialogue. */
const val NO_DIALOGUE = "No dialogue"

/** What a track is called whose language is not known ("und", none). */
const val UNKNOWN_LANGUAGE = "Unknown"

// The codes for no one language that still say what a track is in, and what
// such a track is called: no dialogue ("zxx"), several languages ("mul"), a
// language ISO 639 has no code for ("mis").
private val NOT_ONE_LANGUAGE = mapOf(
    "zxx" to NO_DIALOGUE,
    "mul" to "Multiple languages",
    "mis" to "Other language",
)

// The English name of each normalised language (639-1, else 639-2/T).
private val NAMES = mapOf(
    "af" to "Afrikaans", "am" to "Amharic", "ar" to "Arabic", "az" to "Azerbaijani",
    "be" to "Belarusian", "bg" to "Bulgarian", "bn" to "Bangla", "bo" to "Tibetan",
    "bs" to "Bosnian", "ca" to "Catalan", "cs" to "Czech", "cy" to "Welsh", "da" to "Danish",
    "de" to "German", "el" to "Greek", "en" to "English", "es" to "Spanish", "et" to "Estonian",
    "eu" to "Basque", "fa" to "Persian", "fi" to "Finnish", "fr" to "French", "ga" to "Irish",
    "gl" to "Galician", "gu" to "Gujarati", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian",
    "hu" to "Hungarian", "hy" to "Armenian", "id" to "Indonesian", "is" to "Icelandic",
    "it" to "Italian", "ja" to "Japanese", "ka" to "Georgian", "kk" to "Kazakh", "km" to "Khmer",
    "kn" to "Kannada", "ko" to "Korean", "la" to "Latin", "lb" to "Luxembourgish", "lo" to "Lao",
    "lt" to "Lithuanian", "lv" to "Latvian", "mi" to "Māori", "mk" to "Macedonian",
    "ml" to "Malayalam", "mn" to "Mongolian", "mr" to "Marathi", "ms" to "Malay", "mt" to "Maltese",
    "my" to "Burmese", "nb" to "Norwegian Bokmål", "ne" to "Nepali", "nl" to "Dutch",
    "nn" to "Norwegian Nynorsk", "no" to "Norwegian", "pa" to "Punjabi", "pl" to "Polish",
    "ps" to "Pashto", "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "si" to "Sinhala",
    "sk" to "Slovak", "sl" to "Slovenian", "so" to "Somali", "sq" to "Albanian", "sr" to "Serbian",
    "sv" to "Swedish", "sw" to "Swahili", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai",
    "tl" to "Tagalog", "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu", "uz" to "Uzbek",
    "vi" to "Vietnamese", "yi" to "Yiddish", "zh" to "Chinese", "zu" to "Zulu",
    "fil" to "Filipino", "gsw" to "Swiss German", "yue" to "Cantonese",
)

// The English names of the scripts a tag carries, as CLDR puts them in a
// language's name: "zh-Hans" is "Chinese (Simplified)", "sr-Latn" "Serbian
// (Latin)".
private val SCRIPT_NAMES = mapOf(
    "Hans" to "Simplified", "Hant" to "Traditional", "Latn" to "Latin", "Cyrl" to "Cyrillic",
    "Arab" to "Arabic", "Deva" to "Devanagari", "Beng" to "Bangla", "Guru" to "Gurmukhi",
    "Grek" to "Greek", "Hebr" to "Hebrew", "Jpan" to "Japanese", "Kore" to "Korean", "Thai" to "Thai",
    "Geor" to "Georgian", "Armn" to "Armenian", "Ethi" to "Ethiopic", "Mong" to "Mongolian",
    "Tibt" to "Tibetan", "Taml" to "Tamil", "Telu" to "Telugu", "Knda" to "Kannada", "Mlym" to "Malayalam",
    "Gujr" to "Gujarati", "Orya" to "Odia", "Sinh" to "Sinhala", "Khmr" to "Khmer", "Laoo" to "Lao",
    "Mymr" to "Myanmar",
)

// The English names of the regions a tag carries, as CLDR puts them in a
// language's name: "pt-BR" is "Portuguese (Brazil)", "es-419" "Spanish
// (Latin America)". A region not here is named by its code.
private val REGION_NAMES = mapOf(
    "US" to "United States", "GB" to "United Kingdom", "AU" to "Australia", "CA" to "Canada",
    "NZ" to "New Zealand", "IE" to "Ireland", "IN" to "India", "ZA" to "South Africa", "SG" to "Singapore",
    "PH" to "Philippines", "MY" to "Malaysia", "NG" to "Nigeria", "KE" to "Kenya",
    "BR" to "Brazil", "PT" to "Portugal", "AO" to "Angola", "MZ" to "Mozambique",
    "ES" to "Spain", "MX" to "Mexico", "AR" to "Argentina", "CO" to "Colombia", "CL" to "Chile",
    "PE" to "Peru", "VE" to "Venezuela", "UY" to "Uruguay", "CU" to "Cuba", "419" to "Latin America",
    "FR" to "France", "BE" to "Belgium", "CH" to "Switzerland", "LU" to "Luxembourg", "MC" to "Monaco",
    "DE" to "Germany", "AT" to "Austria", "LI" to "Liechtenstein", "NL" to "Netherlands", "SR" to "Suriname",
    "IT" to "Italy", "SM" to "San Marino",
    "CN" to "China", "TW" to "Taiwan", "HK" to "Hong Kong SAR China", "MO" to "Macao SAR China",
    "JP" to "Japan", "KR" to "South Korea", "KP" to "North Korea", "MN" to "Mongolia",
    "RU" to "Russia", "UA" to "Ukraine", "BY" to "Belarus", "KZ" to "Kazakhstan", "UZ" to "Uzbekistan",
    "SE" to "Sweden", "FI" to "Finland", "NO" to "Norway", "DK" to "Denmark", "IS" to "Iceland",
    "EE" to "Estonia", "LV" to "Latvia", "LT" to "Lithuania",
    "PL" to "Poland", "CZ" to "Czechia", "SK" to "Slovakia", "HU" to "Hungary", "RO" to "Romania",
    "MD" to "Moldova", "BG" to "Bulgaria", "RS" to "Serbia", "ME" to "Montenegro",
    "BA" to "Bosnia & Herzegovina", "HR" to "Croatia", "SI" to "Slovenia", "MK" to "North Macedonia",
    "AL" to "Albania", "XK" to "Kosovo", "GR" to "Greece", "CY" to "Cyprus", "TR" to "Türkiye",
    "GE" to "Georgia", "AM" to "Armenia", "AZ" to "Azerbaijan", "IL" to "Israel",
    "SA" to "Saudi Arabia", "EG" to "Egypt", "AE" to "United Arab Emirates", "MA" to "Morocco",
    "DZ" to "Algeria", "TN" to "Tunisia", "LB" to "Lebanon", "JO" to "Jordan", "IQ" to "Iraq",
    "SY" to "Syria", "KW" to "Kuwait", "QA" to "Qatar", "BH" to "Bahrain", "OM" to "Oman", "YE" to "Yemen",
    "LY" to "Libya", "SD" to "Sudan", "IR" to "Iran", "AF" to "Afghanistan", "PK" to "Pakistan",
    "BD" to "Bangladesh", "LK" to "Sri Lanka", "NP" to "Nepal",
    "TH" to "Thailand", "VN" to "Vietnam", "ID" to "Indonesia", "KH" to "Cambodia", "LA" to "Laos",
    "MM" to "Myanmar", "001" to "world", "150" to "Europe",
)

/**
 * The words a track's name may say its language's script or region in, per
 * language, and the subtag each stands for: chino-stream names a track by
 * its language and what its title says besides ("Chinese · Simplified",
 * "Portuguese · Brazilian"), where the file tags the track "chi" or "por"
 * alone. Only for the language named: "Canadian" makes French (Canada), and
 * says nothing of a German track.
 */
private val VARIANT_WORDS: Map<String, List<Pair<Regex, String>>> = mapOf(
    "zh" to listOf(
        words("simplified", "hans", cjk = listOf("简体", "簡體")) to "Hans",
        words("traditional", "hant", cjk = listOf("繁體", "繁体")) to "Hant",
    ),
    "pt" to listOf(
        words("brazilian", "brazil", "brasil", "brasileiro", "brasileira", "pt-br") to "BR",
        words("european", "portugal", "pt-pt") to "PT",
    ),
    "es" to listOf(
        words("latin american", "latin america", "latinoamericano", "latinoamérica", "latino", "latam", "es-419") to "419",
        words("castilian", "castellano", "european", "spain", "españa", "es-es") to "ES",
        words("mexican", "mexico", "méxico") to "MX",
    ),
    "fr" to listOf(
        words("canadian", "canada", "québécois", "quebecois", "fr-ca") to "CA",
        words("parisian", "france", "european", "fr-fr") to "FR",
        words("belgian", "belgium") to "BE",
        words("swiss") to "CH",
    ),
    "en" to listOf(
        words("british", "united kingdom", "en-gb", upper = listOf("UK")) to "GB",
        words("american", "united states", "en-us", upper = listOf("US")) to "US",
        words("australian", "australia") to "AU",
        words("canadian", "canada") to "CA",
    ),
    "de" to listOf(
        words("swiss", "schweiz") to "CH",
        words("austrian", "österreich") to "AT",
    ),
    "nl" to listOf(words("flemish", "vlaams", "belgian") to "BE"),
)

/** One pattern for any of [ws] as whole words in any case, of [upper] as
 *  whole words in capitals only ("US", not "us"), and of [cjk] anywhere:
 *  Chinese writes no spaces between words. */
private fun words(vararg ws: String, upper: List<String> = emptyList(), cjk: List<String> = emptyList()): Regex {
    val whole = { w: String, caseless: Boolean ->
        "(?<![\\p{L}\\p{N}])" + (if (caseless) "(?iu:" else "(?:") + Regex.escape(w) + ")(?![\\p{L}\\p{N}])"
    }
    val alternatives = ws.map { whole(it, true) } + upper.map { whole(it, false) } + cjk.map { Regex.escape(it) }
    return Regex(alternatives.joinToString("|") { "(?:$it)" })
}

// What each language calls itself, as a track's own title often gives it
// ("Deutsch", "中文"): chino-mobile's table.
private val ENDONYMS: Map<String, Set<String>> = mapOf(
    "af" to setOf("afrikaans"), "ar" to setOf("العربية", "عربي"), "bg" to setOf("български"), "bn" to setOf("বাংলা"),
    "ca" to setOf("català"), "cs" to setOf("čeština", "česky"), "da" to setOf("dansk"), "de" to setOf("deutsch"),
    "el" to setOf("ελληνικά"), "en" to setOf("english"), "es" to setOf("español", "castellano"), "et" to setOf("eesti"),
    "eu" to setOf("euskara"), "fa" to setOf("فارسی"), "fi" to setOf("suomi"), "fr" to setOf("français"),
    "ga" to setOf("gaeilge"), "gl" to setOf("galego"), "he" to setOf("עברית"), "hi" to setOf("हिन्दी"),
    "hr" to setOf("hrvatski"), "hu" to setOf("magyar"), "id" to setOf("bahasa indonesia"), "is" to setOf("íslenska"),
    "it" to setOf("italiano"), "ja" to setOf("日本語"), "ko" to setOf("한국어"), "lt" to setOf("lietuvių"),
    "lv" to setOf("latviešu"), "mk" to setOf("македонски"), "ms" to setOf("bahasa melayu"), "nb" to setOf("norsk bokmål", "norsk"),
    "nl" to setOf("nederlands"), "nn" to setOf("norsk nynorsk", "nynorsk"), "no" to setOf("norsk"), "pl" to setOf("polski"),
    "pt" to setOf("português"), "ro" to setOf("română"), "ru" to setOf("русский"), "sk" to setOf("slovenčina"),
    "sl" to setOf("slovenščina"), "sq" to setOf("shqip"), "sr" to setOf("српски", "srpski"), "sv" to setOf("svenska"),
    "th" to setOf("ไทย"), "tr" to setOf("türkçe"), "uk" to setOf("українська"), "vi" to setOf("tiếng việt"),
    "zh" to setOf("中文", "汉语", "漢語", "简体中文", "繁體中文", "简体", "繁體", "國語", "普通话", "粵語"),
)

/** The languages a language's name is looked up in besides its own: what
 *  a file's title may call it ("Allemand", "Tedesco", "德语" for German). */
private val NAMED_IN = listOf(
    "en", "de", "fr", "es", "it", "pt", "nl", "sv", "da", "no", "fi", "pl", "cs", "sk", "hu", "ro", "ru",
    "uk", "el", "tr", "ar", "he", "hi", "th", "vi", "id", "ja", "ko", "zh",
)

/** What a language is called, in its own words and in the languages of
 *  [NAMED_IN], lower-cased: the words a track's title may name it with. */
private val namesOf = HashMap<String, Set<String>>()

private fun languageWords(lang: String): Set<String> = synchronized(namesOf) {
    namesOf.getOrPut(lang) {
        val locale = Locale.forLanguageTag(lang)
        val inOthers = NAMED_IN.mapNotNull { other ->
            locale.getDisplayLanguage(Locale.forLanguageTag(other)).trim().lowercase().takeIf { it.isNotEmpty() && it != lang }
        }
        ENDONYMS[lang].orEmpty() + inOthers + languageName(lang).lowercase()
    }
}

/** Whether [title] only names the track's language again - in its own words
 *  ("Deutsch", "中文") or another language's ("German", "Allemand") - and so
 *  says nothing the label's name does not. */
private fun onlyNamesLanguage(title: String, lang: String?): Boolean {
    val key = normalizeLang(lang).ifEmpty { return false }
    return title.trim().lowercase() in languageWords(key)
}

private val PRIMARY = Regex("^[a-z]{2,3}$")

private fun subtags(code: String): List<String> = code.trim().replace('_', '-').split('-')

/** The code's language subtag, lower-cased ("pt" of "PT_br"); "" for none. */
private fun primarySubtag(code: String?): String = code?.let { subtags(it).first().lowercase() }.orEmpty()

/** Whether the code is "zxx": no linguistic content, no dialogue. */
fun isNoDialogue(code: String?): Boolean = primarySubtag(code) == "zxx"

/**
 * The language a code names, as one comparable key: the 639-1 code where there
 * is one ("ger", "deu", "de", "de-CH" are all "de"), else the 639-2/T code.
 * "" when the code names no language ("und", "zxx", empty, not a code).
 */
fun normalizeLang(code: String?): String {
    if (code == null) return ""
    val primary = subtags(code).first().lowercase()
    if (!PRIMARY.matches(primary) || primary in NO_LANGUAGE) return ""
    if (primary.length == 2) return OLD_1[primary] ?: primary
    val t = B_TO_T[primary] ?: primary
    return T_TO_1[t] ?: PLATFORM_T_TO_1[t] ?: t
}

/** The ISO 639-1 code of a language tag - "en" of "eng", "en-US", "EN" - or
 *  its three letters where there is none; null for none, "und" or "zxx". */
fun languageKey(tag: String?): String? = normalizeLang(tag).ifEmpty { null }

/** The code as a BCP 47 tag: the normalised language, with the script and
 *  region it carried, canonically cased ("pt_br" -> "pt-BR", "zh-hant" ->
 *  "zh-Hant"). "" when the code names no language. */
fun languageTag(code: String?): String {
    val lang = normalizeLang(code)
    if (lang.isEmpty() || code == null) return lang
    val rest = subtags(code).drop(1).filter { it.isNotEmpty() }.map { tag ->
        when {
            tag.length == 4 && tag.all { it.isLetter() } -> tag.lowercase().replaceFirstChar { it.uppercase() }
            (tag.length == 2 && tag.all { it.isLetter() }) || (tag.length == 3 && tag.all { it.isDigit() }) -> tag.uppercase()
            else -> tag.lowercase()
        }
    }
    return (listOf(lang) + rest).joinToString("-")
}

/** The script subtag of a canonical tag ("Hans" of "zh-Hans-CN"), null for none. */
internal fun scriptOf(tag: String): String? =
    tag.split('-').drop(1).takeWhile { it.length != 1 }.firstOrNull { it.length == 4 && it.all(Char::isLetter) }

/** The region subtag of a canonical tag ("BR" of "pt-BR", "419" of "es-419"), null for none. */
internal fun regionOf(tag: String): String? =
    tag.split('-').drop(1).takeWhile { it.length != 1 }
        .firstOrNull { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }

/** The language's English name, with the script and the region the code
 *  names in brackets after it ("ger" -> "German", "zh-Hans" -> "Chinese
 *  (Simplified)", "pt-BR" -> "Portuguese (Brazil)", "zh-Hant-TW" -> "Chinese
 *  (Traditional, Taiwan)"); the platform's English name where the table has
 *  none, else the code as it came; "No dialogue" for "zxx", "Multiple
 *  languages" for "mul", "Other language" for "mis", "Unknown" for none. */
fun languageName(code: String?): String {
    NOT_ONE_LANGUAGE[primarySubtag(code)]?.let { return it }
    val tag = languageTag(code)
    if (tag.isEmpty()) return UNKNOWN_LANGUAGE
    val lang = normalizeLang(code)
    val name = NAMES[lang] ?: platformName(lang) ?: return code.orEmpty().trim()
    val variant = variantNames(tag)
    return if (variant.isEmpty()) name else "$name (${variant.joinToString(", ")})"
}

/** The platform's English name of a language, where it knows one. */
private fun platformName(lang: String): String? =
    Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH)
        .takeIf { it.isNotBlank() && !it.equals(lang, ignoreCase = true) }

/** The names of the script and the region of a canonical tag ([languageTag]),
 *  in that order; a variant, an extension or a private use names nothing. */
private fun variantNames(tag: String): List<String> {
    var script: String? = null
    var region: String? = null
    for (sub in tag.split('-').drop(1)) {
        if (sub.length == 1) break
        when {
            script == null && region == null && sub.length == 4 && sub.all { it.isLetter() } -> script = SCRIPT_NAMES[sub] ?: sub
            region == null && ((sub.length == 2 && sub.all { it.isLetter() }) || (sub.length == 3 && sub.all { it.isDigit() })) ->
                region = REGION_NAMES[sub] ?: sub
        }
    }
    return listOfNotNull(script, region)
}

/** Whether the code says what the track is in: a language, no dialogue,
 *  several languages or one with no code. */
private fun hasLanguage(code: String?): Boolean =
    normalizeLang(code).isNotEmpty() || primarySubtag(code) in NOT_ONE_LANGUAGE

/** A track's language by name ("German", "No dialogue" for zxx), else its own
 *  label for a track in no language; null for neither. What a title's
 *  subtitle languages are listed as. */
fun languageOrLabel(tag: String?, label: String?): String? =
    if (hasLanguage(tag)) languageName(tag) else label?.trim()?.takeIf { it.isNotEmpty() }

/**
 * A subtitle's language as one tag, with the script or region its name says
 * where its own tag says none: "chi" named "Chinese · Simplified" is
 * "zh-Hans", "por" named "Portuguese · Brazilian" "pt-BR"; and its name
 * without those words. A tag that has a script keeps it, one with a region
 * keeps it; "" for a track in no language.
 */
internal data class TaggedTitle(val tag: String, val title: String)

internal fun subtitleTag(lang: String?, title: String?): TaggedTitle {
    val tag = languageTag(lang)
    val raw = title?.trim().orEmpty()
    val forLanguage = VARIANT_WORDS[normalizeLang(lang)] ?: return TaggedTitle(tag, raw)
    var script = scriptOf(tag)
    var region = regionOf(tag)
    var rest = raw
    for ((pattern, subtag) in forLanguage) {
        if (!pattern.containsMatchIn(rest)) continue
        val isScript = subtag.length == 4
        if (isScript && script == null) script = subtag
        else if (!isScript && region == null) region = subtag
        else continue
        rest = pattern.replace(rest, " ")
    }
    if (rest == raw) return TaggedTitle(tag, raw)
    val newTag = listOfNotNull(normalizeLang(lang), script, region).joinToString("-")
    return TaggedTitle(newTag, rest.replace(EMPTY_BRACKETS, " ").replace(SEPARATOR_RUN, " $1 ").replace(SPACES, " ").replace(EDGE_SEPARATORS, ""))
}

/** What a subtitle menu is told about one track. */
data class SubtitleLabelInput(
    /** The language code as the track is tagged. */
    val lang: String?,
    /** Its own label: the sidecar's, the rendition's NAME, /play/info's name. */
    val title: String? = null,
    /** Forced: only the lines in another language than the audio's. A title
     *  that says so counts too. */
    val forced: Boolean = false,
    /** For the deaf and hard of hearing, where the player knows it other than
     *  by the title (an HLS rendition's characteristics); a title that says
     *  so ([isSdhSubtitle]) counts too. */
    val sdh: Boolean = false,
)

/** What a forced subtitle is, after its language ("English · foreign-language
 *  parts only"): the lines a film shows in another language than its audio's,
 *  not the dialogue. */
const val FORCED_SUBTITLE_KIND = "foreign-language parts only"

/** What a subtitle for the deaf and hard of hearing is, after its language
 *  ("English · for deaf and hard of hearing"). */
const val SDH_SUBTITLE_KIND = "for deaf and hard of hearing"

// What a track's own title may say of a forced subtitle.
private val FORCED_WORDS = Regex(
    """(?<![A-Za-z])(foreign[ -]language parts( only)?|foreign parts( only)?|forced( narrative| subtitles?| subs)?)(?![A-Za-z])""",
    RegexOption.IGNORE_CASE,
)

private val SDH_WORDS = Regex(
    """(^|[^A-Za-z])(SDH|CC|HoH|closed[ -]captions?|hearing[ -]impaired|hard[ -]of[ -]hearing|deaf)([^A-Za-z]|$)""",
    RegexOption.IGNORE_CASE,
)

/** The HLS characteristic of a subtitle for the deaf and hard of hearing. */
private const val DESCRIBES_SOUND = "public.accessibility.describes-music-and-sound"

/** Whether a subtitle is for the deaf and hard of hearing: its name says so
 *  ("English SDH", "English (CC)", "for deaf and hard of hearing"), or its
 *  HLS characteristics do. */
fun isSdhSubtitle(name: String?, characteristics: String? = null): Boolean =
    characteristics?.contains(DESCRIBES_SOUND, ignoreCase = true) == true ||
        (name != null && SDH_WORDS.containsMatchIn(name))

/** Whether a subtitle's own title says it is a forced one ("English (Forced)",
 *  "Forced narrative", "foreign-language parts only"). */
fun saysForced(title: String?): Boolean = title != null && FORCED_WORDS.containsMatchIn(title)

// What a track's own title may say of a subtitle for the deaf and hard of
// hearing - the words isSdhSubtitle reads, and the phrases they are part of.
private val SDH_PHRASES = Regex(
    """(?<![A-Za-z])((for )?(the )?deaf( and | & | ?/ ?)hard[ -]of[ -]hearing|(for )?(the )?hard[ -]of[ -]hearing|(for )?(the )?hearing[ -]impaired|closed[ -]captions?|SDH|CC|HoH|deaf)(?![A-Za-z])""",
    RegexOption.IGNORE_CASE,
)

// A number a server gave a title to keep it unique in its group ("English
// (2)"); the menu shows each subtitle once, by its label.
private val TRAILING_NUMBER = Regex("""\s*\(\d+\)$""")

private val SEPARATOR_RUN = Regex("""\s*([·\-–—:,|/])(\s*[·\-–—:,|/])+\s*""")
private val SEPARATOR_AFTER_OPEN = Regex("""([(\[])\s*[·\-–—:,|/]+\s*""")
private val SEPARATOR_BEFORE_CLOSE = Regex("""\s*[·\-–—:,|/]+\s*([)\]])""")

private val CODE_LIKE = Regex("^[a-z]{2,3}([-_][a-z0-9]+)*$", RegexOption.IGNORE_CASE)

/** A title that is only a language code: the track's own again ("eng" on
 *  English), or one that names no language ("und"). */
private fun sameLanguageCode(title: String, lang: String?): Boolean {
    if (!CODE_LIKE.matches(title)) return false
    val named = normalizeLang(title)
    return named.isEmpty() || named == normalizeLang(lang)
}

/** A track's own title without what it says of the track's kind - forced,
 *  for the deaf and hard of hearing - or a number that only kept it unique:
 *  "English SDH" is "English", "German (Forced)" "German", "SDH" nothing. */
private fun titleWithoutKind(title: String): String =
    title.replace(TRAILING_NUMBER, "")
        .replace(FORCED_WORDS, " ")
        .replace(SDH_PHRASES, " ")
        .replace(EMPTY_BRACKETS, " ")
        .replace(SEPARATOR_AFTER_OPEN, "$1")
        .replace(SEPARATOR_BEFORE_CLOSE, "$1")
        .replace(EMPTY_BRACKETS, " ")
        .replace(SEPARATOR_RUN, " $1 ")
        .replace(SPACES, " ")
        .replace(EDGE_SEPARATORS, "")

/**
 * One subtitle's label: the language's name, its script or region in it -
 * from its tag, else from the words of its title that say one ("German",
 * "Chinese (Simplified)", "No dialogue" for zxx); the track's own title where
 * it says more than that ("English · Commentary", "Chinese (Simplified) ·
 * AI") - not where it only names the language again, in any language
 * ("Deutsch", "中文", "Allemand") - or alone for a track tagged with no
 * language, else "Unknown"; then its
 * kind in words - "foreign-language parts only" for a forced one, "for deaf
 * and hard of hearing" for one with sound described, both in that order. A
 * title that says the kind itself ("English SDH", "German (forced)") says it
 * once, in these words.
 */
fun subtitleLabel(track: SubtitleLabelInput): String {
    val raw = track.title?.trim().orEmpty()
    val forced = track.forced || saysForced(raw)
    val sdh = track.sdh || isSdhSubtitle(raw)
    val tagged = subtitleTag(track.lang, raw)
    val title = titleWithoutKind(tagged.title)
    val name = if (tagged.tag.isNotEmpty()) languageName(tagged.tag) else languageName(track.lang)
    val label = when {
        title.isEmpty() || title.equals(name, ignoreCase = true) || sameLanguageCode(title, track.lang) ||
            onlyNamesLanguage(title, track.lang) -> name
        !hasLanguage(track.lang) -> title
        title.contains(name, ignoreCase = true) -> title
        // "English" on a British English track: the name says it already.
        name.contains(title, ignoreCase = true) -> name
        else -> {
            // "Spanish · Commentary" of a track the name made Spanish (Latin
            // America): the language once, in the name.
            val rest = withoutLeadingName(title, languageName(normalizeLang(track.lang)))
            if (rest.isEmpty() || onlyNamesLanguage(rest, track.lang)) name else "$name · $rest"
        }
    }
    val kinds = listOfNotNull(FORCED_SUBTITLE_KIND.takeIf { forced }, SDH_SUBTITLE_KIND.takeIf { sdh })
    return (listOf(label) + kinds).joinToString(" · ")
}

/** [title] without the language's name [base] it starts with ("Spanish ·
 *  Commentary" is "Commentary"); as it is where it does not. */
private fun withoutLeadingName(title: String, base: String): String {
    if (base.isEmpty() || !title.startsWith(base, ignoreCase = true)) return title
    val after = title.substring(base.length)
    if (!QUALIFIER_START.containsMatchIn(after)) return title
    return after.replace(LEADING_SEPARATORS, "").trim()
}

/** The subtitle labels, one per track, in order ([subtitleLabel]); where
 *  two tracks would still read the same, a number for the second and later
 *  ones ("German (2)"). The menu itself shows such a pair once
 *  ([subtitleMenu]). */
fun subtitleLabels(tracks: List<SubtitleLabelInput>): List<String> = numbered(tracks.map(::subtitleLabel))

/** The labels, the second and later of the ones that read the same numbered
 *  ("German (2)"). [key] says which read the same: by default the label. */
private fun numbered(labels: List<String>, key: (String, Int) -> String = { label, _ -> label }): List<String> {
    val seen = HashMap<String, Int>()
    return labels.mapIndexed { i, label ->
        val k = key(label, i)
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        if (n == 1) label else "$label ($n)"
    }
}

/** The codec a track's row shows: its codecs string ("mp4a.40.2"), else the
 *  one of its sample format. Media3 leaves a track's codecs unset where it
 *  cannot tell which of a variant's audio codecs is the track's: the 5.1
 *  E-AC-3 companions of a group that holds stereo AAC as well ("ec-3"). */
fun audioCodec(codecs: String?, sampleMimeType: String?): String? =
    codecs?.trim()?.takeIf { it.isNotEmpty() } ?: when (sampleMimeType) {
        "audio/eac3", "audio/eac3-joc" -> "ec-3"
        "audio/ac3" -> "ac-3"
        "audio/ac4" -> "ac-4"
        else -> null
    }

/** What an audio menu is told about one track. */
data class AudioLabelInput(
    /** The language tag as the track has it. */
    val lang: String?,
    /** What the track is called: the rendition's NAME. */
    val name: String? = null,
    /** What the menu shows beside the label ("Stereo · mp4a.40.2"). Two
     *  tracks that differ there are told apart there. */
    val detail: String? = null,
)

// A name that describes the source's audio format - a codec, a bitrate, a
// sample rate or depth ("AC3 5.1 @ 640 Kbps", "DTS-HD MA 5.1") - and so
// nothing of the track.
private val FORMAT_WORDS = Regex(
    """(^|[^A-Za-z0-9])(dts(-hd)?|truehd|atmos|dolby|e?-?ac-?3|ddp?\+?|aac|flac|l?pcm|opus|mp3|vorbis|lossless|master audio|\d+ ?k?hz|\d* ?[km]bps|kb/s|\d+[- ]?bit)(?![A-Za-z0-9])""",
    RegexOption.IGNORE_CASE,
)

// A channel layout, which goes from a name that names the track ("Commentary
// 5.1" is "Commentary"): the menu shows the channels beside it.
private val LAYOUT_WORDS = Regex(
    """(^|[^A-Za-z0-9.])(mono|stereo|surround|[1-9]\.[0-2]|\d{1,2} ?ch(annels?)?)(?![A-Za-z0-9.])""",
    RegexOption.IGNORE_CASE,
)

// A name that only numbers the track ("Track 2", "Audio Track 1", "2").
private val NUMBERED = Regex("""^(audio|sound|track|stream|[\s#])*\d*$""", RegexOption.IGNORE_CASE)

private val EMPTY_BRACKETS = Regex("""\(\s*\)|\[\s*]""")
private val SPACES = Regex("""\s+""")
private val EDGE_SEPARATORS = Regex("""^[\s\-–—:·,|/]+|[\s\-–—:·,|/]+$""")
private val LEADING_SEPARATORS = Regex("""^[\s\-–—:·,|]+""")
private val QUALIFIER_START = Regex("""^([\s\-–—:·,|(\[]|$)""")

/** What a track's name says about it, or "" when it says nothing: none, a
 *  format, a number, a language code. */
private fun trackName(name: String?, tag: String?): String {
    val raw = name?.trim().orEmpty()
    if (raw.isEmpty() || FORMAT_WORDS.containsMatchIn(raw)) return ""
    val t = raw.replace(LAYOUT_WORDS, "$1")
        .replace(EMPTY_BRACKETS, "")
        .replace(SPACES, " ")
        .replace(EDGE_SEPARATORS, "")
    if (t.isEmpty() || NUMBERED.matches(t) || sameLanguageCode(t, tag)) return ""
    return t
}

/** What a track's name adds to its label ("English Commentary" on an English
 *  track: "Commentary"); "" when nothing. */
private fun nameQualifier(name: String?, label: String, tag: String?): String {
    var t = trackName(name, tag)
    if (t.startsWith(label, ignoreCase = true) && QUALIFIER_START.containsMatchIn(t.substring(label.length))) {
        t = t.substring(label.length).replace(LEADING_SEPARATORS, "").trim()
        if ((t.startsWith("(") && t.endsWith(")")) || (t.startsWith("[") && t.endsWith("]"))) {
            t = t.substring(1, t.length - 1).trim()
        }
    }
    return if (t.isNotEmpty() && !NUMBERED.matches(t)) t else ""
}

/**
 * The audio menu's labels, one per track, in order: the language the track is
 * tagged with, by name ("German"; "No dialogue" for zxx). A track tagged with
 * none is called what its name says ("Commentary"), else "Unknown". Two that
 * would read the same, with the same detail, are told apart by their names
 * ("English · Commentary"), else numbered ("English (2)").
 */
fun audioLabels(tracks: List<AudioLabelInput>): List<String> {
    val bases = tracks.map { t ->
        if (hasLanguage(t.lang)) {
            val name = languageName(t.lang)
            // No name for the code: the track's own name before the code.
            if (name != t.lang.orEmpty().trim()) return@map name
        }
        trackName(t.name, t.lang).ifEmpty { if (hasLanguage(t.lang)) t.lang.orEmpty().trim() else UNKNOWN_LANGUAGE }
    }
    val key = { label: String, i: Int -> label + "\u0000" + tracks[i].detail.orEmpty() }
    val count = HashMap<String, Int>()
    bases.forEachIndexed { i, b -> count[key(b, i)] = (count[key(b, i)] ?: 0) + 1 }
    val labels = bases.mapIndexed { i, b ->
        if ((count[key(b, i)] ?: 0) < 2) return@mapIndexed b
        val q = nameQualifier(tracks[i].name, b, tracks[i].lang)
        if (q.isNotEmpty() && !q.equals(b, ignoreCase = true)) "$b · $q" else b
    }
    return numbered(labels, key)
}
