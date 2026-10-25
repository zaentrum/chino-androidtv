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
val ChinoBg = Color(0xFF0B0F19)           // nalet.bg — primary canvas
val ChinoSurface = Color(0xFF11161F)      // nalet.surface
val ChinoSurfaceHi = Color(0xFF161B26)    // nalet.surface-2
val ChinoBorder = Color(0xFF1F2633)       // nalet.border
val ChinoBorderHi = Color(0xFF2A3142)     // nalet.border-2
val ChinoBorderControl = Color(0xFF646C7A) // nalet.border-control — a control's boundary, 3:1 on every background
val ChinoFg = Color(0xFFE6E6E6)           // fg
val ChinoText = Color(0xFFC9D1D9)         // nalet.text / fg-2 — wordmark grey
val ChinoMuted = Color(0xFFAEB8C2)        // nalet.muted / fg-muted
val ChinoDim = Color(0xFF7B8597)          // fg-dim — the dimmest text, 4.5:1 on the canvas (not on tints)
val ChinoAccent = Color(0xFF58A6FF)       // nalet.accent — cloud-blue
val ChinoCyan = Color(0xFF00A4DC)         // cloud-cyan
val ChinoSignalGreen = Color(0xFF2EA043)  // signal-green — watched marker
val ChinoSignalAmber = Color(0xFFF2B233)  // signal-amber — intro segments
val ChinoSignalRed = Color(0xFFF85149)    // signal-red — the raw red, not a state: the seek bar's credits
val ChinoError = Color(0xFFF85149)        // nalet.state-error — errors, a destructive action's red
val ChinoErrorBg = Color(0xFF25171C)      // nalet.state-error-bg — the error's tint, behind red text
// A label or an icon on a filled colour (blue, green, red, amber) is ChinoBg,
// the darkest background colour.
// The TV focus style (Focus.kt), the design system's tv focus (10-foot).
val ChinoFocusRing = ChinoCyan            // nalet.focus-ring — cloud-cyan, the ring around a focused item
val ChinoSurfaceFocus = Color(0xFF2A3142) // nalet.surface-focus — behind a focused row, plain button or chip
