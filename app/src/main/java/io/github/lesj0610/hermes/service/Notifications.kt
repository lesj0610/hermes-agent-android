package io.github.lesj0610.hermes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lesj0610.hermes.MainActivity
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.PendingApproval

object Notifications {

    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_RUNS = "runs"

    const val ID_FOREGROUND = 1
    const val ID_APPROVAL = 2
    const val ID_RESULT = 3

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVALS,
                context.getString(R.string.notif_channel_approvals),
                // High importance: the run is blocked until this is answered, so
                // it has to surface on the lock screen rather than sit silently.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notif_channel_approvals_desc) },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNS,
                context.getString(R.string.notif_channel_runs),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_runs_desc) },
        )
    }

    fun foreground(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_RUNS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.notif_running_title))
            .setContentText(context.getString(R.string.notif_running_body))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .build()

    /**
     * Approval prompt with the server's own choices as actions.
     *
     * Only the first three fit as notification actions; the rest stay available
     * in the app. Tapping the body opens the sheet, so nothing is unreachable.
     */
    fun approval(context: Context, approval: PendingApproval): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.notif_approval_title))
            .setContentText(approval.command.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(approval.command.orEmpty()))
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        approval.choices.take(3).forEachIndexed { index, choice ->
            builder.addAction(
                0,
                labelFor(context, choice),
                PendingIntent.getBroadcast(
                    context,
                    index,
                    Intent(context, ApprovalActionReceiver::class.java).apply {
                        action = ApprovalActionReceiver.ACTION_RESPOND
                        putExtra(ApprovalActionReceiver.EXTRA_CHOICE, choice)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    fun result(context: Context, ok: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_RUNS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                context.getString(
                    if (ok) R.string.notif_finished_title else R.string.notif_failed_title,
                ),
            )
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun labelFor(context: Context, choice: String): String = when (choice) {
        "once" -> context.getString(R.string.approval_choice_once)
        "session" -> context.getString(R.string.approval_choice_session)
        "always" -> context.getString(R.string.approval_choice_always)
        "deny" -> context.getString(R.string.approval_choice_deny)
        else -> choice
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
