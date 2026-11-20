import com.android.build.api.artifact.SingleArtifact
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rembrowser.rembrowse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rembrowser.rembrowse"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.13.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the app looks for new versions: the APKs and update.json that publishApk
        // writes, as raw files from the repository (which must be public for this to work)
        buildConfigField(
            "String",
            "UPDATE_BASE_URL",
            "\"https://raw.githubusercontent.com/sakifmostafa/RemBrowse/main/build/\""
        )
    }

    // One APK per CPU type instead of a single 380 MB package carrying both engines.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    val keystoreFile = rootProject.file("signing/rembrowse.jks")
    signingConfigs {
        create("rembrowseKey") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("REMBROWSE_STORE_PASSWORD") ?: "rembrowse-dev-key"
                keyAlias = "rembrowse"
                keyPassword = System.getenv("REMBROWSE_KEY_PASSWORD") ?: "rembrowse-dev-key"
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("rembrowseKey")
            }
        }
        release {
            // Shrink only (see proguard-rules.pro): removes unused library code, mostly the
            // bundled icon set, without renaming or rewriting anything.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            // Without the project keystore the build is signed with the local debug key so it
            // can still be installed. Such a build cannot update one signed with the real key.
            signingConfig = if (keystoreFile.exists()) {
                signingConfigs.getByName("rembrowseKey")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                // Opt-in UI renders: ./gradlew testDebugUnitTest -Prembrowse.screenshots=build/ui-screenshots
                (project.findProperty("rembrowse.screenshots") as String?)?.let { dir ->
                    test.systemProperty("rembrowse.screenshots", project.file(dir).absolutePath)
                    test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                }
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    // Release APKs store the native engine compressed, which halves the download.
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
    // Every build ships GeckoView with the proxy.settings patch (see PatchGeckoOmniJa)
    onVariants { variant ->
        val patch = tasks.register<PatchGeckoOmniJa>("patch${variant.name.replaceFirstChar(Char::uppercase)}GeckoOmniJa")
        variant.artifacts.use(patch)
            .wiredWithDirectories(PatchGeckoOmniJa::inputAssets, PatchGeckoOmniJa::outputAssets)
            .toTransform(SingleArtifact.ASSETS)
    }
}

// Builds the installable APKs and copies them to <repo>/build under stable names, ready to be
// committed, with update.json for the app's update check. The README's "Download latest" links
// point at these files.
tasks.register<Copy>("publishApk") {
    group = "distribution"
    description = "Builds release APKs and copies them to the repository's build/ folder."
    dependsOn("assembleRelease")
    val versionName = android.defaultConfig.versionName
    val versionCode = android.defaultConfig.versionCode
    val target = rootProject.layout.projectDirectory.dir("build")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { it.replace("-v$versionName", "").replace("-release", "") }
    }
    into(target)
    doLast {
        val dir = target.asFile
        dir.resolve("VERSION.txt").writeText("RemBrowse $versionName (versionCode $versionCode)\n")
        // What the app's update check reads: the version, and each APK's size and SHA-256 so a
        // download can be checked before it is installed
        val apks = dir.listFiles { file -> file.name.startsWith("RemBrowse-") && file.name.endsWith(".apk") }
            .orEmpty()
            .sortedBy { it.name }
            .joinToString(",\n") { apk ->
                val abi = apk.name.removePrefix("RemBrowse-").removeSuffix(".apk")
                val digest = MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                val sha = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                "    \"$abi\": {\"file\": \"${apk.name}\", \"size\": ${apk.length()}, \"sha256\": \"$sha\"}"
            }
        dir.resolve("update.json").writeText(
            "{\n  \"versionName\": \"$versionName\",\n  \"versionCode\": $versionCode,\n  \"apks\": {\n$apks\n  }\n}\n"
        )
    }
}

base {
    // Follows versionName so the APK name never drifts from the version it contains
    archivesName.set("RemBrowse-v${android.defaultConfig.versionName}")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Mozilla GeckoView — real browser engine with native WebExtensions (.xpi)
    implementation("org.mozilla.geckoview:geckoview:138.0.20250517143237")

    // Networking & extension icon loading
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Unit & Headless Robolectric + Compose UI Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * Lets extensions use the WebExtensions `proxy.settings` API, which GeckoView refuses on Android.
 *
 * Firefox's code for the API (`toolkit/components/extensions/parent/ext-proxy.js`) throws
 * "proxy.settings is not supported on android." before doing anything on Android (Mozilla bug
 * 1725981). VPN extensions such as Proton VPN use the API to take control of the proxy
 * setting, then route each request through `proxy.onRequest`, which Android does support; the
 * refusal stops them from connecting at all. This rewrites that one script inside GeckoView's
 * `omni.ja` so its two Android-only refusals are skipped, and gives a preference that only
 * desktop Firefox defines a default where the script reads it (see [PATCHES]). Every other
 * entry of the archive is
 * copied unchanged, in the same order and with the same compression method.
 *
 * The task checks its own result and fails the build if the code it expects is not found
 * exactly once, so a GeckoView update that changes the script is noticed instead of shipping
 * unpatched.
 */
abstract class PatchGeckoOmniJa : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputAssets: DirectoryProperty

    @get:OutputDirectory
    abstract val outputAssets: DirectoryProperty

    @TaskAction
    fun patch() {
        val input = inputAssets.get().asFile
        val output = outputAssets.get().asFile
        output.deleteRecursively()
        input.copyRecursively(output)
        val source = File(input, OMNI_JA)
        check(source.isFile) { "GeckoView's $OMNI_JA is missing from the merged assets" }
        val target = File(output, OMNI_JA)
        rewriteOmniJa(source, target)
        verify(source, target)
    }

    private fun rewriteOmniJa(source: File, target: File) {
        var patched = 0
        ZipFile(source).use { zip ->
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                out.setLevel(Deflater.BEST_COMPRESSION)
                for (entry in zip.entries()) {
                    var bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == PROXY_SCRIPT) {
                        bytes = patchProxyScript(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                        patched++
                    }
                    out.putNextEntry(copyOf(entry, bytes))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        check(patched == 1) { "$PROXY_SCRIPT was not found in $OMNI_JA" }
    }

    /**
     * A new entry like [entry] for [bytes], with every size and checksum filled in up front so
     * no data descriptors are written: the archive keeps the plain layout Gecko's own build
     * produces.
     */
    private fun copyOf(entry: ZipEntry, bytes: ByteArray): ZipEntry = ZipEntry(entry.name).apply {
        time = entry.time
        method = entry.method
        size = bytes.size.toLong()
        crc = CRC32().apply { update(bytes) }.value
        compressedSize = if (entry.method == ZipEntry.STORED) bytes.size.toLong() else deflatedSize(bytes)
    }

    private fun deflatedSize(bytes: ByteArray): Long {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (!deflater.finished()) total += deflater.deflate(buffer)
            return total
        } finally {
            deflater.end()
        }
    }

    /** Reads every entry back (which checks its CRC) and checks only the proxy script changed. */
    private fun verify(source: File, target: File) {
        ZipFile(source).use { before ->
            ZipFile(target).use { after ->
                val names = before.entries().toList().map { it.name }
                check(after.entries().toList().map { it.name } == names) { "Patched $OMNI_JA lost or reordered entries" }
                for (name in names) {
                    val old = before.getInputStream(before.getEntry(name)).use { it.readBytes() }
                    val new = after.getInputStream(after.getEntry(name)).use { it.readBytes() }
                    if (name == PROXY_SCRIPT) {
                        val text = new.toString(Charsets.UTF_8)
                        check(PATCHES.all { (from, to) -> from !in text && to in text }) {
                            "Patched $PROXY_SCRIPT still refuses proxy.settings on Android"
                        }
                    } else {
                        check(old.contentEquals(new)) { "Patched $OMNI_JA changed $name" }
                    }
                }
            }
        }
    }

    companion object {
        const val OMNI_JA = "omni.ja"
        const val PROXY_SCRIPT = "chrome/toolkit/content/extensions/parent/ext-proxy.js"
        const val PATCH_MARKER = "RemBrowse: proxy.settings is allowed on Android"

        /** What is replaced in Firefox 138's ext-proxy.js; each is expected exactly once. */
        val PATCHES = listOf(
            // The refusal in validate(), which get(), set(), clear() and onChange all run
            "if (AppConstants.platform == \"android\") {" to "if (false /* $PATCH_MARKER */) {",
            // The refusal in set()
            "if (AppConstants.platform === \"android\") {" to "if (false /* $PATCH_MARKER */) {",
            // get() reads a preference that only desktop Firefox defines. On Android the read
            // throws, so get() fails and an extension that checks its control after set()
            // (Proton VPN does) gives up. Its desktop default is false.
            "getBoolPref(\n                  \"network.proxy.share_proxy_settings\"\n                )" to
                "getBoolPref(\n                  \"network.proxy.share_proxy_settings\",\n" +
                "                  false /* RemBrowse: defined only on desktop */\n                )",
            // set() asks the enterprise policy service, which Android builds do not include, so
            // Services.policies is null there and the call throws. Gecko's own code guards it
            // the same way (Services.policies && ...).
            "if (!Services.policies.isAllowed(\"changeProxySettings\")) {" to
                "if (Services.policies && !Services.policies.isAllowed(\"changeProxySettings\")) {"
        )

        fun patchProxyScript(script: String): String = PATCHES.fold(script) { text, (from, to) ->
            val found = text.split(from).size - 1
            check(found == 1) {
                "Expected `$from` once in $PROXY_SCRIPT but found it $found times. " +
                    "GeckoView's proxy code changed: review the patch in app/build.gradle.kts."
            }
            text.replace(from, to)
        }
    }
}
