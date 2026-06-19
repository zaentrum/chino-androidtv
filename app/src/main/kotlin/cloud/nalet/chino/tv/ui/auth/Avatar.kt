package cloud.nalet.chino.tv.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.security.MessageDigest

/**
 * Round avatar: Gravatar image fetched from email's md5, with an initial-
 * on-coloured-circle fallback when there's no email or Gravatar returns
 * 404. Coil's `error` slot can't be a Composable, so we always render the
 * fallback layer BEHIND the AsyncImage — the image just covers it when it
 * loads, and the fallback shows through when it doesn't.
 *
 * Colour assigned deterministically from the seed (email or displayName)
 * so the same user always gets the same hue — useful for spotting "the red
 * one is mine" in the picker.
 */
@Composable
fun Avatar(
    displayName: String,
    email: String,
    size: Dp = 96.dp,
) {
    val seed = email.ifBlank { displayName }
    val bg = avatarColor(seed)
    // Two-letter initials from the first two words ("Andreas Meinen" -> "AM",
    // "andreas" -> "A"), matching the mobile/web avatar. Splits on whitespace
    // and common separators.
    val initial = displayName.split(Regex("[\\s._-]+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    val gravatar = email.takeIf { it.isNotBlank() }?.let {
        // d=404 → 404 on missing avatar so we fall through to the
        // background-rendered initial (rather than Gravatar's default
        // mystery-man placeholder).
        "https://www.gravatar.com/avatar/${md5(it.trim().lowercase())}?s=240&d=404"
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (gravatar != null) {
            AsyncImage(
                model = gravatar,
                contentDescription = "Avatar for $displayName",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

private fun md5(s: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Deterministic hue from a string — picks one of 12 muted Tailwind-flavoured
 *  hues so adjacent avatars in the picker look visually distinct without
 *  being garish. */
private fun avatarColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF58A6FF), // brand blue
        Color(0xFFFFB454), // amber
        Color(0xFF2EA043), // emerald
        Color(0xFF9E86FF), // violet
        Color(0xFFFF6B6B), // coral
        Color(0xFF14B8A6), // teal
        Color(0xFFE879F9), // fuchsia
        Color(0xFFEAB308), // mustard
        Color(0xFF38BDF8), // sky
        Color(0xFFF472B6), // pink
        Color(0xFF4ADE80), // lime
        Color(0xFFFB923C), // orange
    )
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    return palette[Math.floorMod(hash, palette.size)]
}
