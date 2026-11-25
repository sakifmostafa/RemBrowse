package com.rembrowser.rembrowse.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class AppUpdatesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = sha256(apkBytes)

    private fun manifestJson(
        versionCode: Int = 15,
        sha: String = apkSha,
        file: String = "RemBrowse-x86_64.apk",
        size: Long = apkBytes.size.toLong(),
        minSdk: Int = 33
    ) = """
        {
          "versionName": "0.13",
          "versionCode": $versionCode,
          "minSdk": $minSdk,
          "apks": {
            "arm64-v8a": {"file": "RemBrowse-arm64-v8a.apk", "size": 100, "sha256": "${"a".repeat(64)}"},
            "x86_64": {"file": "$file", "size": $size, "sha256": "$sha"}
          }
        }
    """.trimIndent()

    @Test
    fun parse_readsWhatPublishApkWrites() {
        val manifest = UpdateManifests.parse(manifestJson())
        assertNotNull(manifest)
        assertEquals("0.13", manifest!!.versionName)
        assertEquals(15, manifest.versionCode)
        assertEquals(33, manifest.minSdk)
        assertEquals(setOf("arm64-v8a", "x86_64"), manifest.apks.keys)
        assertEquals(UpdateApk("RemBrowse-x86_64.apk", 200_000L, apkSha), manifest.apks["x86_64"])
    }

    @Test
    fun parse_aManifestWithoutMinSdk_asBefore0160_needsNoParticularAndroid() {
        val json = manifestJson().lines().filterNot { "\"minSdk\"" in it }.joinToString("\n")
        val manifest = UpdateManifests.parse(json)!!
        assertEquals(0, manifest.minSdk)
        assertNotNull(UpdateManifests.updateFor(manifest, 14, listOf("x86_64"), BASE, deviceSdk = 26))
    }

    @Test
    fun updateFor_offersNothing_whenTheVersionNeedsANewerAndroidThanTheDevicesOwn() {
        // Android refuses to install an APK whose minSdk is above the device's version
        val manifest = UpdateManifests.parse(manifestJson(minSdk = 34))!!
        assertNull(UpdateManifests.updateFor(manifest, 14, listOf("x86_64"), BASE, deviceSdk = 33))
        assertNotNull("Its own minimum", UpdateManifests.updateFor(manifest, 14, listOf("x86_64"), BASE, deviceSdk = 34))
        assertNotNull(UpdateManifests.updateFor(manifest, 14, listOf("x86_64"), BASE, deviceSdk = 36))
    }

    @Test
    fun parse_dropsApksThatCouldPointElsewhereOrCannotBeChecked() {
        // A path in the file name could leave the published folder
        assertEquals(setOf("arm64-v8a"), UpdateManifests.parse(manifestJson(file = "../evil.apk"))!!.apks.keys)
        assertEquals(setOf("arm64-v8a"), UpdateManifests.parse(manifestJson(sha = "not-a-hash"))!!.apks.keys)
        assertEquals(setOf("arm64-v8a"), UpdateManifests.parse(manifestJson(size = 0))!!.apks.keys)
    }

    @Test
    fun parse_rejectsAnythingElse() {
        assertNull(UpdateManifests.parse("<html>404</html>"))
        assertNull(UpdateManifests.parse("""{"versionName": "1", "versionCode": 2, "apks": {}}"""))
        assertNull(UpdateManifests.parse(manifestJson(versionCode = 0)))
    }

    @Test
    fun updateFor_offersTheApkForThePreferredProcessorType() {
        val manifest = UpdateManifests.parse(manifestJson())!!
        val update = UpdateManifests.updateFor(
            manifest,
            currentVersionCode = 14,
            supportedAbis = listOf("x86_64", "arm64-v8a"),
            baseUrl = "https://example.test/build/",
            deviceSdk = 36
        )
        assertEquals("x86_64", update!!.abi)
        assertEquals("https://example.test/build/RemBrowse-x86_64.apk", update.url)
        assertEquals(apkSha, update.sha256)

        val arm = UpdateManifests.updateFor(manifest, 14, listOf("arm64-v8a", "armeabi-v7a"), "https://example.test/build", deviceSdk = 36)
        assertEquals("https://example.test/build/RemBrowse-arm64-v8a.apk", arm!!.url)
    }

    @Test
    fun updateFor_offersNothingWhenNotNewerOrNoApkFits() {
        val manifest = UpdateManifests.parse(manifestJson())!!
        assertNull(UpdateManifests.updateFor(manifest, 15, listOf("x86_64"), BASE, deviceSdk = 36))
        assertNull(UpdateManifests.updateFor(manifest, 16, listOf("x86_64"), BASE, deviceSdk = 36))
        assertNull(UpdateManifests.updateFor(manifest, 14, listOf("armeabi-v7a"), BASE, deviceSdk = 36))
    }

    @Test
    fun fetchManifest_readsTheFilesAtTheBranchsLatestCommit() = runTest {
        val requested = mutableListOf<String>()
        var accept: String? = null
        val source = source { url ->
            requested += url
            when (url) {
                COMMIT_API -> {
                    accept = request().header("Accept")
                    respond(200, "$SHA\n")
                }
                "$RAW/owner/repo/$SHA/build/update.json" -> respond(200, manifestJson())
                else -> respond(404, "Not Found")
            }
        }
        val manifest = source.fetchManifest().getOrThrow()

        assertEquals(15, manifest.versionCode)
        assertEquals(listOf(COMMIT_API, "$RAW/owner/repo/$SHA/build/update.json"), requested)
        assertEquals("Just the id, not a JSON commit", "application/vnd.github.sha", accept)
        // An address with the commit in it never changes, so no cache can serve an older file
        assertEquals("$RAW/owner/repo/$SHA/build/", manifest.filesBaseUrl)
        val update = UpdateManifests.updateFor(manifest, 14, listOf("x86_64"), "unused", deviceSdk = 36)
        assertEquals("$RAW/owner/repo/$SHA/build/RemBrowse-x86_64.apk", update!!.url)
    }

    @Test
    fun fetchManifest_onRemBrowsesOwnSite_readsTheFilesThere_withoutAskingGitHub() = runTest {
        val requested = mutableListOf<String>()
        var cacheControl: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                requested += url
                cacheControl = chain.request().header("Cache-Control")
                if (url.startsWith("https://downloads.test/rem/update.json?t=")) chain.respond(200, manifestJson()) else chain.respond(404, "Not Found")
            })
            .build()
        val source = OkHttpUpdateSource(
            hostedAt = "https://downloads.test/rem",
            client = client,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        val manifest = source.fetchManifest().getOrThrow()

        assertEquals(15, manifest.versionCode)
        assertEquals("One request, to the site itself", 1, requested.size)
        assertTrue("A query no cache has seen", requested.single().startsWith("https://downloads.test/rem/update.json?t="))
        assertEquals("no-cache", cacheControl)
        assertEquals("https://downloads.test/rem/", manifest.filesBaseUrl)
        val update = UpdateManifests.updateFor(manifest, 14, listOf("x86_64"), "unused", deviceSdk = 36)
        assertEquals("The APK beside update.json", "https://downloads.test/rem/RemBrowse-x86_64.apk", update!!.url)
    }

    @Test
    fun fetchManifest_readsTheBranch_whenTheApiCannotAnswer() = runTest {
        for (apiAnswer in listOf(403 to "rate limited", 200 to "<html>not a commit</html>")) {
            val requested = mutableListOf<String>()
            val source = source { url ->
                requested += url
                when (url) {
                    COMMIT_API -> respond(apiAnswer.first, apiAnswer.second)
                    "$RAW/owner/repo/main/build/update.json" -> respond(200, manifestJson())
                    else -> respond(404, "Not Found")
                }
            }
            val manifest = source.fetchManifest().getOrThrow()
            assertEquals(15, manifest.versionCode)
            assertEquals("$RAW/owner/repo/main/build/", manifest.filesBaseUrl)
            assertEquals(listOf(COMMIT_API, "$RAW/owner/repo/main/build/update.json"), requested)
        }
    }

    @Test
    fun fetchManifest_reportsServerErrorsAndUnreadableFiles() = runTest {
        val missing = source { url -> if (url == COMMIT_API) respond(200, SHA) else respond(404, "Not Found") }
            .fetchManifest().exceptionOrNull()
        assertTrue(missing is UpdateHttpException && missing.code == 404)

        val garbled = source { url -> if (url == COMMIT_API) respond(200, SHA) else respond(200, "<html></html>") }
            .fetchManifest().exceptionOrNull()
        assertTrue(garbled is UnreadableUpdateException)
    }

    @Test
    fun download_savesTheFileOnlyWhenItMatchesTheManifest() = runTest {
        val target = folder.root.resolve("updates/RemBrowse.apk")
        val progress = mutableListOf<Long>()
        val result = source { respondBytes(apkBytes) }.download(update(), target) { progress += it }

        assertEquals(target, result.getOrThrow())
        assertTrue(target.readBytes().contentEquals(apkBytes))
        assertFalse(folder.root.resolve("updates/RemBrowse.apk.part").exists())
        assertEquals(apkBytes.size.toLong(), progress.last())
    }

    @Test
    fun download_deletesAFileThatIsDamagedTruncatedOrTooLarge() = runTest {
        val target = folder.root.resolve("updates/RemBrowse.apk")
        val damaged = apkBytes.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val cases = listOf(damaged, apkBytes.copyOf(apkBytes.size - 10), apkBytes + ByteArray(10))
        for (body in cases) {
            val error = source { respondBytes(body) }.download(update(), target) { }.exceptionOrNull()
            assertTrue("$error", error is UpdateIntegrityException)
            assertFalse(target.exists())
            assertTrue(target.parentFile!!.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun download_reportsServerErrors() = runTest {
        val target = folder.root.resolve("updates/RemBrowse.apk")
        val error = source { respond(500, "oops") }.download(update(), target) { }.exceptionOrNull()
        assertTrue(error is UpdateHttpException && error.code == 500)
        assertFalse(target.exists())
    }

    private fun update() = AvailableUpdate(
        versionName = "0.13",
        versionCode = 15,
        abi = "x86_64",
        url = "$BASE/RemBrowse-x86_64.apk",
        size = apkBytes.size.toLong(),
        sha256 = apkSha
    )

    /** An update source whose network answers with [handler], given the requested address. */
    private fun source(handler: Interceptor.Chain.(url: String) -> Response): OkHttpUpdateSource {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> chain.handler(chain.request().url.toString()) })
            .build()
        return OkHttpUpdateSource(
            repository = "owner/repo",
            client = client,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            apiBaseUrl = "https://api.test",
            rawBaseUrl = RAW
        )
    }

    private fun Interceptor.Chain.respond(code: Int, body: String): Response = Response.Builder()
        .request(request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun Interceptor.Chain.respondBytes(bytes: ByteArray): Response = Response.Builder()
        .request(request())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(bytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
        .build()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val BASE = "https://example.test/build"
        const val RAW = "https://raw.test"
        const val COMMIT_API = "https://api.test/repos/owner/repo/commits/main"
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
