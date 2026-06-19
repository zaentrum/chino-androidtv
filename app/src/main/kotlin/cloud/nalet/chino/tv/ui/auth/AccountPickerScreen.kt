package cloud.nalet.chino.tv.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import kotlinx.coroutines.launch

/**
 * "Who's watching?" — horizontal row of account avatars + "Add account"
 * tile. Mirrors the cross-platform pattern (Netflix, Disney+) for TV
 * apps with shared remotes.
 *
 * Hosting NavHost only routes here when [AccountStore.accounts] has ≥2
 * entries; a single-account user skips this screen and lands on the
 * library directly. From Settings the user can navigate here manually
 * to switch accounts.
 */
@Composable
fun AccountPickerScreen(
    accountStore: AccountStore,
    onPicked: (Account) -> Unit,
    onAddAccount: () -> Unit,
) {
    // Seed the cold-emitting accounts flow with a synchronous snapshot of
    // EncryptedSharedPreferences so the picker renders its tile row on the
    // very first frame — without this we'd flash an empty row (just the
    // AddAccountTile) while waiting for the callbackFlow to emit, and then
    // pop the real tiles in a frame later.
    val seedAccounts = remember { accountStore.snapshotBlocking().accounts }
    val accounts by accountStore.accounts.collectAsState(initial = seedAccounts)
    val scope = rememberCoroutineScope()
    var pendingRemoval by remember { mutableStateOf<Account?>(null) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Text(
                text = "Who's watching?",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Long-press an account to remove it.",
                color = Color(0xFF8B949E),
                fontSize = 14.sp,
            )
            // Initial-focus target: most-recently-used account. Accounts is
            // already sorted by lastUsedAt DESC in AccountStore.addOrUpdate
            // / setActive, so the first entry is the right default. Without
            // this the focus would land on the trailing "Add account" tile,
            // which makes the common case (pick the same account as last
            // time) a two-button-press round-trip.
            val initialFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(accounts.firstOrNull()?.id) {
                if (accounts.isNotEmpty()) {
                    runCatching { initialFocus.requestFocus() }
                }
            }
            Row(
                modifier = Modifier.fillMaxHeight().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                accounts.forEachIndexed { index, account ->
                    AccountTile(
                        account = account,
                        onSelect = {
                            scope.launch {
                                accountStore.setActive(account.id)
                                onPicked(account)
                            }
                        },
                        onLongPress = { pendingRemoval = account },
                        focusRequester = if (index == 0) initialFocus else null,
                    )
                }
                AddAccountTile(onClick = onAddAccount)
            }
        }
    }
    pendingRemoval?.let { acct ->
        ConfirmRemoveDialog(
            account = acct,
            onConfirm = {
                scope.launch {
                    accountStore.remove(acct.id)
                    pendingRemoval = null
                }
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AccountTile(
    account: Account,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    // Pre-render the most-recent tile (the one carrying a FocusRequester) as
    // visually focused so there's no perceptible jump from "no border" to
    // "blue ring" when the LaunchedEffect-driven focus request lands a frame
    // later. onFocusChanged overrides this as soon as the real focus event
    // arrives, so DPAD navigation continues to behave normally.
    var focused by remember { mutableStateOf(focusRequester != null) }
    // onFocusChanged fires `false` on first attach — BEFORE the LaunchedEffect's
    // requestFocus lands — which would clear the pre-rendered ring for a frame
    // and the user sees the selection "switch"/flicker in. Honour `false` only
    // AFTER real focus has actually arrived, so the preselected avatar shows its
    // ring from the very first frame with no jump.
    var settled by remember { mutableStateOf(false) }
    val focusModifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .padding(8.dp)
            .then(focusModifier)
            .onFocusChanged { st ->
                if (st.isFocused) { focused = true; settled = true } else if (settled) focused = false
            }
            // combinedClickable is itself focusable — a separate .focusable()
            // here created a SECOND focus node, so the first CENTER only moved
            // focus to the clickable node and the second actually selected
            // (the "double-click to log in" bug). One node = one press.
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongPress,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .then(
                    if (focused) {
                        Modifier.border(width = 4.dp, color = Color(0xFF58A6FF), shape = CircleShape)
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(displayName = account.displayName, email = account.email, size = 120.dp)
        }
        Text(
            text = account.displayName,
            color = if (focused) Color.White else Color(0xFFC9D1D9),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConfirmRemoveDialog(account: Account, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // Full-screen dim with a centered card. Compose-for-TV doesn't have a
    // first-class AlertDialog, so we draw our own card on top of a scrim
    // Box that intercepts clicks (BackHandler covers BACK).
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(420.dp)
                .padding(24.dp)
                .clickable(enabled = false) { }, // swallow scrim clicks on the card
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(displayName = account.displayName, email = account.email, size = 72.dp)
                Text(
                    text = "Remove ${account.displayName}?",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "You'll need to sign in again to use this account on this TV.",
                    color = Color(0xFFC9D1D9),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.colors(containerColor = Color(0xFFDA3633)),
                    ) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun AddAccountTile(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .padding(8.dp)
            .onFocusChanged { focused = it.isFocused }
            // clickable is focusable on its own — no separate .focusable()
            // (which would need two CENTER presses to activate).
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF))
                .then(
                    if (focused) {
                        Modifier.border(width = 4.dp, color = Color(0xFF58A6FF), shape = CircleShape)
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add account",
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
        Text(
            text = "Add account",
            color = if (focused) Color.White else Color(0xFFC9D1D9),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
