#!/usr/bin/env bash
# Scans the release APKs in build/ on VirusTotal and writes the result next to update.json:
#   build/virustotal.json  read by the download page (and checked by scripts/publish-to-site.sh)
#   build/virustotal.md    a short block for the GitHub release notes
#
#   bash scripts/virustotal-scan.sh                     # scan (needs a key, see below)
#   bash scripts/virustotal-scan.sh --allow-detections  # exit 0 even if an engine flags a file
#   bash scripts/virustotal-scan.sh --dry-run           # no network, fixture numbers, no key needed
#
# The API key is read from $VT_API_KEY, else from the VT_API_KEY= line in the repo-root .env (read
# as text, never sourced), else from ~/.config/virustotal/api_key (mode 600). It is
# handed to curl as a header read from a file in a private temp folder, so it never appears in a
# command line or in the output.
#
# Each APK's SHA-256 must match build/update.json. If VirusTotal already has a completed analysis
# of that hash it is reused; otherwise the file is uploaded (through upload_url, as it is over
# 32 MB) and the analysis is polled every 30 s (the free API allows 4 requests a minute) for up to
# 20 minutes. Exit status is 1 if any file has malicious or suspicious detections, because Android
# APKs sometimes get false positives and a person should look before publishing.
set -euo pipefail

cd "$(dirname "$0")/.."
API="https://www.virustotal.com/api/v3"
KEY_FILE="${VT_API_KEY_FILE:-$HOME/.config/virustotal/api_key}"
POLL_SECONDS="${VT_POLL_SECONDS:-30}"
TIMEOUT_SECONDS="${VT_TIMEOUT_SECONDS:-1200}"
FILES=(RemBrowse-arm64-v8a.apk RemBrowse-x86_64.apk)

DRY=0
ALLOW=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=1 ;;
    --allow-detections) ALLOW=1 ;;
    *) echo "Unknown option: $arg (options: --dry-run, --allow-detections)" >&2; exit 2 ;;
  esac
done

fail() { echo "virustotal-scan: $*" >&2; exit 1; }
[ -f build/update.json ] || fail "build/update.json not found: run ./gradlew publishApk first"

TMP="$(mktemp -d)"
chmod 700 "$TMP"
trap 'rm -rf -- "$TMP"' EXIT

# A value from a JSON file (python3 -I: ignores the environment and the current folder)
json_get() {
  python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1]))
for k in sys.argv[2:]: d=d[int(k)] if isinstance(d,list) else d[k]
print(d)' "$@"
}

VERSION_NAME="$(json_get build/update.json versionName)"
declare -A SHA
for f in "${FILES[@]}"; do
  abi="${f#RemBrowse-}"; abi="${abi%.apk}"
  want="$(json_get build/update.json apks "$abi" sha256)"
  [[ "$want" =~ ^[0-9a-f]{64}$ ]] || fail "update.json has no valid sha256 for $abi"
  if [ "$DRY" = 0 ] || [ -f "build/$f" ]; then
    [ -f "build/$f" ] || fail "build/$f not found"
    have="$(sha256sum "build/$f" | cut -d' ' -f1)"
    [ "$have" = "$want" ] || fail "$f: SHA-256 differs from update.json"
  fi
  SHA[$f]="$want"
done

# ---- VirusTotal API ----
if [ "$DRY" = 0 ]; then
  command -v curl > /dev/null || fail "curl not found"
  KEY="${VT_API_KEY:-}"
  if [ -z "$KEY" ] && [ -f .env ]; then
    KEY="$(sed -n 's/^[[:space:]]*\(export[[:space:]]\+\)\?VT_API_KEY=//p' .env | tail -n1 | tr -d '[:space:]"'"'"'')"
  fi
  if [ -z "$KEY" ] && [ -f "$KEY_FILE" ]; then
    KEY="$(head -n1 "$KEY_FILE" | tr -d '[:space:]')"
  fi
  [ -n "$KEY" ] || fail "no API key: set VT_API_KEY or put VT_API_KEY=... in .env or the key in $KEY_FILE (mode 600)"
  [[ "$KEY" =~ ^[0-9A-Za-z]+$ ]] || fail "the API key has unexpected characters"
  (umask 077; printf 'x-apikey: %s\n' "$KEY" > "$TMP/header")
  unset KEY
fi

# GET/POST against the API; the key header comes from a file. Output: body on stdout, HTTP status
# in $TMP/status.
vt() {
  local method="$1" path="$2"; shift 2
  curl -sS --max-time 1800 -o "$TMP/body" -w '%{http_code}' -X "$method" -H "@$TMP/header" "$@" "$API$path" > "$TMP/status" \
    || fail "network error calling $method $path"
  cat "$TMP/body"
}
status() { cat "$TMP/status"; }

# Prints the stats line "malicious suspicious harmless undetected" for a hash whose analysis is
# complete. Returns 4 if VirusTotal doesn't know the file, 5 if it knows it but its analysis hasn't
# finished (stats still empty); any other failure is an error (so a rate limit never turns into an
# upload).
lookup() {
  local out
  out="$(vt GET "/files/$1")"
  case "$(status)" in
    200) ;;
    404) return 4 ;;
    429) fail "rate limit hit (free API: 4 requests a minute, 500 a day); try again later" ;;
    401|403) fail "VirusTotal refused the API key (HTTP $(status))" ;;
    *) fail "unexpected answer looking up $1 (HTTP $(status))" ;;
  esac
  python3 -I -c 'import json,sys
s=json.load(sys.stdin)["data"]["attributes"].get("last_analysis_stats") or {}
if not s or sum(s.get(k,0) for k in ("malicious","suspicious","harmless","undetected"))==0: sys.exit(5)
print(*[s.get(k,0) for k in ("malicious","suspicious","harmless","undetected")])' <<< "$out"
}

# Waits until VirusTotal has stats for hash $1, polling the file itself; prints them.
wait_for_stats() {
  local sha="$1" name="$2" deadline rc out
  deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while :; do
    sleep "$POLL_SECONDS"
    rc=0; out="$(lookup "$sha")" || rc=$?
    [ "$rc" = 0 ] && { printf '%s\n' "$out"; return 0; }
    [ "$rc" = 4 ] || [ "$rc" = 5 ] || exit 1
    echo "    $name: waiting for VirusTotal's result" >&2
    [ "$(date +%s)" -lt "$deadline" ] || fail "$name: no result within $((TIMEOUT_SECONDS / 60)) minutes; run again later"
  done
}

upload_and_wait() {
  local f="$1" out url id deadline st
  echo "    uploading $f" >&2
  out="$(vt GET /files/upload_url)"
  [ "$(status)" = 200 ] || fail "couldn't get an upload URL (HTTP $(status))"
  url="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["data"])' <<< "$out")"
  [[ "$url" == https://* ]] || fail "unexpected upload URL"
  out="$(curl -sS --max-time 3600 -o "$TMP/body" -w '%{http_code}' -H "@$TMP/header" -F "file=@build/$f" "$url" > "$TMP/status"; cat "$TMP/body")" \
    || fail "upload of $f failed"
  # 409: the same file is already being analysed (an earlier upload); wait for its result instead
  if [ "$(status)" = 409 ]; then echo "    $f: already being analysed; waiting for that result" >&2; return 0; fi
  [ "$(status)" = 200 ] || fail "upload of $f failed (HTTP $(status))"
  id="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])' <<< "$out")"
  [[ "$id" =~ ^[A-Za-z0-9=_-]+$ ]] || fail "unexpected analysis id"
  deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while :; do
    sleep "$POLL_SECONDS"
    out="$(vt GET "/analyses/$id")"
    [ "$(status)" = 200 ] || fail "polling the analysis failed (HTTP $(status))"
    st="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["data"]["attributes"]["status"])' <<< "$out")"
    echo "    $f: analysis $st" >&2
    [ "$st" = completed ] && return 0
    [ "$(date +%s)" -lt "$deadline" ] || fail "$f: the analysis didn't complete within $((TIMEOUT_SECONDS / 60)) minutes"
  done
}

: > "$TMP/stats.tsv"
for f in "${FILES[@]}"; do
  sha="${SHA[$f]}"
  echo "==> $f ($sha)"
  if [ "$DRY" = 1 ]; then
    # Fixture: 0 malicious, 0 suspicious, 14 harmless, 51 undetected = 65 engines
    stats="0 0 14 51"
  else
    rc=0; stats="$(lookup "$sha")" || rc=$?
    if [ "$rc" = 0 ]; then
      echo "    VirusTotal already has a completed analysis; reusing it" >&2
    elif [ "$rc" = 4 ]; then
      sleep "$POLL_SECONDS"
      upload_and_wait "$f"
      stats="$(wait_for_stats "$sha" "$f")"
    elif [ "$rc" = 5 ]; then
      echo "    VirusTotal is still analysing it (an earlier upload); waiting" >&2
      stats="$(wait_for_stats "$sha" "$f")"
    else
      exit 1
    fi
  fi
  printf '%s\t%s\t%s\n' "$f" "$sha" "$stats" >> "$TMP/stats.tsv"
  # Free API: 4 requests a minute
  [ "$DRY" = 1 ] || sleep "$POLL_SECONDS"
done

# ---- Output ----
RESULT="$(python3 -I - "$TMP/stats.tsv" "$VERSION_NAME" "$DRY" <<'PY'
import datetime, json, sys
tsv, version, dry = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
files, lines = {}, []
for row in open(tsv):
    name, sha, counts = row.rstrip("\n").split("\t")
    mal, sus, har, und = counts.split()
    mal, sus, har, und = int(mal), int(sus), int(har), int(und)
    url = "https://www.virustotal.com/gui/file/" + sha
    engines = mal + sus + har + und
    files[name] = {"sha256": sha, "url": url, "malicious": mal, "suspicious": sus,
                   "harmless": har, "undetected": und, "engines": engines}
    lines.append("- %s: %d of %d engines flagged it ([report](%s))" % (name, mal + sus, engines, url))
out = {"versionName": version,
       "scannedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
       "files": files}
if dry:
    out["dryRun"] = True  # fixture numbers: publish-to-site.sh and the page refuse this file
with open("build/virustotal.json", "w") as f:
    json.dump(out, f, indent=2)
    f.write("\n")
with open("build/virustotal.md", "w") as f:
    f.write("**VirusTotal scan**\n\n" + "\n".join(lines) + "\n")
if dry:
    print("(dry run: these numbers are a fixture, not a real scan)", file=sys.stderr)
bad = [n for n, v in files.items() if v["malicious"] or v["suspicious"]]
print("DETECTIONS " + ",".join(bad) if bad else "CLEAN")
PY
)"
echo "==> Wrote build/virustotal.json and build/virustotal.md"
cat build/virustotal.md
if [[ "$RESULT" == DETECTIONS* ]]; then
  echo "virustotal-scan: engines flagged ${RESULT#DETECTIONS }: open the report links and look before publishing" >&2
  [ "$ALLOW" = 1 ] || exit 1
fi
