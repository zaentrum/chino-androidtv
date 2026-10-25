package cloud.nalet.chino.tv.ui.player

import androidx.media3.common.MimeTypes
import cloud.nalet.chino.tv.data.api.InfoTrack
import cloud.nalet.chino.tv.data.api.SidecarSubtitle
import java.text.Collator
import java.util.Locale

/*
 * The subtitles of a playback: which tracks the player loads, how the menu
 * lists them, and which one comes on by itself. Pure: SubtitleChoicesTest
 * runs it on the JVM.
 *
 * A title's subtitles are its sidecars (GET /items/{id}/subtitles, each at
 * /api/v1/play/subs/{id}.vtt) and, for a title played from its original
 * file, the text streams embedded in the file, which /play/info lists and
 * chino-stream extracts as WebVTT on demand (/items/{id}/play/subtitles/
 * {index}.vtt). The player side-loads both; Media3 fetches a side-loaded
 * subtitle only once it is switched on, so a file with forty tracks costs one
 * extraction, the one watched.
 *
 * The menu (docs/chino, subtitles): "off" first; then the tracks in the
 * viewer's preferred subtitle language (Settings), then those in each of the
 * subtitle languages the server is set up for (GET /subtitles/languages, its
 * setting's order); then one entry, "more languages", that opens the rest of
 * the title's tracks, in alphabetical order of their languages' names - a
 * packaged title's too, whose package keeps what its source brought. The
 * menu never lists every track a file brings: where the server names no
 * subtitle languages (an older one, none set), the languages on top are the
 * viewer's own - the subtitle setting's, the audio setting's where it isn't
 * Original, the TV's - and the rest is under "more languages" as before.
 * A language on top, the server's or the viewer's, named with its region or
 * script or without, brings all of its tracks there, AI ones included: a
 * language with its region or script counts as that language, so a server
 * naming Simplified Chinese shows Traditional Chinese in the Chinese block
 * too, never under "more languages", and the setting's Traditional Chinese
 * and the server's Simplified are one block where Chinese first comes,
 * never split by the languages between. Within a language each region or
 * script has a run of its own,
 * the bare name first and the others in alphabetical order; within a run
 * the plain track comes first, then the one for the deaf and hard of
 * hearing, then the foreign-language parts only, then one the platform made
 * ("· AI"). Tracks that would read the same show once, never numbered: one
 * inside the file before one the server attached, a text one before a
 * picture one, otherwise the first in the file; an untagged one that reads
 * as a language ("English" on "und") stands for its twin, in its language.
 *
 * What comes on by itself: the viewer's preferred subtitle language, where
 * the title has it and the audio is in another language; else the
 * foreign-language parts only in the language of the audio that plays.
 * Nothing else - subtitles in the audio's own language are the viewer's to
 * pick, and no track comes on because it comes first or a file flags it
 * default. The player decides as the playback starts and again
 * whenever the audio's language changes, until the viewer picks in the menu;
 * from then on the subtitles are theirs, Off included, for the rest of the
 * playback (SubtitleSession). One subtitle at a time: picking one turns the
 * other off.
 */

/** The subtitle formats that are pictures rather than text. */
private val IMAGE_SUBTITLES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)

/** Whether a subtitle track of [sampleMimeType] is pictures, not text. One
 *  Media3 parsed as it loaded it has its own format in [codecs]. */
fun isImageSubtitle(sampleMimeType: String?, codecs: String?): Boolean {
    val mime = if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) codecs else sampleMimeType
    return mime?.lowercase() in IMAGE_SUBTITLES
}

/** The codecs of embedded subtitle streams that are pictures: chino-stream's
 *  extractor cannot make WebVTT of them, so they are not offered. */
private val IMAGE_CODECS = setOf("hdmv_pgs_subtitle", "pgssub", "pgs", "dvd_subtitle", "dvdsub", "vobsub", "dvb_subtitle", "dvbsub", "dvb", "xsub")

/** The id prefix of a side-loaded sidecar in the player ("side:<sidecar id>"). */
const val SIDECAR_ID = "side:"

/** The id prefix of a side-loaded embedded stream ("emb:<stream index>"). */
const val EMBEDDED_ID = "emb:"

/** A subtitle the player loads beside the stream: where it is, what Media3 is
 *  told it is. */
data class SideLoadedSubtitle(
    /** "side:<sidecar id>" or "emb:<stream index>": what the menu knows it by. */
    val id: String,
    val url: String,
    val mimeType: String,
    val language: String?,
    val label: String?,
    val forced: Boolean,
    val sdh: Boolean,
)

/**
 * The subtitles to side-load: the [sidecars], their URLs as they are
 * ([SidecarSubtitle.url], the stream token on them), then the text streams
 * of [embedded] that have a stream index, each at [embeddedUrl] of it. The
 * MIME type follows the sidecar's format (PGS and the other picture formats
 * go to their own decoders); an embedded stream is WebVTT, as chino-stream
 * extracts it. Forced and SDH as chino-api, the file or the name says.
 */
fun sideLoadedSubtitles(
    sidecars: List<SidecarSubtitle>,
    embedded: List<InfoTrack>,
    embeddedUrl: (Int) -> String,
): List<SideLoadedSubtitle> {
    val kinds = sidecarKinds(sidecars)
    val out = ArrayList<SideLoadedSubtitle>()
    sidecars.forEachIndexed { k, s ->
        out += SideLoadedSubtitle(
            id = SIDECAR_ID + s.id,
            url = s.url,
            mimeType = when (s.format?.lowercase()) {
                "pgs" -> MimeTypes.APPLICATION_PGS
                "vobsub" -> MimeTypes.APPLICATION_VOBSUB
                "dvb" -> MimeTypes.APPLICATION_DVBSUBS
                "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                else -> MimeTypes.TEXT_VTT
            },
            language = s.lang.takeIf { it.isNotBlank() },
            label = s.label.takeIf { it.isNotBlank() },
            forced = kinds[k] == SubtitleKind.FORCED,
            sdh = kinds[k] == SubtitleKind.SDH,
        )
    }
    for (t in embedded) {
        val index = t.index ?: continue
        if (index < 0 || t.codec?.lowercase() in IMAGE_CODECS) continue
        val name = t.name?.takeIf { it.isNotBlank() } ?: t.title?.takeIf { it.isNotBlank() }
        out += SideLoadedSubtitle(
            id = EMBEDDED_ID + index,
            url = embeddedUrl(index),
            mimeType = MimeTypes.TEXT_VTT,
            language = t.language?.takeIf { it.isNotBlank() },
            label = name,
            forced = t.forced || saysForced(name),
            sdh = isSdhSubtitle(name),
        )
    }
    return out
}

/** The menu's id of a track Media3 lists: the side-loaded subtitle's own
 *  ("emb:4" of the "3:emb:4" a merged source makes of it), null for another
 *  track (a rendition of the master). */
fun sideLoadedId(formatId: String?): String? {
    if (formatId == null) return null
    for (prefix in listOf(SIDECAR_ID, EMBEDDED_ID)) {
        val at = formatId.indexOf(prefix)
        if (at == 0 || (at > 0 && formatId[at - 1] == ':' && formatId.substring(0, at - 1).all { it.isDigit() || it == ':' })) {
            return formatId.substring(at)
        }
    }
    return null
}

/** What the menu is told about one subtitle track the player has. */
data class SubtitleTrackInfo(
    /** What the menu knows it by: [SIDECAR_ID] or [EMBEDDED_ID] and its own
     *  id, else Media3's ("<group>#<index>", a rendition of the master). */
    val id: String,
    /** The language tag as the track has it. */
    val lang: String?,
    /** Its own label: the sidecar's, /play/info's name, the rendition's NAME. */
    val title: String? = null,
    val forced: Boolean = false,
    val sdh: Boolean = false,
    /** Pictures (PGS and the other bitmap formats), not text. */
    val image: Boolean = false,
) {
    /** One of the title's sidecars: attached to the title, where an embedded
     *  stream or a rendition is part of its file. */
    val sidecar: Boolean get() = id.startsWith(SIDECAR_ID)
}

/** A subtitle's kind, in the order a language lists them. */
enum class SubtitleKindOrder { PLAIN, SDH, FORCED, FORCED_SDH }

/** One entry of the subtitle menu. */
data class SubtitleEntry(
    val id: String,
    /** [subtitleLabel]: "Chinese (Simplified) · AI". */
    val label: String,
    /** Its language with the script or region it names ("zh-Hans"); "" for none. */
    val tag: String,
    val kind: SubtitleKindOrder,
    val image: Boolean,
    val sidecar: Boolean,
    /** Made by the platform, as its label says ("· AI"): after a language's
     *  own tracks. */
    val ai: Boolean = false,
) {
    val forced: Boolean get() = kind == SubtitleKindOrder.FORCED || kind == SubtitleKindOrder.FORCED_SDH
}

/** The subtitle menu: [top] the languages that matter here, [more] what
 *  "more languages" opens - empty when the title has nothing more. */
data class SubtitleMenu(val top: List<SubtitleEntry>, val more: List<SubtitleEntry>) {
    /** Every entry, in the menu's order. */
    val all: List<SubtitleEntry> get() = top + more
}

/** What the subtitle menu's first row and Settings' subtitle setting without
 *  a language say: "off", one word in both (docs/chino, subtitles). */
const val SUBTITLES_OFF_NAME = "off"

/** Whether [pref] - Settings' subtitle language - asks for subtitles: a
 *  language, not "off" or nothing. */
fun asksForSubtitles(pref: String?): Boolean =
    !pref.isNullOrBlank() && !pref.trim().equals("off", ignoreCase = true) && normalizeLang(pref).isNotEmpty()

/** The script a canonical tag writes in: its own, else the one its region
 *  writes Chinese in ("zh-TW" is Traditional, "zh-CN" Simplified); null for
 *  none known. */
private fun scriptIn(tag: String): String? {
    scriptOf(tag)?.let { return it }
    if (tag.substringBefore('-') != "zh") return null
    return when (regionOf(tag)) {
        "CN", "SG", "MY" -> "Hans"
        "TW", "HK", "MO" -> "Hant"
        else -> null
    }
}

/**
 * How well a track tagged [tag] is in the language [want] asks for: 0 the
 * very one ("zh-Hans" for "zh-Hans", "en" for "en"), 1 the language without
 * the script or region one of them names ("en-GB" for "en", an unmarked
 * Chinese track for "zh-Hans"), null another language, script or region
 * ("zh-Hant" is no "zh-Hans", "pt-PT" no "pt-BR").
 */
fun languageMatch(want: String, tag: String): Int? {
    val w = languageTag(want)
    if (w.isEmpty() || tag.isEmpty() || w.substringBefore('-') != tag.substringBefore('-')) return null
    var loose = false
    val (ws, ts) = scriptIn(w) to scriptIn(tag)
    if (ws != null || ts != null) {
        if (ws != null && ts != null && ws != ts) return null
        if (ws == null || ts == null) loose = true
    }
    val (wr, tr) = regionOf(w) to regionOf(tag)
    if (wr != null || tr != null) {
        // A script tells the region's script: zh-Hant-TW and zh-Hant are one.
        val scriptSaysIt = ws != null && ws == ts
        if (wr != null && tr != null && wr != tr) return null
        if ((wr == null || tr == null) && !scriptSaysIt) loose = true
    }
    return if (loose) 1 else 0
}

/** Whether two subtitles' tags are the same language: [languageMatch] (a
 *  script or region only one names is no difference); two in no language
 *  are alike. */
fun sameLanguage(a: String, b: String): Boolean =
    if (a.isEmpty() || b.isEmpty()) a == b else languageMatch(a, b) != null

/** The word a track the platform made carries in its label (docs/chino words). */
private val AI_WORD = Regex("(?<![\\p{L}\\p{N}])AI(?![\\p{L}\\p{N}])")

private fun kindOf(forced: Boolean, sdh: Boolean): SubtitleKindOrder = when {
    forced && sdh -> SubtitleKindOrder.FORCED_SDH
    forced -> SubtitleKindOrder.FORCED
    sdh -> SubtitleKindOrder.SDH
    else -> SubtitleKindOrder.PLAIN
}

/** The menu entry of one track: its label and tag by [subtitleLabel] and
 *  [subtitleLanguage], its kind as its flags or its title say it. */
fun subtitleEntry(track: SubtitleTrackInfo): SubtitleEntry {
    val input = SubtitleLabelInput(track.lang, track.title, track.forced, track.sdh)
    val forced = track.forced || saysForced(track.title)
    val sdh = track.sdh || isSdhSubtitle(track.title)
    return SubtitleEntry(
        id = track.id,
        label = subtitleLabel(input),
        tag = subtitleLanguage(track.lang, track.title),
        kind = kindOf(forced, sdh),
        image = track.image,
        sidecar = track.sidecar,
        ai = track.title?.let { AI_WORD.containsMatchIn(it) } == true,
    )
}

/**
 * Which of two tracks that read the same the menu shows, [a] the one that
 * comes first: the file's own, timed to this file, before a sidecar; else a
 * text one before a picture one; else the first. Where the one shown is
 * tagged with no language and reads as one only by its title ("English" on
 * an "und" track), it takes the other's tag, so it is listed with that
 * language and comes on by it. chino-web's better and standsForBoth.
 */
private fun standsForBoth(a: SubtitleEntry, b: SubtitleEntry): SubtitleEntry {
    val kept = when {
        a.sidecar != b.sidecar -> if (a.sidecar) b else a
        a.image != b.image -> if (a.image) b else a
        else -> a
    }
    val other = if (kept === a) b else a
    return if (kept.tag.isEmpty() && other.tag.isNotEmpty()) kept.copy(tag = other.tag) else kept
}

/** English alphabetical order, accents after their letters ("Māori"). */
private val ALPHABETICAL: Collator = Collator.getInstance(Locale.ENGLISH)

/**
 * The language a device is set to, as a tag the menus compare with: its
 * language and the script it writes in ("de", "en", "zh-Hans" for a TV in
 * Chinese of China, "zh-Hant" of Taiwan); "" for none. Its region is left
 * out: a TV in English of the United States reads English, any region.
 */
fun deviceLanguage(locale: Locale): String {
    val tag = languageTag(locale.toLanguageTag())
    if (tag.isEmpty()) return ""
    val base = normalizeLang(tag)
    return scriptIn(tag)?.let { "$base-$it" } ?: base
}

/**
 * The viewer's own languages besides the subtitle setting's, as a menu
 * without the server's languages lists them first (docs/chino, subtitles:
 * the menu): the audio setting's ([audioSetting]) where it is a language
 * and not Original ("orig"), then the device's ([device], [deviceLanguage]).
 */
fun ownLanguages(audioSetting: String?, device: String?): List<String> = buildList {
    audioSetting?.trim()?.takeIf { it.isNotEmpty() && !it.equals("orig", ignoreCase = true) }
        ?.let(::languageTag)?.takeIf { it.isNotEmpty() }?.let(::add)
    device?.let(::languageTag)?.takeIf { it.isNotEmpty() }?.let(::add)
}

/**
 * The menu of [tracks] (in the player's order) for a viewer whose subtitle
 * language is [preferred] (Settings: a code or "off") on a server set up for
 * [serverLanguages] (BCP 47 tags, its order; none from an older server)
 * (docs/chino, subtitles: the menu). It never lists every track a file
 * brings:
 *
 * - [top]: the preferred language's tracks, then each server language's,
 *   each the whole language - its regions and scripts and its AI tracks
 *   with it, with or without the region or script it is named by: the
 *   server's "zh-Hans" brings "Chinese (Traditional)" too, never leaving it
 *   under more languages. A language asked for again by another script or
 *   region - the server's "zh-Hans" after a viewer's "zh-Hant", a server's
 *   "pt-BR" after a viewer's "pt-PT" - is that language's one block, where
 *   the language first comes: "Chinese", "Chinese (Simplified) · AI",
 *   "Chinese (Traditional)", then the others, never Chinese split by English
 *   and German.
 * - Where the server names no language, the languages on [top] are the
 *   viewer's own: [preferred], then [own] - the audio setting's language
 *   unless it is Original, the device's ([ownLanguages]) - each the whole
 *   language in the same way: a viewer's "zh-Hant" puts Chinese on top,
 *   "Chinese", "Chinese (Simplified)", "Chinese (Traditional)" in the order
 *   below, never only its own script.
 * - [more]: the rest, the languages in alphabetical order of their names, a
 *   track in no language by its own.
 *
 * Within a language each region or script has a run of its own, the bare
 * name first, the others in alphabetical order ("Chinese", "Chinese
 * (Simplified)", "Chinese (Simplified) · AI", "Chinese (Traditional)"); within
 * a run plain, for the deaf and hard of hearing, foreign-language parts only,
 * then the platform's ("· AI"), each in the file's order - the file's own
 * tracks (a package's renditions, an original's streams) before the sidecars
 * attached to it. Tracks that would read the same show once, in the first's
 * place, never numbered ([standsForBoth]).
 */
fun subtitleMenu(
    tracks: List<SubtitleTrackInfo>,
    preferred: String?,
    serverLanguages: List<String>,
    own: List<String> = emptyList(),
): SubtitleMenu {
    data class Item(val entry: SubtitleEntry, val order: Int)
    // The file's order: its own tracks before the sidecars attached to it.
    val fileOrder = compareBy<Item> { it.entry.sidecar }.thenBy { it.order }
    val inFile = tracks.mapIndexed { i, t -> Item(subtitleEntry(t), i) }.sortedWith(fileOrder)
    // One of each name, in the place of the first of them.
    val byLabel = LinkedHashMap<String, Item>()
    for (item in inFile) {
        val seen = byLabel[item.entry.label]
        byLabel[item.entry.label] = if (seen == null) item else seen.copy(entry = standsForBoth(seen.entry, item.entry))
    }
    val unique = byLabel.values.toList()
    // A language's tracks: a run per region or script, the bare name first,
    // the others by their names; in a run by kind, then in the file's order.
    val kinds = compareBy<Item> { it.entry.ai }.thenBy { it.entry.kind }.then(fileOrder)
    fun runs(group: List<Item>): List<Item> {
        val tags = group.map { it.entry.tag }.distinct()
        val bare = tags.filter { it == normalizeLang(it) }
        val named = (tags - bare.toSet()).sortedWith(compareBy(ALPHABETICAL) { languageName(it) })
        return (bare + named).flatMap { tag -> group.filter { it.entry.tag == tag }.sortedWith(kinds) }
    }
    val named = serverLanguages.map(::languageTag).filter { it.isNotEmpty() }
    // Without the server's languages, the ones that matter are the viewer's.
    val ownOnTop = named.isEmpty()
    val wants = buildList {
        if (asksForSubtitles(preferred)) add(languageTag(preferred))
        addAll(if (ownOnTop) own.map(::languageTag).filter { it.isNotEmpty() } else named)
    }.distinct()
    val placed = HashSet<Item>()
    // On top, one block per language asked for, the whole language - the
    // server's "zh-Hans" brings the Traditional Chinese too, never leaving it
    // under more languages. A later one in a language already on top - the
    // server's "zh-Hans" after the setting's "zh-Hant" - is that language's
    // block, so its tracks stay together, in their runs; never split by the
    // languages between.
    val blocks = LinkedHashMap<String, MutableList<Item>>()
    for (want in wants) {
        val base = normalizeLang(want)
        val group = unique.filter { item -> item !in placed && normalizeLang(item.entry.tag) == base }
        if (group.isEmpty()) continue
        placed += group
        blocks.getOrPut(base.ifEmpty { want }) { ArrayList() } += group
    }
    // The rest by language, a track in no language on its own; in
    // alphabetical order, a language by its name, a track in no language by
    // its label.
    val rest = LinkedHashMap<String, MutableList<Item>>()
    for (item in unique.filter { it !in placed }) {
        val base = normalizeLang(item.entry.tag)
        rest.getOrPut(base.ifEmpty { "\u0000${item.order}" }) { ArrayList() } += item
    }
    val more = rest.values.map { group -> normalizeLang(group.first().entry.tag) to runs(group) }.sortedWith(
        compareBy(ALPHABETICAL) { (base, items) -> if (base.isEmpty()) items.first().entry.label else languageName(base) },
    )
    return SubtitleMenu(top = blocks.values.flatMap(::runs).map { it.entry }, more = more.flatMap { it.second }.map { it.entry })
}

/**
 * The subtitle that comes on by itself, or null for off: the viewer's
 * preferred subtitle language ([preferred]) where the title has it and the
 * audio that plays ([audioLang]) is in another, known, language - its first
 * track in the menu that is not the foreign-language parts only, else that
 * one; otherwise the foreign-language parts only in the language of the
 * audio, a text one before a picture one. Subtitles in the audio's own
 * language are the viewer's to pick; never another language, and never a
 * track because it comes first or is flagged default (docs/chino, subtitles).
 */
fun autoSubtitle(menu: SubtitleMenu, audioLang: String?, preferred: String?): SubtitleEntry? {
    val all = menu.all
    val audio = normalizeLang(audioLang)
    if (audio.isEmpty()) return null
    if (asksForSubtitles(preferred) && normalizeLang(preferred) != audio) {
        val want = languageTag(preferred)
        val inLang = all.filter { languageMatch(want, it.tag) != null }
        (inLang.firstOrNull { !it.forced } ?: inLang.firstOrNull())?.let { return it }
    }
    val forced = all.filter { it.forced && normalizeLang(it.tag) == audio }
    return forced.firstOrNull { !it.image } ?: forced.firstOrNull()
}

/**
 * Who decides a playback's subtitles. The player, as it starts and again
 * whenever the audio's language changes ([autoSubtitle]), until the viewer
 * picks in the subtitles menu; from then on the viewer, for the rest of the
 * playback - its reloads included: an Off stays off whatever the audio, and a
 * track picked stays on. A switch between a track's stereo and 5.1 renditions
 * is no change of language and changes nothing. Who decides and when as
 * chino-mobile's and chino-web's SubtitleSession has it; what the player
 * decides is [autoSubtitle].
 */
class SubtitleSession {
    /** The viewer has picked in the subtitles menu: a track, or Off. */
    var viewerPicked: Boolean = false
        private set

    /** Whether the player has decided at all. */
    private var decided = false

    /** The language ([normalizeLang]) of the audio the player last decided
     *  for. */
    private var decidedFor: String = ""

    /** The subtitle on - the player's choice or the viewer's - by its menu
     *  id; null for off. What a rebuilt player switches on again. */
    var chosen: String? = null
        private set

    /** Whether the player decides the subtitle now, for audio playing in
     *  [audioLang]: its first time, and whenever the language has changed
     *  since - never once the viewer has picked. */
    fun playerDecides(audioLang: String?): Boolean {
        if (viewerPicked) return false
        val lang = normalizeLang(audioLang)
        if (decided && lang == decidedFor) return false
        decided = true
        decidedFor = lang
        return true
    }

    /** The player's choice ([autoSubtitle]): [id] on, or null for off. */
    fun playerChose(id: String?) {
        if (!viewerPicked) chosen = id
    }

    /** The viewer picked [id] in the menu, or Off (null): theirs from now on. */
    fun viewerPicks(id: String?) {
        viewerPicked = true
        chosen = id
    }
}
