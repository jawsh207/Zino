/*
 * Nostr's shareable codes, NIP-19 and NIP-21: npub, nsec, note, nevent, nprofile and naddr, in
 * Bech32 with type-length-value fields for relays, authors and kinds. Also reads nostr: links.
 */
package app.struthio.nostr.core

import java.io.ByteArrayOutputStream

/** Bech32 (BIP-173), without the 90 character limit, which NIP-19 entities exceed. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** Bech32's checksum over a run of 5-bit values. */
    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    /** The prefix expanded the way Bech32's checksum needs. */
    private fun hrpExpand(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1).also { out ->
            hrp.forEachIndexed { i, c ->
                out[i] = c.code ushr 5
                out[hrp.length + 1 + i] = c.code and 31
            }
        }

    /** Regroups bits, as between 8-bit bytes and Bech32's 5-bit words. */
    private fun convert(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ByteArrayOutputStream()
        val max = (1 shl to) - 1
        for (b in data) {
            acc = (acc shl from) or (b.toInt() and 0xff)
            bits += from
            while (bits >= to) {
                bits -= to
                out.write((acc ushr bits) and max)
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (pad) {
            if (bits > 0) out.write((acc shl (to - bits)) and max)
        } else {
            require(bits < from && (acc shl (to - bits)) and max == 0) { "invalid padding" }
        }
        return out.toByteArray()
    }

    /** Encodes bytes under a prefix, like npub or note. */
    fun encode(hrp: String, data: ByteArray): String {
        val five = convert(data, 8, 5, true).map { it.toInt() }.toIntArray()
        val values = hrpExpand(hrp) + five + IntArray(6)
        val mod = polymod(values) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return buildString {
            append(hrp).append('1')
            (five + checksum).forEach { append(CHARSET[it]) }
        }
    }

    /** The checked 5-bit words, for formats that aren't bytes underneath, such as BOLT-11. */
    fun decodeWords(input: String): Pair<String, ByteArray> {
        val text = input.lowercase()
        require(input == text || input == input.uppercase()) { "mixed case" }
        val sep = text.lastIndexOf('1')
        require(sep >= 1 && sep + 7 <= text.length) { "invalid separator" }
        val hrp = text.substring(0, sep)
        val values = IntArray(text.length - sep - 1) {
            CHARSET.indexOf(text[sep + 1 + it]).also { v -> require(v >= 0) { "invalid character" } }
        }
        require(polymod(hrpExpand(hrp) + values) == 1) { "invalid checksum" }
        return hrp to ByteArray(values.size - 6) { values[it].toByte() }
    }

    /** 5-bit words back to bytes. */
    fun wordsToBytes(words: ByteArray): ByteArray = convert(words, 5, 8, false)

    /** Decodes a Bech32 string to its prefix and bytes. */
    fun decode(input: String): Pair<String, ByteArray> {
        val (hrp, words) = decodeWords(input)
        return hrp to wordsToBytes(words)
    }
}

/** What a NIP-19 code points to. */
sealed interface Entity {
    /** A person, with relays that may have their posts. */
    data class Profile(val pubkey: String, val relays: List<String> = emptyList()) : Entity
    /** A private key. Compared by value, as byte arrays aren't. */
    data class Secret(val key: ByteArray) : Entity {
        /** Equal when the keys are the same bytes. */
        override fun equals(other: Any?) = other is Secret && other.key.contentEquals(key)
        /** Hashes the key's bytes. */
        override fun hashCode() = key.contentHashCode()
    }
    /** A post by id, with hints where to find it: relays, author and kind. */
    data class Note(
        val id: String,
        val relays: List<String> = emptyList(),
        val author: String? = null,
        val kind: Int? = null,
    ) : Entity
    /** An addressable post (an article or editable video), by its `d` tag, author and kind. */
    data class Address(
        val identifier: String,
        val author: String,
        val kind: Int,
        val relays: List<String> = emptyList(),
    ) : Entity
}

/** NIP-19 bech32 entities. */
object Nip19 {
    /** A public key as an npub. */
    fun npub(hex: String) = Bech32.encode("npub", Hex.decode(hex))
    /** A private key as an nsec. */
    fun nsec(key: ByteArray) = Bech32.encode("nsec", key)
    /** An event id as a note code. */
    fun note(id: String) = Bech32.encode("note", Hex.decode(id))

    /** Writes one type-length-value field. */
    private fun tlv(out: ByteArrayOutputStream, type: Int, value: ByteArray) {
        out.write(type)
        out.write(value.size)
        out.write(value)
    }

    /** An event as an nevent, with up to three relays and the author as hints. */
    fun nevent(id: String, relays: List<String> = emptyList(), author: String? = null): String {
        val out = ByteArrayOutputStream()
        tlv(out, 0, Hex.decode(id))
        relays.take(3).forEach { tlv(out, 1, it.toByteArray()) }
        author?.let { tlv(out, 2, Hex.decode(it)) }
        return Bech32.encode("nevent", out.toByteArray())
    }

    /** A person as an nprofile, with up to three relays. */
    fun nprofile(pubkey: String, relays: List<String> = emptyList()): String {
        val out = ByteArrayOutputStream()
        tlv(out, 0, Hex.decode(pubkey))
        relays.take(3).forEach { tlv(out, 1, it.toByteArray()) }
        return Bech32.encode("nprofile", out.toByteArray())
    }

    /** The fields read out of an nevent, nprofile or naddr. */
    private class Tlv(val special: ByteArray?, val relays: List<String>, val author: ByteArray?, val kind: Int?)

    /** Reads type-length-value fields, skipping types it doesn't know. */
    private fun readTlv(data: ByteArray): Tlv {
        var special: ByteArray? = null
        var author: ByteArray? = null
        var kind: Int? = null
        val relays = ArrayList<String>()
        var i = 0
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xff
            val length = data[i + 1].toInt() and 0xff
            require(i + 2 + length <= data.size) { "truncated TLV" }
            val value = data.copyOfRange(i + 2, i + 2 + length)
            when (type) {
                0 -> if (special == null) special = value
                1 -> relays += String(value)
                2 -> if (length == 32 && author == null) author = value
                3 -> if (length == 4) {
                    kind = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
                }
            }
            i += 2 + length
        }
        return Tlv(special, relays, author, kind)
    }

    /** Decodes any NIP-19 code, with or without nostr: in front, or returns null. */
    fun decode(text: String): Entity? = try {
        val (hrp, data) = Bech32.decode(text.removePrefix("nostr:"))
        when (hrp) {
            "npub" -> Entity.Profile(Hex.encode(data.also { require(it.size == 32) }))
            "nsec" -> Entity.Secret(data.also { require(it.size == 32) })
            "note" -> Entity.Note(Hex.encode(data.also { require(it.size == 32) }))
            "nprofile" -> readTlv(data).let {
                Entity.Profile(Hex.encode(it.special!!.also { s -> require(s.size == 32) }), it.relays)
            }
            "nevent" -> readTlv(data).let { tlv ->
                Entity.Note(
                    Hex.encode(tlv.special!!.also { s -> require(s.size == 32) }),
                    tlv.relays,
                    tlv.author?.let(Hex::encode),
                    tlv.kind,
                )
            }
            "naddr" -> readTlv(data).let {
                Entity.Address(String(it.special!!), Hex.encode(it.author!!), it.kind!!, it.relays)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
