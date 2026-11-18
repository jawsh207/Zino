/*
 * Muted words and hashtags (NIP-51 mute list). Posts, replies and notifications that contain a
 * muted word, or carry a muted hashtag, are hidden everywhere in the app. The list is your public
 * mute list, so it follows you to other apps that read NIP-51; private entries already on it are
 * kept as they are.
 */
package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.struthio.nostr.R
import app.struthio.nostr.str

/** One muted entry: a word, or a hashtag when [hashtag] is true. */
private data class MutedTerm(val text: String, val hashtag: Boolean)

/** The muted words and hashtags, with a field to add more and a button to remove each. */
@Composable
fun MutesScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val words by core.mutedWords.collectAsState()
    val tags by core.mutedTags.collectAsState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val terms = remember(words, tags) {
        tags.sorted().map { MutedTerm(it, true) } + words.sorted().map { MutedTerm(it, false) }
    }

    /** Adds or removes one entry, starting from the newest mute list on your relays. */
    fun change(term: MutedTerm, mute: Boolean) {
        if (busy) return
        busy = true
        scope.act(nav) {
            try {
                core.setMutedTerm(if (term.hashtag) "t" else "word", term.text, mute)
                if (mute) input = ""
            } finally {
                busy = false
            }
        }
    }

    /** Adds what was typed: a hashtag if it starts with #, otherwise a word or phrase. */
    fun add() {
        val text = input.trim()
        if (text.isEmpty()) return
        change(MutedTerm(text.removePrefix("#"), text.startsWith("#")), mute = true)
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(str(R.string.muted_words_and_hashtags))
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                str(R.string.muted_words_explained),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    input,
                    { input = it },
                    Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(str(R.string.add_a_word_or_hashtag)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                Spacer(Modifier.width(8.dp))
                Button({ add() }, enabled = !busy && input.isNotBlank()) { Text(str(R.string.mute)) }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (terms.isEmpty()) {
            Text(
                str(R.string.nothing_muted_yet),
                Modifier.fillMaxWidth().padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(terms, key = { _, term -> (if (term.hashtag) "#" else "w:") + term.text }) { index, term ->
                CardRow(
                    segmentShape(index, terms.size),
                    null,
                    headlineContent = { Text(if (term.hashtag) "#${term.text}" else term.text) },
                    leadingContent = { Icon(if (term.hashtag) Icons.Rounded.Tag else Icons.Rounded.TextFields, null) },
                    trailingContent = {
                        IconButton({ change(term, mute = false) }, enabled = !busy) {
                            Icon(Icons.Rounded.Close, str(R.string.unmute))
                        }
                    },
                )
            }
        }
    }
}
