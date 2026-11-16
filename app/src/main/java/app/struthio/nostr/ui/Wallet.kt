/*
 * The Wallet tab: connect a wallet with Nostr Wallet Connect (NIP-47), see the balance and recent
 * payments, pay an invoice, and receive with a new invoice shown as a QR code.
 */
package app.struthio.nostr.ui

import app.struthio.nostr.str
import app.struthio.nostr.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * The Wallet tab: the balance, payments, and send and receive buttons, or how to connect a wallet.
 */
@Composable
fun WalletScreen(pending: String?, onConsumed: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val wallet = core.wallet
    val connected by wallet.connected.collectAsState()
    val balance by wallet.balance.collectAsState()
    val transactions by wallet.transactions.collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pending) {
        if (pending != null) {
            scope.act(nav) { wallet.connect(pending) }
            onConsumed()
        }
    }
    val active = LocalScreenActive.current
    val problem by wallet.problem.collectAsState()
    // Coming back to the app counts as a visit too, since Android cuts the network while away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var returns by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val watcher = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) returns++ }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }
    // Every visit asks the wallet again, so a dropped connection recovers by itself.
    LaunchedEffect(connected, active, returns) {
        if (!active) return@LaunchedEffect
        if (connected) wallet.refresh() else wallet.restore()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(str(R.string.wallet), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        if (!connected) {
            ConnectWallet()
            return@Column
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceBright,
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(str(R.string.balance), style = MaterialTheme.typography.labelLarge)
                Text(
                    balance?.let { str(R.string.x_sats, NumberFormat.getIntegerInstance().format(it / 1000)) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                problem?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            str(R.string.couldn_t_reach_your_wallet_x, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton({ scope.launch { wallet.refresh() } }) { Text(str(R.string.retry)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ sheet = "send" }, Modifier.weight(1f)) { Text(str(R.string.send)) }
                    FilledTonalButton({ sheet = "receive" }, Modifier.weight(1f)) { Text(str(R.string.receive)) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(transactions) { index, t ->
                CardRow(
                    segmentShape(index, transactions.size),
                    null,
                    headlineContent = {
                        Text(str(R.string.x_sats, NumberFormat.getIntegerInstance().format(t.msats / 1000)))
                    },
                    supportingContent = t.description.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = {
                        Icon(
                            if (t.incoming) Icons.AutoMirrored.Rounded.CallReceived else Icons.AutoMirrored.Rounded.CallMade,
                            if (t.incoming) str(R.string.received) else str(R.string.sent),
                        )
                    },
                    trailingContent = { Text(relativeTime(t.createdAt)) },
                )
            }
            item {
                TextButton({ wallet.disconnect() }, Modifier.padding(8.dp)) { Text(str(R.string.disconnect_wallet)) }
            }
        }
    }
    when (sheet) {
        "send" -> SendSheet { sheet = null }
        "receive" -> ReceiveSheet { sheet = null }
    }
}

/** Connecting a wallet by pasting or scanning its link. */
@Composable
private fun ConnectWallet() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(R.string.connect_a_lightning_wallet), style = MaterialTheme.typography.titleMedium)
            Text(
                str(R.string.paste_a_nostr_wallet_connect_link),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                uri,
                { uri = it },
                label = { Text("nostr+walletconnect://…") },
                singleLine = true,
                trailingIcon = { ScanButton { uri = it } },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    busy = true
                    scope.act(nav) {
                        try {
                            core.wallet.connect(uri)
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = uri.isNotBlank() && !busy,
            ) { Text(if (busy) str(R.string.connecting) else str(R.string.connect)) }
        }
    }
}

/** Paying a lightning invoice, pasted or scanned. */
@Composable
private fun SendSheet(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(R.string.send), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                target,
                { target = it },
                label = { Text(str(R.string.invoice_or_lightning_address)) },
                trailingIcon = { ScanButton { target = it.removePrefix("lightning:") } },
                modifier = Modifier.fillMaxWidth(),
            )
            if (target.contains('@')) {
                OutlinedTextField(
                    amount,
                    { amount = it.filter(Char::isDigit).take(9) },
                    label = { Text(str(R.string.amount_in_sats)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    busy = true
                    scope.act(nav) {
                        try {
                            core.wallet.send(target, amount.toLongOrNull())
                            nav.say(str(R.string.payment_sent))
                            onDismiss()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = target.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) str(R.string.sending) else str(R.string.pay)) }
        }
    }
}

/** Making an invoice to receive sats, shown as a QR code to share. */
@Composable
private fun ReceiveSheet(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(R.string.receive), style = MaterialTheme.typography.titleLarge)
            val ready = invoice
            if (ready == null) {
                OutlinedTextField(
                    amount,
                    { amount = it.filter(Char::isDigit).take(9) },
                    label = { Text(str(R.string.amount_in_sats)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(memo, { memo = it }, label = { Text(str(R.string.description_optional)) }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { scope.act(nav) { invoice = core.wallet.makeInvoice(amount.toLong(), memo) } },
                    enabled = (amount.toLongOrNull() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(str(R.string.create_invoice)) }
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    QrImage(ready.uppercase())
                }
                Row {
                    Button({
                        context.copy(ready)
                        nav.say(str(R.string.invoice_copied))
                    }) { Text(str(R.string.copy_invoice)) }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
