/*
 * Feeds: one scrolling list of posts. A feed shows what's already stored at once, then asks the
 * right relays for newer posts and keeps listening, and loads older posts as you scroll. Each kind
 * of feed (Following, Global, a profile, a hashtag and so on) decides which relays to ask and what
 * to keep.
 */
package app.struthio.nostr.data

import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.addressOf
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.threadRef
import app.struthio.nostr.core.values
import app.struthio.nostr.net.Subscription
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a feed's posts come from. */
sealed interface FeedSource {
    /** Posts by people you follow, from the relays they write to. */
    data object Following : FeedSource
    /** Popular posts, from a relay that ranks them. */
    data object Trending : FeedSource
    /** Everything on Zino's default relays. */
    data object Global : FeedSource
    /** Everything on your own read relays. */
    data object MyRelays : FeedSource
    /** One person's posts, replies and boosts. */
    data class Author(val pubkey: String) : FeedSource
    /** Posts with a hashtag. */
    data class Hashtag(val tag: String) : FeedSource
    /** A text search, on relays that support it (NIP-50). */
    data class Search(val query: String) : FeedSource
    /** Posts you've bookmarked. */
    data object Bookmarks : FeedSource
}

/** One list of notes: cache first, then live from the relays that matter for it. */
class Feed(private val core: Core, private val source: FeedSource) {
    val notes = MutableStateFlow<List<Event>>(emptyList())
    val loading = MutableStateFlow(true)

    private val byId = LinkedHashMap<String, Event>()
    private val subs = ArrayList<Subscription>()
    private var flushJob: Job? = null
    private var started = false
    private var coverage: Map<String, Set<String>> = emptyMap()
    private var moreRunning = false

    /** Notes and polls, picture and video posts, articles, and boosts of them. */
    private val kinds = Kind.POSTS + Kind.REPOST +
        // A profile also has tabs for comments and for boosts of things other than notes.
        if (source is FeedSource.Author) listOf(Kind.COMMENT, Kind.GENERIC_REPOST) else emptyList()

    /** The trusted network applies to discovery feeds only, never to replies or notifications. */
    private val filtered: Boolean
        get() = (source == FeedSource.Global || source == FeedSource.Trending) && core.trustFilterActive()

    /** Shows stored posts, then subscribes to relays. Does nothing if already started. */
    fun start() {
        if (started) return
        started = true
        core.scope.launch {
            preload()
            when (source) {
                FeedSource.Trending -> loadTrending()
                is FeedSource.Search -> loadSearch(source.query)
                FeedSource.Bookmarks -> loadBookmarks()
                else -> subscribe()
            }
            loading.value = false
        }
    }

    /** Closes the feed's relay subscriptions. */
    fun stop() {
        subs.forEach(core.pool::close)
        subs.clear()
        flushJob?.cancel()
        started = false
    }

    /** Starts the feed afresh, as pull to refresh does. */
    fun refresh() {
        stop()
        start()
    }

    /** The relays a relay-based feed reads from, which is also how its cached posts are found. */
    private fun feedRelays(): Set<String> = when (source) {
        FeedSource.MyRelays -> core.readRelays() + core.writeRelays()
        else -> Core.DEFAULT_RELAYS.toSet()
    }

    /** Stored posts for this feed from before [until], for the cache-first part of loading. */
    private fun cached(until: Long): List<Event> = when (source) {
        FeedSource.Following -> core.store.followingFeed(PAGE, until)
        is FeedSource.Author -> core.store.authorNotes(source.pubkey, PAGE, until)
        FeedSource.Global, FeedSource.MyRelays -> core.store.relayFeed(feedRelays(), PAGE, until)
        else -> emptyList()
    }

    /**
     * Fills the list from the database straight away, before any relay answers. Posts already
     * showing stay, so coming back to a feed never blanks it.
     */
    private fun preload() {
        if (source == FeedSource.Trending) {
            preloadTrending()
            return
        }
        val cached = when (source) {
            is FeedSource.Search -> core.store.searchNotes(source.query, PAGE)
            FeedSource.Bookmarks -> core.bookmarks.value.mapNotNull(core.store::event)
            else -> cached(nowSeconds() + 60)
        }
        cached.forEach { add(it) }
        flush()
    }

    /** Shows the last trending list worked out, until the new one is ready. */
    private fun preloadTrending() {
        if (notes.value.isNotEmpty()) return
        val ids = core.prefs.getString(TRENDING_KEY, null)?.split(',')?.filter(String::isNotBlank).orEmpty()
        if (ids.isEmpty()) return
        val rank = ids.withIndex().associate { it.value to it.index }
        val muted = core.muted.value
        notes.value = core.store.eventsById(ids).filter { it.pubkey !in muted }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    /** The relay filter for this feed: its kinds, plus the authors, hashtag or search it needs. */
    private fun filterFor(authors: Collection<String>?, since: Long?, until: Long? = null) = Filter(
        authors = authors,
        kinds = kinds,
        tags = (source as? FeedSource.Hashtag)?.let { mapOf("t" to listOf(it.tag.lowercase())) } ?: emptyMap(),
        since = since,
        until = until,
        // Most of an open stream is dropped by the filter, so ask for more of it.
        limit = if (filtered) PAGE * 4 else PAGE,
    )

    /**
     * Subscribes to the feed's relays from the newest post already shown, so nothing is fetched
     * twice.
     */
    private suspend fun subscribe() {
        val newest = byId.values.maxOfOrNull { it.createdAt }
        coverage = when (val s = source) {
            FeedSource.Following -> core.outbox(core.follows.value)
            is FeedSource.Author -> {
                val relays = core.outbox(listOf(s.pubkey), 4).keys
                relays.associateWith { setOf(s.pubkey) }
            }
            FeedSource.MyRelays -> (core.readRelays() + core.writeRelays()).associateWith { emptySet() }
            else -> feedRelays().associateWith { emptySet() }
        }
        val waiting = java.util.concurrent.atomic.AtomicInteger(0)
        for ((relay, authors) in coverage) {
            if (source == FeedSource.Following && authors.isEmpty()) continue
            val groups = if (authors.isEmpty()) listOf(null) else authors.chunked(Core.MAX_AUTHORS)
            for (group in groups) {
                waiting.incrementAndGet()
                subs += core.pool.subscribe(
                    setOf(relay),
                    listOf(filterFor(group, newest?.minus(3600))),
                    onDone = { _, _ -> waiting.decrementAndGet() },
                    onEvent = ::receive,
                )
            }
        }
        // "Nothing here yet" only once the relays have had their say: stop waiting when posts are
        // showing, when every relay has sent what it had, or after a while for slow ones.
        withTimeoutOrNull(FIRST_ANSWER_MS) {
            while (waiting.get() > 0 && synchronized(byId) { byId.isEmpty() }) delay(200)
        }
    }

    /** Takes a post from a relay: stores it, then shows it if it belongs in this feed. */
    private fun receive(event: Event, relay: String) {
        core.ingest(event, relay)
        if (source == FeedSource.Following && event.pubkey !in core.follows.value) return
        if (source is FeedSource.Author && event.pubkey != (source as FeedSource.Author).pubkey) return
        add(event)
        scheduleFlush()
    }

    /**
     * Adds a post to the list, unless it's muted, expired, a reply where replies aren't shown, or
     * an older copy of something newer.
     */
    private fun add(event: Event) {
        if (event.pubkey in core.muted.value) return
        if (event.isExpired()) return
        val keepsReplies = source is FeedSource.Author || source is FeedSource.Search || source == FeedSource.Bookmarks
        if (!keepsReplies && threadRef(event).root != null) return
        if (filtered && event.pubkey !in core.trustedNow()) return
        synchronized(byId) {
            // An edited article or video replaces the version already listed.
            if (Kind.isAddressable(event.kind)) {
                val address = addressOf(event)
                val older = byId.values.firstOrNull { Kind.isAddressable(it.kind) && it.id != event.id && addressOf(it) == address }
                if (older != null) {
                    if (older.createdAt >= event.createdAt) return
                    byId.remove(older.id)
                }
            }
            byId.putIfAbsent(event.id, event)
        }
    }

    /** Updates the list shortly after posts arrive, so a burst of them redraws once. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = core.scope.launch {
            delay(350)
            flush()
        }
    }

    /** Publishes the list, newest first. */
    private fun flush() {
        notes.value = synchronized(byId) { byId.values.sortedByDescending { it.createdAt } }
    }

    /** Loads older posts when you near the end: from the database first, then from relays. */
    fun loadMore() {
        if (moreRunning || source == FeedSource.Trending || source is FeedSource.Search) return
        val oldest = notes.value.lastOrNull()?.createdAt ?: return
        moreRunning = true
        core.scope.launch {
            val cached = cached(oldest)
            cached.forEach { add(it) }
            flush()
            if (cached.size < PAGE / 2) {
                for ((relay, authors) in coverage) {
                    if (source == FeedSource.Following && authors.isEmpty()) continue
                    val groups = if (authors.isEmpty()) listOf(null) else authors.chunked(Core.MAX_AUTHORS)
                    groups.forEach { group -> core.pool.query(setOf(relay), listOf(filterFor(group, null, oldest)), 5000, ::receive) }
                }
                flush()
            }
            moreRunning = false
        }
    }

    /** What the relays are reacting to right now, counted here rather than taken from a ranking service. */
    private suspend fun loadTrending() {
        val score = HashMap<String, Int>()
        val trusted = if (filtered) core.trustedNow() else null
        val since = nowSeconds() - 86400
        core.pool.query(
            Core.DEFAULT_RELAYS.toSet(),
            listOf(Filter(kinds = listOf(Kind.REACTION, Kind.REPOST), since = since, limit = 800)),
            8000,
        ) { e, _ ->
            if (trusted != null && e.pubkey !in trusted) return@query
            e.tags.values("e").lastOrNull()?.let { id ->
                synchronized(score) { score[id] = (score[id] ?: 0) + if (e.kind == Kind.REPOST) 3 else 1 }
            }
        }
        val top = synchronized(score) { score.entries.sortedByDescending { it.value }.take(50).map { it.key } }
        if (top.isEmpty()) return
        val found = ArrayList<Event>()
        core.pool.query(Core.DEFAULT_RELAYS.toSet(), listOf(Filter(ids = top, kinds = listOf(Kind.NOTE)))) { e, relay ->
            core.ingest(e, relay)
            synchronized(found) { found += e }
        }
        val rank = top.withIndex().associate { it.value to it.index }
        val muted = core.muted.value
        val list = synchronized(found) {
            found.distinctBy { it.id }.filter { it.pubkey !in muted && (trusted == null || it.pubkey in trusted) }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        }
        notes.value = list
        // Kept for next time, so Trending opens with something while the new list is worked out.
        core.prefs.edit().putString(TRENDING_KEY, list.joinToString(",") { it.id }).apply()
    }

    /** Fetches any bookmarked posts not yet stored, then shows them all. */
    private suspend fun loadBookmarks() {
        val missing = core.bookmarks.value.filter { core.store.event(it) == null }
        if (missing.isNotEmpty()) {
            core.pool.query(core.readRelays() + Core.DEFAULT_RELAYS, listOf(Filter(ids = missing)), 6000, ::receive)
        }
        flush()
    }

    /** NIP-50 on search relays, added to whatever the local cache already matched. */
    private suspend fun loadSearch(query: String) {
        core.pool.query(
            core.searchRelays(),
            listOf(Filter(kinds = listOf(Kind.NOTE), search = query, limit = PAGE)),
            8000,
            ::receive,
        )
        flush()
    }

    private companion object {
        const val PAGE = 50
        const val FIRST_ANSWER_MS = 10_000L
        const val TRENDING_KEY = "trending_ids"
    }
}
