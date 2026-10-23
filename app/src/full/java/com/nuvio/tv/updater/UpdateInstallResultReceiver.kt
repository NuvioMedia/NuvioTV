package com.nuvio.tv.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

private const val TAG = "UpdateInstall"

/**
 * Receives the outcome of an update install session. A confirmation request is shown only for installs the user
 * started; a background install that still needs confirmation is dropped and the downloaded APK waits for the banner.
 * A failed session is retried without dex metadata, then falls back to the system installer screen.
 */
class UpdateInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val background = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        val withDexMetadata = intent.getBooleanExtra(EXTRA_WITH_DEX_METADATA, false)
        val apkFile = intent.getStringExtra(EXTRA_APK_PATH)?.let(::File)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "Session $sessionId status=$status background=$background dm=$withDexMetadata message=$message")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (background) {
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                } else {
                    confirmationIntent(intent)?.let { confirm ->
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            else -> retryAfterFailure(context, apkFile, background, withDexMetadata)
        }
    }

    private fun retryAfterFailure(context: Context, apkFile: File?, background: Boolean, withDexMetadata: Boolean) {
        if (apkFile == null || !apkFile.exists()) return
        runCatching {
            when {
                withDexMetadata -> ApkInstaller.install(context, apkFile, background, withDexMetadata = false)
                !background -> ApkInstaller.launchSystemInstaller(context, apkFile)
            }
        }.onFailure { Log.w(TAG, "Update install retry failed", it) }
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val EXTRA_APK_PATH = "com.nuvio.tv.updater.APK_PATH"
        private const val EXTRA_BACKGROUND = "com.nuvio.tv.updater.BACKGROUND"
        private const val EXTRA_WITH_DEX_METADATA = "com.nuvio.tv.updater.WITH_DEX_METADATA"

        fun statusReceiver(
            context: Context,
            sessionId: Int,
            apkFile: File,
            background: Boolean,
            withDexMetadata: Boolean
        ): PendingIntent {
            val intent = Intent(context, UpdateInstallResultReceiver::class.java)
                .putExtra(EXTRA_APK_PATH, apkFile.absolutePath)
                .putExtra(EXTRA_BACKGROUND, background)
                .putExtra(EXTRA_WITH_DEX_METADATA, withDexMetadata)
            // The installer adds the status extras, so the intent has to stay mutable.
            val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
            )
        }
    }
}
