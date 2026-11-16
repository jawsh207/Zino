package app.struthio.nostr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Negentropy (NIP-77): two sides with different events find exactly their differences. */
class NegentropyTest {
    private val random = Random(77)

    /** A made-up event: a time and a random id. */
    private fun item(time: Long) = time to Hex.encode(random.nextBytes(32))

    /** Runs Zino against a relay holding [theirs] until done; returns (need, have, rounds). */
    private fun run(ours: List<Pair<Long, String>>, theirs: List<Pair<Long, String>>): Triple<Set<String>, Set<String>, Int> {
        val client = Negentropy(ours)
        val relay = Negentropy(theirs, responder = true)
        var message: String? = client.initiate()
        var rounds = 0
        while (message != null) {
            rounds++
            assertTrue("too many rounds", rounds < 50)
            message = client.reconcile(relay.reconcile(message)!!)
        }
        return Triple(client.need, client.have, rounds)
    }

    @Test
    fun varintsAreBigEndianBase128() {
        assertEquals("00", Hex.encode(Negentropy.varint(0)))
        assertEquals("7f", Hex.encode(Negentropy.varint(127)))
        assertEquals("8100", Hex.encode(Negentropy.varint(128)))
        assertEquals("8180" + "00", Hex.encode(Negentropy.varint(16384)))
    }

    @Test
    fun theFirstMessageListsFewIdsDirectly() {
        val items = listOf(item(10), item(20))
        val message = Negentropy(items).initiate()
        // Version, then one range to "infinity" (00, empty prefix 00), id list mode (02), two ids.
        assertTrue(message.startsWith("61" + "00" + "00" + "02" + "02"))
        assertEquals(2 * (5 + 64), message.length)
    }

    @Test
    fun identicalSetsAgreeAtOnce() {
        val items = (1..500L).map { item(it) }
        val (need, have, rounds) = run(items, items)
        assertTrue(need.isEmpty())
        assertTrue(have.isEmpty())
        assertEquals(1, rounds)
    }

    @Test
    fun anEmptySideNeedsEverything() {
        val theirs = (1..300L).map { item(it * 7) }
        val (need, have, _) = run(emptyList(), theirs)
        assertEquals(theirs.map { it.second }.toSet(), need)
        assertTrue(have.isEmpty())
    }

    @Test
    fun differencesAreFoundBothWays() {
        val shared = (1..2000L).map { item(1_700_000_000 + it * 13) }
        val onlyRelay = (1..37L).map { item(1_700_000_000 + it * 97) }
        val onlyUs = (1..11L).map { item(1_700_000_000 + it * 131) }
        val (need, have, _) = run(shared + onlyUs, shared + onlyRelay)
        assertEquals(onlyRelay.map { it.second }.toSet(), need)
        assertEquals(onlyUs.map { it.second }.toSet(), have)
    }

    @Test
    fun manyEventsAtTheSameSecondAreToldApart() {
        val shared = (1..400).map { item(1_000) }
        val extra = (1..5).map { item(1_000) }
        val (need, _, _) = run(shared, shared + extra)
        assertEquals(extra.map { it.second }.toSet(), need)
    }
}
