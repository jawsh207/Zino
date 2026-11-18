/*
 * Settings, editing your profile, and signing in. Settings cover keys and backups, relays, media
 * servers, how often to check for activity, the trusted network, link previews, logs and signing
 * out. Sign-in offers a new account, an existing key, a signer app (NIP-55) or a remote signer
 * (NIP-46).
 */
package app.struthio.nostr.ui

import app.struthio.nostr.str
import app.struthio.nostr.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.data.RemoteSigner
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.mutableStateListOf
import app.struthio.nostr.core.Identity
import app.struthio.nostr.core.gistId
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.collectAsState
import app.struthio.nostr.ZinoApp
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.ExternalSigner
import app.struthio.nostr.net.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.struthio.nostr.data.Notifier
import app.struthio.nostr.data.SignerBridge
import org.json.JSONObject

/** A titled card holding a group of rows. */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A titled group of rows, joined into one card the way the other screens group lists. */
@Composable
private fun Group(title: String, vararg rows: (@Composable (Shape) -> Unit)?) {
    val shown = rows.filterNotNull()
    if (shown.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEachIndexed { index, row -> row(segmentShape(index, shown.size)) }
    }
}

/** A settings row with an icon, a title and a short summary. */
@Composable
private fun SettingRow(
    shape: Shape,
    icon: ImageVector,
    title: String,
    summary: String? = null,
    color: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    CardRow(
        shape,
        onClick,
        headlineContent = { Text(title, color = color) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) },
        trailingContent = trailing,
    )
}

/**
 * The other accounts on this phone, each a tap away, and a row for adding one. Switching closes
 * this account's screens and opens the other's.
 */
@Composable
private fun AccountsGroup() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val app = LocalContext.current.applicationContext as ZinoApp
    val accounts by app.accounts.list.collectAsState()
    val others = accounts.filter { it.id != core.accountId && it.pubkey != null }
    val rows = others.map { account ->
        val pubkey = account.pubkey!!
        val row: @Composable (Shape) -> Unit = { shape ->
            CardRow(
                shape,
                {
                    nav.stack.clear()
                    app.switchTo(account.id)
                },
                headlineContent = { Text(account.name?.takeIf { it.isNotBlank() } ?: core.displayName(pubkey), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(str(R.string.switch_to_this_account)) },
                leadingContent = { Avatar(pubkey, 40.dp) },
            )
        }
        row
    } + listOf<@Composable (Shape) -> Unit>({ shape ->
        SettingRow(shape, Icons.Rounded.PersonAdd, str(R.string.add_account), str(R.string.sign_in_to_another_account_on)) {
            nav.stack.clear()
            app.addAccount()
        }
    })
    Group(str(R.string.accounts), *rows.toTypedArray())
}

/** A settings row with a switch. */
@Composable
private fun SwitchRow(shape: Shape, icon: ImageVector, title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(shape, icon, title, summary, trailing = { Switch(checked, onChange) }) { onChange(!checked) }
}

/** How often checks run, in words. */
private fun intervalLabel(minutes: Int) = when {
    minutes == 0 -> str(R.string.off)
    minutes < 60 -> str(R.string.every_x_minutes, minutes)
    minutes == 60 -> str(R.string.every_hour)
    else -> str(R.string.every_x_hours, minutes / 60)
}

/** The settings screen. */
@Composable
fun SettingsScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = core.me ?: return
    val hasKey = remember { core.secure.get(Core.KEY_SECRET) != null }
    var copying by remember { mutableStateOf(false) }
    var sealing by remember { mutableStateOf(false) }
    var interval by remember { mutableIntStateOf(core.notifyMinutes()) }
    var pickingInterval by remember { mutableStateOf(false) }
    var trustFilter by remember { mutableStateOf(core.trustFilterEnabled()) }
    var linkPreviews by remember { mutableStateOf(core.linkPreviewsEnabled()) }
    var videoEmbeds by remember { mutableStateOf(core.videoEmbedsEnabled()) }
    var editingServers by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var confirmReveal by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var vanishing by remember { mutableStateOf(false) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }

    Column(Modifier.fillMaxSize()) {
        BackBar(str(R.string.settings))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter).navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(8.dp))
            CardRow(
                RoundedCornerShape(24.dp),
                { nav.push(Screen.Profile(me)) },
                headlineContent = { Text(core.displayName(me), style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(Nip19.npub(me), maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                leadingContent = { Avatar(me, 48.dp) },
                trailingContent = {
                    IconButton({
                        context.copy(Nip19.npub(me))
                        nav.say(str(R.string.public_key_copied))
                    }) { Icon(Icons.Rounded.ContentCopy, str(R.string.copy_npub)) }
                },
            )
            AccountsGroup()
            Group(
                str(R.string.account),
                { SettingRow(it, Icons.Rounded.Bookmarks, str(R.string.bookmarks)) { nav.push(Screen.Bookmarks) } },
                { SettingRow(it, Icons.Rounded.VisibilityOff, str(R.string.muted_words_and_hashtags), str(R.string.muted_words_summary)) { nav.push(Screen.Mutes) } },
                if (hasKey) {
                    { SettingRow(it, Icons.Rounded.Lock, str(R.string.encrypted_key_backup), str(R.string.export_your_key_protected_by_a)) { sealing = true } }
                } else {
                    null
                },
                if (hasKey) {
                    { SettingRow(it, Icons.Rounded.Key, str(R.string.show_private_key), str(R.string.for_backing_up_by_hand)) { confirmReveal = true } }
                } else {
                    null
                },
            )
            Group(
                str(R.string.relays),
                { SettingRow(it, Icons.Rounded.Hub, str(R.string.manage_relays), str(R.string.general_messaging_search_and_profile_lookup)) { nav.push(Screen.Relays) } },
                { SettingRow(it, Icons.Rounded.FactCheck, str(R.string.account_check), str(R.string.see_which_relays_have_your_profile)) { nav.push(Screen.AccountCheck) } },
                {
                    SettingRow(
                        it,
                        Icons.Rounded.CloudUpload,
                        if (copying) str(R.string.copying) else str(R.string.copy_lists_to_popular_relays),
                        str(R.string.helps_other_apps_and_people_find),
                    ) {
                        if (!copying) {
                            copying = true
                            scope.act(nav) {
                                try {
                                    nav.say(core.copyListsToPopularRelays())
                                } finally {
                                    copying = false
                                }
                            }
                        }
                    }
                },
                { SettingRow(it, Icons.Rounded.Storage, str(R.string.media_servers), str(R.string.where_images_and_files_you_send)) { editingServers = true } },
            )
            Group(
                str(R.string.notifications),
                { SettingRow(it, Icons.Rounded.Notifications, str(R.string.check_for_activity), intervalLabel(interval)) { pickingInterval = true } },
            )
            Group(
                str(R.string.feeds),
                {
                    SwitchRow(it, Icons.Rounded.Groups, str(R.string.trusted_network), str(R.string.global_and_trending_show_only_people), trustFilter) { on ->
                        trustFilter = on
                        core.setTrustFilter(on)
                    }
                },
                {
                    SwitchRow(it, Icons.Rounded.Link, str(R.string.link_previews), str(R.string.shows_a_card_for_links_each), linkPreviews) { on ->
                        linkPreviews = on
                        core.setLinkPreviews(on)
                    }
                },
                {
                    SwitchRow(
                        it,
                        Icons.Rounded.SmartDisplay,
                        str(R.string.embedded_videos),
                        str(R.string.plays_youtube_peertube_invidious_piped_and),
                        videoEmbeds,
                    ) { on ->
                        videoEmbeds = on
                        core.setVideoEmbeds(on)
                    }
                },
            )
            Group(
                str(R.string.troubleshooting),
                {
                    SettingRow(it, Icons.Rounded.ContentCopy, str(R.string.copy_logs), str(R.string.connections_sign_ins_and_errors_leaves)) {
                        scope.act(nav) {
                            val report = core.logReport()
                            withContext(Dispatchers.Main) { context.copy(report) }
                            nav.say(str(R.string.logs_copied))
                        }
                    }
                },
                {
                    SettingRow(it, Icons.Rounded.DeleteSweep, str(R.string.clear_logs)) {
                        AppLog.clear()
                        nav.say(str(R.string.logs_cleared))
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            SettingRow(RoundedCornerShape(24.dp), Icons.AutoMirrored.Rounded.Logout, str(R.string.sign_out), color = MaterialTheme.colorScheme.error) { confirmSignOut = true }
            Spacer(Modifier.height(8.dp))
            SettingRow(
                RoundedCornerShape(24.dp),
                Icons.Rounded.DeleteForever,
                str(R.string.delete_account_everywhere_2),
                str(R.string.ask_every_relay_to_erase_everything),
                color = MaterialTheme.colorScheme.error,
            ) { vanishing = true }
            Text(
                listOfNotNull(str(R.string.zino), version).joinToString(" "),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (sealing) KeyBackupDialog { sealing = false }
    if (editingServers) MediaServersDialog { editingServers = false }
    if (pickingInterval) {
        AlertDialog(
            onDismissRequest = { pickingInterval = false },
            title = { Text(str(R.string.check_for_activity)) },
            text = {
                Column {
                    Text(str(R.string.android_may_delay_checks_while_the), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Notifier.INTERVALS.forEach { minutes ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                interval = minutes
                                core.setNotifyMinutes(minutes)
                                pickingInterval = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(interval == minutes, null)
                            Spacer(Modifier.width(8.dp))
                            Text(intervalLabel(minutes))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ pickingInterval = false }) { Text(str(R.string.close)) } },
        )
    }
    if (confirmReveal) {
        AlertDialog(
            onDismissRequest = { confirmReveal = false },
            title = { Text(str(R.string.show_your_private_key)) },
            text = { Text(str(R.string.anyone_who_sees_this_key_controls)) },
            confirmButton = {
                TextButton({
                    confirmReveal = false
                    scope.act(nav) { revealed = core.exportSecret() }
                }) { Text(str(R.string.show_key)) }
            },
            dismissButton = { TextButton({ confirmReveal = false }) { Text(str(R.string.cancel)) } },
        )
    }
    revealed?.let { key ->
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text(str(R.string.your_private_key)) },
            text = { SelectionContainer { Text(key, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = {
                TextButton({
                    context.copy(key)
                    nav.say(str(R.string.private_key_copied_clear_your_clipboard))
                }) { Text(str(R.string.copy)) }
            },
            dismissButton = { TextButton({ revealed = null }) { Text(str(R.string.close)) } },
        )
    }
    if (vanishing) VanishDialog { vanishing = false }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(str(R.string.sign_out_2)) },
            text = { Text(str(R.string.this_removes_your_key_and_messages)) },
            confirmButton = {
                TextButton({
                    confirmSignOut = false
                    nav.stack.clear()
                    scope.act(nav) { core.signOut() }
                }) { Text(str(R.string.sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmSignOut = false }) { Text(str(R.string.cancel)) } },
        )
    }
}

/** Choosing the servers your pictures and files are uploaded to (Blossom). */
@Composable
private fun MediaServersDialog(onClose: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(core.mediaServers().joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.media_servers)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.images_and_files_you_send_are), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(servers, { servers = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton({
                val list = servers.lines().map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }.distinct()
                if (list.isEmpty()) {
                    nav.say(str(R.string.servers_start_with_https))
                } else {
                    scope.act(nav) {
                        core.saveMediaServers(list)
                        nav.say(str(R.string.media_servers_saved))
                        onClose()
                    }
                }
            }) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClose) { Text(str(R.string.cancel)) } },
    )
}

/**
 * Editing your profile: names, about, picture, banner, website, Nostr address, lightning address
 * and status.
 */
@Composable
fun EditProfileScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val me = core.me ?: return
    val current = remember { runCatching { JSONObject(core.profiles[me]?.raw ?: "{}") }.getOrDefault(JSONObject()) }
    var name by remember { mutableStateOf(current.optString("name")) }
    var displayName by remember { mutableStateOf(current.optString("display_name")) }
    var about by remember { mutableStateOf(current.optString("about")) }
    var picture by remember { mutableStateOf(current.optString("picture")) }
    var banner by remember { mutableStateOf(current.optString("banner")) }
    var website by remember { mutableStateOf(current.optString("website")) }
    var nip05 by remember { mutableStateOf(current.optString("nip05")) }
    var lud16 by remember { mutableStateOf(current.optString("lud16")) }
    var saving by remember { mutableStateOf(false) }
    // NIP-38 status and NIP-39 linked accounts live in their own events, loaded from the network.
    val loadedStatus by produceState<String?>(null) { value = core.statusesOf(me).firstOrNull { !it.music }?.text.orEmpty() }
    val loadedIdentities by produceState<List<Identity>?>(null) { value = core.identitiesOf(me) }
    var status by remember(loadedStatus) { mutableStateOf(loadedStatus.orEmpty()) }
    val identities = remember(loadedIdentities) { mutableStateListOf<Identity>().apply { addAll(loadedIdentities.orEmpty()) } }
    var addingAccount by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(str(R.string.edit_profile))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                OutlinedTextField(displayName, { displayName = it }, label = { Text(str(R.string.display_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(str(R.string.username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(about, { about = it }, label = { Text(str(R.string.about)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(picture, { picture = it }, label = { Text(str(R.string.picture_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    banner,
                    { banner = it },
                    label = { Text(str(R.string.banner_url)) },
                    supportingText = { Text(str(R.string.a_wide_picture_across_the_top)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(website, { website = it }, label = { Text(str(R.string.website)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(nip05, { nip05 = it }, label = { Text(str(R.string.nostr_address_nip_05)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lud16, { lud16 = it }, label = { Text(str(R.string.lightning_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    status,
                    { status = it },
                    label = { Text(str(R.string.status)) },
                    placeholder = { Text(str(R.string.what_are_you_up_to)) },
                    supportingText = { Text(str(R.string.shown_under_your_name_leave_empty)) },
                    singleLine = true,
                    enabled = loadedStatus != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(str(R.string.linked_accounts), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (loadedIdentities == null) {
                    Text(str(R.string.loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    identities.forEach { identity ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(str(R.string.x_x, identity.label, identity.name), style = MaterialTheme.typography.bodyMedium)
                                Text(identity.proof, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton({ identities.remove(identity) }) { Icon(Icons.Rounded.Close, str(R.string.remove_x_account, identity.label)) }
                        }
                    }
                    OutlinedButton({ addingAccount = true }) { Text(str(R.string.link_an_account)) }
                }
                Button(
                    onClick = {
                        saving = true
                        scope.act(nav) {
                            try {
                                core.saveProfile(
                                    mapOf(
                                        "name" to name, "display_name" to displayName, "about" to about,
                                        "picture" to picture, "banner" to banner, "website" to website, "nip05" to nip05, "lud16" to lud16,
                                    ),
                                )
                                if (loadedStatus != null && status.trim() != loadedStatus) core.setStatus(status)
                                if (loadedIdentities != null && identities.toList() != loadedIdentities) core.setIdentities(identities.toList())
                                nav.say(str(R.string.profile_saved))
                                nav.pop()
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                ) { Text(if (saving) str(R.string.saving) else str(R.string.save_profile)) }
        }
    }
    if (addingAccount) LinkAccountDialog(Nip19.npub(me), { identities += it }) { addingAccount = false }
}

/**
 * Adds a NIP-39 linked account. The proof is a post or gist on that account holding the text below,
 * so others can check the account is really yours.
 */
@Composable
private fun LinkAccountDialog(npub: String, onAdd: (Identity) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var platform by remember { mutableStateOf("github") }
    var name by remember { mutableStateOf("") }
    var proof by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val proofHint = when (platform) {
        "github" -> str(R.string.gist_id)
        "twitter" -> str(R.string.post_id)
        "mastodon" -> str(R.string.post_id)
        "bluesky" -> str(R.string.post_record_key)
        "telegram" -> "channel/message"
        "discord" -> str(R.string.server_channel_message_ids)
        else -> str(R.string.proof)
    }
    val nameHint = when (platform) {
        "mastodon" -> "instance/@username"
        "bluesky" -> str(R.string.handle_like_alice_bsky_social)
        "telegram" -> str(R.string.user_id)
        else -> str(R.string.username)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.link_an_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton({ choosing = true }, Modifier.fillMaxWidth()) { Text(Identity.PLATFORMS[platform] ?: platform) }
                    DropdownMenu(choosing, { choosing = false }) {
                        Identity.PLATFORMS.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                platform = key
                                choosing = false
                            })
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(nameHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(proof, { proof = it }, label = { Text(proofHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    str(R.string.post_this_from_that_account_then),
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    onClick = { context.copy(Identity.proofText(npub)) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(Identity.proofText(npub), modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
                Text(str(R.string.tap_the_text_to_copy_it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                {
                    val normalized = name.trim().removePrefix("@").let { if (platform == "mastodon") it else it.lowercase() }
                    onAdd(Identity(platform, normalized, if (platform == "github") gistId(proof) else proof.trim()))
                    onDismiss()
                },
                enabled = name.isNotBlank() && proof.isNotBlank(),
            ) { Text(str(R.string.add)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(str(R.string.cancel)) } },
    )
}

/** Shows what each relay really holds for this account. Everything in the report is public. */
@Composable
fun AccountCheckScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val report by produceState<String?>(null) { value = core.accountCheck() }
    Column(Modifier.fillMaxSize()) {
        BackBar(str(R.string.account_check))
        val text = report
        if (text == null) {
            Text(str(R.string.asking_your_relays), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Loading(Modifier.fillMaxWidth().padding(24.dp))
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton({
                    context.copy(text)
                    nav.say(str(R.string.report_copied))
                }) { Text(str(R.string.copy_report)) }
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Exports the key sealed with a password. Nothing here is saved or sent anywhere. */
@Composable
private fun KeyBackupDialog(onClose: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val sealed = backup
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.encrypted_key_backup)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sealed == null) {
                    Text(str(R.string.choose_a_strong_password_nobody_can), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(password, { password = it }, label = { Text(str(R.string.password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(repeat, { repeat = it }, label = { Text(str(R.string.repeat_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (password.isNotEmpty() && password.length < 12) Text(str(R.string.use_at_least_12_characters), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(str(R.string.keep_this_somewhere_safe_don_t), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer { Text(sealed, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (sealed == null) {
                TextButton(
                    {
                        busy = true
                        scope.act(nav) {
                            try {
                                backup = core.exportEncrypted(password)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && password.length >= 12 && password == repeat,
                ) { Text(if (busy) str(R.string.encrypting) else str(R.string.encrypt)) }
            } else {
                TextButton({
                    context.copy(sealed)
                    nav.say(str(R.string.encrypted_backup_copied))
                }) { Text(str(R.string.copy)) }
            }
        },
        dismissButton = { TextButton(onClose) { Text(str(R.string.close)) } },
    )
}

/**
 * Sign-in for someone not signed in: make a new account, or use a key, a backup, an npub, a signer
 * app or a remote signer.
 */
@Composable
fun Onboarding(modifier: Modifier = Modifier) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val app = context.applicationContext as ZinoApp
    val accounts by app.accounts.list.collectAsState()
    // Adding an account opens sign-in; this is the way back to the account you came from.
    val previous = accounts.lastOrNull { it.id != core.accountId && it.pubkey != null }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var remoteSigning by remember { mutableStateOf(false) }

    /** Runs a sign-in step, showing it's busy meanwhile. */
    fun run(block: suspend () -> Unit) {
        busy = true
        scope.act(nav) {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (previous != null) {
            TextButton({ app.cancelAdding(previous.id) }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                Spacer(Modifier.width(8.dp))
                Text(str(R.string.back_to_x, previous.name?.takeIf { it.isNotBlank() } ?: core.displayName(previous.pubkey!!)))
            }
        } else {
            Spacer(Modifier.height(32.dp))
        }
        Text(str(R.string.zino), style = MaterialTheme.typography.displayMedium)
        Text(str(R.string.fast_light_nostr), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Section(str(R.string.new_to_nostr)) {
            OutlinedTextField(name, { name = it }, label = { Text(str(R.string.your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({ run { core.createAccount(name.trim()) } }, enabled = name.isNotBlank() && !busy) { Text(str(R.string.create_account)) }
        }
        Section(str(R.string.already_have_a_key)) {
            OutlinedTextField(key, { key = it }, label = { Text(str(R.string.nsec_ncryptsec_or_npub_to_browse)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val encrypted = key.trim().startsWith("ncryptsec1")
            if (encrypted) {
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(str(R.string.backup_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FilledTonalButton(
                { run { if (encrypted) core.signInEncrypted(key, password) else core.signIn(key) } },
                enabled = key.isNotBlank() && !busy && (!encrypted || password.isNotEmpty()),
            ) { Text(str(R.string.sign_in_2)) }
            if (ExternalSigner.isInstalled(context)) {
                OutlinedButton(
                    onClick = {
                        run {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
                                putExtra("type", "get_public_key")
                                putExtra("permissions", ExternalSigner.PERMISSIONS)
                            }
                            val reply = SignerBridge.request(intent)
                            val pubkey = reply.result
                            val pkg = reply.packageName
                            if (reply.ok && !reply.rejected && pubkey != null && pkg != null) {
                                val hex = (Nip19.decode(pubkey) as? app.struthio.nostr.core.Entity.Profile)?.pubkey ?: pubkey
                                core.signInExternal(hex, pkg)
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text(str(R.string.use_a_signer_app)) }
            }
            OutlinedButton({ remoteSigning = true }, enabled = !busy) { Text(str(R.string.use_a_remote_signer_bunker)) }
        }
    }
    if (remoteSigning) RemoteSignInSheet { remoteSigning = false }
}

/**
 * NIP-46 sign-in. Either paste a bunker:// link from the signer, or let the signer connect to the
 * nostrconnect:// link shown here, by scanning it or opening it on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteSignInSheet(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    // One throwaway client key and secret for this sheet; a new sheet makes new ones.
    val clientKey = remember { RemoteSigner.newClientKey() }
    val secret = remember { RemoteSigner.randomSecret() }
    val connectUri = remember {
        RemoteSigner.nostrConnectUri(LocalSigner(clientKey).pubkey, RemoteSigner.CONNECT_RELAYS, secret)
    }
    // Listens for a signer answering the link for as long as the sheet is open.
    LaunchedEffect(clientKey) {
        val ok = runCatching { core.signInNostrConnect(clientKey, secret, RemoteSigner.CONNECT_RELAYS, 10 * 60_000L) }
        ok.exceptionOrNull()?.let { nav.say(it.message ?: str(R.string.the_remote_signer_couldn_t_connect)) }
        if (ok.getOrNull() == true) onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(str(R.string.use_a_remote_signer), style = MaterialTheme.typography.titleLarge)
            Text(
                str(R.string.your_key_stays_in_a_signer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(link, { link = it }, label = { Text(str(R.string.bunker_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                {
                    connecting = true
                    scope.act(nav) {
                        try {
                            core.signInBunker(link)
                            onDismiss()
                        } finally {
                            connecting = false
                        }
                    }
                },
                enabled = link.trim().startsWith("bunker://") && !connecting,
            ) { Text(if (connecting) str(R.string.connecting_approve_it_in_your_signer) else str(R.string.connect)) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(str(R.string.or_connect_from_your_signer), style = MaterialTheme.typography.titleSmall)
            Text(
                str(R.string.scan_this_with_your_signer_or),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(connectUri) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({
                    context.copy(connectUri)
                    nav.say(str(R.string.link_copied))
                }) { Text(str(R.string.copy_link)) }
                OutlinedButton({
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(connectUri))) }
                        .onFailure { nav.say(str(R.string.no_signer_on_this_phone_opens)) }
                }) { Text(str(R.string.open_in_signer)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * NIP-62 request to vanish. Relays that support it must erase every event from this key, and
 * messages sent to it, and refuse them if they're sent again. There is no undo, so it asks twice.
 */
@Composable
private fun VanishDialog(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(str(R.string.delete_account_everywhere)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    str(R.string.vanish_explained),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    str(R.string.vanish_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(reason, { reason = it }, label = { Text(str(R.string.reason_optional)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(typed, { typed = it }, label = { Text(str(R.string.type_delete_to_confirm)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                {
                    sending = true
                    scope.act(nav) {
                        try {
                            val taken = core.requestVanish(reason)
                            nav.say(if (taken > 0) str(R.string.sent_to_x_relays_signing_out, taken) else str(R.string.no_relay_took_the_request_try))
                            if (taken > 0) {
                                nav.stack.clear()
                                core.signOut()
                            }
                        } finally {
                            sending = false
                            onDismiss()
                        }
                    }
                },
                enabled = typed.trim() == "DELETE" && !sending,
            ) { Text(if (sending) str(R.string.sending) else str(R.string.delete_everywhere), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onDismiss, enabled = !sending) { Text(str(R.string.cancel)) } },
    )
}
