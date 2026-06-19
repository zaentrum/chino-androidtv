package cloud.nalet.chino.tv.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.R

@Composable
fun DeviceCodeScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        // Reset on (re)entry so a stale Authenticated/Error state from a
        // prior visit doesn't short-circuit the QR. Then start() fires
        // afresh. Without this, after the user adds Account A and the
        // host pops back to AUTH later for re-login, the Authenticated
        // state would still be cached and onAuthenticated() would fire
        // immediately for the WRONG account.
        viewModel.reset()
        viewModel.start()
    }
    LaunchedEffect(state) {
        if (state is AuthUiState.Authenticated) onAuthenticated()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.auth_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                // Crossfade the {Idle/Starting placeholder, QR, Error} states
                // so the user doesn't see a hard pop from the dot to the full
                // QR arrangement. Authenticated case renders nothing — the
                // LaunchedEffect navigates away while the fade-out runs, so
                // the screen smoothly dims into the next route's mount.
                androidx.compose.animation.Crossfade(
                    targetState = state,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 220),
                    label = "auth_state_crossfade",
                ) { s -> when (s) {
                    AuthUiState.Idle, AuthUiState.Starting -> {
                        // Subtle spinner instead of the static "…" — matches
                        // the loading affordance the user sees during playback
                        // prep, so the visual vocabulary across the app is
                        // consistent. The Crossfade hides the placeholder
                        // entirely once the QR arrives, no hard pop.
                        Box(
                            modifier = Modifier.size(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "auth_spin")
                            val rotation by infinite.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                    animation = androidx.compose.animation.core.tween(
                                        durationMillis = 900,
                                        easing = androidx.compose.animation.core.LinearEasing,
                                    ),
                                ),
                                label = "auth_spin_rotation",
                            )
                            androidx.compose.foundation.Canvas(modifier = Modifier.size(48.dp)) {
                                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 4.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                                drawArc(
                                    color = Color(0x33FFFFFF),
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    style = stroke,
                                )
                                drawArc(
                                    color = Color(0xFF58A6FF),
                                    startAngle = rotation,
                                    sweepAngle = 270f,
                                    useCenter = false,
                                    style = stroke,
                                )
                            }
                        }
                    }
                    is AuthUiState.WaitingForUser -> {
                        // QR on the left, text-instructions stack on the right. The
                        // verification_uri_complete encodes the user_code in the URL
                        // querystring so scanning jumps straight to the consent
                        // screen — no typing required. We still surface the URL +
                        // chips next to it as a fallback for cameras-off contexts.
                        val qrPayload = s.authorization.verificationUriComplete
                            ?: s.authorization.verificationUri
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(48.dp),
                            ) {
                                QrPanel(payload = qrPayload)
                                Column(
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.auth_qr_or),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = s.authorization.verificationUri,
                                        fontSize = 24.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.auth_enter_code),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    CodeChips(code = s.authorization.userCode)
                                }
                            }
                            Text(
                                text = stringResource(R.string.auth_waiting),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    AuthUiState.Authenticated -> {
                        // nav handled in LaunchedEffect; render an empty box
                        // so the Crossfade has something to dissolve into
                        // while navigation completes.
                        Box(modifier = Modifier.size(1.dp))
                    }
                    is AuthUiState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.auth_error_prefix) + s.message,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = { viewModel.start() }) {
                                Text(text = stringResource(R.string.auth_retry))
                            }
                        }
                    }
                } }
            }
        }
    }
}

@Composable
private fun QrPanel(payload: String) {
    // White-padded chip so the QR's quiet zone is fully white even against a
    // dark page bg — phone cameras struggle when the QR sits on near-black.
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
    ) {
        Image(
            bitmap = rememberQrImage(text = payload, sizePx = 280),
            contentDescription = stringResource(R.string.auth_qr_description),
            modifier = Modifier.size(220.dp),
        )
    }
}

@Composable
private fun CodeChips(code: String) {
    // Render each character with comfortable spacing — easy to read across the room
    // and helps the user re-locate their place if they look up at the screen mid-typing.
    val chars = code.toCharArray()
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chars.forEach { c ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(width = 56.dp, height = 72.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = c.toString(),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
