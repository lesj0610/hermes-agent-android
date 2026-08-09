package io.github.lesj0610.hermes.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.lesj0610.hermes.data.RunEngine
import io.github.lesj0610.hermes.net.DashboardApi
import io.github.lesj0610.hermes.net.HermesApi

/**
 * Application-scoped wiring.
 *
 * Deliberately not a DI framework: this app has exactly three long-lived
 * objects and they all outlive any single screen. The run engine in particular
 * must survive the chat ViewModel, because a run keeps streaming while the user
 * is on another screen or the app is backgrounded.
 */
class Graph(context: Context) {
    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)

    val api = HermesApi(
        baseUrlProvider = { settings.current().baseUrl },
        tokenProvider = { settings.current().token },
    )

    /**
     * Second server, second auth model. Kept apart from [api] rather than
     * folded into it: the dashboard is optional, uses a cookie session instead
     * of a bearer token, and may be down while the gateway is up.
     */
    val dashboard = DashboardApi(
        baseUrlProvider = { settings.current().dashboardUrl },
        credentialsProvider = {
            val current = settings.current()
            current.dashboardUsername to current.dashboardPassword
        },
    )

    val runEngine = RunEngine(api, scope)

    companion object {
        @Volatile
        private var instance: Graph? = null

        fun get(context: Context): Graph =
            instance ?: synchronized(this) {
                instance ?: Graph(context).also { instance = it }
            }
    }
}
