package app.struthio.nostr.net

import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Negentropy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/** The pool's NIP-77 runs: against a fake relay, and, when asked for, against a real one. */
class NegentropyPoolTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = RelayPool(scope) { null }.apply { activityStarted() }
    private val filter = Filter(kinds = listOf(Kind.GIFT_WRAP))
    private val random = Random(7)

    @After
    fun tearDown() = scope.cancel()

    /** Made-up events: times and random ids. */
    private fun items(n: Int) = (1..n).map { 1_700_000_000L + it * 11 to Hex.encode(random.nextBytes(32)) }

    @Test
    fun aRelayThatComparesListsReportsWhatsMissing() = runBlocking {
        val shared = items(300)
        val extra = items(9)
        val sides = HashMap<String, Negentropy>()
        val relay = FakeRelay(onOther = { m, send ->
            val id = m.getString(1)
            when (m.getString(0)) {
                "NEG-OPEN" -> {
                    val side = Negentropy(shared + extra, responder = true).also { sides[id] = it }
                    send("""["NEG-MSG","$id","${side.reconcile(m.getString(3))}"]""")
                }
                "NEG-MSG" -> send("""["NEG-MSG","$id","${sides.getValue(id).reconcile(m.getString(2))}"]""")
            }
        })
        assertEquals(extra.map { it.second }.toSet(), pool.missing(relay.url, filter, shared, timeoutMs = 5000))
        relay.close()
    }

    @Test
    fun aRelayWithoutNegentropyGivesNull() = runBlocking {
        val relay = FakeRelay(onOther = { m, send ->
            if (m.getString(0) == "NEG-OPEN") send("""["NOTICE","unknown command"]""")
        })
        assertNull(pool.missing(relay.url, filter, items(3), timeoutMs = 1500))
        relay.close()
    }

    @Test
    fun aRefusalGivesNull() = runBlocking {
        val relay = FakeRelay(onOther = { m, send ->
            if (m.getString(0) == "NEG-OPEN") send("""["NEG-ERR","${m.getString(1)}","blocked: too many"]""")
        })
        assertNull(pool.missing(relay.url, filter, items(3), timeoutMs = 3000))
        relay.close()
    }

    /**
     * Read-only check against a real relay, run by the Negentropy check workflow with NEG_RELAY set:
     * fetch some public notes the usual way, keep only some, and the relay must name the rest.
     */
    @Test
    fun aRealRelayAgrees() = runBlocking {
        val url = System.getenv("NEG_RELAY")
        assumeTrue("set NEG_RELAY to run", !url.isNullOrBlank())
        val author = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        val notes = Filter(authors = listOf(author), kinds = listOf(Kind.NOTE), until = System.currentTimeMillis() / 1000 - 3600)
        val seen = HashMap<String, Long>()
        val answered = pool.query(setOf(url!!), listOf(notes), 15_000) { e, _ -> synchronized(seen) { seen[e.id] = e.createdAt } }
        println("fetched ${seen.size} notes from $url (answered: $answered, problems: ${pool.problemsOf(url)})")
        assumeTrue("relay returned too few notes", seen.size >= 40)
        val kept = seen.entries.filterIndexed { i, _ -> i % 3 != 0 }.map { it.value to it.key }
        val missing = pool.missing(url, notes, kept, timeoutMs = 20_000)
        println("relay says ${missing?.size} missing, expected at least ${seen.size - kept.size} (problems: ${pool.problemsOf(url)})")
        assertTrue("relay doesn't do negentropy", missing != null)
        val dropped = seen.keys - kept.map { it.second }.toSet()
        assertTrue("missing ids not reported", missing!!.containsAll(dropped))
    }
}
