/*
 * Negentropy (NIP-77): a way for Zino and a relay to find out which events the other is missing
 * without sending the events themselves. Each side sorts its events by time and id, then they trade
 * short fingerprints of ranges, splitting only the ranges that differ, until the differences are
 * small enough to list by id. Zino then asks for just the ids it lacks.
 *
 * This is the client half of protocol version 1 (the 0x61 byte), written from the specification.
 */
package app.struthio.nostr.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * One side of a negentropy run, for the events Zino has: pairs of time and id. Zino always starts;
 * the [responder] side, which relays play, exists for tests.
 */
class Negentropy(items: Collection<Pair<Long, String>>, private val responder: Boolean = false) {
    private val items: List<Item> = items.map { Item(it.first, Hex.decode(it.second)) }.sorted()

    /** Ids the relay has and Zino doesn't. */
    val need = LinkedHashSet<String>()

    /** Ids Zino has and the relay doesn't. */
    val have = LinkedHashSet<String>()

    private var lastIn = 0L
    private var lastOut = 0L

    /** The first message, for NEG-OPEN, as hex. */
    fun initiate(): String {
        lastOut = 0
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        splitRange(0, items.size, Bound(MAX_TIME, ByteArray(0)), out)
        return Hex.encode(out.toByteArray())
    }

    /**
     * Reads the relay's reply and returns the next message to send, or null when the two sides
     * agree on everything and the run is over. [need] and [have] fill up along the way.
     */
    fun reconcile(messageHex: String): String? {
        val input = Reader(Hex.decode(messageHex))
        lastIn = 0
        lastOut = 0
        val version = input.byte()
        if (version != VERSION) throw IllegalArgumentException("unsupported negentropy version $version")
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        var prevIndex = 0
        var prevBound = Bound(0, ByteArray(0))
        var skip = false

        /** Writes a pending skip before anything else goes out. */
        fun flushSkip() {
            if (!skip) return
            skip = false
            writeBound(prevBound, out)
            writeVarint(SKIP, out)
        }

        while (!input.done) {
            val bound = readBound(input)
            val mode = input.varint()
            val lower = prevIndex
            val upper = lowerBound(prevIndex, bound)
            when (mode) {
                SKIP -> skip = true
                FINGERPRINT -> {
                    val theirs = input.bytes(FINGERPRINT_SIZE)
                    if (theirs.contentEquals(fingerprint(lower, upper))) {
                        skip = true
                    } else {
                        flushSkip()
                        splitRange(lower, upper, bound, out)
                    }
                }
                ID_LIST -> {
                    val count = input.varint().toInt()
                    val theirs = HashSet<String>()
                    repeat(count) { theirs += Hex.encode(input.bytes(ID_SIZE)) }
                    if (responder) {
                        // The relay's side answers with its own ids for the range.
                        flushSkip()
                        writeBound(bound, out)
                        writeVarint(ID_LIST, out)
                        writeVarint((upper - lower).toLong(), out)
                        for (i in lower until upper) out.write(items[i].id)
                    } else {
                        for (i in lower until upper) {
                            val id = Hex.encode(items[i].id)
                            if (!theirs.remove(id)) have += id
                        }
                        need += theirs
                        skip = true
                    }
                }
                else -> throw IllegalArgumentException("unknown negentropy mode $mode")
            }
            prevIndex = upper
            prevBound = bound
        }
        val message = out.toByteArray()
        return if (message.size == 1 && !responder) null else Hex.encode(message)
    }

    /** Describes items [lower] to [upper]: their ids if few, otherwise fingerprints of 16 slices. */
    private fun splitRange(lower: Int, upper: Int, upperBound: Bound, out: ByteArrayOutputStream) {
        val count = upper - lower
        if (count < BUCKETS * 2) {
            writeBound(upperBound, out)
            writeVarint(ID_LIST, out)
            writeVarint(count.toLong(), out)
            for (i in lower until upper) out.write(items[i].id)
            return
        }
        val perBucket = count / BUCKETS
        val withExtra = count % BUCKETS
        var current = lower
        for (i in 0 until BUCKETS) {
            val size = perBucket + if (i < withExtra) 1 else 0
            val print = fingerprint(current, current + size)
            current += size
            val next = if (current == upper) upperBound else minimalBound(items[current - 1], items[current])
            writeBound(next, out)
            writeVarint(FINGERPRINT, out)
            out.write(print)
        }
    }

    /** The first index from [from] whose item is at or after [bound]. */
    private fun lowerBound(from: Int, bound: Bound): Int {
        var lo = from
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].compareTo(bound) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The fingerprint of items [lower] to [upper]: their ids summed, with the count, hashed. */
    private fun fingerprint(lower: Int, upper: Int): ByteArray {
        val sum = IntArray(ID_SIZE)
        for (i in lower until upper) {
            // Ids are added as 256-bit little-endian numbers, overflow dropped.
            var carry = 0
            val id = items[i].id
            for (b in 0 until ID_SIZE) {
                val total = sum[b] + (id[b].toInt() and 0xff) + carry
                sum[b] = total and 0xff
                carry = total ushr 8
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteArray(ID_SIZE) { sum[it].toByte() })
        digest.update(varint((upper - lower).toLong()))
        return digest.digest().copyOf(FINGERPRINT_SIZE)
    }

    /** The shortest bound that falls after [prev] and at or before [next]. */
    private fun minimalBound(prev: Item, next: Item): Bound {
        if (next.time != prev.time) return Bound(next.time, ByteArray(0))
        var shared = 0
        while (shared < ID_SIZE && prev.id[shared] == next.id[shared]) shared++
        return Bound(next.time, next.id.copyOf(shared + 1))
    }

    /** Writes a bound: its time as a step from the last one, then its id prefix. */
    private fun writeBound(bound: Bound, out: ByteArrayOutputStream) {
        if (bound.time == MAX_TIME) {
            writeVarint(0, out)
        } else {
            writeVarint(bound.time - lastOut + 1, out)
        }
        lastOut = bound.time
        writeVarint(bound.prefix.size.toLong(), out)
        out.write(bound.prefix)
    }

    /** Reads a bound written the same way. */
    private fun readBound(input: Reader): Bound {
        val step = input.varint()
        val time = if (step == 0L) MAX_TIME else lastIn + step - 1
        lastIn = time
        val length = input.varint().toInt()
        if (length > ID_SIZE) throw IllegalArgumentException("bound prefix too long")
        return Bound(time, input.bytes(length))
    }

    /** One event: its time and its 32-byte id. */
    private class Item(val time: Long, val id: ByteArray) : Comparable<Item> {
        /** Orders by time, then by id. */
        override fun compareTo(other: Item): Int = compareParts(time, id, other.time, other.id)

        /** Compares against a bound, which may hold only the start of an id. */
        fun compareTo(bound: Bound): Int = compareParts(time, id, bound.time, bound.prefix)
    }

    /** A place in the sorted order: a time, and as much of an id as needed to tell neighbours apart. */
    private class Bound(val time: Long, val prefix: ByteArray)

    /** Reads bytes and varints from a message. */
    private class Reader(private val data: ByteArray) {
        private var at = 0

        /** True once everything has been read. */
        val done get() = at >= data.size

        /** One byte. */
        fun byte(): Int = (data.getOrNull(at++) ?: throw IllegalArgumentException("message ended early")).toInt() and 0xff

        /** [n] bytes. */
        fun bytes(n: Int): ByteArray {
            if (at + n > data.size) throw IllegalArgumentException("message ended early")
            return data.copyOfRange(at, at + n).also { at += n }
        }

        /** A varint: seven bits per byte, most significant first, high bit set on all but the last. */
        fun varint(): Long {
            var value = 0L
            while (true) {
                val b = byte()
                value = (value shl 7) or (b and 0x7f).toLong()
                if (b and 0x80 == 0) return value
            }
        }
    }

    companion object {
        private const val VERSION = 0x61
        private const val SKIP = 0L
        private const val FINGERPRINT = 1L
        private const val ID_LIST = 2L
        private const val BUCKETS = 16
        private const val ID_SIZE = 32
        private const val FINGERPRINT_SIZE = 16

        /** Stands for "the end of time" in bounds. */
        const val MAX_TIME = Long.MAX_VALUE

        /** Orders by time, then byte by byte, a shorter prefix coming first. */
        private fun compareParts(t1: Long, a: ByteArray, t2: Long, b: ByteArray): Int {
            if (t1 != t2) return t1.compareTo(t2)
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        /** A number as a varint. */
        fun varint(n: Long): ByteArray {
            if (n == 0L) return byteArrayOf(0)
            val groups = ArrayList<Int>()
            var v = n
            while (v != 0L) {
                groups += (v and 0x7f).toInt()
                v = v ushr 7
            }
            return ByteArray(groups.size) { i ->
                val g = groups[groups.size - 1 - i]
                (if (i < groups.size - 1) g or 0x80 else g).toByte()
            }
        }

        /** Writes a number as a varint. */
        private fun writeVarint(n: Long, out: ByteArrayOutputStream) = out.write(varint(n))
    }
}
