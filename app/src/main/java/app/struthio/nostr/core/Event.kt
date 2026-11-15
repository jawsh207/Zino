/*
 * Nostr events and the things around them: the event kinds Zino knows, tags, the exact JSON and id
 * rules from NIP-01, drafts before signing, filters for asking relays, and the Signer interface
 * with its two simplest forms, a key kept on the phone and a read-only account.
 */
package app.struthio.nostr.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The event kinds Zino reads or writes, by name, and which ranges of kinds replace older versions.
 */
object Kind {
    const val METADATA = 0
    const val NOTE = 1
    const val FOLLOWS = 3
    const val DELETION = 5
    const val REPOST = 6
    const val REACTION = 7
    const val SEAL = 13
    const val DM = 14
    const val FILE_DM = 15
    const val GENERIC_REPOST = 16
    const val POLL_VOTE = 1018
    const val GIFT_WRAP = 1059
    const val POLL = 1068
    const val COMMENT = 1111
    const val REPORT = 1984
    const val ZAP_REQUEST = 9734
    const val ZAP_RECEIPT = 9735
    const val MUTES = 10000
    const val RELAYS = 10002
    const val SEARCH_RELAYS = 10007
    const val DM_RELAYS = 10050
    const val BOOKMARKS = 10003
    const val MEDIA_SERVERS = 10063
    const val NWC_INFO = 13194
    const val AUTH = 22242
    const val BLOSSOM_AUTH = 24242
    const val NWC_REQUEST = 23194
    const val NWC_RESPONSE = 23195

    /** NIP-68 picture post. */
    const val PICTURE = 20

    /** NIP-71 video posts: normal and short, each also in an editable (addressable) form. */
    const val VIDEO = 21
    const val SHORT_VIDEO = 22
    const val EDITABLE_VIDEO = 34235
    const val EDITABLE_SHORT_VIDEO = 34236

    /** NIP-23 article. */
    const val ARTICLE = 30023

    /** NIP-38 user status. */
    const val STATUS = 30315

    /** NIP-39 linked accounts. */
    const val IDENTITIES = 10011

    /** NIP-62 request to vanish. */
    const val VANISH = 62

    /** NIP-46 remote signing messages. */
    const val NOSTR_CONNECT = 24133

    val VIDEOS = setOf(VIDEO, SHORT_VIDEO, EDITABLE_VIDEO, EDITABLE_SHORT_VIDEO)

    /** Everything that shows as a post of its own in feeds. */
    val POSTS = listOf(NOTE, POLL, PICTURE, VIDEO, SHORT_VIDEO, EDITABLE_VIDEO, EDITABLE_SHORT_VIDEO, ARTICLE)

    /** Kinds where only the newest event per author counts, like profiles and follow lists. */
    fun isReplaceable(kind: Int) = kind == METADATA || kind == FOLLOWS || kind in 10000..19999
    /** Kinds where only the newest event per author and `d` tag counts, like articles. */
    fun isAddressable(kind: Int) = kind in 30000..39999
    /** Kinds relays pass on but don't keep, like sign-in challenges and remote-signer messages. */
    fun isEphemeral(kind: Int) = kind in 20000..29999
}

typealias Tags = List<List<String>>

/** The first tag with this name that has a value. */
fun Tags.first(name: String): List<String>? = firstOrNull { it.size >= 2 && it[0] == name }
/** The value of the first tag with this name. */
fun Tags.value(name: String): String? = first(name)?.get(1)
/** The values of every tag with this name. */
fun Tags.values(name: String): List<String> = filter { it.size >= 2 && it[0] == name }.map { it[1] }

/** JSON string escaping exactly as NIP-01 prescribes for event ids. */
private fun StringBuilder.jsonString(value: String) {
    append('"')
    for (c in value) {
        when (c) {
            '\n' -> append("\\n")
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
    append('"')
}

/** Writes tags as a JSON array of string arrays. */
private fun StringBuilder.jsonTags(tags: Tags) {
    append('[')
    tags.forEachIndexed { i, tag ->
        if (i > 0) append(',')
        append('[')
        tag.forEachIndexed { j, v ->
            if (j > 0) append(',')
            jsonString(v)
        }
        append(']')
    }
    append(']')
}

/** A string as a quoted JSON value. */
fun quoteJson(value: String) = StringBuilder().apply { jsonString(value) }.toString()

/** Tags as JSON. */
fun tagsToJson(tags: Tags) = StringBuilder().apply { jsonTags(tags) }.toString()

/** Tags from JSON, keeping only entries that are arrays of strings. */
fun parseTags(array: JSONArray?): Tags {
    if (array == null) return emptyList()
    return List(array.length()) { i ->
        val tag = array.optJSONArray(i)
        if (tag == null) emptyList() else List(tag.length()) { j -> tag.optString(j) }
    }
}

/** An event's id: the SHA-256 of its fields serialised exactly as NIP-01 says. */
fun computeEventId(pubkey: String, createdAt: Long, kind: Int, tags: Tags, content: String): String {
    val json = StringBuilder(content.length + 128).apply {
        append("[0,")
        jsonString(pubkey)
        append(',').append(createdAt).append(',').append(kind).append(',')
        jsonTags(tags)
        append(',')
        jsonString(content)
        append(']')
    }
    return Hex.encode(sha256(json.toString().toByteArray()))
}

/** An event template: not yet signed. The id is what a rumor carries too. */
class Draft(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: Tags,
    val content: String,
) {
    val id: String by lazy { computeEventId(pubkey, createdAt, kind, tags, content) }

    /**
     * The draft as unsigned event JSON, as sent to a remote signer or sealed inside a private
     * message.
     */
    fun toJson(): String = StringBuilder(content.length + 256).apply {
        append("{\"id\":\"").append(id).append("\",\"pubkey\":\"").append(pubkey)
        append("\",\"created_at\":").append(createdAt).append(",\"kind\":").append(kind).append(",\"tags\":")
        jsonTags(tags)
        append(",\"content\":")
        jsonString(content)
        append('}')
    }.toString()
}

/** A signed Nostr event, as relays send it. */
class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: Tags,
    val content: String,
    val sig: String,
) {
    /** The event as JSON, as relays expect it. */
    fun toJson(): String = StringBuilder(content.length + 400).apply {
        append("{\"id\":\"").append(id).append("\",\"pubkey\":\"").append(pubkey)
        append("\",\"created_at\":").append(createdAt).append(",\"kind\":").append(kind).append(",\"tags\":")
        jsonTags(tags)
        append(",\"content\":")
        jsonString(content)
        append(",\"sig\":\"").append(sig).append("\"}")
    }.toString()

    /** True if the id matches the event's contents. */
    fun hasValidId() = computeEventId(pubkey, createdAt, kind, tags, content) == id

    /** True if the signature is the author's, over this id. */
    fun hasValidSignature() = try {
        Keys.verify(Hex.decode(sig), Hex.decode(id), Hex.decode(pubkey))
    } catch (_: Exception) {
        false
    }

    /** True if both the id and the signature check out. Events failing this are dropped. */
    fun isValid() = hasValidId() && hasValidSignature()

    /** True if the event's NIP-40 `expiration` time has passed. */
    fun isExpired(now: Long = nowSeconds()) =
        tags.value("expiration")?.toLongOrNull()?.let { it <= now } ?: false

    companion object {
        /** An event from relay JSON, or null if a field is missing or the wrong type. */
        fun parse(o: JSONObject): Event? = try {
            val id = o.getString("id")
            val pubkey = o.getString("pubkey")
            val sig = o.getString("sig")
            if (Hex.isKey(id) && Hex.isKey(pubkey) && sig.length == 128) {
                Event(id, pubkey, o.getLong("created_at"), o.getInt("kind"), parseTags(o.getJSONArray("tags")), o.getString("content"), sig)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        /** An event from JSON text, or null if it isn't one. */
        fun parse(json: String): Event? = try {
            parse(JSONObject(json))
        } catch (_: Exception) {
            null
        }

        /** An unsigned event (a "rumor") from inside a private message, as a draft. */
        fun parseRumor(json: String): Draft? = try {
            val o = JSONObject(json)
            Draft(o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"), parseTags(o.getJSONArray("tags")), o.getString("content"))
        } catch (_: Exception) {
            null
        }
    }
}

/** The current time in seconds, as Nostr timestamps use. */
fun nowSeconds() = System.currentTimeMillis() / 1000

/**
 * What to ask a relay for: any mix of ids, authors, kinds, tags, a time window, a limit and a
 * search.
 */
class Filter(
    val ids: Collection<String>? = null,
    val authors: Collection<String>? = null,
    val kinds: Collection<Int>? = null,
    val tags: Map<String, Collection<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,
) {
    /** The filter as JSON, leaving out whatever isn't set. */
    fun toJson(): String = StringBuilder("{").apply {
        var first = true
        /** Writes a field name, with a comma before it if it isn't the first. */
        fun key(name: String) {
            if (!first) append(',')
            first = false
            jsonString(name)
            append(':')
        }
        /** Writes a field holding a list of strings. */
        fun strings(name: String, values: Collection<String>) {
            key(name)
            append('[')
            values.forEachIndexed { i, v ->
                if (i > 0) append(',')
                jsonString(v)
            }
            append(']')
        }
        ids?.let { strings("ids", it) }
        authors?.let { strings("authors", it) }
        kinds?.let {
            key("kinds")
            append(it.joinToString(",", "[", "]"))
        }
        tags.forEach { (name, values) -> strings("#$name", values) }
        since?.let { key("since"); append(it) }
        until?.let { key("until"); append(it) }
        limit?.let { key("limit"); append(it) }
        search?.let { key("search"); jsonString(it) }
        append('}')
    }.toString()
}

/** Signs and encrypts on behalf of the user: with a local key or an external NIP-55 signer. */
interface Signer {
    val pubkey: String

    /** [background] means no prompt may be shown; a signer that needs one throws instead. */
    suspend fun sign(draft: Draft, background: Boolean = false): Event
    /** Encrypts text to [peer] with NIP-44. */
    suspend fun nip44Encrypt(plaintext: String, peer: String): String

    /** [background] means no prompt may be shown; a signer that needs one throws instead. */
    suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean = true): String
}

/**
 * Signing with a private key kept on the phone. Conversation keys are remembered, as working them
 * out is slow.
 */
class LocalSigner(private val secret: ByteArray) : Signer {
    override val pubkey: String = Keys.publicKey(secret)
    private val conversations = HashMap<String, ByteArray>()

    /** The NIP-44 key shared with [peer], worked out once and then remembered. */
    private fun conversation(peer: String): ByteArray = synchronized(conversations) {
        conversations.getOrPut(peer) { Nip44.conversationKey(secret, peer) }
    }

    /** Signs a draft at once; there's nothing to wait for with a key on the phone. */
    fun signNow(draft: Draft): Event {
        require(draft.pubkey == pubkey)
        val sig = Hex.encode(Keys.sign(Hex.decode(draft.id), secret))
        return Event(draft.id, draft.pubkey, draft.createdAt, draft.kind, draft.tags, draft.content, sig)
    }

    /** Signs with the key on the phone. */
    override suspend fun sign(draft: Draft, background: Boolean) = signNow(draft)
    /** Encrypts to [peer] with the key on the phone. */
    override suspend fun nip44Encrypt(plaintext: String, peer: String) = Nip44.encrypt(plaintext, conversation(peer))
    /** Decrypts from [peer] with the key on the phone. */
    override suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean) = Nip44.decrypt(payload, conversation(peer))
}

/** Signed in with an npub: everything can be read, nothing can be signed. */
class ReadOnlySigner(override val pubkey: String) : Signer {
    /** The answer to anything that needs the private key. */
    private fun refuse(): Nothing = throw IllegalStateException("This account is read-only. Sign in with your private key or a signer app.")
    /** Read-only: can't sign. */
    override suspend fun sign(draft: Draft, background: Boolean): Event = refuse()
    /** Read-only: can't encrypt. */
    override suspend fun nip44Encrypt(plaintext: String, peer: String): String = refuse()
    /** Read-only: can't decrypt. */
    override suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean): String = refuse()
}
