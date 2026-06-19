package cloud.nalet.chino.tv.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import cloud.nalet.chino.tv.R

/**
 * App-wide font families — mirrors chino-web's Google Fonts config in
 * Chino Logos.html (`--ff-ui: Inter; --ff-mono: JetBrains Mono`). The
 * TTFs live in app/src/main/res/font/ (filenames lowercased per Android
 * resource naming rules) and are referenced via R.font.*.
 *
 * Same families ship in chino-mobile via Compose Multiplatform resources;
 * the two apps look like family members because they consume the same
 * type. Cross-platform unification through tokens, not through code.
 */
val ChinoInterFamily: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val ChinoMonoFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_extrabold, FontWeight.ExtraBold),
)
