package io.github.lesj0610.hermes.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * The two OS-level grants this app cannot work properly without.
 *
 * Neither is cosmetic. Without the notification grant an approval request never
 * reaches the user, and the agent sits blocked forever with no visible reason.
 * Without a battery-optimisation exemption the system is free to freeze the
 * foreground service during a long run, which drops the event stream — again
 * silently.
 */
object SystemPermissions {

    /**
     * POST_NOTIFICATIONS became a runtime permission in API 33. Below that the
     * manifest declaration is enough and this is always true.
     */
    fun canNotify(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun isExemptFromBatteryOptimization(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Sends the user to the battery-optimisation list to exempt this app.
     *
     * The one-tap dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) would be
     * friendlier, but it requires a manifest permission Google Play only grants
     * to alarm, VoIP and companion-device apps. Shipping on Play matters more
     * than saving the user two taps, so this opens the list instead and the
     * settings screen explains what to pick.
     *
     * Falls back to this app's detail page if the OEM lacks the list screen.
     */
    fun requestBatteryExemption(context: Context) {
        // Try-and-catch rather than resolveActivity: package visibility rules
        // from API 30 can hide a system Activity from the query even when
        // launching it would have worked.
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.tryStart(list)) return

        context.tryStart(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Opens this app's notification settings, for when the grant was denied permanently. */
    fun openNotificationSettings(context: Context) {
        context.tryStart(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun Context.tryStart(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess
}
