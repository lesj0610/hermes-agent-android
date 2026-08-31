package io.github.lesj0610.hermes.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.lesj0610.hermes.net.Release
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where an update has got to. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float) : UpdateState

    /** Downloaded and verified; waiting for the user to confirm the install. */
    data class Ready(val release: Release, val file: File) : UpdateState
    /** Carries the release so a retry does not have to check again. */
    data class Failed(val release: Release, val reason: Reason) : UpdateState

    enum class Reason { Download, Signature, Permission }
}

/**
 * Fetches a release APK and hands it to the system installer.
 *
 * The app never installs anything itself — it cannot. It downloads a file and
 * raises the ordinary install intent, and Android shows its own confirmation
 * with the package name and the permissions. Declining there declines the
 * update; nothing here can proceed past it.
 *
 * Two checks happen before the intent is raised, both of which Android would
 * also make. Doing them first turns a confusing system refusal into a message
 * that says what went wrong:
 *
 *  - the download must be signed by the same key as the running app, so a
 *    substituted file is refused before the user is asked about it;
 *  - the app must hold permission to request installs, and when it does not
 *    the user is sent to the settings page that grants it.
 */
class Updater(private val context: Context) {

    private val directory: File
        get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** What came of a download. The two failures need different words. */
    sealed interface Result {
        data class Ok(val file: File) : Result
        data object Failed : Result
        data object WrongSignature : Result
    }

    /** Downloads [release], reporting progress to [onProgress]. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): Result =
        withContext(Dispatchers.IO) {
            // One file per version, and the directory is emptied first: a
            // half-written APK from an interrupted attempt must never be the
            // thing that gets installed.
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "hermes-${release.version}.apk")

            val ok = runCatching {
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val total = release.apkBytes.takeIf { it > 0 }
                            ?: connection.contentLength.toLong()
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                connection.disconnect()
            }.isSuccess

            if (!ok) {
                target.delete()
                return@withContext Result.Failed
            }
            if (!signedLikeUs(target)) {
                // Deleted rather than kept for the user to inspect: a file this
                // app downloaded and could not vouch for should not be sitting
                // in a directory it hands out read grants to.
                target.delete()
                return@withContext Result.WrongSignature
            }
            Result.Ok(target)
        }

    /**
     * Whether [apk] carries the same signing certificate as the running app.
     *
     * Android refuses a same-package install signed by a different key, so this
     * changes no outcome — it only means the refusal is explained here instead
     * of arriving as a bare system error after a download the user waited for.
     */
    fun signedLikeUs(apk: File): Boolean = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val downloaded = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return false
        // The archive must also be this app, not merely something signed the
        // same way.
        if (downloaded.packageName != context.packageName) return false

        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        digests(downloaded.signatures()) == digests(installed.signatures())
    }.getOrDefault(false)

    private fun android.content.pm.PackageInfo.signatures(): Array<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            signatures ?: emptyArray()
        }

    /** Order-independent, so a multiply-signed APK compares by content. */
    private fun digests(signatures: Array<Signature>): Set<String> =
        signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()

    /** Whether the system will let the app raise an install at all. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /**
     * Raises the system installer for [apk].
     *
     * The grant is per-Uri and read-only, and expires with the intent: the
     * installer can read this one file and nothing else in the cache.
     */
    fun requestInstall(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** Sends the user to the page that grants install permission. */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Called once an install has been raised: the file is no longer needed. */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }
}
