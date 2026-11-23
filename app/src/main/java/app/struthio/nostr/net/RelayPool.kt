/*
 * Talking to relays (NIP-01). One connection per relay, opened only while something needs it and
 * the app is in front. Subscriptions are spread over the right relays, events are checked before
 * use, posts are sent and their answers collected, sign-in (NIP-42) is handled, and failing relays
 * rest for a while before being tried again. Relays that keep turning us away rest longer each
 * time, and that survives a restart; relays that refuse our posts outright aren't sent more for a
 * while.
 */
package app.struthio.nostr.net

import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Negentropy
import app.struthio.nostr.core.Signer
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.quoteJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A request for events from some relays. Events arrive through [onEvent] and each relay reports
 * through [onDone] when it has sent what it had.
 */
class Subscription internal constructor(
    val id: String,
    val relays: Set<String>,
    val filters: List<Filter>,
    internal val onEvent: (Event, String) -> Unit,
    /** Called per relay: true for end of stored events, false when the relay failed or refused. */
    internal val onDone: (String, Boolean) -> Unit,
) {
    internal val request: String by lazy { requestFor(filters) }

    /**
     * The same request, asking only for events from [since] on: what a relay that already sent
     * everything older is asked after a reconnect, rather than sending it all again. A lookup by id
     * is left alone, as it isn't about time.
     */
    internal fun requestSince(since: Long): String = requestFor(
        filters.map { f ->
            // Gift wraps carry a made-up time up to two days back (NIP-59), so one that came in while
            // we were away can look older than the moment we left. Those are asked for further back.
            val from = if (f.kinds?.contains(Kind.GIFT_WRAP) == true) since - GIFT_WRAP_BACKDATE_S else since
            if (f.ids != null) f else Filter(f.ids, f.authors, f.kinds, f.tags, maxOf(f.since ?: 0, from), f.until, f.limit, f.search)
        },
    )

    private companion object {
        const val GIFT_WRAP_BACKDATE_S = 2 * 86400L
    }

    /** The REQ message for these filters. */
    private fun requestFor(list: List<Filter>) = buildString {
        append("[\"REQ\",").append(quoteJson(id))
        list.forEach { append(',').append(it.toJson()) }
        append(']')
    }
}

/** A relay's answer to a post: accepted or not, and its message. */
data class PublishResult(val ok: Boolean, val message: String, val retryable: Boolean = false) {
    companion object {
        /** Answers that mean "not now" rather than "no": trying again later may work (NIP-01). */
        fun meansLater(message: String) =
            message.startsWith("rate-limited:") || message.startsWith("error:") || message == RESTING
        const val RESTING = "relay is unavailable for now"
    }
}

/** All relay connections. Sockets exist only while something needs them and the app is in front. */
class RelayPool(
    private val scope: CoroutineScope,
    /** Data use and reachability per relay, shared by every pool so background checks count too. */
    private val stats: RelayStats? = null,
    private val signer: () -> Signer?,
) {
    /** The last 30 days of Zino's use of [url]. */
    fun usage(url: String): RelayStats.Summary? = stats?.summary(url)

    private val relays = ConcurrentHashMap<String, Relay>()
    private val nextId = AtomicInteger()
    private val verified = object : LinkedHashMap<String, Boolean>(4096, 0.75f, true) {
        /** Keeps the memory of seen events from growing without end. */
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 8000
    }

    private val problems = HashMap<String, MutableList<String>>()
    private var pauseJob: Job? = null
    private val visible = AtomicInteger()

    /** Nothing connects until something is on screen, so a background wake-up opens no sockets. */
    @Volatile private var foreground = false

    /** Whether sockets may be open now, with Zino in front. */
    val inForeground: Boolean get() = foreground

    /** Android cuts a backgrounded app's network within seconds, so nothing is retried until it's back. */
    @Volatile private var leaving = false

    private val onlineState = MutableStateFlow(true)

    /** Whether the phone has a network, as Android reports it. */
    val online: StateFlow<Boolean> = onlineState

    /** The network Android routes through by default; a different one means Wi-Fi and mobile data swapped. */
    private var network: Any? = null

    /**
     * Until when a network that just came up is still settling: Android reports it a moment before
     * names can be looked up, so failures then say nothing about the relays.
     */
    @Volatile private var settleUntil = 0L

    val isVisible: Boolean get() = visible.get() > 0

    /** True if any relay connection is open right now, which means the phone is online. */
    val anyLive: Boolean get() = relays.values.any { it.live }

    /** The connection for a relay, made the first time it's needed. */
    private fun relay(url: String) = relays.getOrPut(url) { Relay(url) }

    /** Asks these relays for events matching the filters. */
    fun subscribe(
        urls: Set<String>,
        filters: List<Filter>,
        onDone: (String, Boolean) -> Unit = { _, _ -> },
        onEvent: (Event, String) -> Unit,
    ): Subscription {
        val sub = Subscription("s${nextId.incrementAndGet()}", urls, filters, onEvent, onDone)
        urls.forEach { relay(it).add(sub) }
        return sub
    }

    /**
     * Finds the events matching [filter] that a relay has and [have] lacks, by comparing lists
     * instead of downloading (NIP-77). [have] holds time and id pairs. Null when the relay doesn't
     * do this, fails, or takes longer than [timeoutMs]: then ask with a plain REQ instead.
     */
    suspend fun missing(url: String, filter: Filter, have: Collection<Pair<Long, String>>, timeoutMs: Long = 8_000): Set<String>? {
        val negentropy = Negentropy(have)
        val id = "neg" + negCounter.incrementAndGet()
        val relay = relay(url)
        val done = CompletableDeferred<Set<String>?>()
        relay.negOpen(id, "[\"NEG-OPEN\",${quoteJson(id)},${filter.toJson()},\"${negentropy.initiate()}\"]") { payload ->
            if (payload == null) {
                done.complete(null)
                return@negOpen
            }
            val next = try {
                negentropy.reconcile(payload)
            } catch (e: IllegalArgumentException) {
                note(url, "negentropy: ${e.message}")
                done.complete(null)
                return@negOpen
            }
            if (next == null) done.complete(negentropy.need.toSet()) else relay.negSend("[\"NEG-MSG\",${quoteJson(id)},\"$next\"]")
        }
        return try {
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            relay.negClose(id)
        }
    }

    private val negCounter = java.util.concurrent.atomic.AtomicLong()

    /** Ends a subscription on all its relays. */
    fun close(sub: Subscription) {
        sub.relays.forEach { relays[it]?.remove(sub) }
    }

    /** Fetches stored events and returns the relays that really answered, not those that merely failed. */
    suspend fun query(
        urls: Set<String>,
        filters: List<Filter>,
        timeoutMs: Long = 6_000,
        onEvent: (Event, String) -> Unit,
    ): Set<String> {
        if (urls.isEmpty()) return emptySet()
        val remaining = HashSet(urls)
        val answered = HashSet<String>()
        val done = CompletableDeferred<Unit>()
        val sub = subscribe(urls, filters, onDone = { url, ok ->
            val finished = synchronized(remaining) {
                if (remaining.remove(url) && ok) answered += url
                remaining.isEmpty()
            }
            if (finished) done.complete(Unit)
        }, onEvent = onEvent)
        withTimeoutOrNull(timeoutMs) { done.await() }
        close(sub)
        return synchronized(remaining) { answered.toSet() }
    }

    /**
     * How long a relay takes to answer a request, in ms, on a connection of its own: the shared
     * one may be busy with Zino's own requests, queued behind them, or resting after failures,
     * none of which says how the relay itself is doing. Any answer counts, a refusal too. Null when
     * it can't be reached or doesn't answer within [timeoutMs].
     */
    suspend fun probe(url: String, filter: Filter, timeoutMs: Long = 8_000): Long? {
        val answer = CompletableDeferred<Long?>()
        var sentAt = 0L
        val id = "p${nextId.incrementAndGet()}"
        lateinit var ws: WebSocket
        ws = WebSocket(
            url,
            onOpen = {
                stats?.attempt(url, true)
                // It let us in after all, so a long rest from earlier failures no longer applies.
                stats?.reached(url)
                relays[url]?.forgive()
                sentAt = System.currentTimeMillis()
                if (!ws.send("[\"REQ\",${quoteJson(id)},${filter.toJson()}]")) answer.complete(null)
            },
            onText = { text ->
                val type = runCatching { JSONArray(text).optString(0) }.getOrNull()
                // A sign-in challenge can come before the request is even read; it isn't an answer.
                if (type != null && type != "AUTH" && sentAt > 0) answer.complete(System.currentTimeMillis() - sentAt)
            },
            onClosed = { error ->
                if (sentAt == 0L && error != null) stats?.attempt(url, false)
                answer.complete(null)
            },
            onTraffic = { down, up -> stats?.traffic(url, down, up) },
        )
        ws.connect()
        return try {
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            ws.close()
        }
    }

    /** NIP-45 counts where relays offer them; the highest answer wins because relays see partial data. */
    suspend fun count(urls: Set<String>, filter: Filter, timeoutMs: Long = 5_000): Long? {
        var best: Long? = null
        val remaining = HashSet(urls)
        val done = CompletableDeferred<Unit>()
        val id = "c${nextId.incrementAndGet()}"
        val request = "[\"COUNT\",${quoteJson(id)},${filter.toJson()}]"
        urls.forEach { url ->
            relay(url).count(id, request) { value ->
                val finished = synchronized(remaining) {
                    if (value != null) best = maxOf(best ?: 0, value)
                    remaining.remove(url)
                    remaining.isEmpty()
                }
                if (finished) done.complete(Unit)
            }
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        urls.forEach { relays[it]?.endCount(id) }
        return synchronized(remaining) { best }
    }

    /** Sends an event to these relays and waits for each one's answer, up to [timeoutMs]. */
    suspend fun publish(event: Event, urls: Set<String>, timeoutMs: Long = 8_000): Map<String, PublishResult> {
        if (urls.isEmpty()) return emptyMap()
        val pending = urls.associateWith { relay(it).publish(event) }
        withTimeoutOrNull(timeoutMs) { pending.values.awaitAll() }
        return pending.mapValues { (url, deferred) ->
            relays[url]?.forget(event.id)
            if (deferred.isCompleted) deferred.getCompleted() else PublishResult(false, "timed out")
        }
    }

    /** Answers as soon as one relay accepts. The others keep going here until they finish or time out. */
    suspend fun publishFirst(event: Event, urls: Set<String>, timeoutMs: Long = 8_000): PublishResult {
        if (urls.isEmpty()) return PublishResult(false, "")
        val pending = urls.associateWith { relay(it).publish(event) }
        val answer = CompletableDeferred<PublishResult>()
        scope.launch {
            withTimeoutOrNull(timeoutMs) {
                pending.values.map { sent ->
                    launch { if (sent.await().ok) answer.complete(PublishResult(true, "")) }
                }.joinAll()
            }
            urls.forEach { relays[it]?.forget(event.id) }
            if (!answer.isCompleted) {
                // What each relay did, so a failed post can be explained from the logs.
                val each = pending.map { (url, sent) ->
                    val what = if (sent.isCompleted) sent.getCompleted().message.ifBlank { "refused" } else "no answer"
                    "${url.removePrefix("wss://")}: $what"
                }
                AppLog.add("kind ${event.kind} not taken: ${each.joinToString("; ")}")
                val silent = pending.values.any { !it.isCompleted }
                // Worth another try later when some relay never answered, or every answer was "not now".
                val later = silent || pending.values.all { it.getCompleted().let { r -> r.retryable || PublishResult.meansLater(r.message) } }
                answer.complete(
                    PublishResult(
                        false,
                        if (silent) "No relay answered in time. Check your connection and try again." else
                            pending.values.first().getCompleted().message.ifBlank { "No relay accepted this." },
                        retryable = later,
                    ),
                )
            }
        }
        return answer.await()
    }

    /**
     * Leaving the screen closes the sockets after a short grace period, because a trip to a signer
     * app or the photo picker also stops the activity and must not drop what is loading.
     */
    fun activityStopped() {
        if (visible.decrementAndGet() > 0) return
        AppLog.add("app left")
        stats?.flush()
        leaving = true
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(PAUSE_GRACE_MS)
            if (visible.get() > 0) return@launch
            foreground = false
            relays.values.forEach { it.disconnect() }
        }
    }

    /**
     * Counted, because an old activity's stop can arrive after the next one has started. A pool
     * used for background checks passes [log] false, so the log doesn't say the app was opened.
     */
    fun activityStarted(log: Boolean = true) {
        if (visible.incrementAndGet() == 1 && log) AppLog.add("app opened")
        leaving = false
        pauseJob?.cancel()
        foreground = true
        relays.values.forEach { it.wake() }
    }

    /**
     * Android's default network came up, or changed, from Wi-Fi to mobile data say. Sockets opened on
     * the old one can hang until they time out, so they're closed and every relay reconnects at once.
     */
    fun networkAvailable(id: Any) {
        val previous = synchronized(this) { network.also { network = id } }
        val wasOnline = onlineState.value
        onlineState.value = true
        if (previous == null && wasOnline) return // the first report, at start-up
        if (previous == id && wasOnline) return
        val switched = previous != null && previous != id
        settleUntil = System.currentTimeMillis() + SETTLE_MS
        AppLog.add(if (!wasOnline) "network back" else "network changed")
        relays.values.forEach { it.renew(dropOld = switched) }
    }

    /** The default network went away. Only the current one counts: after a swap the old one is lost too. */
    fun networkLost(id: Any) {
        synchronized(this) { if (network != id) return }
        if (onlineState.value) AppLog.add("network lost")
        onlineState.value = false
    }

    /** What a relay refused or what we refused from it, for the account check. */
    fun problemsOf(url: String): List<String> = synchronized(problems) { problems[url]?.toList().orEmpty() }

    /** Disconnects every relay and saves the data use figures. */
    fun shutdown() {
        foreground = false
        relays.values.forEach { it.disconnect() }
        stats?.flush()
    }

    /** Forgets the problems noted for each relay. */
    fun clearProblems() {
        synchronized(problems) { problems.clear() }
    }

    /** True if none of these relays sent lists that had to be dropped as invalid. */
    fun noDroppedLists(urls: Set<String>): Boolean = urls.none { url -> problemsOf(url).any { it.startsWith("dropped") } }

    /** Whether this relay has accepted our sign-in on the current connection. */
    fun signedIn(url: String): Boolean = relays[url]?.signedIn == true

    /** Whether this relay turned down a correct sign-in, as a misconfigured relay does. */
    fun signInRefused(url: String): Boolean = relays[url]?.signInRefused == true

    /** Notes a problem with a relay, for the log and the relay screen. */
    private fun note(url: String, problem: String) {
        AppLog.add("$url $problem")
        synchronized(problems) {
            val list = problems.getOrPut(url) { ArrayList() }
            if (problem !in list && list.size < 6) list += problem
        }
    }

    /**
     * Checks an event from a relay: its id and signature must match. Bad ones are dropped and
     * noted.
     */
    private fun accept(event: Event, url: String): Boolean {
        if (!event.hasValidId()) {
            note(url, "dropped kind ${event.kind}: id does not match its content")
            return false
        }
        synchronized(verified) { if (verified.containsKey(event.id)) return true }
        if (!event.hasValidSignature()) {
            note(url, "dropped kind ${event.kind}: bad signature")
            return false
        }
        synchronized(verified) { verified[event.id] = true }
        return true
    }

    /** A post waiting for a relay's answer. */
    private class Pending(val event: Event, val result: CompletableDeferred<PublishResult>) {
        var retriedAuth = false
    }

    private inner class Relay(val url: String) {
        private val lock = Any()
        private val subs = LinkedHashMap<String, Subscription>()
        private val pending = LinkedHashMap<String, Pending>()
        private val counts = LinkedHashMap<String, Pair<String, (Long?) -> Unit>>()

        /** Negentropy runs (NIP-77): the NEG-OPEN to send, and who hears each NEG-MSG, or null on failure. */
        private val negs = LinkedHashMap<String, Pair<String, (String?) -> Unit>>()
        private var socket: WebSocket? = null
        private var open = false
        private var failures = 0
        private var openedAt = 0L
        private var failedUntil = 0L
        private var limitedUntil = 0L
        private val sent = HashSet<String>()
        private var pacer: Job? = null

        /** Events held until the open REQs are out, so an instant answer to them isn't missed. */
        private val afterReqs = ArrayList<String>()

        /**
         * Per subscription, up to when this relay is known to have sent everything (seconds): its
         * end of stored events, then the moment the connection dropped while events streamed live.
         */
        private val caughtUp = HashMap<String, Long>()

        /** Subscriptions that reached their end of stored events on the current connection. */
        private val caughtUpHere = HashSet<String>()

        /** Whether REQs are still going out; guarded by the lock, unlike the pacer job's own state. */
        private var pumping = false

        /** Bumped when the connection closes, so a pacer from the old connection stops quietly. */
        private var pumpRound = 0
        private var challenge: String? = null
        private var authEventId: String? = null

        @Volatile
        var signedIn = false

        /** Open right now; read without the lock, to tell a dead relay from a dead network. */
        @Volatile
        var live = false

        /** Kept across reconnects, so a relay that can't take sign-ins isn't asked to on every one. */
        @Volatile
        var signInRefused = false
        private val waitingForAuth = LinkedHashSet<String>()
        private var idleJob: Job? = null

        // Set when this relay said it doesn't support COUNT (NIP-45).
        @Volatile private var noCount = false

        // Set when this relay said it can't compare lists (NIP-77); remembered across restarts.
        @Volatile private var noNegentropy = stats?.noNegentropy(url) == true

        // Until when this relay won't take our posts, and what it said: paid and read-only relays.
        @Volatile private var writesRefusedUntil = 0L
        @Volatile private var writeRefusal = ""

        // No new dial before this, so a burst of requests after a failure doesn't redial at once.
        private var nextDialAt = 0L

        // Set when this relay said it takes no requests at all, as write-only relays do.
        @Volatile private var readsRefused = false
        private var retryJob: Job? = null

        init {
            // A long rest from an earlier run still holds, so a dead relay isn't dialled at every start.
            val rest = stats?.restingUntil(url) ?: 0L
            if (rest > System.currentTimeMillis()) {
                failedUntil = rest
                limitedUntil = rest
            }
        }

        /**
         * Adds a subscription and sends it once there's room. A resting relay answers straight away
         * that it's done.
         */
        fun add(sub: Subscription) {
            val cooling = synchronized(lock) {
                if (cooling() || readsRefused) return@synchronized true
                subs[sub.id] = sub
                idleJob?.cancel()
                ensureConnected()
                pump()
                false
            }
            if (cooling) scope.launch { sub.onDone(url, false) }
        }

        /** True while this relay is resting after failures or a rate limit. */
        private fun cooling(): Boolean = System.currentTimeMillis() < maxOf(failedUntil, limitedUntil)

        /** REQs go out one at a time and only so many stay open, because relays limit both. */
        private fun pump() {
            val round = synchronized(lock) {
                if (!open || pumping) return
                pumping = true
                pumpRound
            }
            pacer = scope.launch {
                while (true) {
                    // Stopping and handing over the held events happen under one lock, so an event
                    // arriving as the last REQ goes out is either held and sent here, or sent directly.
                    var held: List<String> = emptyList()
                    val next = synchronized(lock) {
                        if (round != pumpRound) return@launch
                        val n = if (!open || sent.size >= MAX_OPEN_SUBS) null
                        else subs.values.firstOrNull { it.id !in sent }?.also { sent += it.id }
                        if (n == null) {
                            if (open) held = afterReqs.toList()
                            afterReqs.clear()
                            pumping = false
                        }
                        // After a reconnect, only what's newer than what this relay already sent.
                        n?.let { sub -> caughtUp[sub.id]?.let { sub.requestSince(it - CATCH_UP_MARGIN_S) } ?: sub.request }
                    }
                    if (next == null) {
                        held.forEach { socket?.send(it) }
                        break
                    }
                    socket?.send(next)
                    delay(REQ_SPACING_MS)
                }
            }
        }

        /** Ends a subscription, telling the relay if it was sent. */
        fun remove(sub: Subscription) {
            val wasOpen = synchronized(lock) {
                subs.remove(sub.id)
                val wasSent = sent.remove(sub.id)
                pump()
                scheduleIdleClose()
                wasSent && open
            }
            if (wasOpen) write("[\"CLOSE\",${quoteJson(sub.id)}]")
        }

        /** Asks this relay how many events match (NIP-45). */
        fun count(id: String, request: String, onResult: (Long?) -> Unit) {
            // A relay that said it doesn't know COUNT isn't asked again this session.
            if (noCount) {
                onResult(null)
                return
            }
            val send = synchronized(lock) {
                counts[id] = request to onResult
                idleJob?.cancel()
                ensureConnected()
                open
            }
            if (send) write(request)
        }

        /** Starts a negentropy run, sent once connected; a resting relay fails it at once. */
        fun negOpen(id: String, request: String, onMessage: (String?) -> Unit) {
            val send = synchronized(lock) {
                // A relay that said it can't compare lists answers with a plain REQ instead, unasked.
                if (cooling() || noNegentropy) {
                    null
                } else {
                    negs[id] = request to onMessage
                    idleJob?.cancel()
                    ensureConnected()
                    open
                }
            }
            when (send) {
                null -> onMessage(null)
                true -> write(request)
                false -> Unit
            }
        }

        /** Sends the next message of a negentropy run. */
        fun negSend(text: String) = write(text)

        /** Ends a negentropy run. */
        fun negClose(id: String) {
            val wasOpen = synchronized(lock) {
                val had = negs.remove(id) != null
                scheduleIdleClose()
                had && open
            }
            if (wasOpen) write("[\"NEG-CLOSE\",${quoteJson(id)}]")
        }

        /** Stops waiting for a count. */
        fun endCount(id: String) {
            synchronized(lock) {
                counts.remove(id)
                scheduleIdleClose()
            }
        }

        /** Sends an event to this relay; the result says if it was accepted. */
        fun publish(event: Event): CompletableDeferred<PublishResult> {
            val deferred = CompletableDeferred<PublishResult>()
            val send = synchronized(lock) {
                if (cooling()) {
                    deferred.complete(PublishResult(false, PublishResult.RESTING))
                    return deferred
                }
                if (System.currentTimeMillis() < writesRefusedUntil) {
                    deferred.complete(PublishResult(false, writeRefusal))
                    return deferred
                }
                pending[event.id] = Pending(event, deferred)
                idleJob?.cancel()
                ensureConnected()
                if (open && pumping) {
                    afterReqs += "[\"EVENT\",${event.toJson()}]"
                    false
                } else {
                    open
                }
            }
            if (send) write("[\"EVENT\",${event.toJson()}]")
            return deferred
        }

        /** Stops waiting for an answer to this post. */
        fun forget(id: String) {
            synchronized(lock) {
                pending.remove(id)
                scheduleIdleClose()
            }
        }

        /** Sends a message to the relay. */
        private fun write(text: String) {
            scope.launch { socket?.send(text) }
        }

        /** Set while a socket from an old network is being replaced, so its closing isn't a failure. */
        private var renewing = false

        /**
         * Opens the connection if something needs it, the app is in front and there's a network.
         */
        private fun ensureConnected() {
            if (socket != null || !foreground || !onlineState.value) return
            // A paused relay, after failures or a refusal, isn't dialled early because a feed wants it.
            val wait = maxOf(failedUntil, limitedUntil, nextDialAt) - System.currentTimeMillis()
            if (wait > 50) {
                if (retryJob?.isActive != true) retryLater(wait)
                return
            }
            val ws = WebSocket(url, onOpen = ::opened, onText = ::message, onClosed = ::closed, onTraffic = { down, up -> stats?.traffic(url, down, up) })
            socket = ws
            ws.connect()
        }

        /** Closes the connection after a quiet spell when nothing needs it. */
        private fun scheduleIdleClose() {
            if (subs.isNotEmpty() || pending.isNotEmpty() || counts.isNotEmpty() || negs.isNotEmpty()) return
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(IDLE_CLOSE_MS)
                val ws = synchronized(lock) {
                    if (subs.isEmpty() && pending.isEmpty() && counts.isEmpty() && negs.isEmpty()) socket else null
                }
                ws?.close()
            }
        }

        /** The connection is open: resend subscriptions and waiting posts. */
        private fun opened() {
            AppLog.add("$url connected")
            stats?.attempt(url, true)
            stats?.reached(url)
            synchronized(problems) { problems[url]?.removeAll { it.startsWith("connection:") } }
            val requests = synchronized(lock) {
                open = true
                live = true
                openedAt = System.currentTimeMillis()
                sent.clear()
                afterReqs.clear()
                // Subscriptions go out before queued events: a wallet answers at once and its
                // answers aren't stored, so a request sent ahead of the listener loses its answer.
                afterReqs += pending.values.map { "[\"EVENT\",${it.event.toJson()}]" }
                pump()
                // A run cut off by a reconnect starts over; each answer stands on its own, so nothing is lost.
                counts.values.map { it.first } + negs.values.map { it.first }
            }
            requests.forEach { socket?.send(it) }
        }

        /** Tries again after [delayMs], if anything still wants this relay. */
        private fun retryLater(delayMs: Long) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(delayMs)
                synchronized(lock) {
                    retryJob = null
                    if (subs.isNotEmpty() || pending.isNotEmpty()) ensureConnected()
                }
            }
        }

        /**
         * The connection closed: decide whether the relay should rest, then retry if it's still
         * needed.
         */
        private fun closed(error: Throwable?) {
            // Without a network every relay fails at once: that says nothing about the relays. Nor
            // does a failure while a network that just came up is still settling.
            val settling = System.currentTimeMillis() < settleUntil
            val offline = !onlineState.value || settling
            val replaced = synchronized(lock) { renewing.also { renewing = false } }
            if (!leaving && !offline && !replaced) error?.let { note(url, "connection: ${it.message ?: it.javaClass.simpleName}") }
            signedIn = false
            val failed: List<Pending>
            val current: List<Subscription>
            val retry: Boolean
            val waiting: List<(Long?) -> Unit>
            val syncing: List<(String?) -> Unit>
            synchronized(lock) {
                waiting = counts.values.map { it.second }
                counts.clear()
                syncing = negs.values.map { it.second }
                negs.clear()
                val now = System.currentTimeMillis()
                // Only a connection that lasted counts as healthy. A relay that accepts and then
                // drops us must back off, or every subscription gets resent every few seconds.
                val lasted = open && now - openedAt > STABLE_MS
                // A try that never got through. Being offline, leaving, or our own closing isn't the relay's doing.
                if (!open && error != null && !leaving && !offline && !replaced) stats?.attempt(url, false)
                // Everything up to now came in live, for subscriptions that had caught up.
                caughtUp.keys.retainAll(subs.keys)
                if (open) caughtUpHere.forEach { if (it in subs) caughtUp[it] = now / 1000 }
                caughtUpHere.clear()
                open = false
                live = false
                socket = null
                sent.clear()
                afterReqs.clear()
                pumping = false
                pumpRound++
                challenge = null
                authEventId = null
                waitingForAuth.clear()
                current = subs.values.toList()
                if (offline || replaced) {
                    // Kept: sent again once connected, if their senders are still waiting.
                    failed = emptyList()
                } else {
                    failed = pending.values.toList()
                    pending.clear()
                }
                if (lasted || offline || replaced) failures = 0 else failures++
                val wait = minOf(MAX_BACKOFF_S, 2L shl minOf(failures, 8)) * 1000 + (0L until 1000L).random()
                if (!lasted && failures >= 2) failedUntil = now + wait
                // A try that failed before connecting isn't repeated straight away by the next request.
                if (!open && error != null && !offline && !replaced) nextDialAt = now + MIN_REDIAL_MS
                val message = error?.message.orEmpty()
                // An HTTP answer, even an error, means the relay's server was reached: a new network
                // won't help, so its pause goes where a network change leaves it alone.
                val serverAnswered = message.startsWith("handshake refused: HTTP/")
                if (serverAnswered && !lasted && failures >= 2) limitedUntil = maxOf(limitedUntil, failedUntil)
                // A relay turning us away, as members-only relays do, won't change its mind soon. Nor will
                // an address that answers as a web page or redirects (200, 301): it isn't a relay at all.
                // These rests get longer each time and are kept across restarts.
                val notARelay = message.startsWith("handshake refused: HTTP/1.1 2") || message.startsWith("handshake refused: HTTP/1.1 3")
                if (notARelay || (message.startsWith("handshake refused: HTTP/1.1 4") && " 429" !in message)) {
                    val until = stats?.restLonger(url, REFUSED_MS) ?: (now + REFUSED_MS)
                    failedUntil = until
                    limitedUntil = until
                }
                // A name that doesn't exist, while other relays are reachable, is a relay that's gone:
                // it won't come back on another network either. Telling the two apart needs another
                // relay open, so a network whose name lookups fail doesn't pause every relay.
                if (!lasted && failures >= 2 && error is java.net.UnknownHostException && relays.values.any { it !== this && it.live }) {
                    val until = stats?.restLonger(url, REFUSED_MS) ?: (now + REFUSED_MS)
                    failedUntil = until
                    limitedUntil = until
                } else if (!lasted && failures >= DEAD_AFTER && serverAnswered) {
                    // Server errors (503, 530) try after try: the relay is down, whatever the network.
                    val until = stats?.restLonger(url, REFUSED_MS) ?: (now + REFUSED_MS)
                    failedUntil = until
                    limitedUntil = until
                    failures = 0
                } else if (!lasted && failures >= DEAD_AFTER) {
                    // Unreachable try after try: rest it longer, though a new network still clears this.
                    failedUntil = now + REFUSED_MS
                }
                retry = current.isNotEmpty() && foreground && !leaving && !offline
                if (replaced && foreground && (subs.isNotEmpty() || pending.isNotEmpty())) {
                    retryJob?.cancel()
                    ensureConnected()
                } else if (settling && foreground && !leaving && (subs.isNotEmpty() || pending.isNotEmpty())) {
                    retryLater(settleUntil - now + 500)
                } else if (retry) {
                    retryLater(maxOf(wait, limitedUntil - now))
                }
            }
            // A dropped connection is "not now": the post can go once the relay is back.
            failed.forEach { it.result.complete(PublishResult(false, error?.message ?: "connection closed", retryable = true)) }
            if (!offline && !replaced) current.forEach { it.onDone(url, false) }
            waiting.forEach { it(null) }
            syncing.forEach { it(null) }
        }

        /** Closes the connection and stops any retry. */
        fun disconnect() {
            val ws = synchronized(lock) {
                retryJob?.cancel()
                socket
            }
            ws?.close()
        }

        /** After a network change: forget past failures, and replace a socket from the old network. */
        fun renew(dropOld: Boolean) {
            val old = synchronized(lock) {
                // A new network says nothing about a relay that refused us, so that pause stays.
                failures = 0
                failedUntil = limitedUntil
                retryJob?.cancel()
                val current = socket
                if (current != null && dropOld) {
                    renewing = true
                    current
                } else {
                    if (subs.isNotEmpty() || pending.isNotEmpty()) ensureConnected()
                    null
                }
            }
            old?.close()
        }

        /**
         * True if a refused post means this relay won't take posts from us at all for now (a paid
         * or read-only relay), rather than objecting to this one event (NIP-01 prefixes).
         */
        private fun refusesWrites(message: String): Boolean {
            val m = message.lowercase()
            if (m.startsWith("restricted:")) return true
            return m.startsWith("blocked:") && listOf("pay", "disabled", "whitelist", "allowlist", "member", "not allowed").any { it in m }
        }

        /** Ends any rest: the relay was just reached on a connection of its own. */
        fun forgive() {
            synchronized(lock) {
                failures = 0
                failedUntil = 0
                nextDialAt = 0
                // A rate limit is the relay asking for quiet, which connecting says nothing about.
                if (limitedUntil > System.currentTimeMillis() + RATE_LIMIT_PAUSE_MS) limitedUntil = 0
            }
        }

        /** Reconnects after a network change if anything needs this relay. */
        fun wake() {
            // Pauses stay: a network change, which does say something, is reported on its own.
            synchronized(lock) { if (subs.isNotEmpty()) ensureConnected() }
        }

        /**
         * Handles one message from the relay: events, end of stored events, answers, closed
         * requests, notices, sign-in challenges and counts.
         */
        private fun message(text: String) {
            val msg = try {
                JSONArray(text)
            } catch (_: Exception) {
                return
            }
            when (msg.optString(0)) {
                "EVENT" -> {
                    val sub = synchronized(lock) { subs[msg.optString(1)] } ?: return
                    val raw = msg.optJSONObject(2) ?: return
                    val event = Event.parse(raw)
                    if (event == null) {
                        note(url, "dropped kind ${raw.optInt("kind", -1)}: could not be parsed")
                        return
                    }
                    if (accept(event, url)) sub.onEvent(event, url)
                }
                "NEG-MSG" -> synchronized(lock) { negs[msg.optString(1)] }?.second?.invoke(msg.optString(2))
                "NOTICE" -> {
                    val text = msg.optString(1)
                    note(url, "notice: ${text.take(200)}")
                    // Relays without NIP-77 answer NEG-OPEN with a notice about an unknown command,
                    // not NEG-ERR. Give up on those runs now rather than waiting out the timeout.
                    if (text.contains("NEG-", ignoreCase = true) || text.contains("negentropy", ignoreCase = true) ||
                        text.contains("unknown cmd", ignoreCase = true)
                    ) {
                        // "unknown cmd" alone could be about something else, so only a named refusal is kept.
                        if (text.contains("NEG-", ignoreCase = true) || text.contains("negentropy", ignoreCase = true)) {
                            if (!noNegentropy) stats?.refuseNegentropy(url)
                            noNegentropy = true
                        }
                        val failed = synchronized(lock) { negs.values.map { it.second }.also { negs.clear() } }
                        failed.forEach { it(null) }
                    }
                    // The same for counts (NIP-45): "Unknown message type: COUNT" and the like.
                    if (text.contains("COUNT")) {
                        noCount = true
                        val failed = synchronized(lock) { counts.values.map { it.second }.also { counts.clear() } }
                        failed.forEach { it(null) }
                    }
                }
                "NEG-ERR" -> {
                    note(url, "negentropy: ${msg.optString(2)}")
                    synchronized(lock) { negs.remove(msg.optString(1)) }?.second?.invoke(null)
                }
                "COUNT" -> {
                    val item = synchronized(lock) { counts.remove(msg.optString(1)) } ?: return
                    item.second(msg.optJSONObject(2)?.optLong("count"))
                }
                "EOSE" -> synchronized(lock) {
                    subs[msg.optString(1)]?.also {
                        caughtUp[it.id] = maxOf(caughtUp[it.id] ?: 0, System.currentTimeMillis() / 1000)
                        caughtUpHere += it.id
                    }
                }?.onDone?.invoke(url, true)
                "OK" -> ok(msg.optString(1), msg.optBoolean(2), msg.optString(3))
                "CLOSED" -> {
                    val id = msg.optString(1)
                    val reason = msg.optString(2)
                    synchronized(lock) { counts.remove(id) }?.let {
                        it.second(null)
                        return
                    }
                    synchronized(lock) { negs.remove(id) }?.let {
                        it.second(null)
                        return
                    }
                    val sub = synchronized(lock) { subs[id] } ?: return
                    if ("auth-required" in reason && signer() != null && !signInRefused) { // Damus puts "ERROR: " before it.
                        AppLog.add("$url asked for sign-in: $reason")
                        synchronized(lock) { waitingForAuth += id }
                        authenticate()
                    } else {
                        note(url, "closed: $reason")
                        // A refused request is not resent on the next reconnect.
                        synchronized(lock) {
                            subs.remove(id)
                            sent.remove(id)
                            if (reason.startsWith("rate-limited")) limitedUntil = System.currentTimeMillis() + RATE_LIMIT_PAUSE_MS
                            // "this relay does not accept REQs": asking again this session is pointless.
                            if (reason.contains("not accept REQ", ignoreCase = true)) readsRefused = true
                            pump()
                        }
                        sub.onDone(url, false)
                    }
                }
                "AUTH" -> {
                    synchronized(lock) { challenge = msg.optString(1) }
                }
            }
        }

        /** Handles the relay's answer to a post or to our sign-in. */
        private fun ok(id: String, accepted: Boolean, message: String) {
            val isAuth = synchronized(lock) { id == authEventId }
            if (isAuth) {
                if (accepted) {
                    AppLog.add("$url signed in")
                    signedIn = true
                    signInRefused = false
                    replayAfterAuth()
                } else {
                    signInRefused = true
                    failAuth(message)
                }
                return
            }
            val item = synchronized(lock) { pending[id] } ?: return
            if (!accepted && "auth-required" in message && !item.retriedAuth && signer() != null && !signInRefused) {
                item.retriedAuth = true
                authenticate()
                return
            }
            synchronized(lock) { pending.remove(id) }
            if (!accepted) AppLog.add("$url refused kind ${item.event.kind}: $message")
            if (!accepted && refusesWrites(message)) {
                writeRefusal = message
                writesRefusedUntil = System.currentTimeMillis() + REFUSED_MS
            }
            item.result.complete(PublishResult(accepted, message))
        }

        /** Signed in: send the requests and posts that were waiting for it. */
        private fun replayAfterAuth() {
            val requests = synchronized(lock) {
                authEventId = null
                val subRequests = waitingForAuth.mapNotNull { subs[it]?.request }
                waitingForAuth.clear()
                subRequests + pending.values.filter { it.retriedAuth }.map { "[\"EVENT\",${it.event.toJson()}]" }
            }
            requests.forEach { socket?.send(it) }
        }

        /** Sign-in failed: end the requests and posts that needed it. */
        private fun failAuth(message: String) {
            note(url, "sign-in failed: $message")
            val affected: List<Subscription>
            val failed: List<Pending>
            synchronized(lock) {
                authEventId = null
                affected = waitingForAuth.mapNotNull { subs[it] }
                // Refused requests aren't resent on the next reconnect, as with any other refusal.
                waitingForAuth.forEach {
                    subs.remove(it)
                    sent.remove(it)
                }
                waitingForAuth.clear()
                failed = pending.values.filter { it.retriedAuth }
                failed.forEach { pending.remove(it.event.id) }
                pump()
            }
            affected.forEach { it.onDone(url, false) }
            failed.forEach { it.result.complete(PublishResult(false, message)) }
        }

        /** NIP-42, only when a relay demands it, so the user's key is not shown to every relay. */
        private fun authenticate() {
            val token = synchronized(lock) {
                if (authEventId != null) return
                authEventId = "pending"
                challenge
            }
            val who = signer()
            if (token == null || who == null) {
                failAuth("auth-required: no challenge")
                return
            }
            scope.launch {
                try {
                    val auth = who.sign(
                        Draft(who.pubkey, nowSeconds(), Kind.AUTH, listOf(listOf("relay", url), listOf("challenge", token)), ""),
                        background = true,
                    )
                    synchronized(lock) { authEventId = auth.id }
                    socket?.send("[\"AUTH\",${auth.toJson()}]")
                } catch (e: Exception) {
                    failAuth("auth-required: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val IDLE_CLOSE_MS = 45_000L
        const val PAUSE_GRACE_MS = 20_000L
        const val MAX_BACKOFF_S = 300L

        /** Failed connections in a row after which a relay is left alone for a while. */
        const val DEAD_AFTER = 6
        const val SETTLE_MS = 4_000L

        /** Slack in catch-up requests, for relays whose clocks run behind. */
        const val CATCH_UP_MARGIN_S = 120L
        const val STABLE_MS = 30_000L
        const val RATE_LIMIT_PAUSE_MS = 300_000L
        const val REFUSED_MS = 30 * 60 * 1000L
        const val MIN_REDIAL_MS = 3_000L
        const val MAX_OPEN_SUBS = 12
        const val REQ_SPACING_MS = 120L
    }
}
