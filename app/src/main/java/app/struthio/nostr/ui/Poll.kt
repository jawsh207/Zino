/*
 * Polls (NIP-88): voting on a poll in a post, seeing the results, and making a new poll in the
 * composer.
 */
package app.struthio.nostr.ui

import app.struthio.nostr.str
import app.struthio.nostr.plural
import app.struthio.nostr.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Poll
import app.struthio.nostr.core.countVotes
import app.struthio.nostr.core.nowSeconds

private const val MAX_OPTIONS = 8

/** A poll in a post: its options to vote on, or the results once you've voted or it has ended. */
@Composable
fun PollCard(event: Event, poll: Poll) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val me = core.me
    LaunchedEffect(event.id) { core.loadVotes(event, poll) }
    val votes by liveQuery(emptyList<Event>(), event.id) { core.store.votes(event.id) }
    val result = remember(votes, poll, me) { countVotes(poll, votes, me) }
    val ended = poll.endsAt != null && nowSeconds() > poll.endsAt
    var busy by remember(event.id) { mutableStateOf(false) }
    val picked = remember(event.id) { mutableStateListOf<String>() }
    val showResults = ended || result.mine.isNotEmpty() || event.pubkey == me
    /** Sends your vote. */
    fun send(choices: List<String>) {
        busy = true
        scope.act(nav) {
            try {
                core.vote(event, poll, choices)
                picked.clear()
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        poll.options.forEach { option ->
            val count = result.counts[option.id] ?: 0
            val share = if (result.voters > 0) count.toFloat() / result.voters else 0f
            val chosen = option.id in result.mine || option.id in picked
            Surface(
                onClick = {
                    when {
                        !poll.multiple -> send(listOf(option.id))
                        option.id in picked -> picked.remove(option.id)
                        else -> picked += option.id
                    }
                },
                enabled = !ended && !busy,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    if (showResults && share > 0f) {
                        Box(Modifier.matchParentSize()) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(share)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                            )
                        }
                    }
                    Row(
                        Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (chosen) Icon(Icons.Rounded.Check, str(R.string.your_choice), tint = MaterialTheme.colorScheme.primary)
                        Text(option.label, modifier = Modifier.weight(1f), fontWeight = if (chosen) FontWeight.SemiBold else null)
                        if (showResults) Text("${(share * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (poll.multiple && !ended && picked.isNotEmpty()) {
            FilledTonalButton({ send(picked.toList()) }, enabled = !busy) { Text(str(R.string.vote)) }
        }
        val parts = listOfNotNull(
            plural(R.plurals.votes, result.voters, result.voters),
            str(R.string.choose_several).takeIf { poll.multiple },
            when {
                poll.endsAt == null -> null
                ended -> str(R.string.ended)
                else -> str(R.string.ends_in_x, timeLeft(poll.endsAt - nowSeconds()))
            },
        )
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Time left in short form, like 2d or 5h. */
internal fun timeLeft(seconds: Long): String = when {
    seconds >= 86400 -> str(R.string.x_d, seconds / 86400)
    seconds >= 3600 -> str(R.string.x_h, seconds / 3600)
    else -> str(R.string.x_m, maxOf(1, seconds / 60))
}

/** The choices a new poll is being built from. */
class PollDraft {
    val options = mutableStateListOf("", "")
    var multiple by mutableStateOf(false)
    var days by mutableStateOf<Int?>(1)

    val filled: List<String> get() = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** The options and settings for a new poll. */
@Composable
fun PollEditor(draft: PollDraft) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.options.forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text,
                    { draft.options[index] = it.take(100) },
                    placeholder = { Text(str(R.string.option_x, index + 1)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (draft.options.size > 2) {
                    IconButton({ draft.options.removeAt(index) }) { Icon(Icons.Rounded.Close, str(R.string.remove_option)) }
                }
            }
        }
        if (draft.options.size < MAX_OPTIONS) TextButton({ draft.options += "" }) { Text(str(R.string.add_option)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(draft.multiple, { draft.multiple = !draft.multiple }, { Text(str(R.string.choose_several)) })
            listOf(1 to str(R.string.n_1_day), 3 to str(R.string.n_3_days), 7 to str(R.string.n_7_days), null to str(R.string.no_end)).forEach { (days, label) ->
                FilterChip(draft.days == days, { draft.days = days }, { Text(label) })
            }
        }
    }
}
