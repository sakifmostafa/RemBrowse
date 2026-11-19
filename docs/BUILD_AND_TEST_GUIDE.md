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

Run all **98 tests** (JVM unit tests, Robolectric persistence and headless Jetpack Compose UI tests):

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
* `UrlResolverTest` — address vs. search detection, display form, favicon location, extension link validation.
* `KeyboardShortcutsTest` — shortcut mapping, key-repeat suppression, modifier handling.
* `ErrorPagesTest` — error page content and HTML escaping of the failed address.
* `AmoJsonParserTest` — AMO v5 response parsing, locale fallbacks, duplicate and insecure entry filtering.
* `BrowserViewModelTest` — layouts, lazy session creation, tab closing, new-window requests, split ratios, prompts, bookmarks, settings.
* `ExtensionsViewModelTest` — catalog loading and cancellation, install validation, cancel and failure handling.
* `CommandPaletteResultsTest` — what the command palette offers for a typed query.
* `ThemeContrastTest` — WCAG contrast of every theme and accent combination.
* `BrowserStorageRobolectricTest` — real Android `SharedPreferences` round trip, including data from v0.01.
* `RemBrowseUiTest` / `CompactWindowUiTest` — the real Compose screen on a laptop-sized and a phone-sized window.

### Rendering the UI without a device

`UiSnapshotTest` renders the main screens (start page, browsing, tab switcher, split view, command palette, settings, light theme, compact layout) to PNG files. It is skipped unless you name a target directory:

```bash
./gradlew testDebugUnitTest --tests "*UiSnapshotTest*" -Prembrowse.screenshots=build/ui-screenshots
```

The images land in `app/build/ui-screenshots/`. Web content is a placeholder, because the real engine cannot run on the JVM, and dialogs are drawn at the top-left corner without the dimmed backdrop a device shows.

---

## 4. Building the APKs

Every build produces **one APK per processor type** (`arm64-v8a` and `x86_64`).

### Publishing the "Download latest" APKs

```bash
./gradlew publishApk --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

This builds the release variant and copies the result to the repository's `build/` folder under stable names, so the README links never need to change:

```text
build/RemBrowse-arm64-v8a.apk
build/RemBrowse-x86_64.apk
build/VERSION.txt
```

Commit those three files to publish a new version. Release APKs are about 85–90 MB: the native engine is stored compressed and unused library code is removed (shrink only; nothing is renamed or optimised, see `app/proguard-rules.pro`). Each file must stay under GitHub's 100 MB limit.

### Debug builds

```bash
./gradlew assembleDebug --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

Output (about 190–210 MB each, uncompressed for fast incremental builds):
```text
app/build/outputs/apk/debug/RemBrowse-v0.03-arm64-v8a-debug.apk
app/build/outputs/apk/debug/RemBrowse-v0.03-x86_64-debug.apk
```

### Verifying an APK

```bash
APK="build/RemBrowse-x86_64.apk"
"$HOME/Android/Sdk/build-tools/35.0.0/aapt2" dump badging "$APK" | grep -E 'package:|targetSdkVersion:|application-label:|native-code:'
"$HOME/Android/Sdk/build-tools/35.0.0/apksigner" verify --verbose "$APK"
```

---

## 5. Signing & Versioning Rules

* **Persistent keystore**: when `signing/rembrowse.jks` exists (4096-bit RSA, alias `rembrowse`; passwords from `REMBROWSE_STORE_PASSWORD` / `REMBROWSE_KEY_PASSWORD`), debug and release builds are signed with it. Keeping this keystore stable lets installed builds upgrade in place. It is intentionally not committed.
* **Without the keystore** (a fresh clone, CI, a cloud build), builds are signed with that machine's debug key so they remain installable. Android will not install such a build over one signed with a different key: uninstall the old copy first. **Publish from a machine that has the keystore** if you want downloads to update in place.
* **Version increments**: for each new release, update `versionName` (e.g. `"0.04"`) and **always increment `versionCode`** (`5` → `6` …) in `app/build.gradle.kts` to avoid `INSTALL_FAILED_VERSION_DOWNGRADE`. APK names and `build/VERSION.txt` follow `versionName` automatically; update the version shown in the README's download section.
* **Processor types**: `arm64-v8a` covers ARM phones, tablets and laptops; `x86_64` covers Intel/AMD Android laptops.
