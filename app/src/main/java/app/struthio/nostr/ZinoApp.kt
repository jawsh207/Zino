/*
 * The application object. It keeps the list of accounts and the Core of the one in use, starts the
 * log, and switches accounts: the old Core is closed and a new one opens on the other account's
 * own files. Screens watch [ZinoApp.current], so they redraw for the new account. Home screen
 * widgets always show the account in use, so they're redrawn on every switch.
 */
package app.struthio.nostr

import android.app.Application
import android.os.Handler
import android.os.Looper
import app.struthio.nostr.data.Accounts
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.Notifier
import app.struthio.nostr.net.AppLog
import app.struthio.nostr.widget.Widgets
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Holds the accounts and the Core of the one in use. */
class ZinoApp : Application() {
    val accounts: Accounts by lazy { Accounts(this) }
    private val state = MutableStateFlow<Core?>(null)
    private var started = 0

    /** The Core of the account in use, for screens to watch. */
    val current: StateFlow<Core?> get() = state.also { core }

    /** The Core of the account in use, opened the first time it's needed. */
    val core: Core
        get() = state.value ?: synchronized(this) { state.value ?: open(accounts.activeId).also { state.value = it } }

    /** Starts the log and puts the shortcuts on the launcher icon as the app starts. */
    override fun onCreate() {
        super.onCreate()
        Texts.use(this)
        AppLog.start(File(filesDir, "log.txt"))
        Shortcuts.install(this)
    }

    /** A Core for one account, which tells us when that account signs out. */
    private fun open(id: String) = Core(this, id, accounts, ::signedOut)

    /** The app came to the front. */
    fun appStarted() {
        started++
        core.checkNetwork()
        core.pool.activityStarted()
    }

    /** The app went away. */
    fun appStopped() {
        started--
        core.pool.activityStopped()
    }

    /** Closes the account in use and opens [id]. Call on the main thread. */
    fun switchTo(id: String) {
        val old = core
        if (old.accountId == id) return
        accounts.setActive(id)
        val new = open(id)
        if (started > 0) new.pool.activityStarted()
        state.value = new
        old.close()
        AppLog.add("switched account")
        // Widgets show the account in use.
        Widgets.update(this)
    }

    /**
     * One background check for every signed-in account, side by side. The account in use checks
     * through its own Core; the others each get a Core of their own for the check, closed after.
     * The widgets are redrawn afterwards with whatever the check brought in.
     */
    suspend fun checkAllAccounts() {
        checkEach()
        Widgets.update(this)
    }

    /** Runs the check for each signed-in account side by side, returning once all are done. */
    private suspend fun checkEach() = coroutineScope {
        val active = core
        launch { runCatching { active.backgroundCheck() } }
        for (account in accounts.list.value) {
            if (account.pubkey == null || account.id == active.accountId) continue
            launch {
                val other = Core(this@ZinoApp, account.id, accounts)
                try {
                    other.backgroundCheck()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.add("background check failed for another account: $e")
                } finally {
                    other.close()
                }
            }
        }
    }

    /** Opens the account a tapped notification belongs to, if it's another one still on the phone. */
    fun openAccountOf(intent: android.content.Intent?) {
        val id = intent?.getStringExtra(Notifier.EXTRA_ACCOUNT) ?: return
        if (id != core.accountId && accounts.list.value.any { it.id == id && it.pubkey != null }) switchTo(id)
    }

    /** Adds an empty account and switches to it, which shows sign-in. */
    fun addAccount() = switchTo(accounts.add().id)

    /**
     * Gives up on an account that never signed in, as when sign-in is cancelled, and goes back to
     * [previous].
     */
    fun cancelAdding(previous: String) {
        val abandoned = core.accountId
        switchTo(previous)
        forget(abandoned)
    }

    /**
     * An account signed out. With others on the phone it's removed and the most recently added
     * signed-in one takes over; the last account just stays, empty, showing sign-in.
     */
    private fun signedOut(id: String) {
        val others = accounts.list.value.filter { it.id != id }
        if (others.isEmpty()) {
            accounts.remove(id)
            return
        }
        switchTo(others.lastOrNull { it.pubkey != null }?.id ?: others.last().id)
        forget(id)
    }

    /** Takes an account off the list now, and deletes its files once its closed Core lets go of them. */
    private fun forget(id: String) {
        if (!accounts.remove(id)) return
        Handler(Looper.getMainLooper()).postDelayed({ accounts.deleteFiles(id) }, REMOVE_DELAY_MS)
    }

    private companion object {
        const val REMOVE_DELAY_MS = 15_000L
    }
}
