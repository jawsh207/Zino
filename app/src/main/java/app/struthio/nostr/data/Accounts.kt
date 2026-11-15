/*
 * The accounts on this phone. Each one keeps its own database, settings and stored secrets in
 * files of its own, so accounts never see each other's messages, wallet or keys, and switching is
 * like opening a separate copy of the app. The first account uses the file names Zino has always
 * used, so updating from a single-account version keeps everything.
 */
package app.struthio.nostr.data

import android.content.Context
import app.struthio.nostr.core.randomBytes
import app.struthio.nostr.core.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One account on this phone: its own id, and who it is once signed in. */
data class Account(val id: String, val pubkey: String? = null, val name: String? = null, val picture: String? = null)

/** The list of accounts and which one is in use, kept in their own small settings file. */
class Accounts(private val context: Context) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    /** Every account, in the order they were added. */
    val list: StateFlow<List<Account>> = state

    /** The account in use. */
    val activeId: String
        get() = prefs.getString(ACTIVE, null)?.takeIf { id -> state.value.any { it.id == id } } ?: state.value.first().id

    /** Reads the saved list; a phone that never had more than one account has just the first. */
    private fun load(): List<Account> {
        val saved = runCatching { JSONArray(prefs.getString(LIST, "[]")) }.getOrDefault(JSONArray())
        val out = (0 until saved.length()).mapNotNull { i ->
            val o = saved.optJSONObject(i) ?: return@mapNotNull null
            Account(o.getString("id"), o.optString("pubkey").ifEmpty { null }, o.optString("name").ifEmpty { null }, o.optString("picture").ifEmpty { null })
        }
        return out.ifEmpty { listOf(Account(FIRST)) }
    }

    /** Saves the list and tells whoever is watching. */
    private fun save(accounts: List<Account>) {
        val array = JSONArray()
        accounts.forEach { a ->
            array.put(JSONObject().put("id", a.id).put("pubkey", a.pubkey ?: "").put("name", a.name ?: "").put("picture", a.picture ?: ""))
        }
        prefs.edit().putString(LIST, array.toString()).apply()
        state.value = accounts
    }

    /** Adds an empty account, not yet signed in, and returns it. */
    @Synchronized
    fun add(): Account {
        val account = Account("a" + Hex.encode(randomBytes(6)))
        save(state.value + account)
        return account
    }

    /** Makes this account the one in use. */
    fun setActive(id: String) {
        prefs.edit().putString(ACTIVE, id).apply()
    }

    /** Remembers who an account is, for the account list. Nothing changes if nothing is new. */
    @Synchronized
    fun update(id: String, pubkey: String?, name: String? = null, picture: String? = null) {
        val list = state.value
        val old = list.firstOrNull { it.id == id } ?: return
        val new = if (pubkey != old.pubkey) Account(id, pubkey, name, picture) else old.copy(name = name ?: old.name, picture = picture ?: old.picture)
        if (new != old) save(list.map { if (it.id == id) new else it })
    }

    /** The other account already signed in as [pubkey], if there is one. */
    fun holding(pubkey: String, except: String): Account? = state.value.firstOrNull { it.pubkey == pubkey && it.id != except }

    /**
     * Takes an account off the list; true if it was. The last account is never removed, only
     * emptied, so there's always one to sign in to. Its files are deleted separately, with
     * [deleteFiles], once nothing is using them.
     */
    @Synchronized
    fun remove(id: String): Boolean {
        val list = state.value
        if (list.size == 1) {
            save(listOf(Account(id)))
            return false
        }
        save(list.filterNot { it.id == id })
        if (prefs.getString(ACTIVE, null) == id) setActive(state.value.first().id)
        return true
    }

    /** Deletes an account's database, settings and secrets files. */
    fun deleteFiles(id: String) {
        context.deleteDatabase(databaseName(id))
        context.deleteSharedPreferences(settingsName(id))
        context.deleteSharedPreferences(secretsName(id))
    }

    companion object {
        /** The first account's id. Its files keep the names from before there were accounts. */
        const val FIRST = "main"
        private const val LIST = "list"
        private const val ACTIVE = "active"

        /** The database file of an account. */
        fun databaseName(id: String) = if (id == FIRST) "struthio.db" else "struthio_$id.db"

        /** The settings file of an account. */
        fun settingsName(id: String) = if (id == FIRST) "app" else "app_$id"

        /** The file of an account's encrypted secrets. */
        fun secretsName(id: String) = if (id == FIRST) "secure" else "secure_$id"
    }
}
