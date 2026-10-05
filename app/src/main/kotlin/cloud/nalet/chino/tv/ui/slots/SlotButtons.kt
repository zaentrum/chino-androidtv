package cloud.nalet.chino.tv.ui.slots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.SlotButton
import cloud.nalet.chino.tv.data.SlotKind
import cloud.nalet.chino.tv.ui.auth.rememberQrImage
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.QrCode

/** How an action button's POST went. */
enum class SlotActionState { Sending, Sent, Failed }

/**
 * An addon's buttons for a slot, as native TV buttons in the order given
 * (data/Slots.kt checked every row; the labels are the addon's). LEFT and
 * RIGHT move between them.
 *  - A link opens nothing on a TV: its button shows the address under the
 *    row, as a QR code and as text, for a phone to open. Pressing it again,
 *    or BACK, hides it.
 *  - An action POSTs ([onAction]), and its button says how that went: a check
 *    once the addon took it, an alert when it did not — press again to retry.
 */
@Composable
fun SlotButtons(
    buttons: List<SlotButton>,
    actionStates: Map<String, SlotActionState>,
    onAction: (SlotButton) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    // The link whose address shows, by key: one at a time, none for a new
    // set of buttons (a new query).
    var shown by remember(buttons) { mutableStateOf<String?>(null) }
    val shownLink = buttons.firstOrNull { it.key == shown && it.kind == SlotKind.Link }
    if (shownLink != null) {
        androidx.activity.compose.BackHandler { shown = null }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            buttons.forEachIndexed { idx, button ->
                SlotButtonView(
                    button = button,
                    state = actionStates[button.key],
                    onClick = {
                        when (button.kind) {
                            SlotKind.Link -> shown = if (shown == button.key) null else button.key
                            SlotKind.Action -> onAction(button)
                        }
                    },
                    modifier = if (idx == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        shownLink?.let { LinkAddress(it) }
    }
}

@Composable
private fun SlotButtonView(
    button: SlotButton,
    state: SlotActionState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Accent at rest like chino-web's slot buttons, white on focus like the
    // detail actions. Never disabled while it sends: a disabled button gives
    // up focus — the view model ignores the press instead.
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RectangleShape),
        colors = ButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        val (icon, description) = when {
            button.kind == SlotKind.Link -> Lucide.QrCode to null
            state == SlotActionState.Sent -> Lucide.Check to "Sent"
            state == SlotActionState.Failed -> Lucide.CircleAlert to "Not sent, press to try again"
            else -> null to null
        }
        if (icon != null) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = button.label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(if (state == SlotActionState.Sending) 0.6f else 1f),
        )
    }
}

/** A link's address for a phone: the QR code on white (cameras struggle with
 *  one on near-black), and the address beside it to type instead. */
@Composable
private fun LinkAddress(button: SlotButton) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(modifier = Modifier.background(Color.White).padding(10.dp)) {
            Image(
                bitmap = rememberQrImage(text = button.url, sizePx = 240),
                contentDescription = "QR code to open this on your phone",
                modifier = Modifier.size(160.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Scan with your phone — or visit:", color = ChinoMuted, fontSize = 15.sp)
            Text(
                text = button.url,
                color = ChinoAccent,
                fontSize = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
    }
}
