package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Global UI down-scale. The AOSP TV emulator is 1920x1080 @ density 2.0, i.e.
 * only 960dp wide, so fixed-dp components render ~25% larger than on the
 * tablet (a 2560px landscape ≈ 1280dp wide). Scaling the effective density by
 * 0.75 widens the TV canvas to ~1280dp so the layout reads at the same
 * density as the tablet. Scales dp AND sp uniformly
 * (sp px = sp · fontScale · density).
 */
private const val UI_SCALE = 0.75f

/**
 * Terminal-style heading letter spacing from the design system (~-0.015em).
 */
private val HeadingTracking = (-0.015f).em

/**
 * Typography that applies JetBrains Mono to HEADING text styles ONLY
 * (display / headline / title), leaving body and label on the platform
 * default (Inter-equivalent sans).
 *
 * WHY headings-only, and why this is not the previously-disabled path:
 * the original global override supplied a full [Typography] whose *every*
 * style carried a custom [FontFamily] AND additionally overrode
 * LocalTextStyle app-wide. On the TV that forced a synchronous font
 * resolve for essentially every text node during the first-frame
 * composition, which hung startup / tripped ANR. Restricting the custom
 * family to the handful of heading styles (and NOT touching LocalTextStyle)
 * means the overwhelming majority of text nodes — body copy, labels,
 * metadata — still resolve against the default family with no extra font
 * load, so the startup font-resolve pressure that caused the hang is
 * avoided. Screens that draw their own fonts (e.g. LogoMark uses
 * jetbrains_mono_extrabold directly) are unaffected.
 *
 * If heading monospace ever reintroduces the startup hang on real
 * hardware, revert to `Typography()` (default sans) — this is the
 * minimal, heading-only surface, so that revert is a one-line change.
 */
private fun chinoTypography(mono: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.mono(): TextStyle =
        copy(fontFamily = mono, letterSpacing = HeadingTracking)
    return base.copy(
        displayLarge = base.displayLarge.mono(),
        displayMedium = base.displayMedium.mono(),
        displaySmall = base.displaySmall.mono(),
        headlineLarge = base.headlineLarge.mono(),
        headlineMedium = base.headlineMedium.mono(),
        headlineSmall = base.headlineSmall.mono(),
        titleLarge = base.titleLarge.mono(),
        titleMedium = base.titleMedium.mono(),
        titleSmall = base.titleSmall.mono(),
    )
}

// SQUARE — design system mandates zero corner radius everywhere.
private val ChinoSquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

private val ChinoDarkColors = darkColorScheme(
    primary = ChinoAccent,
    onPrimary = ChinoBg,
    background = ChinoBg,
    onBackground = ChinoText,
    surface = ChinoSurface,
    onSurface = ChinoText,
    surfaceVariant = ChinoSurfaceHi,
    onSurfaceVariant = ChinoMuted,
    border = ChinoBorder,
    error = ChinoError,
    onError = ChinoBg,
)

@Composable
fun ChinoTvTheme(content: @Composable () -> Unit) {
    // Headings/display/title text styles use JetBrains Mono (terminal look);
    // body/label stay on the platform default. LocalTextStyle is intentionally
    // NOT overridden here — see chinoTypography() for why the previous
    // full-global override was the source of the TV startup hang / ANR, and
    // why this heading-only surface avoids it.
    MaterialTheme(
        colorScheme = ChinoDarkColors,
        typography = chinoTypography(ChinoMonoFamily),
        shapes = ChinoSquareShapes,
    ) {
        // Down-scale the whole UI to tablet-like density (see UI_SCALE).
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = base.density * UI_SCALE,
                fontScale = base.fontScale,
            ),
            content = content,
        )
    }
}
