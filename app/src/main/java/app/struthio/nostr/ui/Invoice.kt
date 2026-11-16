/*
 * Lightning invoices in posts and messages: a card with the amount and description, and a button
 * to pay through the connected wallet or open another wallet app.
 */
package app.struthio.nostr.ui

import app.struthio.nostr.str
import app.struthio.nostr.R
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Invoice
import app.struthio.nostr.core.formatSats
import app.struthio.nostr.core.nowSeconds

/** A BOLT-11 invoice, wherever one appears. Paying always stops to ask first. */
@Composable
fun InvoiceCard(invoice: Invoice) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by core.wallet.connected.collectAsState()
    var confirming by remember(invoice.text) { mutableStateOf(false) }
    var paying by remember(invoice.text) { mutableStateOf(false) }
    val paid = core.paidInvoices.containsKey(invoice.text)
    val left = invoice.expiresAt - nowSeconds()
    val amount = invoice.msats?.let(::formatSats)
    Surface(
        Modifier.padding(top = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                Text(amount ?: str(R.string.any_amount), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    when {
                        paid -> str(R.string.paid)
                        left <= 0 -> str(R.string.expired)
                        else -> str(R.string.expires_in_x, timeLeft(left))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(str(R.string.lightning_invoice), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            invoice.memo?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!paid && left > 0) {
                    FilledTonalButton(
                        {
                            if (connected && invoice.msats != null) {
                                confirming = true
                            } else {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:" + invoice.text))) }
                                    .onFailure { nav.say(str(R.string.no_wallet_app_found_copy_the)) }
                            }
                        },
                        enabled = !paying,
                    ) { Text(if (paying) str(R.string.paying) else if (connected && invoice.msats != null) str(R.string.pay) else str(R.string.open_in_wallet)) }
                }
                OutlinedButton({
                    context.copy(invoice.text)
                    nav.say(str(R.string.invoice_copied))
                }) { Text(str(R.string.copy)) }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(str(R.string.pay_x, amount.orEmpty())) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    invoice.memo?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    Text(str(R.string.this_pays_from_your_connected_wallet))
                }
            },
            confirmButton = {
                TextButton({
                    confirming = false
                    paying = true
                    scope.act(nav) {
                        try {
                            core.wallet.pay(invoice.text)
                            core.paidInvoices[invoice.text] = true
                            nav.say(str(R.string.paid_x, amount.orEmpty()))
                        } finally {
                            paying = false
                        }
                    }
                }) { Text(str(R.string.pay)) }
            },
            dismissButton = { TextButton({ confirming = false }) { Text(str(R.string.cancel)) } },
        )
    }
}
