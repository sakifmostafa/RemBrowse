package com.rembrowser.rembrowse.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * What `./gradlew publishApk` writes next to the published APKs as `build/update.json`:
 * the version, and for each processor type the APK's file name, size and SHA-256.
 */
data class UpdateManifest(
    val versionName: String,
    val versionCode: Int,
    val apks: Map<String, UpdateApk>
)

data class UpdateApk(val file: String, val size: Long, val sha256: String)

/** A newer version this device can install: the APK for its processor, and where to get it. */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int,
    val abi: String,
    val url: String,
    val size: Long,
    val sha256: String
)

object UpdateManifests {

    /** Reads `update.json`; null when it is not one. */
    fun parse(json: String): UpdateManifest? = runCatching {
        val root = JSONObject(json)
        val apksJson = root.getJSONObject("apks")
        val apks = apksJson.keys().asSequence().mapNotNull { abi ->
            val apk = apksJson.optJSONObject(abi) ?: return@mapNotNull null
            val file = apk.optString("file").takeIf { it.isNotBlank() && '/' !in it } ?: return@mapNotNull null
            val sha = apk.optString("sha256").lowercase().takeIf { SHA256_HEX.matches(it) } ?: return@mapNotNull null
            val size = apk.optLong("size", -1L).takeIf { it > 0 } ?: return@mapNotNull null
            abi to UpdateApk(file, size, sha)
        }.toMap()
        UpdateManifest(
            versionName = root.getString("versionName"),
            versionCode = root.getInt("versionCode"),
            apks = apks
        ).takeIf { it.versionCode > 0 && it.apks.isNotEmpty() }
    }.getOrNull()

    /**
     * The update to offer: a version newer than [currentVersionCode], in the APK for the first
     * of the device's [supportedAbis] (most preferred first) that was published. Null when
     * there is nothing newer or no APK fits this device.
     */
    fun updateFor(
        manifest: UpdateManifest,
        currentVersionCode: Int,
        supportedAbis: List<String>,
        baseUrl: String
    ): AvailableUpdate? {
        if (manifest.versionCode <= currentVersionCode) return null
        val abi = supportedAbis.firstOrNull { it in manifest.apks } ?: return null
        val apk = manifest.apks.getValue(abi)
        return AvailableUpdate(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            abi = abi,
            url = baseUrl.trimEnd('/') + "/" + apk.file,
            size = apk.size,
            sha256 = apk.sha256
        )
    }

    private val SHA256_HEX = Regex("[0-9a-f]{64}")
}

/** Where updates come from. */
interface UpdateSource {
    suspend fun fetchManifest(): Result<UpdateManifest>

    /**
     * Downloads [update] to [target], reporting bytes received, and checks it is exactly the
     * file the manifest describes (size and SHA-256). A partial or wrong file is deleted.
     */
    suspend fun download(update: AvailableUpdate, target: File, onProgress: (Long) -> Unit): Result<File>
}

/** Thrown when a downloaded APK is not the file the manifest describes. */
class UpdateIntegrityException(message: String) : IOException(message)

/** `update.json` was fetched but is not one. */
class UnreadableUpdateException : IOException("Unreadable update information")

/** The update server answered with an error, such as 404 while nothing is published. */
class UpdateHttpException(val code: Int) : IOException("HTTP $code")

/**
 * Reads `update.json` and the APKs from the repository's published `build/` folder (raw
 * files on GitHub), which needs the repository to be public.
 */
class OkHttpUpdateSource(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = "RemBrowse (Android; GeckoView)"
) : UpdateSource {

    override suspend fun fetchManifest(): Result<UpdateManifest> = withContext(ioDispatcher) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/" + MANIFEST_FILE)
                .header("User-Agent", userAgent)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateHttpException(response.code)
                val body = response.body?.string() ?: throw IOException("Empty response")
                UpdateManifests.parse(body) ?: throw UnreadableUpdateException()
            }
        }
    }

    override suspend fun download(
        update: AvailableUpdate,
        target: File,
        onProgress: (Long) -> Unit
    ): Result<File> = withContext(ioDispatcher) {
        runCatching {
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            try {
                val request = Request.Builder().url(update.url).header("User-Agent", userAgent).build()
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw UpdateHttpException(response.code)
                    val body = response.body ?: throw IOException("Empty response")
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                received += read
                                if (received > update.size) throw UpdateIntegrityException("The download is larger than expected")
                                onProgress(received)
                            }
                        }
                    }
                }
                if (received != update.size) throw UpdateIntegrityException("The download is incomplete")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != update.sha256) throw UpdateIntegrityException("The download is damaged")
                if (!partial.renameTo(target)) throw IOException("Couldn't save the update")
                target
            } catch (t: Throwable) {
                partial.delete()
                throw t
            }
        }
    }

    companion object {
        const val MANIFEST_FILE = "update.json"
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/** What the updater remembers between launches. */
interface UpdatePreferences {
    var checkAutomatically: Boolean
    var lastCheckedMillis: Long?

    /** The version whose notice was closed, so the same version is not offered again unasked. */
    var dismissedVersionCode: Int
}

/** Remembers nothing beyond the process. */
class InMemoryUpdatePreferences(
    override var checkAutomatically: Boolean = true,
    override var lastCheckedMillis: Long? = null,
    override var dismissedVersionCode: Int = 0
) : UpdatePreferences

class SharedPrefsUpdatePreferences(context: Context) : UpdatePreferences {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var checkAutomatically: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    override var lastCheckedMillis: Long?
        get() = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L }
        set(value) = prefs.edit().putLong(KEY_LAST_CHECKED, value ?: 0L).apply()

    override var dismissedVersionCode: Int
        get() = prefs.getInt(KEY_DISMISSED, 0)
        set(value) = prefs.edit().putInt(KEY_DISMISSED, value).apply()

    private companion object {
        const val PREFS_NAME = "rembrowse_updates"
        const val KEY_AUTO = "check_automatically"
        const val KEY_LAST_CHECKED = "last_checked_millis"
        const val KEY_DISMISSED = "dismissed_version_code"
    }
}

/** What happened when a downloaded update was handed over for installing. */
sealed interface InstallOutcome {
    /** The system installer is showing its confirmation. */
    data object Started : InstallOutcome

    /**
     * RemBrowse may not install apps yet. The system screen to allow it was opened; installing
     * continues when the user comes back.
     */
    data object PermissionNeeded : InstallOutcome

    /** The file is not an update of this app from the same developer, or could not be opened. */
    data class Rejected(val message: String) : InstallOutcome
}

/** Hands a downloaded APK to the system installer. */
interface UpdateInstaller {
    /** Whether Android lets RemBrowse install apps. */
    fun canInstallPackages(): Boolean

    /** Checks [apk] and opens the system installer, or the screen to allow it. Slow: reads the whole file. */
    fun install(apk: File, update: AvailableUpdate): InstallOutcome
}
