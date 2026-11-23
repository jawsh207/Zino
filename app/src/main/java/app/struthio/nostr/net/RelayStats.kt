/*
 * Per-relay figures for the relay screens: data used and how often each relay answered, over the
 * last few days. Also kept here, so they survive the app being closed: how long a relay that keeps
 * turning us away should be left alone, and which relays said they can't compare lists (NIP-77).
 */
package app.struthio.nostr.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Zino has seen of each relay over the last [DAYS] days, kept per day: data down and up,
 * and how many connection attempts reached it. Only Zino's own use is counted, so a relay that
 * was down while the app was closed isn't known to have been.
 */
class RelayStats(load: () -> String?, private val save: (String) -> Unit) {
    /**
     * A relay's totals over recent days: data down and up, and connection attempts made and
     * reached.
     */
    data class Summary(val down: Long, val up: Long, val tries: Int, val reached: Int) {
        /** Percent of connection attempts that got through, or null before any. */
        val uptime: Int? get() = if (tries == 0) null else (reached * 100L / tries).toInt()
    }

    /** One day's figures for one relay. */
    private class Day(var tries: Int = 0, var reached: Int = 0, var down: Long = 0, var up: Long = 0)

    private val lock = Any()
    private val relays = HashMap<String, java.util.TreeMap<Long, Day>>()
    private var dirty = false
    private var savedAt = System.currentTimeMillis()

    /** Per relay: until when it rests (ms), and how many long rests in a row it has had. */
    private val rests = HashMap<String, Pair<Long, Int>>()

    /** Per relay: when it said it doesn't do negentropy (ms). */
    private val noSync = HashMap<String, Long>()

    init {
        runCatching { load()?.let(::parse) }
    }

    /** Until when [url] should be left alone (ms since 1970), or 0. */
    fun restingUntil(url: String): Long = synchronized(lock) { rests[url]?.first ?: 0L }

    /**
     * Rests [url] after it turned us away again, starting at [base] ms and doubling with each rest
     * in a row, up to a day: a relay that's been gone for weeks shouldn't be dialled every half hour.
     * Returns until when.
     */
    fun restLonger(url: String, base: Long): Long = synchronized(lock) {
        val streak = rests[url]?.second ?: 0
        val until = System.currentTimeMillis() + minOf(base shl minOf(streak, 6), MAX_REST_MS)
        rests[url] = until to streak + 1
        dirty = true
        until
    }

    /** [url] let us in, so any rest and its streak are forgotten. */
    fun reached(url: String) {
        synchronized(lock) { if (rests.remove(url) != null) dirty = true }
    }

    /**
     * True if [url] said lately that it can't compare lists. Asked again after a week, as relays
     * do add it.
     */
    fun noNegentropy(url: String): Boolean = synchronized(lock) {
        (noSync[url] ?: return false) > System.currentTimeMillis() - NO_SYNC_KEEP_MS
    }

    /** Notes that [url] can't compare lists (NIP-77). */
    fun refuseNegentropy(url: String) {
        synchronized(lock) {
            noSync[url] = System.currentTimeMillis()
            dirty = true
        }
    }

    /** Today, counted in days since 1970. */
    private fun today() = System.currentTimeMillis() / 86_400_000L

    /** Changes today's figures for a relay. */
    private inline fun update(url: String, change: Day.() -> Unit) {
        synchronized(lock) {
            val days = relays.getOrPut(url) { java.util.TreeMap() }
            val day = today()
            days.getOrPut(day) { Day() }.change()
            days.headMap(day - DAYS + 1).clear()
            dirty = true
        }
    }

    /** Adds data sent and received, in bytes. */
    fun traffic(url: String, down: Int, up: Int) = update(url) {
        this.down += down
        this.up += up
    }

    /** Counts a connection attempt and whether it got through. */
    fun attempt(url: String, reached: Boolean) {
        update(url) {
            tries++
            if (reached) this.reached++
        }
        // Kept now and then too, not only when the app is left, so a crash loses little.
        if (System.currentTimeMillis() - savedAt > SAVE_EVERY_MS) flush()
    }

    /** A relay's totals for the days kept. */
    fun summary(url: String): Summary = synchronized(lock) {
        val days = relays[url]?.tailMap(today() - DAYS + 1)?.values.orEmpty()
        Summary(days.sumOf { it.down }, days.sumOf { it.up }, days.sumOf { it.tries }, days.sumOf { it.reached })
    }

    /** Saves the figures, if they changed. */
    fun flush() {
        val text = synchronized(lock) {
            if (!dirty) return
            dirty = false
            savedAt = System.currentTimeMillis()
            val oldest = today() - DAYS + 1
            JSONObject().apply {
                relays.forEach { (url, days) ->
                    val list = JSONArray()
                    days.tailMap(oldest).forEach { (day, d) -> list.put(JSONArray(listOf(day, d.tries, d.reached, d.down, d.up))) }
                    if (list.length() > 0) put(url, list)
                }
                val now = System.currentTimeMillis()
                // Underscored keys can't be relay addresses, and older versions skip what isn't a list.
                put(REST_KEY, JSONObject().apply { rests.forEach { (url, r) -> if (r.first > now - MAX_REST_MS) put(url, JSONArray(listOf(r.first, r.second))) } })
                put(NO_SYNC_KEY, JSONObject().apply { noSync.forEach { (url, at) -> if (at > now - NO_SYNC_KEEP_MS) put(url, at) } })
            }.toString()
        }
        save(text)
    }

    /** Reads saved figures back. */
    private fun parse(text: String) {
        val o = JSONObject(text)
        o.optJSONObject(REST_KEY)?.let { r ->
            r.keys().forEach { url -> r.optJSONArray(url)?.let { rests[url] = it.optLong(0) to it.optInt(1) } }
        }
        o.optJSONObject(NO_SYNC_KEY)?.let { n -> n.keys().forEach { url -> noSync[url] = n.optLong(url) } }
        o.keys().forEach { url ->
            if (url == REST_KEY || url == NO_SYNC_KEY) return@forEach
            val list = o.optJSONArray(url) ?: return@forEach
            val days = java.util.TreeMap<Long, Day>()
            for (i in 0 until list.length()) {
                val d = list.optJSONArray(i) ?: continue
                days[d.optLong(0)] = Day(d.optInt(1), d.optInt(2), d.optLong(3), d.optLong(4))
            }
            relays[url] = days
        }
    }

    companion object {
        const val DAYS = 30
        private const val SAVE_EVERY_MS = 5 * 60_000L
        private const val MAX_REST_MS = 24 * 3600_000L
        private const val NO_SYNC_KEEP_MS = 7 * 24 * 3600_000L
        private const val REST_KEY = "_rest"
        private const val NO_SYNC_KEY = "_nosync"
    }
}

/** Data sizes as people read them: KB below a tenth of a megabyte, then MB, then GB. */
fun formatBytes(bytes: Long): String = when {
    bytes < 102_400 -> "${(bytes + 1023) / 1024} KB"
    bytes < 1_073_741_824 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
}
