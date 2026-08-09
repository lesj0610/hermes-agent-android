package io.github.lesj0610.hermes.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lesj0610.hermes.core.Graph

/**
 * Answers an approval straight from the notification.
 *
 * The whole point of the notification actions is that the user never has to
 * open the app, so this pushes the choice through the same engine call the
 * sheet uses and dismisses the prompt.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESPOND) return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return

        Graph.get(context).runEngine.respondToApproval(choice)
        Notifications.cancel(context, Notifications.ID_APPROVAL)
    }

    companion object {
        const val ACTION_RESPOND = "io.github.lesj0610.hermes.APPROVAL_RESPOND"
        const val EXTRA_CHOICE = "choice"
    }
}
