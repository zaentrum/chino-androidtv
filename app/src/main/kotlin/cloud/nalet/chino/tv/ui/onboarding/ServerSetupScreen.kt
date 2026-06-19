package cloud.nalet.chino.tv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.ui.theme.LogoMark

/**
 * First-run "connect to your server" screen for the neutral self-host client.
 * The user types their server address (on-screen keyboard), or one-taps the
 * build-flavor preset / a recent. On submit the VM probes the server and, on
 * success, persists the config and advances to device-flow sign-in.
 */
@Composable
fun ServerSetupScreen(
    viewModel: ServerSetupViewModel,
    onConnected: () -> Unit,
    /** Settings "Change server" back-out. Null on the first-run path (there's
     *  nowhere to cancel to). */
    onCancel: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val recents by viewModel.recents.collectAsState()
    var url by remember { mutableStateOf(viewModel.prefillUrl) }

    LaunchedEffect(state) {
        if (state is ServerSetupState.Done) onConnected()
    }

    val probing = state is ServerSetupState.Probing

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.width(640.dp),
            ) {
                LogoMark(sizeDp = 56)
                Text(
                    text = if (viewModel.changeServer) "Change server" else "Connect to your server",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Enter the address of your Chino server.",
                    color = Color(0xFF8B949E),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )

                UrlField(
                    value = url,
                    onChange = { url = it },
                    onSubmit = { viewModel.connect(url) },
                    enabled = !probing,
                )

                Button(
                    onClick = { viewModel.connect(url) },
                    enabled = !probing && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = if (probing) "Connecting…" else "Connect")
                }

                if (viewModel.presetUrl.isNotBlank() && url.trim() != viewModel.presetUrl) {
                    Button(
                        onClick = {
                            url = viewModel.presetUrl
                            viewModel.connect(viewModel.presetUrl)
                        },
                        enabled = !probing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Use ${viewModel.presetUrl.substringAfter("://")}")
                    }
                }

                (state as? ServerSetupState.Error)?.let {
                    Text(
                        text = it.message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                if (recents.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "Recent", color = Color(0xFF8B949E), fontSize = 14.sp)
                        recents.forEach { r ->
                            Button(
                                onClick = { url = r; viewModel.connect(r) },
                                enabled = !probing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = r.substringAfter("://"))
                            }
                        }
                    }
                }

                // Change-server back-out — only on the Settings entry path.
                if (onCancel != null) {
                    Button(
                        onClick = onCancel,
                        enabled = !probing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlField(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    // BasicTextField + styled wrapper — Compose for TV's TextField is still
    // alpha; mirrors the SearchScreen input. The on-screen keyboard handles
    // D-pad text entry; the IME "Go" key submits.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF58A6FF) else Color(0xFF30363D),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                enabled = enabled,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
            )
            if (value.isEmpty()) {
                Text(
                    // Neutral placeholder — no operator-specific URL on the
                    // store build.
                    text = "https://media.example.com",
                    color = Color(0xFF8B949E),
                    fontSize = 16.sp,
                )
            }
        }
    }
}
