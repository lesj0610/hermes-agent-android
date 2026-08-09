package io.github.lesj0610.hermes.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.lesj0610.hermes.core.Graph
import io.github.lesj0610.hermes.data.RunSignal

/**
 * Keeps the process alive for the duration of a run and mirrors run signals
 * into notifications.
 *
 * Without this, Android is free to kill the app while it is backgrounded, which
 * would drop the event stream mid-run and — worse — silently swallow an
 * approval request the agent is blocked on.
 */
class RunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(Notifications.ID_FOREGROUND, Notifications.foreground(this))
        observeSignals()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restarting with no intent would leave the service running with no run
        // behind it; the Activity restarts it when a run is actually in flight.
        return START_NOT_STICKY
    }

    private fun observeSignals() {
        if (collector != null) return
        val graph = Graph.get(this)

        collector = scope.launch {
            graph.runEngine.signals.collect { signal ->
                val settings = graph.settings.settings.first()
                when (signal) {
                    is RunSignal.ApprovalNeeded ->
                        if (settings.notifyApprovals) {
                            notify(Notifications.ID_APPROVAL, Notifications.approval(this@RunService, signal.approval))
                        }

                    RunSignal.ApprovalCleared ->
                        Notifications.cancel(this@RunService, Notifications.ID_APPROVAL)

                    is RunSignal.Finished -> {
                        Notifications.cancel(this@RunService, Notifications.ID_APPROVAL)
                        if (settings.notifyCompletion) {
                            notify(Notifications.ID_RESULT, Notifications.result(this@RunService, signal.ok))
                        }
                        stopSelf()
                    }

                    is RunSignal.Started -> Unit
                }
            }
        }
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        // POST_NOTIFICATIONS is a runtime permission from API 33. Without it the
        // post is a no-op, so check rather than throw.
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(id, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        collector = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunService::class.java))
        }
    }
}
