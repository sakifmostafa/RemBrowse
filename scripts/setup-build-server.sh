#!/usr/bin/env bash
# Sets up an Ubuntu machine (22.04 or later) to build, test and sign RemBrowse:
# a JDK, the Android SDK (command-line tools, platform 37, build tools 36.0.0, platform tools)
# and the environment variables Gradle needs. Safe to run again.
#
#   bash scripts/setup-build-server.sh
#
# ANDROID_HOME picks where the SDK goes (default ~/android-sdk).
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip" # Command-line tools 12.0
PACKAGES=(${SDK_PACKAGES:-platform-tools "platforms;android-37.0" "build-tools;36.0.0"})

if [ "${SKIP_APT:-0}" != "1" ]; then
  echo "==> Installing the JDK, git and unzip"
  sudo apt-get update -qq
  sudo apt-get install -y -qq openjdk-21-jdk-headless git unzip curl
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "==> Downloading the Android command-line tools into $SDK"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

echo "==> Accepting the SDK licences and installing: ${PACKAGES[*]}"
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses > /dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" "${PACKAGES[@]}" > /dev/null

if ! grep -q "# RemBrowse build environment" "$HOME/.bashrc" 2>/dev/null; then
  echo "==> Adding ANDROID_HOME to ~/.bashrc"
  cat >> "$HOME/.bashrc" << EOF

# RemBrowse build environment
export ANDROID_HOME="$SDK"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/build-tools/36.0.0:\$PATH"
EOF
fi

echo
echo "Done. Open a new shell (or run: source ~/.bashrc), then in the repository:"
echo "  ./gradlew testDebugUnitTest -Dorg.gradle.jvmargs=\"-Xmx4g -Dfile.encoding=UTF-8\""
echo "  ./gradlew publishApk"
echo "Release builds are signed with signing/rembrowse.jks when it is there (see docs/BUILD_AND_TEST_GUIDE.md)."
