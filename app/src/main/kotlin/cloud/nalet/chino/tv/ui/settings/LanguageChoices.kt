package cloud.nalet.chino.tv.ui.settings

import cloud.nalet.chino.tv.ui.player.asksForSubtitles
import cloud.nalet.chino.tv.ui.player.languageName
import cloud.nalet.chino.tv.ui.player.languageTag
import java.text.Collator
import java.util.Locale

/*
 * The languages Settings offers for the audio and the subtitles (docs/chino,
 * subtitles: the setting; playback: the audio setting). Pure:
 * LanguageChoicesTest runs it on the JVM.
 *
 * The subtitles: "Off", then the server's subtitle languages, read from its
 * configuration (GET /v1/subtitles/languages) like the player's menu, in its
 * order - never a list fixed in the app; a server that names none offers
 * every language by name. The audio: "Original", then every language by
 * name, as the server names no audio languages. Each language by the name
 * the player's menus give it ([languageName]: "German", "Chinese
 * (Simplified)"), so one language has one name in the app. A language chosen
 * before that the list no longer has stays in it, so the setting says what
 * is set.
 */

/** One entry of a language setting: what is stored, what it is called. */
data class LanguageChoice(val code: String, val name: String)

/** The subtitle setting's value for no subtitles by themselves. */
const val SUBTITLES_OFF = "off"

/** The audio setting's value for each title's own language. */
const val AUDIO_ORIGINAL = "orig"

/** English alphabetical order, accents after their letters. */
private val ALPHABETICAL: Collator = Collator.getInstance(Locale.ENGLISH)

/** Every language the platform has a name for, by that name, in
 *  alphabetical order, each once. */
val EVERY_LANGUAGE: List<LanguageChoice> by lazy {
    Locale.getISOLanguages().asSequence()
        .map(::languageTag)
        .filter { it.isNotEmpty() }
        .distinct()
        .map { LanguageChoice(it, languageName(it)) }
        // A code the platform has no name for is no name to pick.
        .filter { !it.name.equals(it.code, ignoreCase = true) }
        .distinctBy { it.name }
        .sortedWith(compareBy(ALPHABETICAL) { it.name })
        .toList()
}

/** The subtitle setting's entries: "Off", then [serverLanguages] (the
 *  server's tags, its order), or every language where it names none. */
fun subtitleLanguageChoices(serverLanguages: List<String>, current: String?): List<LanguageChoice> =
    listOf(LanguageChoice(SUBTITLES_OFF, "Off")) +
        languages(serverLanguages, current?.takeIf { asksForSubtitles(it) })

/** The audio setting's entries: "Original", then every language. */
fun audioLanguageChoices(current: String?): List<LanguageChoice> =
    listOf(LanguageChoice(AUDIO_ORIGINAL, "Original")) +
        languages(emptyList(), current?.takeIf { it != AUDIO_ORIGINAL })

/** The languages of a setting: [serverLanguages] by name, or every language;
 *  [current], a language, among them where they lack it. */
private fun languages(serverLanguages: List<String>, current: String?): List<LanguageChoice> {
    val named = serverLanguages.map(::languageTag).filter { it.isNotEmpty() }.distinct()
        .map { LanguageChoice(it, languageName(it)) }
    val offered = named.ifEmpty { EVERY_LANGUAGE }
    val chosen = current?.let(::languageTag)?.takeIf { it.isNotEmpty() } ?: return offered
    if (offered.any { it.code == chosen }) return offered
    val withChosen = offered + LanguageChoice(chosen, languageName(chosen))
    return if (named.isEmpty()) withChosen.sortedWith(compareBy(ALPHABETICAL) { it.name }) else withChosen
}

/** The entry of [choices] that [value] - a stored setting - stands for:
 *  "zh-hans" is "zh-Hans", "ger" is "de"; null for none. */
fun chosenLanguage(choices: List<LanguageChoice>, value: String?): LanguageChoice? {
    if (value == null) return null
    choices.firstOrNull { it.code == value }?.let { return it }
    val tag = languageTag(value).takeIf { it.isNotEmpty() } ?: return null
    return choices.firstOrNull { it.code == tag }
}
