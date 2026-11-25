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
 * the version, the oldest Android it installs on, and for each processor type the APK's file
 * name, size and SHA-256.
 */
data class UpdateManifest(
    val versionName: String,
    val versionCode: Int,
    val apks: Map<String, UpdateApk>,
    /** Where the APKs named in [apks] were published, when the source knows; null for its usual place. */
    val filesBaseUrl: String? = null,
    /**
     * The oldest Android version (API level) the APKs install on: their minSdk. 0 when the file
     * doesn't say, as before 0.16.0, which installed on every version RemBrowse ran on.
     */
    val minSdk: Int = 0
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
            apks = apks,
            minSdk = if (root.has("minSdk")) root.getInt("minSdk") else 0
        ).takeIf { it.versionCode > 0 && it.apks.isNotEmpty() }
    }.getOrNull()

    /**
     * The update to offer: a version newer than [currentVersionCode], in the APK for the first
     * of the device's [supportedAbis] (most preferred first) that was published. The APK is
     * fetched from where the manifest was read ([UpdateManifest.filesBaseUrl]), or else from
     * [baseUrl]. Null when there is nothing newer, no APK fits this device, or the version needs
     * a newer Android than the device's [deviceSdk] (Android would refuse to install it).
     */
    fun updateFor(
        manifest: UpdateManifest,
        currentVersionCode: Int,
        supportedAbis: List<String>,
        baseUrl: String,
        deviceSdk: Int
    ): AvailableUpdate? {
        if (manifest.versionCode <= currentVersionCode) return null
        if (manifest.minSdk > deviceSdk) return null
        val abi = supportedAbis.firstOrNull { it in manifest.apks } ?: return null
        val apk = manifest.apks.getValue(abi)
        return AvailableUpdate(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            abi = abi,
            url = (manifest.filesBaseUrl ?: baseUrl).trimEnd('/') + "/" + apk.file,
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
 * Reads `update.json`, and the APKs beside it, from a web address: [hostedAt] when set (RemBrowse's
 * own download site), otherwise the repository's published `build/` folder as raw files on
 * GitHub, which needs the repository to be public.
 *
 * At [hostedAt], `update.json` is asked for with a changing query string and `no-cache`, so a
 * cache in front of the site cannot hand back the previous release; the APKs are checked against
 * the manifest's SHA-256 whatever the cache does.
 *
 * GitHub's raw files are cached for up to 5 minutes, whatever the request asks, so a check made
 * just after a release could still see the previous one, and an APK fetched then could be the
 * previous build, failing its checksum. So the files are read at the branch's latest commit,
 * asked of GitHub's API first (one small request): an address with a commit in it never changes
 * and is never stale. If the API cannot answer (offline, or past its limit of 60 requests an hour
 * without an account), the branch itself is read, as before.
 *
 * @param repository `owner/name` on GitHub.
 */
class OkHttpUpdateSource(
    private val repository: String = "",
    /** The address `update.json` and the APKs are published at, ending in `/`; null for GitHub. */
    private val hostedAt: String? = null,
    private val branch: String = "main",
    private val folder: String = "build",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = "RemBrowse (Android; GeckoView)",
    private val apiBaseUrl: String = "https://api.github.com",
    private val rawBaseUrl: String = "https://raw.githubusercontent.com"
) : UpdateSource {

    override suspend fun fetchManifest(): Result<UpdateManifest> = withContext(ioDispatcher) {
        runCatching {
            val filesBaseUrl: String
            val manifestUrl: String
            if (hostedAt != null) {
                filesBaseUrl = hostedAt.trimEnd('/') + "/"
                manifestUrl = filesBaseUrl + MANIFEST_FILE + "?t=" + System.currentTimeMillis()
            } else {
                val commit = runCatching { latestCommit() }.getOrNull()
                filesBaseUrl = "${rawBaseUrl.trimEnd('/')}/$repository/${commit ?: branch}/$folder/"
                manifestUrl = filesBaseUrl + MANIFEST_FILE
            }
            val request = Request.Builder()
                .url(manifestUrl)
                .header("User-Agent", userAgent)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateHttpException(response.code)
                val body = response.body.string()
                val manifest = UpdateManifests.parse(body) ?: throw UnreadableUpdateException()
                manifest.copy(filesBaseUrl = filesBaseUrl)
            }
        }
    }

    /** The branch's latest commit, from GitHub's API, which answers with just its 40-character id. */
    private fun latestCommit(): String {
        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/repos/$repository/commits/$branch")
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github.sha")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateHttpException(response.code)
            val sha = response.body.string().trim()
            if (!COMMIT_SHA.matches(sha)) throw IOException("Not a commit id")
            sha
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
                    val body = response.body
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
        private val COMMIT_SHA = Regex("[0-9a-f]{40}")
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
