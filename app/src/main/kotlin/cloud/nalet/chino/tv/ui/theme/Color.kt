package cloud.nalet.chino.tv.ui.theme

import androidx.compose.ui.graphics.Color

// Canonical dark palette — values mirror chino-web's tailwind 'nalet'
// tokens (chino-web tailwind.config.ts + index.css), the cross-client
// reference. Most components already hard-code these hexes; the theme
// tokens here must stay in lock-step so MaterialTheme-driven surfaces
// match the hard-coded ones side-by-side on web/mobile/TV. Keep names
// aligned with the web token vocabulary (bg, surface, border, muted,
// accent, …) so cross-platform design discussions reference the same
// words.
val ChinoBg = Color(0xFF0D1117)           // nalet.bg — primary canvas
val ChinoSurface = Color(0xFF161B22)      // nalet.surface
val ChinoSurfaceHi = Color(0xFF1C2128)    // nalet.surface-2
val ChinoBorder = Color(0xFF21262D)       // nalet.border
val ChinoBorderHi = Color(0xFF30363D)     // nalet.border-2
val ChinoFg = Color(0xFFE6E6E6)           // fg
val ChinoText = Color(0xFFC9D1D9)         // nalet.text / fg-2 — wordmark grey
val ChinoMuted = Color(0xFF8B949E)        // nalet.muted
val ChinoDim = Color(0xFF6E7787)          // fg-dim
val ChinoAccent = Color(0xFF58A6FF)       // nalet.accent — cloud-blue
val ChinoCyan = Color(0xFF00A4DC)         // cloud-cyan
val ChinoSignalGreen = Color(0xFF2EA043)  // signal-green — watched marker
val ChinoSignalAmber = Color(0xFFF2B233)  // signal-amber — intro segments
val ChinoError = Color(0xFFFF6B6B)
