#!/usr/bin/env bash
# Publishes RemBrowse to the download site's web root (default ~/RemBrowse/www, served at
# https://rembrowse.com/ by deploy/site-host): the page from site/, and the release that
# ./gradlew publishApk wrote to build/ (both APKs and update.json).
#
#   bash scripts/publish-to-site.sh               # the page and the release
#   bash scripts/publish-to-site.sh --site-only   # only the page (index.html, assets, icons)
#   bash scripts/publish-to-site.sh --allow-older # publish even if the site has a newer version
#   bash scripts/publish-to-site.sh --skip-virustotal # publish without build/virustotal.json
#
# Before the site changes, the release is copied next to it and checked: update.json lists exactly
# RemBrowse-arm64-v8a.apk and RemBrowse-x86_64.apk with their sizes and SHA-256, each APK is this
# app at update.json's version with only its own processor's engine, and each is signed with the
# RemBrowse key only. Then the page goes up, and the release goes live as three renames: the APKs,
# then virustotal.json (the scan from scripts/virustotal-scan.sh, which must be for this version and
# these SHA-256s), then update.json. A phone never sees a version before its APK is fully there, and never
# downloads a half-written file.
set -euo pipefail

cd "$(dirname "$0")/.."
WWW="${REMBROWSE_WWW:-$HOME/RemBrowse/www}"
CHECK_URL="${REMBROWSE_SITE_CHECK_URL:-http://127.0.0.1:8474/}"
PACKAGE="com.rembrowser.rembrowse"
CERT_SHA256="f1a5306a47e4be78ad7fb0c1418871e3a6c9a8351349e24239db20b475b2be06"
ABIS=(arm64-v8a x86_64)

SITE_ONLY=0
ALLOW_OLDER=0
SKIP_VT=0
for arg in "$@"; do
  case "$arg" in
    --site-only) SITE_ONLY=1 ;;
    --allow-older) ALLOW_OLDER=1 ;;
    --skip-virustotal) SKIP_VT=1 ;;
    *) echo "Unknown option: $arg (options: --site-only, --allow-older, --skip-virustotal)" >&2; exit 1 ;;
  esac
done

fail() { echo "publish-to-site: $*" >&2; exit 1; }

# One publish at a time (the lock is held on this script file until it exits)
exec 9< "$0"
flock -n 9 || fail "another publish is running"

[ -f site/index.html ] || fail "site/index.html not found"
mkdir -p "$WWW"

# Build tools for checking the APKs
BUILD_TOOLS="${ANDROID_HOME:-$HOME/android-sdk}/build-tools/35.0.0"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
if [ -z "${JAVA_HOME:-}" ] && [ -x "$HOME/android-sdk/jdk-21/bin/java" ]; then
  export JAVA_HOME="$HOME/android-sdk/jdk-21"
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

# A value from a JSON file, read with Python's own parser (python3 -I: ignores the environment
# and the current folder)
json_get() {
  python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1]));
for k in sys.argv[2:]: d=d[k]
print(d)' "$@"
}

# Release files are staged in a hidden folder inside the web root (same file system, so going live
# is a rename) and checked there: what is checked is exactly what goes live, even if build/ changes
# meanwhile. The folder is removed on any failure or interruption before the switch.
STAGE=""
cleanup() {
  if [ -n "$STAGE" ] && [ -d "$STAGE" ]; then rm -rf -- "${STAGE:?}"; fi
}
trap cleanup EXIT
# Leftovers of an earlier run that was killed outright (only this script's own temporary names)
find "${WWW:?}" -maxdepth 1 -name '.publish-*' -exec rm -rf -- {} +

if [ "$SITE_ONLY" = 0 ]; then
  [ -f build/update.json ] || fail "build/update.json not found: run ./gradlew publishApk first"
  [ -x "$APKSIGNER" ] && [ -x "$AAPT2" ] || fail "apksigner or aapt2 not found in $BUILD_TOOLS"

  STAGE="$(mktemp -d "$WWW/.publish-stage.XXXXXX")"
  cp build/update.json "$STAGE/update.json"

  # The manifest must list exactly the known processor types, each as RemBrowse-<abi>.apk, with a
  # version name that is safe to show and to compare as plain text
  python3 -I - "$STAGE/update.json" "${ABIS[@]}" <<'PY' || fail "build/update.json isn't a valid RemBrowse manifest"
import json, re, sys
path, abis = sys.argv[1], sys.argv[2:]
def need(ok, why):
    if not ok:
        sys.exit("  " + why)
d = json.load(open(path))
need(isinstance(d.get("versionName"), str) and re.fullmatch(r"[0-9A-Za-z.+-]{1,32}", d["versionName"]), "bad versionName")
need(type(d.get("versionCode")) is int and d["versionCode"] > 0, "bad versionCode")
apks = d.get("apks")
need(isinstance(apks, dict) and sorted(apks) == sorted(abis), "apks must list exactly: " + ", ".join(abis))
for abi in abis:
    a = apks[abi]
    need(isinstance(a, dict) and a.get("file") == "RemBrowse-%s.apk" % abi, abi + ": file must be RemBrowse-%s.apk" % abi)
    need(type(a.get("size")) is int and a["size"] > 0, abi + ": bad size")
    need(isinstance(a.get("sha256"), str) and re.fullmatch(r"[0-9a-f]{64}", a["sha256"]), abi + ": bad sha256")
PY

  VERSION_NAME="$(json_get "$STAGE/update.json" versionName)"
  VERSION_CODE="$(json_get "$STAGE/update.json" versionCode)"
  echo "==> Checking RemBrowse $VERSION_NAME (versionCode $VERSION_CODE)"

  for abi in "${ABIS[@]}"; do
    file="RemBrowse-$abi.apk"
    [ -f "build/$file" ] || fail "build/$file not found"
    apk="$STAGE/$file"
    cp "build/$file" "$apk"
    [ "$(stat -c %s "$apk")" = "$(json_get "$STAGE/update.json" apks "$abi" size)" ] || fail "$file: size differs from update.json"
    [ "$(sha256sum "$apk" | cut -d' ' -f1)" = "$(json_get "$STAGE/update.json" apks "$abi" sha256)" ] \
      || fail "$file: SHA-256 differs from update.json"

    badging="$("$AAPT2" dump badging "$apk")" || fail "$file: aapt2 couldn't read it"
    first_line="${badging%%$'\n'*}"
    [[ "$first_line" == "package: name='$PACKAGE' versionCode='$VERSION_CODE' versionName='$VERSION_NAME' "* ]] \
      || fail "$file is not $PACKAGE $VERSION_NAME ($VERSION_CODE)"
    # Only the engine for this processor type, or Android refuses to install it there.
    # A here-string, not a pipe: grep stops reading early, which pipefail would count as a failure.
    grep -qxF "native-code: '$abi'" <<< "$badging" || fail "$file doesn't contain exactly the $abi engine"

    certs="$("$APKSIGNER" verify --print-certs "$apk" 2>&1)" || fail "$file: signature doesn't verify"
    signers="$(grep -c 'certificate SHA-256 digest:' <<< "$certs" || true)"
    digest="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$certs")"
    [ "$signers" = 1 ] && [ "$digest" = "$CERT_SHA256" ] || fail "$file is not signed with the RemBrowse key only"
    echo "    $file: $(stat -c %s "$apk") bytes, $abi, signed with the RemBrowse key"
  done
  if [ "$SKIP_VT" = 0 ]; then
    [ -f build/virustotal.json ] || fail "build/virustotal.json not found: run scripts/virustotal-scan.sh (or pass --skip-virustotal)"
    cp build/virustotal.json "$STAGE/virustotal.json"
    python3 -I - "$STAGE/update.json" "$STAGE/virustotal.json" <<'PY' || fail "build/virustotal.json doesn't match this release: run scripts/virustotal-scan.sh again"
import json, sys
rel, vt = (json.load(open(p)) for p in sys.argv[1:3])
def need(ok, why):
    if not ok:
        sys.exit("  " + why)
need(not vt.get("dryRun"), "it is a dry-run fixture, not a real scan")
need(vt.get("versionName") == rel["versionName"], "versionName differs from update.json")
files = vt.get("files")
need(isinstance(files, dict) and sorted(files) == sorted(a["file"] for a in rel["apks"].values()), "files differ from update.json")
for a in rel["apks"].values():
    need(files[a["file"]].get("sha256") == a["sha256"], a["file"] + ": sha256 differs from update.json")
PY
  fi
  chmod 755 "$STAGE"
  chmod 644 "$STAGE"/*

  if [ -f "$WWW/update.json" ]; then
    LIVE_CODE="$(json_get "$WWW/update.json" versionCode 2>/dev/null || echo 0)"
    if [[ "$LIVE_CODE" =~ ^[0-9]+$ ]] && [ "$VERSION_CODE" -lt "$LIVE_CODE" ] && [ "$ALLOW_OLDER" = 0 ]; then
      fail "the site has versionCode $LIVE_CODE, newer than $VERSION_CODE (use --allow-older to publish anyway)"
    fi
  fi
fi

echo "==> Publishing the page to $WWW"
# --delay-updates renames all changed files into place at the end and --delete-delay removes page
# files that are gone from site/ only after that; the release files and the staging folder are
# never touched here
rsync -a --delay-updates --delete-delay --chmod=D755,F644 \
  --exclude '/RemBrowse-*.apk' --exclude '/update.json' --exclude '/virustotal.json' --exclude '/.publish-*' \
  site/ "$WWW/"

if [ "$SITE_ONLY" = 0 ]; then
  echo "==> Switching to $VERSION_NAME: the APKs, then update.json"
  # Three renames in a row; a download already under way keeps reading the file it opened
  for abi in "${ABIS[@]}"; do
    mv -f "$STAGE/RemBrowse-$abi.apk" "$WWW/RemBrowse-$abi.apk"
  done
  if [ "$SKIP_VT" = 0 ]; then
    mv -f "$STAGE/virustotal.json" "$WWW/virustotal.json"
  else
    # No scan for this version: a stale one must not stay up (the page ignores a mismatch anyway)
    rm -f -- "$WWW/virustotal.json"
  fi
  mv -f "$STAGE/update.json" "$WWW/update.json"
  rmdir "$STAGE"
  STAGE=""
fi

# The site, as nginx serves it (skipped if the local port isn't up)
if command -v curl > /dev/null && curl -fsS -o /dev/null --max-time 5 "${CHECK_URL}healthz" 2>/dev/null; then
  curl -fsS --max-time 10 "${CHECK_URL}" -o /dev/null || fail "the page isn't served at ${CHECK_URL}"
  if [ "$SITE_ONLY" = 0 ]; then
    served="$(curl -fsS --max-time 10 "${CHECK_URL}update.json?t=$(date +%s)")" || fail "update.json isn't served"
    [ "$served" = "$(cat "$WWW/update.json")" ] || fail "the served update.json differs from the published one"
    if [ "$SKIP_VT" = 0 ]; then
      served="$(curl -fsS --max-time 10 "${CHECK_URL}virustotal.json?t=$(date +%s)")" || fail "virustotal.json isn't served"
      [ "$served" = "$(cat "$WWW/virustotal.json")" ] || fail "the served virustotal.json differs from the published one"
    fi
  fi
  echo "==> Served correctly at ${CHECK_URL}"
else
  echo "==> Not checked: nothing answers at ${CHECK_URL} (is deploy/site-host running?)"
fi

if [ "$SITE_ONLY" = 0 ]; then
  echo "Done: RemBrowse $VERSION_NAME is published."
else
  echo "Done: the page is published."
fi
