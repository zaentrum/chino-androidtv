package cloud.nalet.chino.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-device playback ergonomics — mirrors chino-web's lib/settings.ts
 * binge group (intro/credits skip, auto-play next, countdown). Stored in
 * DataStore Preferences because these don't need to sync across devices;
 * they're remote-control behaviour preferences for this TV.
 */
data class AppSettings(
    val autoSkipIntro: Boolean = true,
    val autoSkipCredits: Boolean = true,
    val autoPlayNext: Boolean = true,
    val countdownSec: Int = 5,
    /** ISO 639-1 code (en, de, …) or "off" to disable subtitle auto-pick. */
    val preferredSubLang: String = "off",
    /** ISO 639-1 code (en, de, …) or "orig" to keep the source default audio.
     *  Mirrors chino-web's audio.preferredLang (default "eng"). The player
     *  auto-selects the closest-matching audio track on first load; "orig"
     *  leaves whatever the source marks as default. */
    val preferredAudioLang: String = "en",
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val ds = context.applicationContext.settingsDataStore

    val flow: Flow<AppSettings> = ds.data.map { p ->
        AppSettings(
            autoSkipIntro = p[KEY_SKIP_INTRO] ?: true,
            autoSkipCredits = p[KEY_SKIP_CREDITS] ?: true,
            autoPlayNext = p[KEY_AUTOPLAY_NEXT] ?: true,
            countdownSec = p[KEY_COUNTDOWN_SEC] ?: 5,
            preferredSubLang = p[KEY_SUB_LANG] ?: "off",
            preferredAudioLang = p[KEY_AUDIO_LANG] ?: "en",
        )
    }

    suspend fun setAutoSkipIntro(v: Boolean) = ds.edit { it[KEY_SKIP_INTRO] = v }
    suspend fun setAutoSkipCredits(v: Boolean) = ds.edit { it[KEY_SKIP_CREDITS] = v }
    suspend fun setAutoPlayNext(v: Boolean) = ds.edit { it[KEY_AUTOPLAY_NEXT] = v }
    suspend fun setCountdownSec(v: Int) = ds.edit { it[KEY_COUNTDOWN_SEC] = v.coerceIn(1, 15) }
    suspend fun setPreferredSubLang(v: String) = ds.edit { it[KEY_SUB_LANG] = v }
    suspend fun setPreferredAudioLang(v: String) = ds.edit { it[KEY_AUDIO_LANG] = v }

    private companion object {
        val KEY_SKIP_INTRO = booleanPreferencesKey("binge_auto_skip_intro")
        val KEY_SKIP_CREDITS = booleanPreferencesKey("binge_auto_skip_credits")
        val KEY_AUTOPLAY_NEXT = booleanPreferencesKey("binge_auto_play_next")
        val KEY_COUNTDOWN_SEC = intPreferencesKey("binge_countdown_sec")
        val KEY_SUB_LANG = stringPreferencesKey("subtitles_preferred_lang")
        val KEY_AUDIO_LANG = stringPreferencesKey("audio_preferred_lang")
    }
}
