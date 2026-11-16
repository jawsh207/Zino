/*
 * Relay screens: editing your general (NIP-65), messaging, search and profile lookup relays, each
 * with how it's answering, and a details screen per relay with its own information (NIP-11), data
 * use and uptime.
 */
package app.struthio.nostr.ui

import app.struthio.nostr.str
import app.struthio.nostr.R
import app.struthio.nostr.core.parseContent
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.RelayInfo
import app.struthio.nostr.core.RelayList
import app.struthio.nostr.core.nipName
import app.struthio.nostr.core.normalizeRelay
import app.struthio.nostr.data.Core
import app.struthio.nostr.net.RelayStats
import app.struthio.nostr.net.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHECKING = -1L

/** A relay being edited, with whether you read from it and post to it. */
private class EditRelay(val url: String, read: Boolean, write: Boolean) {
    var read by mutableStateOf(read)
    var write by mutableStateOf(write)
}

/** The relay's own description of itself (NIP-11), once fetched. */
@Composable
private fun rememberRelayInfo(url: String): RelayInfo? {
    val core = LocalCore.current
    return produceState<RelayInfo?>(null, url) { value = core.relayInfo(url) }.value
}

/** Milliseconds to answer, [CHECKING] until known, or null if the relay didn't answer. */
@Composable
private fun rememberPing(url: String): Long? {
    val core = LocalCore.current
    return produceState<Long?>(CHECKING, url) { value = core.pingRelay(url) }.value
}

/** One line for the list: data down and up, and how often it could be reached. Null before any use. */
private fun summaryLine(s: RelayStats.Summary): String? {
    if (s.tries == 0 && s.down == 0L && s.up == 0L) return null
    return listOfNotNull(
        "↓ ${formatBytes(s.down)}",
        "↑ ${formatBytes(s.up)}",
        s.uptime?.let { str(R.string.x_up, it) },
    ).joinToString(" · ")
}

/**
 * How a relay is doing, in words: its response time, a sign-in note, or that it's not answering.
 */
private fun statusText(ping: Long?, info: RelayInfo?): String = when {
    ping == CHECKING -> str(R.string.checking)
    ping != null -> str(R.string.x_ms, ping)
    info?.authRequired == true -> str(R.string.answers_after_sign_in)
    else -> str(R.string.not_answering)
}

/** The colour of the dot beside a relay's status. */
@Composable
private fun statusColor(ping: Long?, info: RelayInfo?): Color = when {
    ping == CHECKING -> MaterialTheme.colorScheme.outlineVariant
    ping != null -> MaterialTheme.colorScheme.primary
    info?.authRequired == true -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/** One relay in a list: its icon, address and status. Tapping opens its details. */
@Composable
private fun RelayRow(url: String, onRemove: () -> Unit) {
    val nav = LocalNav.current
    val core = LocalCore.current
    val info = rememberRelayInfo(url)
    val ping = rememberPing(url)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { nav.push(Screen.RelayInfo(url)) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(ping, info)))
        Column(Modifier.weight(1f)) {
            Text(url.removePrefix("wss://"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(statusText(ping, info), info?.tag).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Read again once the check is done, as the check itself is counted.
            val usage = remember(url, ping) { core.pool.usage(url) }
            usage?.let { summaryLine(it) }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onRemove) { Icon(Icons.Rounded.Close, str(R.string.remove)) }
    }
}

/** A field for adding a relay by its address. */
@Composable
private fun AddRelay(onAdd: (String) -> Unit) {
    val nav = LocalNav.current
    var input by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(input, { input = it }, label = { Text("wss://…") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton({
            val url = normalizeRelay(input)
            if (url == null) {
                nav.say(str(R.string.relays_start_with_wss))
            } else {
                onAdd(url)
                input = ""
            }
        }) { Text(str(R.string.add)) }
    }
}

/** A save button that shows a message once saved. */
@Composable
private fun SaveButton(enabled: Boolean, saved: String, save: suspend () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Button(
        onClick = {
            busy = true
            scope.act(nav) {
                try {
                    save()
                    nav.say(saved)
                } finally {
                    busy = false
                }
            }
        },
        enabled = enabled && !busy,
    ) { Text(str(R.string.save)) }
}

/** A list of relays of one kind that can be edited and saved. */
@Composable
private fun RelayGroup(title: String, hint: String, initial: List<String>, saved: String, save: suspend (List<String>) -> Unit) {
    val relays = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    Section(title) {
        Text(hint, style = MaterialTheme.typography.bodyMedium)
        relays.toList().forEach { url -> RelayRow(url) { relays.remove(url) } }
        AddRelay { if (it !in relays) relays += it }
        SaveButton(relays.isNotEmpty(), saved) { save(relays.toList()) }
    }
}

/** Your relay lists: general, messaging, search and profile lookup. */
@Composable
fun RelaysScreen() {
    val core = LocalCore.current
    val list by core.myRelays.collectAsState()
    val dm by core.dmRelays.collectAsState()
    val general = remember {
        mutableStateListOf<EditRelay>().apply {
            (list.read + list.write).distinct().forEach { add(EditRelay(it, it in list.read, it in list.write)) }
        }
    }
    val search by produceState<List<String>?>(null) { value = withContext(Dispatchers.IO) { core.searchRelays().toList() } }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(str(R.string.relays))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section(str(R.string.general)) {
                Text(str(R.string.your_feed_and_the_notes_you), style = MaterialTheme.typography.bodyMedium)
                general.forEach { relay ->
                    Column {
                        RelayRow(relay.url) { general.remove(relay) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(relay.read, { relay.read = !relay.read }, { Text(str(R.string.read)) })
                            FilterChip(relay.write, { relay.write = !relay.write }, { Text(str(R.string.write)) })
                        }
                    }
                }
                AddRelay { url -> if (general.none { it.url == url }) general += EditRelay(url, true, true) }
                SaveButton(general.any { it.write } && general.any { it.read }, str(R.string.relays_saved)) {
                    core.saveRelays(RelayList(general.filter { it.read }.map { it.url }, general.filter { it.write }.map { it.url }))
                }
            }
            RelayGroup(
                str(R.string.messaging),
                str(R.string.where_encrypted_messages_to_you_arrive),
                dm.ifEmpty { Core.DEFAULT_DM_RELAYS },
                str(R.string.messaging_relays_saved),
                core::saveDmRelays,
            )
            search?.let { RelayGroup(str(R.string.search), str(R.string.finds_notes_and_people), it, str(R.string.search_relays_saved), core::saveSearchRelays) }
            RelayGroup(
                str(R.string.profile_lookup),
                str(R.string.finds_people_s_profiles_and_relay),
                core.lookupRelays(),
                str(R.string.lookup_relays_saved),
            ) { core.saveLookupRelays(it) }
        }
    }
}

/** One labelled line of relay details. */
@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Yes, no, or that the relay doesn't say. */
private fun stated(value: Boolean?, yes: String, no: String) = when (value) {
    true -> yes
    false -> no
    null -> str(R.string.not_stated)
}

/**
 * Everything about one relay: what it says about itself, its limits, data used and how often it
 * answered.
 */
@Composable
fun RelayInfoScreen(url: String) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val info = rememberRelayInfo(url)
    val ping = rememberPing(url)
    val signedIn = remember(ping) { core.pool.signedIn(url) }
    val refused = remember(ping) { core.pool.signInRefused(url) }
    val uses by produceState(emptyList<String>(), url) { value = withContext(Dispatchers.IO) { core.relayUses(url) } }
    val open = { link: String ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        Unit
    }
    val host = url.removePrefix("wss://")
    Column(Modifier.fillMaxSize()) {
        BackBar(str(R.string.relay_details))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            info?.banner?.let {
                NetImage(it, 380.dp, Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(24.dp)))
            }
            Section(info?.name ?: host) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    info?.icon?.let { NetImage(it, 56.dp, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))) }
                    Column {
                        Text(host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(ping, info)))
                            Text(statusText(ping, info), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                info?.description?.let { Text(linkedText(remember(it) { parseContent(it) }, emptyMap()), style = MaterialTheme.typography.bodyMedium) }
            }
            if (uses.isNotEmpty()) {
                Section(str(R.string.you_use_it_for)) { uses.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            }
            val usage = remember(url, ping) { core.pool.usage(url) }
            if (usage != null && (usage.tries > 0 || usage.down > 0 || usage.up > 0)) {
                Section(str(R.string.last_x_days, RelayStats.DAYS)) {
                    Fact(str(R.string.downloaded), formatBytes(usage.down))
                    Fact(str(R.string.uploaded), formatBytes(usage.up))
                    usage.uptime?.let { Fact(str(R.string.uptime), str(R.string.x_reached_x_of_x_tries, it, usage.reached, usage.tries)) }
                    Text(
                        str(R.string.measured_from_zino_s_own_connections),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val problems = core.pool.problemsOf(url)
            if (problems.isNotEmpty()) {
                Section(str(R.string.recent_problems)) { problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
            if (info == null) {
                Section(str(R.string.details)) { Text(str(R.string.this_relay_doesn_t_share_any), style = MaterialTheme.typography.bodyMedium) }
                return@Column
            }
            Section(str(R.string.cost_and_access)) {
                val cost = when {
                    info.paymentRequired == true -> str(R.string.payment_required)
                    info.fees.isNotEmpty() -> str(R.string.fees_listed)
                    info.paymentRequired == false -> str(R.string.free)
                    else -> str(R.string.no_fee_listed)
                }
                Fact(str(R.string.cost), cost)
                info.fees.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Fact(
                    str(R.string.sign_in),
                    when {
                        signedIn -> str(R.string.signed_in)
                        refused -> str(R.string.not_working_on_this_relay)
                        else -> stated(info.authRequired, str(R.string.required), str(R.string.not_required))
                    },
                )
                Fact(str(R.string.posting), stated(info.restrictedWrites, str(R.string.restricted), str(R.string.open)))
                info.paymentsUrl?.let { link -> OutlinedButton({ open(link) }) { Text(str(R.string.payment_page)) } }
            }
            if (info.nips.isNotEmpty()) {
                Section(str(R.string.features)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        info.nips.map(::nipName).distinct().forEach { name ->
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
            if (info.limits.isNotEmpty()) {
                Section(str(R.string.limits)) { info.limits.forEach { (label, value) -> Fact(label, value) } }
            }
            Section(str(R.string.details)) {
                info.software?.let { Fact(str(R.string.software), listOfNotNull(it, info.version).joinToString(" ")) }
                info.contact?.let { Fact(str(R.string.contact), it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.operator?.let { pubkey -> OutlinedButton({ nav.push(Screen.Profile(pubkey)) }) { Text(str(R.string.operator)) } }
                    info.terms?.let { link -> OutlinedButton({ open(link) }) { Text(str(R.string.terms)) } }
                }
            }
        }
    }
}
