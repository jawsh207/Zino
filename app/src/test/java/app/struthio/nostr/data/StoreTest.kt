package app.struthio.nostr.data

import org.robolectric.RuntimeEnvironment
import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Keys
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.LocalSigner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The database: what it keeps, replaces, deletes and counts. */
@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private val store = Store(RuntimeEnvironment.getApplication(), "test.db")
    private val alice = LocalSigner(Keys.generate())
    private val bob = LocalSigner(Keys.generate())

    @After
    fun tearDown() = store.close()

    /** A signed event by [who]. */
    private fun event(who: LocalSigner, kind: Int, at: Long, content: String = "", tags: List<List<String>> = emptyList()): Event =
        who.signNow(Draft(who.pubkey, at, kind, tags, content))

    /** Stores events as if from a relay, and returns the new ones. */
    private fun insert(vararg events: Event) = store.insert(events.map { it to "wss://relay.example" })

    @Test
    fun onlyTheNewestReplaceableEventIsKept() {
        val old = event(alice, Kind.FOLLOWS, 100)
        val new = event(alice, Kind.FOLLOWS, 200)
        assertEquals(2, insert(old, new).size)
        assertEquals(new.id, store.latest(alice.pubkey, Kind.FOLLOWS)?.id)
        assertTrue(insert(event(alice, Kind.FOLLOWS, 150)).isEmpty())
        assertEquals(new.id, store.latest(alice.pubkey, Kind.FOLLOWS)?.id)
        assertNull(store.event(old.id))
    }

    @Test
    fun addressableEventsAreReplacedPerIdentifier() {
        val a1 = event(alice, Kind.ARTICLE, 100, "first", listOf(listOf("d", "a")))
        val b1 = event(alice, Kind.ARTICLE, 100, "other", listOf(listOf("d", "b")))
        val a2 = event(alice, Kind.ARTICLE, 200, "second", listOf(listOf("d", "a")))
        insert(a1, b1, a2)
        assertEquals("second", store.address(Kind.ARTICLE, alice.pubkey, "a")?.content)
        assertEquals("other", store.address(Kind.ARTICLE, alice.pubkey, "b")?.content)
    }

    @Test
    fun giftWrapsAreNeverStored() {
        assertTrue(insert(event(alice, Kind.GIFT_WRAP, 100, "sealed", listOf(listOf("p", bob.pubkey)))).isEmpty())
    }

    @Test
    fun onlyTheAuthorCanDelete() {
        val note = event(alice, Kind.NOTE, 100, "hello")
        insert(note)
        insert(event(bob, Kind.DELETION, 200, tags = listOf(listOf("e", note.id))))
        assertNotNull(store.event(note.id))
        insert(event(alice, Kind.DELETION, 300, tags = listOf(listOf("e", note.id))))
        assertNull(store.event(note.id))
    }

    @Test
    fun aDeletedEventDoesNotComeBack() {
        val note = event(alice, Kind.NOTE, 100, "regret")
        insert(event(alice, Kind.DELETION, 200, tags = listOf(listOf("e", note.id))))
        assertTrue(insert(note).isEmpty())
        assertNull(store.event(note.id))
        // Someone else's deletion doesn't hold back the author's note.
        val other = event(bob, Kind.NOTE, 100, "fine")
        insert(event(alice, Kind.DELETION, 200, tags = listOf(listOf("e", other.id))))
        assertEquals(1, insert(other).size)
    }

    @Test
    fun repliesAndReactionsAreCounted() {
        val note = event(alice, Kind.NOTE, 100, "hello")
        val reply = event(bob, Kind.NOTE, 110, "hi", listOf(listOf("e", note.id, "", "root"), listOf("p", alice.pubkey)))
        val like = event(bob, Kind.REACTION, 120, "+", listOf(listOf("e", note.id), listOf("p", alice.pubkey)))
        val dislike = event(alice, Kind.REACTION, 130, "-", listOf(listOf("e", note.id)))
        insert(note, reply, like, dislike)
        val stats = store.stats(listOf(note.id), bob.pubkey).getValue(note.id)
        assertEquals(1, stats.comments)
        assertEquals(1, stats.reactions)
        assertTrue(stats.mineReacted)
        assertEquals(listOf(reply.id), store.thread(note.id).map { it.id } - note.id)
    }

    @Test
    fun repliesAndReactionsNotifyThePersonTagged() {
        val note = event(alice, Kind.NOTE, 100, "hello")
        insert(note, event(bob, Kind.REACTION, 120, "+", listOf(listOf("e", note.id), listOf("p", alice.pubkey))))
        val alerts = store.alerts(alice.pubkey, emptySet(), 10)
        assertEquals(1, alerts.size)
        assertEquals(bob.pubkey, alerts[0].actor)
        assertTrue(store.alerts(alice.pubkey, setOf(bob.pubkey), 10).isEmpty())
    }

    @Test
    fun notesCanBeSearched() {
        insert(event(alice, Kind.NOTE, 100, "ostriches run fast"), event(alice, Kind.NOTE, 101, "something else"))
        assertEquals(listOf("ostriches run fast"), store.searchNotes("ostrich", 10).map { it.content })
    }

    @Test
    fun messagesKeepTheirRepliesAndReactions() {
        val room = roomKey(setOf(alice.pubkey, bob.pubkey))
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        store.addDm(Dm(first, room, alice.pubkey, 100, "hi", null))
        store.addDm(Dm(second, room, bob.pubkey, 110, "hello", null))
        // Read again later, the second message turns out to be a reply.
        store.addDm(Dm(second, room, bob.pubkey, 110, "hello", null, replyTo = first))
        store.addDmReaction(room, DmReaction("c".repeat(64), first, bob.pubkey, "💜", 120))
        val messages = store.messages(room, 10)
        assertEquals(2, messages.size)
        assertEquals(first, messages.first { it.id == second }.replyTo)
        assertEquals("💜", store.dmReactions(room).getValue(first).single().content)
        assertEquals(mapOf(room to 1), store.unreadRooms(alice.pubkey, 0))
        store.markRead("room:$room", 110)
        assertTrue(store.unreadRooms(alice.pubkey, 0).isEmpty())
        assertEquals(room, store.rooms(alice.pubkey).single().key)
    }

    @Test
    fun wrapsAreOpenedOnce() {
        assertFalse(store.hasWrap("w"))
        store.addWrap("w")
        assertTrue(store.hasWrap("w"))
        store.forgetOpenedWraps()
        assertFalse(store.hasWrap("w"))
    }

    @Test
    fun theOutboxKeepsEventsUntilSentOrTooOld() {
        val first = event(alice, Kind.NOTE, 100, "one")
        val second = event(alice, Kind.NOTE, 101, "two")
        store.queueOutgoing(first, setOf("wss://a.example", "wss://b.example"), now = 1_000)
        store.queueOutgoing(second, setOf("wss://a.example"), now = 2_000)
        assertEquals(2, store.outgoingCount())
        val waiting = store.outgoing()
        assertEquals(listOf(first.id, second.id), waiting.map { it.first.id })
        assertEquals(setOf("wss://a.example", "wss://b.example"), waiting[0].second)
        assertTrue(waiting[0].first.isValid())
        store.removeOutgoing(first.id)
        assertEquals(1, store.outgoingCount())
        assertEquals(1, store.dropOutgoingBefore(3_000))
        assertEquals(0, store.outgoingCount())
    }

    @Test
    fun signingOutEmptiesEverything() {
        insert(event(alice, Kind.NOTE, 100, "hello"))
        store.queueOutgoing(event(alice, Kind.NOTE, 101, "later"), setOf("wss://a.example"), 1)
        store.clearAll()
        assertTrue(store.searchNotes("hello", 10).isEmpty())
        assertEquals(0, store.outgoingCount())
    }

    @Test
    fun pruningDropsOldStrangersButKeepsYoursAndBookmarks() {
        val old = (1..4).map { event(bob, Kind.NOTE, it * 100L, "bob $it") }
        val mine = event(alice, Kind.NOTE, 50, "mine")
        val saved = event(bob, Kind.NOTE, 60, "saved")
        val picture = event(bob, Kind.PICTURE, 70, "picture")
        val list = event(alice, Kind.BOOKMARKS, 500, tags = listOf(listOf("e", saved.id)))
        insert(*old.toTypedArray(), mine, saved, picture, list)
        store.prune(alice.pubkey, keep = 2)
        assertNull(store.event(old[0].id))
        assertNull(store.event(old[1].id))
        assertNull(store.event(picture.id))
        assertNotNull(store.event(old[2].id))
        assertNotNull(store.event(mine.id))
        assertNotNull(store.event(saved.id))
        assertTrue(store.searchNotes("bob", 10).none { it.id == old[0].id })
    }

    @Test
    fun pruningKeepsRecentPostsByPeopleYouFollow() {
        val now = System.currentTimeMillis() / 1000
        val carol = LocalSigner(Keys.generate())
        val dave = LocalSigner(Keys.generate())
        val followed = event(bob, Kind.NOTE, now - 10 * 86400, "followed")
        val ancient = event(bob, Kind.NOTE, now - 200 * 86400, "ancient")
        val stranger = event(dave, Kind.NOTE, now - 9 * 86400, "stranger")
        insert(followed, ancient, stranger, event(carol, Kind.NOTE, now - 5 * 86400), event(carol, Kind.NOTE, now - 86400))
        store.setFollows(listOf(bob.pubkey))
        store.prune(alice.pubkey, keep = 1)
        assertNotNull(store.event(followed.id))
        assertNull(store.event(ancient.id))
        assertNull(store.event(stranger.id))
    }

    @Test
    fun compactingGivesSpaceBackAfterACleanup() {
        val text = "x".repeat(2000)
        insert(*(1..600).map { event(bob, Kind.NOTE, it.toLong(), "$text $it") }.toTypedArray())
        assertEquals(0L, store.compactIfWasteful(minFree = Long.MAX_VALUE))
        val path = store.readableDatabase.path
        val full = java.io.File(path).length()
        store.clearAll()
        val saved = store.compactIfWasteful(minFree = 1)
        val after = java.io.File(path).length()
        assertTrue("saved $saved, file $full then $after", saved > 0 || after < full)
        val mode = store.readableDatabase.rawQuery("PRAGMA auto_vacuum", null).use { it.moveToFirst(); it.getInt(0) }
        val journal = store.readableDatabase.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) }
        println("COMPACT-PROBE auto_vacuum=$mode journal=$journal saved=$saved full=$full after=$after")
        // Nothing left to give back the second time.
        assertEquals(0L, store.compactIfWasteful(minFree = 1))
    }
}
