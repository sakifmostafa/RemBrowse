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
        versionCode = 6
        versionName = "0.04"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

// Builds the installable APKs and copies them to <repo>/build under stable names, ready to be
// committed. The README's "Download latest" links point at these files.
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
        target.file("VERSION.txt").asFile.writeText("RemBrowse $versionName (versionCode $versionCode)\n")
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
