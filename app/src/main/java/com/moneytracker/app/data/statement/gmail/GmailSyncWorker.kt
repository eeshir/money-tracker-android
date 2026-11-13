package com.moneytracker.app.data.statement.gmail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moneytracker.app.MainActivity
import com.moneytracker.app.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The daily Gmail statement run: fetch what is new, import it, and say so. When
 * Google needs the person to approve access again (it does, about weekly, while the
 * project is in testing), nothing can be fetched — a notification says so and
 * opens the import screen, where one tap signs in again.
 */
@HiltWorker
class GmailSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val authorizer: GmailAuthorizer,
    private val sync: GmailStatementSync,
    private val importer: StatementAutoImporter
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!authorizer.isSupported) return Result.success()
        val token = when (val auth = authorizer.authorize()) {
            is GmailAuthorization.Granted -> auth.accessToken
            is GmailAuthorization.NeedsConsent -> {
                notifyBlocked()
                return Result.success()
            }
            // No network, or Play services busy: try again soon, not forever.
            GmailAuthorization.Failed -> return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
        return try {
            sync.fetch(token)
            val outcome = importer.run()
            if (outcome.imported > 0 || outcome.needPassword > 0) notifyDone(outcome)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmailAuthExpiredException) {
            authorizer.invalidate(token)
            notifyBlocked()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private fun notifyBlocked() = show(
        id = NOTIFICATION_BLOCKED,
        title = context.getString(R.string.gmail_notif_blocked_title),
        text = context.getString(R.string.gmail_notif_blocked_text)
    )

    private fun notifyDone(outcome: StatementAutoImporter.Outcome) {
        val parts = listOfNotNull(
            outcome.imported.takeIf { it > 0 }
                ?.let { context.resources.getQuantityString(R.plurals.gmail_notif_imported, it, it) },
            outcome.needPassword.takeIf { it > 0 }
                ?.let { context.resources.getQuantityString(R.plurals.gmail_notif_need_password, it, it) }
        )
        // No amounts: notifications show on the lock screen.
        show(NOTIFICATION_DONE, context.getString(R.string.gmail_notif_done_title), parts.joinToString(" · "))
    }

    private fun show(id: Int, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.gmail_notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.gmail_notif_channel_description) }
        )
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_STATEMENT_IMPORT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_money)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        )
    }

    companion object {
        private const val WORK_NAME = "gmail_statement_sync"
        private const val WORK_NOW = "gmail_statement_sync_now"
        private const val CHANNEL_ID = "gmail_statement_sync"
        // Transaction alerts use the transaction id; ids are never negative.
        private const val NOTIFICATION_BLOCKED = -11
        private const val NOTIFICATION_DONE = -12
        private const val MAX_ATTEMPTS = 3
        private const val PREFS = "gmail_sync"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /**
         * Turns the daily run on (and runs once now, so it can be seen working) or off.
         * Needs a network; WorkManager holds it until there is one.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                workManager.cancelUniqueWork(WORK_NOW)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GmailSyncWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
            )
            workManager.enqueueUniqueWork(
                WORK_NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<GmailSyncWorker>().setConstraints(constraints).build()
            )
        }
    }
}
