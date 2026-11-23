/*
 * Notifications without a push service: Android runs a short check every so often (JobScheduler),
 * Zino connects, looks for new replies, mentions, zaps and messages, shows notifications, and
 * disconnects. Message notifications never include who sent them or what they say.
 */
package app.struthio.nostr.data

import app.struthio.nostr.str
import app.struthio.nostr.plural
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.struthio.nostr.MainActivity
import app.struthio.nostr.R
import app.struthio.nostr.ZinoApp
import app.struthio.nostr.core.cut
import app.struthio.nostr.core.Nip19
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Scheduled checks through the platform's JobScheduler: no push service and no open connection. */
object Notifier {
    const val DEFAULT_MINUTES = 15
    val INTERVALS = listOf(15, 30, 60, 180, 0)

    private const val JOB_ID = 1
    private const val ACTIVITY = "activity"
    private const val MESSAGES = "messages"
    private const val MAX_SHOWN = 5
    private const val SUMMARY_ID = 100
    private const val MESSAGES_ID = 101
    // Other accounts' summary and message ids start here, below the ids of single notifications.
    private const val ACCOUNT_IDS = 200

    /** The account a notification belongs to, carried to the app when it's tapped. */
    const val EXTRA_ACCOUNT = "account"

    /** Sets how often the check runs, in minutes; 0 turns checks off. */
    fun schedule(context: Context, minutes: Int) {
        // Notifications must never get in the way of the account itself.
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (minutes <= 0) {
                scheduler.cancel(JOB_ID)
                return
            }
            val interval = minutes * 60_000L
            val pending = scheduler.getPendingJob(JOB_ID)
            if (pending?.intervalMillis == interval) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, CheckService::class.java))
                    .setPeriodic(interval)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        } catch (e: RuntimeException) {
            Log.w("Zino", "could not schedule checks", e)
        }
    }

    /** Creates the two notification channels: activity, and private messages. */
    private fun channels(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel(ACTIVITY, str(R.string.activity), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(MESSAGES, str(R.string.private_messages), NotificationManager.IMPORTANCE_HIGH))
    }

    /**
     * What tapping a notification opens: the post it's about, or just the app, in the account it
     * belongs to.
     */
    private fun open(context: Context, id: Int, target: String?, account: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            target?.let { data = Uri.parse("nostr:" + Nip19.note(it)) }
            putExtra(EXTRA_ACCOUNT, account)
        }
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A notification in Zino's style, cleared when tapped. */
    private fun builder(context: Context, channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setAutoCancel(true)

    /**
     * Shows one account's news. Message notifications carry no sender and no text: only the app
     * shows those. With several accounts, [label] names the account under each notification.
     */
    fun post(context: Context, alerts: List<Alert>, messages: Int, account: String, label: String?, name: (String) -> String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        channels(manager)
        // Each account gets its own summary and message notifications, so one can't replace another's.
        val summaryId = if (account == Accounts.FIRST) SUMMARY_ID else ACCOUNT_IDS + (account.hashCode() and 0x7fffffff) % 1900 * 2
        val messagesId = if (account == Accounts.FIRST) MESSAGES_ID else summaryId + 1

        /** A notification builder, marked with the account when there are several. */
        fun styled(channel: String) = builder(context, channel).apply { label?.let { setSubText(it) } }

        if (messages > 0) {
            val text = plural(R.plurals.new_private_messages, messages, messages)
            manager.notify(
                messagesId,
                styled(MESSAGES).setContentTitle(text).setContentIntent(open(context, messagesId, null, account)).build(),
            )
        }
        if (alerts.size > MAX_SHOWN) {
            manager.notify(
                summaryId,
                styled(ACTIVITY)
                    .setContentTitle(str(R.string.x_new_notifications, alerts.size))
                    .setContentText(alerts.first().headline(name(alerts.first().actor)))
                    .setContentIntent(open(context, summaryId, null, account))
                    .build(),
            )
            return
        }
        for (alert in alerts) {
            val id = alert.event.id.hashCode() and 0x7fffffff or 0x1000
            manager.notify(
                id,
                styled(ACTIVITY)
                    .setContentTitle(alert.headline(name(alert.actor)))
                    .setContentText(alert.detail.cut(200))
                    .setContentIntent(open(context, id, alert.target, account))
                    .build(),
            )
        }
    }

    /** Removes all of Zino's notifications. */
    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
}

/** The job Android runs for each check. */
class CheckService : JobService() {
    private var work: Job? = null

    /** Runs one check for every account in the background and tells Android when it's done. */
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as ZinoApp
        work = app.core.scope.launch {
            try {
                withTimeout(CHECK_LIMIT_MS) { app.checkAllAccounts() }
            } catch (_: Exception) {
                // A missed check is simply picked up by the next one, which starts from the same point.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** Android ran out of time for the check: stop it and try again later. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private companion object {
        const val CHECK_LIMIT_MS = 40_000L
    }
}
