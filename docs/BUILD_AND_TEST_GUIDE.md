# RemBrowse Build & Test Guide

This guide covers building, testing, and signing **RemBrowse** (`com.rembrowser.rembrowse`) on Android Laptop / Googlebook OS (Debian Linux VM) or any Linux/macOS/Windows workstation.

---

## 1. Prerequisites

* **JDK 21** (OpenJDK 21; project compiles bytecode targeting Java 17):
  ```bash
  sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless unzip
  ```
* **Android CLI** (`android`) & Android SDK 35:
  ```bash
  curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
  android sdk install platforms/android-35 build-tools/35.0.0 platform-tools
  ```
* Ensure `local.properties` points to your Android SDK directory:
  ```properties
  sdk.dir=/home/droid/Android/Sdk
  ```

---

## 2. Shared Storage (`virtiofs`) Cache Optimization

When working inside `/storage/emulated/10/...` on Android Laptop / Googlebook OS, always redirect Gradle's project cache to the VM's native `ext4` disk (`$HOME/.cache/projects/...`) to avoid FUSE lock contention and slow metadata I/O:

```bash
PROJECT_CACHE_DIR="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

> **Memory Tip (4 GB VM)**: Run `testDebugUnitTest` and `assembleDebug` as separate commands with `--no-daemon` so Robolectric's test worker and Gradle's packaging task do not compete for RAM simultaneously.

---

## 3. Running Automated Tests

Run all **28 tests** (JVM unit tests + Robolectric persistence + headless Jetpack Compose UI tests):

```bash
./gradlew testDebugUnitTest --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

Run a single test class:

```bash
./gradlew testDebugUnitTest --no-daemon \
  --tests "com.rembrowser.rembrowse.viewmodel.BrowserViewModelTest" \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

Test suites included:
* `UrlResolverTest` — URL/domain normalization, localhost/IP handling, DuckDuckGo search fallback, `.xpi` URI validation.
* `AmoJsonParserTest` — AMO v5 JSON response parsing, locale fallback, malformed entry filtering.
* `BrowserViewModelTest` — Layout switching (`1x1`/`1x2`/`2x1`/`2x2`), focused tab retention, multi-pane compositor visibility, split ratio clamping/reset, pane swap, maximize, desktop mode, bookmarks.
* `ExtensionsViewModelTest` — AMO featured/search loading, `.xpi` URL validation & installation, enable/disable/uninstall.
* `BrowserStorageRobolectricTest` — Real Android `SharedPreferences` JSON persistence round-trip.
* `RemBrowseUiTest` — Headless Compose UI verification of layout switcher, Tab Bar tab switching, and Extensions dialog.

---

## 4. Building & Verifying the Signed APK

```bash
./gradlew assembleDebug --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

Output path:
```text
app/build/outputs/apk/debug/RemBrowse-v0.01-debug.apk
```

Verify APK badging, ABIs (`arm64-v8a`, `x86_64`), and v2 signature:

```bash
APK="app/build/outputs/apk/debug/RemBrowse-v0.01-debug.apk"
"$HOME/Android/Sdk/build-tools/35.0.0/aapt2" dump badging "$APK" | grep -E 'package:|targetSdkVersion:|application-label:|native-code:'
"$HOME/Android/Sdk/build-tools/35.0.0/apksigner" verify --verbose "$APK"
```

---

## 5. Signing & Versioning Rules

* **Persistent Keystore**: Builds use `signing/rembrowse.jks` (4096-bit RSA, alias `rembrowse`). Keeping this keystore stable lets you upgrade installed builds in place without uninstalling.
* **Version Increments**: For each new release, update `versionName` (e.g., `"0.02"`, `"0.03"`) and **always increment `versionCode` monotonically** (`3` → `4` → `5`...) in `app/build.gradle.kts` to avoid `INSTALL_FAILED_VERSION_DOWNGRADE`.
* **64-Bit ABIs**: `abiFilters` includes both `arm64-v8a` and `x86_64` for full compatibility across ARM64 Android devices and x86_64 Android Laptops / Googlebooks.
