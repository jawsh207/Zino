/*
 * People: profile screens with banner, picture, about text, status and tabs for posts, replies,
 * boosts and a gallery; lists of who someone follows or who follows them; and search for people,
 * posts, hashtags and Nostr links.
 */
package app.struthio.nostr.ui

import app.struthio.nostr.str
import app.struthio.nostr.R
import android.net.Uri
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.GalleryItem
import app.struthio.nostr.core.ProfileTab
import app.struthio.nostr.core.websiteLabel
import app.struthio.nostr.net.lightningLabel
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.MusicNote
import app.struthio.nostr.core.Identity
import app.struthio.nostr.core.Status
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.parseContent
import app.struthio.nostr.core.invoicesIn
import app.struthio.nostr.core.isNip05Address
import app.struthio.nostr.core.replaceInvoices
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.Feed
import app.struthio.nostr.data.FeedSource
import app.struthio.nostr.data.roomKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** A profile tab's name, in the phone's language. */
private val ProfileTab.title: String
    get() = str(
        when (this) {
            ProfileTab.POSTS -> R.string.posts
            ProfileTab.REPLIES -> R.string.replies
            ProfileTab.BOOSTS -> R.string.boosts
            ProfileTab.GALLERY -> R.string.gallery
        },
    )

/** What a profile tab says when it has nothing to show. */
private val ProfileTab.emptyText: String
    get() = str(
        when (this) {
            ProfileTab.POSTS -> R.string.no_posts_yet
            ProfileTab.REPLIES -> R.string.no_replies_yet
            ProfileTab.BOOSTS -> R.string.no_boosts_yet
            ProfileTab.GALLERY -> R.string.no_pictures_or_videos_yet
        },
    )

/** Copies text to the clipboard. */
fun Context.copy(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Zino", text))
}

/**
 * A person's profile: banner, picture, name, about, links and status, follow and zap buttons, and
 * their posts.
 */
@Composable
fun ProfileScreen(pubkey: String) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feed = remember(pubkey) { Feed(core, FeedSource.Author(pubkey)) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.stop() }
    }
    LaunchedEffect(pubkey) { core.wantProfile(pubkey) }
    val profile = core.profiles[pubkey]
    val follows by core.follows.collectAsState()
    val muted by core.muted.collectAsState()
    val isMe = pubkey == core.me
    var menu by remember { mutableStateOf(false) }
    var zapping by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    val followingList by produceState<List<String>?>(null, pubkey, follows) { value = core.followingOf(pubkey) }
    val followerCount by produceState<Long?>(null, pubkey) { value = core.followerCount(pubkey) }
    var statuses by remember(pubkey) { mutableStateOf(emptyList<Status>()) }
    LaunchedEffect(pubkey) { statuses = core.statusesOf(pubkey) }
    var editingStatus by remember { mutableStateOf(false) }
    val identities by produceState(emptyList<Identity>(), pubkey) { value = core.identitiesOf(pubkey) }
    var tab by rememberSaveable(pubkey) { mutableStateOf(ProfileTab.POSTS) }
    val lightning = lightningLabel(profile?.lud16, profile?.lud06)

    val listState = rememberLazyListState()
    // Once the banner has scrolled up under the bar, the bar turns solid so text doesn't run beneath it.
    val barHeight = with(LocalDensity.current) { (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp).toPx() }
    val bannerHeight = with(LocalDensity.current) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LocalConfiguration.current.screenWidthDp.dp / 3).toPx()
    }
    val solid by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > bannerHeight - barHeight }
    }
    val barColor by animateColorAsState(if (solid) MaterialTheme.colorScheme.background else Color.Transparent, label = "bar")
    // Light status bar icons over the banner's shade, the theme's own once the bar is solid.
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    DisposableEffect(solid, darkTheme) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = solid && !darkTheme
        onDispose { controller?.isAppearanceLightStatusBars = !darkTheme }
    }

    Box(Modifier.fillMaxSize()) {
        NoteList(feed, Modifier.fillMaxSize(), state = listState, pillTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp, empty = tab.emptyText, filter = tab::shows, gallery = tab == ProfileTab.GALLERY, header = {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileBanner(pubkey, profile?.banner, profile?.picture)
                Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EmojiText(core.richName(pubkey), profile?.emoji.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                            if (profile?.verified == true) {
                                Icon(Icons.Rounded.Verified, str(R.string.verified), Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        profile?.nip05?.let { Text(it.removePrefix("_@"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (isMe) {
                    // Your own status is set right here: tap it, or the prompt when there's none.
                    val general = statuses.firstOrNull { !it.music }
                    if (general != null) {
                        StatusLine(general, onClick = { editingStatus = true })
                    } else {
                        InfoLine(Icons.Rounded.AddReaction, str(R.string.set_a_status), str(R.string.set_a_status)) { editingStatus = true }
                    }
                    statuses.filter { it.music }.forEach { StatusLine(it) }
                } else {
                    statuses.forEach { StatusLine(it) }
                }
                profile?.about?.takeIf { it.isNotBlank() }?.let { about ->
                    val text = replaceInvoices(about) { "" }.trim()
                    if (text.isNotEmpty()) {
                        val linked = linkedText(remember(text) { parseContent(text) }, profile.emoji)
                        Text(linked, style = MaterialTheme.typography.bodyMedium, inlineContent = emojiImages(profile.emoji))
                    }
                    invoicesIn(about).forEach { InvoiceCard(it) }
                }
                profile?.website?.let { site ->
                    InfoLine(Icons.Rounded.Link, websiteLabel(site), str(R.string.website)) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }
                    }
                }
                lightning?.let { address ->
                    // Tapping zaps; your own address is copied instead.
                    InfoLine(Icons.Rounded.Bolt, address, str(R.string.lightning_address)) {
                        if (isMe) context.copy(address) else zapping = true
                    }
                }
                if (identities.isNotEmpty()) LinkedAccounts(pubkey, identities)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ nav.push(Screen.People(pubkey, followers = false)) }) {
                        Text(str(R.string.x_following, followingList?.size?.toLong()?.let(::compact) ?: "–"))
                    }
                    TextButton({ nav.push(Screen.People(pubkey, followers = true)) }) {
                        Text(followerCount?.let { str(R.string.x_followers, compact(it)) } ?: str(R.string.followers))
                    }
                }
                if (isMe) {
                    FilledTonalButton({ nav.push(Screen.EditProfile) }, Modifier.fillMaxWidth()) { Text(str(R.string.edit_profile)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val following = pubkey in follows
                        val click = {
                            busy = true
                            scope.act(nav) {
                                try {
                                    core.setFollowing(pubkey, !following)
                                } finally {
                                    busy = false
                                }
                            }
                            Unit
                        }
                        if (following) {
                            FilledTonalButton(click, enabled = !busy, modifier = Modifier.weight(1f)) { Text(str(R.string.following)) }
                        } else {
                            Button(click, enabled = !busy, modifier = Modifier.weight(1f)) { Text(str(R.string.follow)) }
                        }
                        FilledTonalIconButton({
                            nav.push(Screen.Chat(roomKey(setOf(pubkey, core.me ?: ""))))
                        }) { Icon(Icons.Outlined.ChatBubbleOutline, str(R.string.message)) }
                        // Zaps need somewhere to send them.
                        if (lightning != null) FilledTonalIconButton({ zapping = true }) { Icon(Icons.Rounded.Bolt, str(R.string.zap)) }
                    }
                }
                PrimaryScrollableTabRow(
                    selectedTabIndex = tab.ordinal,
                    edgePadding = 0.dp,
                    containerColor = Color.Transparent,
                    divider = {},
                ) {
                    ProfileTab.entries.forEach { t ->
                        Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) })
                    }
                }
            }
        })
        // Back and the menu float over the banner, which runs up behind them.
        Row(
            Modifier.fillMaxWidth().background(barColor).statusBarsPadding().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverBannerButton(Icons.AutoMirrored.Rounded.ArrowBack, str(R.string.back), solid) { nav.pop() }
            Spacer(Modifier.weight(1f))
            Box {
                OverBannerButton(Icons.Rounded.MoreVert, str(R.string.more), solid) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(str(R.string.copy_npub)) }, onClick = {
                        menu = false
                        context.copy(Nip19.npub(pubkey))
                    })
                    DropdownMenuItem(text = { Text(str(R.string.show_qr_code)) }, onClick = {
                        menu = false
                        showQr = true
                    })
                    if (!isMe) {
                        val isMuted = pubkey in muted
                        DropdownMenuItem(text = { Text(if (isMuted) str(R.string.unmute) else str(R.string.mute)) }, onClick = {
                            menu = false
                            scope.act(nav) { core.setMuted(pubkey, !isMuted) }
                        })
                    }
                }
            }
        }
    }
    if (editingStatus) {
        StatusDialog(
            current = statuses.firstOrNull { !it.music }?.text.orEmpty(),
            onDismiss = { editingStatus = false },
        ) { text, hours ->
            editingStatus = false
            scope.launch {
                try {
                    core.setStatus(text, hours)
                    val others = statuses.filter { it.music }
                    statuses = (if (text.isBlank()) emptyList() else listOf(Status(text.trim(), false, null))) + others
                    nav.say(if (text.isBlank()) str(R.string.status_cleared) else str(R.string.status_set))
                } catch (e: Exception) {
                    nav.say(e.message ?: str(R.string.couldn_t_set_your_status))
                }
            }
        }
    }
    if (zapping) ZapSheet(null, pubkey) { zapping = false }
    if (showQr) {
        ModalBottomSheet(onDismissRequest = { showQr = false }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrImage("nostr:" + Nip19.npub(pubkey))
            }
        }
    }
}

/** The people someone follows, or the people who follow them. */
@Composable
fun PeopleScreen(screen: Screen.People) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val people by produceState<List<String>?>(null, screen) {
        value = if (screen.followers) core.followersOf(screen.pubkey) else core.followingOf(screen.pubkey)
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(if (screen.followers) str(R.string.followers) else str(R.string.following))
        val list = people
        when {
            list == null -> Loading(Modifier.fillMaxWidth().padding(48.dp))
            list.isEmpty() -> Text(str(R.string.nobody_found_on_these_relays), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, it -> it }) { index, pubkey ->
                    PersonRow(core, pubkey, segmentShape(index, list.size)) { nav.push(Screen.Profile(pubkey)) }
                }
            }
        }
    }
}

/** People matching what was typed: an npub or nprofile, a NIP-05 address, or a name. Follows come first. */
@Composable
fun rememberPeople(query: String): List<String> {
    val core = LocalCore.current
    val text = query.trim()
    val exact = remember(text) { (Nip19.decode(text.removePrefix("nostr:")) as? Entity.Profile)?.pubkey }
    var owner by remember(text) { mutableStateOf<String?>(null) }
    val named by liveQuery(emptyList(), text) {
        if (text.length >= 2) core.store.searchProfiles(text, 12).map { it.pubkey } else emptyList()
    }
    LaunchedEffect(text) {
        if (exact != null || text.length < 3) return@LaunchedEffect
        delay(500)
        owner = core.resolveAddress(text)
        core.pool.query(
            core.searchRelays(),
            listOf(Filter(kinds = listOf(Kind.METADATA), search = text, limit = 12)),
            4000,
            onEvent = core::ingest,
        )
    }
    return remember(exact, owner, named) {
        val follows = core.follows.value
        (listOfNotNull(exact, owner) + named.sortedByDescending { it in follows }).distinct()
    }
}

/** Search for people and posts. A hashtag, Nostr address or nostr link opens what it points at. */
@Composable
fun SearchScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val feed = remember(submitted) { submitted.takeIf { it.isNotBlank() }?.let { Feed(core, FeedSource.Search(it)) } }
    DisposableEffect(feed) {
        feed?.start()
        onDispose { feed?.stop() }
    }
    val people = rememberPeople(query)

    /** Opens what was typed if it's a hashtag, Nostr address or link; otherwise searches. */
    fun submit() {
        val text = query.trim()
        when {
            text.startsWith("#") && text.length > 1 -> nav.push(Screen.Hashtag(text.drop(1)))
            isNip05Address(text) && people.isNotEmpty() -> nav.push(Screen.Profile(people.first()))
            else -> when (val entity = Nip19.decode(text)) {
                is Entity.Profile -> nav.push(Screen.Profile(entity.pubkey))
                is Entity.Note -> nav.push(Screen.Thread(entity.id, entity.relays, entity.author))
                is Entity.Address -> scope.launch {
                    core.fetchAddress(entity.kind, entity.author, entity.identifier, entity.relays)?.let { nav.push(Screen.Thread(it.id)) }
                }
                else -> submitted = text
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(str(R.string.search))
        OutlinedTextField(
            query,
            { query = it },
            placeholder = { Text(str(R.string.notes_people_tags_npub)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                Row {
                    ScanButton {
                        query = it.removePrefix("nostr:")
                        submit()
                    }
                    IconButton(onClick = ::submit) { Icon(Icons.Rounded.Search, str(R.string.search)) }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter),
        )
        if (feed != null) {
            NoteList(feed, Modifier.weight(1f).padding(top = 8.dp), empty = str(R.string.no_notes_found))
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(people, key = { _, it -> it }) { index, pubkey ->
                    PersonRow(core, pubkey, segmentShape(index, people.size)) { nav.push(Screen.Profile(pubkey)) }
                }
            }
        }
    }
}

/** A person as a row: picture, name and Nostr address. */
@Composable
fun PersonRow(core: Core, pubkey: String, shape: Shape, onClick: () -> Unit) {
    LaunchedEffect(pubkey) { core.wantProfile(pubkey) }
    CardRow(
        shape,
        onClick,
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiText(core.richName(pubkey), core.profiles[pubkey]?.emoji.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (core.profiles[pubkey]?.verified == true) {
                    Icon(Icons.Rounded.Verified, str(R.string.verified), Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        supportingContent = core.profiles[pubkey]?.nip05?.let { { Text(it) } },
        leadingContent = { Avatar(pubkey, 40.dp) },
    )
}

/** NIP-38: a status under someone's name; a music one gets a note. Tapping opens its link. */
@Composable
private fun StatusLine(status: Status, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.then(
            when {
                onClick != null -> Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = str(R.string.change_status), onClick = onClick)
                status.link != null -> Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(status.link))) } }
                else -> Modifier
            },
        ),
    ) {
        Icon(
            if (status.music) Icons.Rounded.MusicNote else Icons.Rounded.ChatBubble,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            status.text,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** NIP-39: accounts elsewhere. GitHub ones are checked against their gist; the tick means it held. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkedAccounts(pubkey: String, identities: List<Identity>) {
    val core = LocalCore.current
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        identities.forEach { identity ->
            val verified by produceState(false, pubkey, identity) { value = core.verifyIdentity(pubkey, identity) }
            // The proof, so anyone can see the claim for themselves.
            val target = identity.proofUrl ?: identity.profileUrl
            AssistChip(
                onClick = { target?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } },
                label = { Text(str(R.string.x_x, identity.label, identity.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = if (verified) {
                    { Icon(Icons.Rounded.Verified, str(R.string.proof_checked), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                } else {
                    null
                },
            )
        }
    }
}

/** A line of profile info with its icon, opening or acting on tap. */
@Composable
private fun InfoLine(icon: ImageVector, text: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = description, onClick = onClick).padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, description, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Three gallery squares; each opens the post it came from. Sensitive posts stay covered here. */
@Composable
fun GalleryRow(row: List<Pair<Event, GalleryItem>>) {
    val nav = LocalNav.current
    val core = LocalCore.current
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        row.forEach { (event, item) ->
            val sensitive = event.tags.any { it.firstOrNull() == "content-warning" }
            Box(
                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { nav.push(Screen.Thread(event.id)) },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    sensitive -> Icon(Icons.Rounded.VisibilityOff, str(R.string.sensitive), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    !item.video -> NetImage(item.url, 160.dp, Modifier.fillMaxSize())
                    item.thumbnail != null -> NetImage(item.thumbnail, 160.dp, Modifier.fillMaxSize())
                    else -> {
                        val frame by produceState<android.graphics.Bitmap?>(null, item.url) {
                            if (core.linkPreviewsEnabled()) value = core.videoFrame(item.url)
                        }
                        frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
                if (item.video && !sensitive) {
                    Icon(Icons.Rounded.PlayArrow, str(R.string.video), Modifier.size(36.dp), tint = Color.White)
                }
            }
        }
        // A short last row keeps square tiles.
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * The banner across the whole width, up behind the status bar and the back button, with the
 * avatar half over its lower edge, ringed in the page colour. Without a banner, a plain band.
 * Tapping the avatar opens the profile picture full size.
 */
@Composable
private fun ProfileBanner(pubkey: String, banner: String?, picture: String?) {
    val avatar = 84.dp
    val ring = 4.dp
    val shape = avatarShape(pubkey)
    var viewing by remember { mutableStateOf(false) }
    val full = picture?.takeIf { it.startsWith("https://") }
    BoxWithConstraints(Modifier.fullBleed(PageGutter)) {
        // Banners are made 3:1; that much shows below the status bar.
        val bannerHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + maxWidth / 3
        Box(Modifier.fillMaxWidth().height(bannerHeight + avatar / 2 + ring)) {
            BannerArea(banner, Modifier.fillMaxWidth().height(bannerHeight))
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = PageGutter - ring)
                    .size(avatar + ring * 2).clip(shape).background(MaterialTheme.colorScheme.background)
                    .then(if (full != null) Modifier.clickable(onClickLabel = str(R.string.view_picture)) { viewing = true } else Modifier),
                contentAlignment = Alignment.Center,
            ) { Avatar(pubkey, avatar) }
        }
    }
    if (viewing && full != null) ImageViewer(listOf(full), 0) { viewing = false }
}

/** The profile's banner picture, or a plain colour without one. */
@Composable
private fun BannerArea(banner: String?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        banner?.let { NetImage(it, 480.dp, Modifier.fillMaxSize()) }
        // A shade at the top keeps the status bar and the buttons readable on light pictures.
        Box(
            Modifier.fillMaxWidth().height(110.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
        )
    }
}

/** A round button over the banner, kept visible on any picture. */
@Composable
private fun OverBannerButton(icon: ImageVector, description: String, solid: Boolean, onClick: () -> Unit) {
    // A dark disc keeps it visible on any banner; on the solid bar it's a plain button.
    val container by animateColorAsState(if (solid) Color.Transparent else Color.Black.copy(alpha = 0.35f), label = "button")
    val content by animateColorAsState(if (solid) MaterialTheme.colorScheme.onSurface else Color.White, label = "icon")
    FilledIconButton(
        onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
    ) { Icon(icon, description) }
}

/** NIP-38: set, change or clear your status, and when it should end by itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusDialog(current: String, onDismiss: () -> Unit, onSave: (text: String, hours: Int?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    var hours by remember { mutableStateOf<Int?>(null) }
    val choices = listOf(null to str(R.string.don_t_clear), 1 to str(R.string.n_1_hour), 4 to str(R.string.n_4_hours), 24 to str(R.string.today), 168 to str(R.string.this_week))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.status)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    text,
                    { text = it.take(140) },
                    placeholder = { Text(str(R.string.what_are_you_up_to)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(str(R.string.clear_after), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { (h, label) ->
                        FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onSave(text, hours) }, enabled = text.isNotBlank() && (text.trim() != current || hours != null)) { Text(str(R.string.set)) }
        },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton({ onSave("", null) }) { Text(str(R.string.clear)) }
                TextButton(onDismiss) { Text(str(R.string.cancel)) }
            }
        },
    )
}
