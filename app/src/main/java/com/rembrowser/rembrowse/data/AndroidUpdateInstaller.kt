package com.rembrowser.rembrowse.data

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * Installs a downloaded update through the system installer, which asks the user to confirm.
 *
 * Before handing it over it checks the file is this app, newer than the copy installed, and
 * signed by the same key. Android refuses an update signed with another key anyway, but only
 * with "App not installed"; this says why instead.
 */
class AndroidUpdateInstaller(context: Context) : UpdateInstaller {

    private val context = context.applicationContext

    override fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun install(apk: File, update: AvailableUpdate): InstallOutcome {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk)
            ?: return InstallOutcome.Rejected("The download isn't an app Android can install.")
        if (archive.packageName != context.packageName) {
            return InstallOutcome.Rejected("The download isn't RemBrowse.")
        }
        val installed = installedInfo(pm)
        if (installed != null && PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return InstallOutcome.Rejected("The download isn't newer than the RemBrowse you have.")
        }
        if (installed != null && !sameDeveloper(installed = installed, update = archive)) {
            return InstallOutcome.Rejected(
                "This update is signed with a different key than the RemBrowse you have, so " +
                    "Android won't install it over your copy."
            )
        }

        if (!canInstallPackages()) {
            val allow = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(allow)
                InstallOutcome.PermissionNeeded
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No screen to allow installing apps", e)
                InstallOutcome.Rejected("Allow RemBrowse to install apps in Android's settings, then try again.")
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}$PROVIDER_SUFFIX", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            InstallOutcome.Started
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No installer for ${update.versionName}", e)
            InstallOutcome.Rejected("This device has no app installer that RemBrowse can open.")
        }
    }

    // The signatures are only compared with this app's own, never trusted on their own
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        runCatching { pm.getPackageArchiveInfo(apk.path, SIGNER_FLAGS) }.getOrNull()

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo? =
        runCatching { pm.getPackageInfo(context.packageName, SIGNER_FLAGS) }.getOrNull()

    /**
     * Whether Android will accept [update] over [installed]: the installed copy's signer is
     * among the update's (its current signers, plus any it rotated from). When the signers
     * cannot be read, the system installer is left to decide.
     */
    private fun sameDeveloper(installed: PackageInfo, update: PackageInfo): Boolean {
        val current = currentSigners(installed) ?: return true
        val accepted = allSigners(update) ?: return true
        return accepted.containsAll(current)
    }

    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String>? {
        val signing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo else null
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && signing != null) {
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners?.toList()
            } else {
                // Oldest first: the last is the key it is signed with now
                signing.signingCertificateHistory?.lastOrNull()?.let(::listOf)
            }
        } else {
            info.signatures?.toList()
        }
        return signatures?.digests()
    }

    @Suppress("DEPRECATION")
    private fun allSigners(info: PackageInfo): Set<String>? {
        val signing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo else null
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && signing != null) {
            signing.apkContentsSigners.orEmpty().toList() + signing.signingCertificateHistory.orEmpty().toList()
        } else {
            info.signatures?.toList()
        }
        return signatures?.digests()
    }

    private fun List<Signature>.digests(): Set<String>? =
        takeIf { it.isNotEmpty() }?.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }

    private companion object {
        const val TAG = "UpdateInstaller"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        /** The FileProvider declared in the manifest, which also shares updates/ in the cache. */
        const val PROVIDER_SUFFIX = ".downloads"

        // Android 9 and 10 only read an archive's certificates when the old flag is set too
        @SuppressLint("PackageManagerGetSignatures")
        @Suppress("DEPRECATION")
        val SIGNER_FLAGS = PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    }
}
