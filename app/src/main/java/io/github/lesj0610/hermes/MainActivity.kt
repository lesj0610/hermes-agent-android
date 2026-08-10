package io.github.lesj0610.hermes

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.runBlocking
import io.github.lesj0610.hermes.core.Graph
import io.github.lesj0610.hermes.core.Language
import io.github.lesj0610.hermes.core.SystemPermissions
import io.github.lesj0610.hermes.service.RunService
import io.github.lesj0610.hermes.ui.AppViewModel
import io.github.lesj0610.hermes.ui.HermesShell
import io.github.lesj0610.hermes.ui.settings.PermissionState
import io.github.lesj0610.hermes.ui.theme.HermesTheme

class MainActivity : ComponentActivity() {

    /** The language chosen when this Activity was created, used to detect a change. */
    private var startedWithLanguage: String = Language.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        // Resources are resolved before onCreate, so the stored language has to
        // be read synchronously here. It is a single small DataStore read on a
        // cold start, and there is no correct asynchronous alternative — an
        // Activity cannot re-resolve its resources later without recreating.
        val tag = runBlocking { Graph.get(newBase).settings.current().language }
        startedWithLanguage = tag
        super.attachBaseContext(Language.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HermesTheme {
                val context = LocalContext.current
                val viewModel: AppViewModel = viewModel()
                val settings by viewModel.settings.collectAsStateWithLifecycle()

                // Applying a language change needs new resources, which means a
                // fresh Activity. Recreating is the supported way to do that.
                LaunchedEffect(settings.language) {
                    if (settings.language != startedWithLanguage) recreate()
                }

                var permissions by remember {
                    mutableStateOf(
                        PermissionState(
                            canNotify = SystemPermissions.canNotify(context),
                            batteryExempt = SystemPermissions.isExemptFromBatteryOptimization(context),
                        ),
                    )
                }

                fun refreshPermissions() {
                    permissions = PermissionState(
                        canNotify = SystemPermissions.canNotify(context),
                        batteryExempt = SystemPermissions.isExemptFromBatteryOptimization(context),
                    )
                }

                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { refreshPermissions() }

                // Asked for on first use rather than at launch: the microphone
                // is optional, and a permission dialog for a feature the user
                // has not reached is the kind of prompt people deny on reflex.
                val micLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> if (granted) viewModel.onMicrophoneGranted() }

                fun askForNotifications() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Pre-33 the grant is implicit; if the user turned it off
                        // it can only be restored from system settings.
                        SystemPermissions.openNotificationSettings(context)
                    }
                }

                // Ask once on first launch. An approval prompt the user never
                // sees is the single worst failure mode this app has, so it is
                // worth one dialog up front rather than waiting for a blocked run.
                LaunchedEffect(Unit) {
                    if (!permissions.canNotify) askForNotifications()
                }

                // The battery exemption is granted in a system screen, so the
                // result only shows up on the way back.
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.currentStateFlow.collect { state ->
                        if (state.isAtLeast(Lifecycle.State.RESUMED)) refreshPermissions()
                    }
                }

                val chat by viewModel.chat.collectAsStateWithLifecycle()
                LaunchedEffect(chat.isBusy) {
                    if (chat.isBusy) RunService.start(this@MainActivity) else RunService.stop(this@MainActivity)
                }

                HermesShell(
                    viewModel = viewModel,
                    permissions = permissions,
                    onRequestMicrophone = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onRequestNotifications = ::askForNotifications,
                    onRequestBackground = { SystemPermissions.requestBatteryExemption(context) },
                )
            }
        }
    }
}
