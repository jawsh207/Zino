/*
 * The menu on each post (copy link, delete, report), and the Bookmarks screen.
 */
package app.struthio.nostr.ui

import app.struthio.nostr.str
import app.struthio.nostr.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.data.Feed
import app.struthio.nostr.data.FeedSource

private val REPORT_TYPES = listOf(
    "spam" to str(R.string.spam),
    "nudity" to str(R.string.nudity),
    "profanity" to str(R.string.hateful_or_abusive),
    "illegal" to str(R.string.illegal),
    "impersonation" to str(R.string.impersonation),
    "malware" to str(R.string.malware),
    "other" to str(R.string.something_else),
)

/** The three-dot menu on a post: copy its link, delete your own (NIP-09), or report it (NIP-56). */
@Composable
fun NoteMenu(event: Event) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookmarks by core.bookmarks.collectAsState()
    var open by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val mine = event.pubkey == core.me
    val saved = event.id in bookmarks

    Box {
        IconButton({ open = true }, Modifier.size(32.dp)) {
            Icon(Icons.Rounded.MoreVert, str(R.string.more), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(if (saved) str(R.string.remove_bookmark) else str(R.string.bookmark)) }, onClick = {
                open = false
                scope.act(nav) {
                    core.setBookmarked(event, !saved)
                    nav.say(if (saved) str(R.string.bookmark_removed) else str(R.string.bookmarked))
                }
            })
            DropdownMenuItem(text = { Text(str(R.string.copy_link)) }, onClick = {
                open = false
                context.copy("nostr:" + Nip19.nevent(event.id, listOf(core.hintFor(event.id)).filter { it.isNotEmpty() }, event.pubkey))
                nav.say(str(R.string.link_copied))
            })
            if (mine) {
                DropdownMenuItem(text = { Text(str(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = {
                    open = false
                    deleting = true
                })
            } else {
                DropdownMenuItem(text = { Text(str(R.string.report)) }, onClick = {
                    open = false
                    reporting = true
                })
            }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(str(R.string.delete_this_note)) },
            text = { Text(str(R.string.relays_are_asked_to_remove_it)) },
            confirmButton = {
                TextButton({
                    deleting = false
                    scope.act(nav) {
                        core.deleteNote(event)
                        nav.say(str(R.string.note_deleted))
                    }
                }) { Text(str(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleting = false }) { Text(str(R.string.cancel)) } },
        )
    }
    if (reporting) {
        var type by remember { mutableStateOf("spam") }
        var mute by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text(str(R.string.report_this_note)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(str(R.string.reports_are_public_events_on_nostr), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        REPORT_TYPES.forEach { (id, label) ->
                            FilterChip(type == id, { type = id }, { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(mute, { mute = it })
                        Text(str(R.string.also_mute_x, core.displayName(event.pubkey)))
                    }
                }
            },
            confirmButton = {
                TextButton({
                    reporting = false
                    scope.act(nav) {
                        core.report(event, type)
                        if (mute) core.setMuted(event.pubkey, true)
                        nav.say(str(R.string.report_sent))
                    }
                }) { Text(str(R.string.report)) }
            },
            dismissButton = { TextButton({ reporting = false }) { Text(str(R.string.cancel)) } },
        )
    }
}

/** Your bookmarked posts (NIP-51). */
@Composable
fun BookmarksScreen() {
    val core = LocalCore.current
    val feed = remember { Feed(core, FeedSource.Bookmarks) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.stop() }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(str(R.string.bookmarks))
        NoteList(feed, Modifier.weight(1f).padding(top = 8.dp), empty = str(R.string.notes_you_bookmark_show_up_here))
    }
}
