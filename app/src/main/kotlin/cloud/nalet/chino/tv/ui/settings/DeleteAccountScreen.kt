package cloud.nalet.chino.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.isFinal
import cloud.nalet.chino.tv.data.text
import cloud.nalet.chino.tv.data.title
import cloud.nalet.chino.tv.ui.theme.ChinoError
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoText

/** What deleting an account takes with it, as the confirm screen lists it. */
private val WHAT_GOES = listOf(
    "your watch progress",
    "your lists",
    "your likes",
    "your watch history",
    "your sign-in: you can't sign in with this account again",
)

/**
 * Delete Account, reached from Settings → Account. A screen of its own —
 * nothing else on it to land on — that says what goes, names the account
 * and the server, and asks: focus starts on Cancel, and the account is
 * deleted only from the separate Delete Account button to its right. BACK
 * cancels, except while the request is out (the answer decides then).
 *
 * The answers: deleted → [onSignedOut] (the account is signed out by then);
 * refused or not available here → the reason, and Close; any other → Try
 * Again Later with the server's message, and Delete Account stays to try
 * again. Progress and answers are announced (live region).
 */
@Composable
fun DeleteAccountScreen(
    viewModel: DeleteAccountViewModel,
    onCancel: () -> Unit,
    /** The account is deleted and signed out. [othersRemain]: other accounts
     *  are still signed in on this TV. */
    onSignedOut: (othersRemain: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val deleting = state is DeleteAccountState.Deleting
    val answer = (state as? DeleteAccountState.NotDeleted)?.answer
    val final = answer?.isFinal == true

    LaunchedEffect(state) {
        (state as? DeleteAccountState.SignedOut)?.let { onSignedOut(it.othersRemain) }
    }
    BackHandler {
        if (!deleting && state !is DeleteAccountState.SignedOut) onCancel()
    }

    // Focus starts on Cancel — and lands on Close once there is nothing to
    // confirm any more (the Delete Account button is gone then).
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(final) { runCatching { cancelFocus.requestFocus() } }

    val who = viewModel.account?.let { a -> a.email.ifBlank { a.displayName } }
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.width(720.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Delete Account",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = buildString {
                        append("This deletes ")
                        append(if (who != null) "the account $who" else "your account")
                        append(" on ")
                        append(viewModel.serverHost ?: "this server")
                        append(", and with it:")
                    },
                    color = ChinoText,
                    fontSize = 18.sp,
                )
                Column(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WHAT_GOES.forEach { line ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(ChinoMuted))
                            Text(text = line, color = ChinoText, fontSize = 17.sp)
                        }
                    }
                }
                Text(text = "This can't be undone.", color = ChinoText, fontSize = 18.sp)

                // Progress, or why the account is still there: announced.
                Column(
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when {
                        deleting -> Text(text = "Deleting your account…", color = ChinoMuted, fontSize = 16.sp)
                        answer != null -> {
                            answer.title?.let {
                                Text(text = it, color = ChinoError, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(text = answer.text, color = ChinoText, fontSize = 16.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(
                        // Never disabled: a disabled button would drop focus
                        // mid-request. A press while deleting does nothing.
                        onClick = { if (!deleting) onCancel() },
                        modifier = Modifier.focusRequester(cancelFocus),
                        shape = ButtonDefaults.shape(shape = RectangleShape),
                    ) {
                        Text(if (final) "Close" else "Cancel")
                    }
                    if (!final) {
                        Button(
                            onClick = { viewModel.delete() },
                            colors = ButtonDefaults.colors(
                                containerColor = ChinoError.copy(alpha = 0.25f),
                                contentColor = ChinoError,
                                focusedContainerColor = ChinoError,
                                focusedContentColor = Color.White,
                                pressedContainerColor = ChinoError,
                                pressedContentColor = Color.White,
                            ),
                            shape = ButtonDefaults.shape(shape = RectangleShape),
                        ) {
                            Text(if (deleting) "Deleting…" else "Delete Account")
                        }
                    }
                }
                if (!final && !deleting) {
                    Text(text = "Press BACK to keep your account.", color = ChinoMuted, fontSize = 14.sp)
                }
            }
        }
    }
}
