package cloud.nalet.chino.tv.ui.auth

import androidx.tv.material3.MaterialTheme
import cloud.nalet.chino.tv.ui.theme.ChinoDangerButton
import cloud.nalet.chino.tv.ui.theme.ChinoFg
import cloud.nalet.chino.tv.ui.theme.ChinoPage
import cloud.nalet.chino.tv.ui.theme.scrim
import cloud.nalet.chino.tv.ui.theme.ChinoBg
import cloud.nalet.chino.tv.ui.theme.ChinoError
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.tv.ui.theme.ChinoButton
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.FocusKind
import cloud.nalet.chino.tv.ui.theme.focusStyle
import cloud.nalet.chino.tv.ui.theme.rememberItemFocus
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import kotlinx.coroutines.launch

/**
 * "Who's watching?" — horizontal row of account avatars + "Add account"
 * tile, the common pattern for TV apps with shared remotes.
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
    /** An account removed here has left this TV: what the TV keeps of its
     *  settings goes with it. */
    forgetAccount: (accountId: String) -> Unit,
    /** The last account was removed here: there is none left to choose. */
    onNoneLeft: () -> Unit,
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
    // Each tile's focus, by its account, and the add tile's: the remove
    // dialog takes the focus while it is open and hands it back when it
    // closes - to the tile that opened it, or after a removal to the first
    // tile left.
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    val addFocus = remember { FocusRequester() }
    var focusBack by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusBack) {
        val id = focusBack ?: return@LaunchedEffect
        runCatching { (tileFocus[id] ?: addFocus).requestFocus() }
        focusBack = null
    }
    ChinoPage {
        Column(
            // Centred on the screen, which the shell lays out edge to edge.
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Text(
                text = "who's watching?",
                style = MaterialTheme.typography.displaySmall,
                color = ChinoFg,
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Long-press an account to remove it.",
                color = ChinoMuted,
                fontSize = 14.sp,
            )
            // Initial-focus target: most-recently-used account. Accounts is
            // already sorted by lastUsedAt DESC in AccountStore.addOrUpdate
            // / setActive, so the first entry is the right default. Without
            // this the focus would land on the trailing "Add account" tile,
            // which makes the common case (pick the same account as last
            // time) a two-button-press round-trip.
            LaunchedEffect(accounts.firstOrNull()?.id) {
                accounts.firstOrNull()?.let { first ->
                    runCatching { tileFocus.getOrPut(first.id) { FocusRequester() }.requestFocus() }
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
                        focusRequester = tileFocus.getOrPut(account.id) { FocusRequester() },
                        preFocused = index == 0,
                    )
                }
                AddAccountTile(onClick = onAddAccount, focusRequester = addFocus)
            }
        }
    }
    pendingRemoval?.let { acct ->
        ConfirmRemoveDialog(
            account = acct,
            onConfirm = {
                scope.launch {
                    accountStore.remove(acct.id)
                    forgetAccount(acct.id)
                    pendingRemoval = null
                    // With nobody active (the active account deleted,
                    // section 16.5.6) a removal changes no active account,
                    // so nothing else starts over: the last one gone, who's
                    // watching would be left with no account on it.
                    val left = accountStore.snapshotBlocking().accounts
                    if (left.isEmpty()) onNoneLeft() else focusBack = left.first().id
                }
            },
            onDismiss = {
                pendingRemoval = null
                focusBack = acct.id
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AccountTile(
    account: Account,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    focusRequester: FocusRequester,
    preFocused: Boolean = false,
) {
    // A card, the avatar its artwork. The most-recent tile ([preFocused]) is
    // drawn focused from the first frame, so there is no jump from no ring to
    // a ring when the LaunchedEffect-driven focus request lands a frame later:
    // onFocusChanged fires `false` on first attach — BEFORE that request lands
    // — and ItemFocus honours `false` only once real focus has come, so the
    // preselected tile does not flicker.
    val focus = rememberItemFocus(preFocused = preFocused)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .width(160.dp)
            .padding(8.dp)
            .focusRequester(focusRequester)
            .focusStyle(focus, FocusKind.Card)
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
            modifier = Modifier.size(128.dp),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(displayName = account.displayName, email = account.email, size = 120.dp)
        }
        Text(
            text = account.displayName,
            color = ChinoFocus.title(ChinoText, focus.focused),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The question before an account leaves this TV (the design system's
 * confirm): "cancel" has the focus when it opens, the remote moves between
 * its two buttons and never out of it, so OK never reaches a tile behind it.
 * BACK, or a tap beside it, cancels.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ConfirmRemoveDialog(account: Account, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // Full-screen dim with a centered card. Compose-for-TV doesn't have a
    // first-class AlertDialog, so we draw our own card on top of a scrim
    // (BackHandler covers BACK).
    androidx.activity.compose.BackHandler { onDismiss() }
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scrim()
            // A tap beside the card cancels; the scrim is no stop of the
            // focus, as a clickable would be one.
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(420.dp)
                .padding(24.dp)
                .pointerInput(Unit) { detectTapGestures { } }, // swallow scrim taps on the card
            shape = RectangleShape,
        ) {
            Column(
                modifier = Modifier
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The title is the action; the confirming button repeats it
                // and is the danger button.
                Text(
                    text = "remove account",
                    style = MaterialTheme.typography.titleLarge,
                    color = ChinoFg,
                    textAlign = TextAlign.Center,
                )
                Avatar(displayName = account.displayName, email = account.email, size = 72.dp)
                Text(
                    text = account.displayName,
                    color = ChinoFg,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "You'll need to sign in again to use this account on this TV.",
                    color = ChinoText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ChinoFocus.ControlGap)) {
                    ChinoButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancel)) { Text("cancel") }
                    ChinoDangerButton(onClick = onConfirm) { Text("remove account") }
                }
            }
        }
    }
}

@Composable
private fun AddAccountTile(onClick: () -> Unit, focusRequester: FocusRequester) {
    val focus = rememberItemFocus()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .width(160.dp)
            .padding(8.dp)
            .focusRequester(focusRequester)
            .focusStyle(focus, FocusKind.Card)
            // clickable is focusable on its own — no separate .focusable()
            // (which would need two CENTER presses to activate).
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.Plus,
                contentDescription = "add account",
                tint = ChinoFg,
                modifier = Modifier.size(56.dp),
            )
        }
        Text(
            text = "add account",
            style = MaterialTheme.typography.titleMedium,
            color = ChinoFocus.title(ChinoText, focus.focused),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
