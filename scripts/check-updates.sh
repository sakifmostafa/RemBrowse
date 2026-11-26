#!/usr/bin/env bash
# Reports every dependency that has a newer stable release. Run it before each release:
#
#   bash scripts/check-updates.sh
#
# It reads, from the repository it lives in:
#   gradle/libs.versions.toml (if there is one), settings.gradle.kts, build.gradle.kts and
#   app/build.gradle.kts   every "group:artifact:version" and every id("...") version "..." plugin
#   gradle/wrapper/gradle-wrapper.properties   the Gradle wrapper
#   GeckoView              looked up on the same artifact, so on the channel the build uses
# and asks the repositories settings.gradle.kts declares (Google Maven, Maven Central, the Gradle
# Plugin Portal, maven.mozilla.org) for their maven-metadata.xml. The Gradle wrapper is compared
# with services.gradle.org/versions/current. uBlock Origin's version on addons.mozilla.org is
# printed for information only (the app installs the latest copy from there), so it never
# changes the exit status.
#
# Only stable releases count: alpha, beta, rc, cr, dev, eap, snapshot, milestone and preview
# versions are ignored. A dependency already ahead of the newest stable one is reported OK.
#
# Exit status: 0 everything is current, 1 something is outdated, 2 a lookup failed (a failed
# lookup is printed as ERROR and wins over OUTDATED, because the result is then incomplete).
# Needs only bash, curl, grep, sed, sort and awk.
set -uo pipefail

cd "$(dirname "$0")/.."

UNSTABLE='(^|[-._])(alpha|beta|rc|cr|dev|eap|snapshot|milestone|preview|m[0-9]+)([-._0-9]|$)'
CURL=(curl -fsS --max-time 30 --retry 2 --retry-delay 2 -A "rembrowse-check-updates")

outdated=0
failed=0
declare -a names=() currents=() latests=() states=()

# Repositories in the order settings.gradle.kts declares them (first mention wins).
repos=()
add_repo() {
  local r
  for r in "${repos[@]:-}"; do [ "$r" = "$1" ] && return; done
  repos+=("$1")
}
while IFS= read -r token; do
  case "$token" in
    google\(*) add_repo "https://dl.google.com/dl/android/maven2" ;;
    mavenCentral\(*) add_repo "https://repo.maven.apache.org/maven2" ;;
    gradlePluginPortal\(*) add_repo "https://plugins.gradle.org/m2" ;;
    *) add_repo "${token%/}" ;;
  esac
done < <(grep -oE 'google\(|mavenCentral\(|gradlePluginPortal\(|uri\("https?://[^"]+"' settings.gradle.kts \
           | sed -E 's/^uri\("//; s/"$//')
if [ "${#repos[@]}" -eq 0 ]; then
  echo "ERROR: no repositories found in settings.gradle.kts" >&2
  exit 2
fi

# latest_stable <group> <artifact>: prints the newest stable version from the first repository
# that lists the artifact with at least one stable version; returns 1 if no repository does or a
# request fails.
latest_stable() {
  local group="$1" artifact="$2" path repo xml v
  path="${group//./\/}/$artifact/maven-metadata.xml"
  for repo in "${repos[@]}"; do
    xml=$("${CURL[@]}" "$repo/$path" 2>/dev/null) || continue
    v=$(printf '%s\n' "$xml" | grep -oE '<version>[^<]+</version>' | sed -E 's/<\/?version>//g' \
          | grep -viE "$UNSTABLE" | sort -V | tail -n 1)
    if [ -n "$v" ]; then printf '%s\n' "$v"; return 0; fi
  done
  return 1
}

# record <name> <current> <latest or ""> : stores one result line.
record() {
  local name="$1" current="$2" latest="$3" state
  if [ -z "$latest" ]; then
    state="ERROR"; failed=1
  elif [ "$latest" = "$current" ] || [ "$(printf '%s\n%s\n' "$current" "$latest" | sort -V | tail -n 1)" = "$current" ]; then
    state="OK"
  else
    state="OUTDATED"; outdated=1
  fi
  names+=("$name"); currents+=("$current"); latests+=("${latest:--}"); states+=("$state")
}

check_coordinate() { # <name> <group> <artifact> <current>
  local latest
  latest=$(latest_stable "$2" "$3") || latest=""
  record "$1" "$4" "$latest"
}

seen=" "
once() { case "$seen" in *" $1 "*) return 1 ;; esac; seen="$seen$1 "; }

# 1. Libraries written as "group:artifact:version" in the Gradle files.
for f in settings.gradle.kts build.gradle.kts app/build.gradle.kts; do
  [ -f "$f" ] || continue
  while IFS=: read -r group artifact version; do
    once "$group:$artifact" || continue
    check_coordinate "$group:$artifact" "$group" "$artifact" "$version"
  done < <(grep -ohE '"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][A-Za-z0-9_.+-]*"' "$f" | tr -d '"')
done

# 2. Plugins written as id("...") version "..." (looked up by their plugin marker artifact).
for f in settings.gradle.kts build.gradle.kts app/build.gradle.kts; do
  [ -f "$f" ] || continue
  while IFS='|' read -r id version; do
    once "plugin:$id" || continue
    check_coordinate "plugin $id" "$id" "$id.gradle.plugin" "$version"
  done < <(sed -nE 's/.*id\("([^"]+)"\)[[:space:]]+version[[:space:]]+"([^"]+)".*/\1|\2/p' "$f")
done

# 3. A version catalog, if the project has one.
if [ -f gradle/libs.versions.toml ]; then
  toml=gradle/libs.versions.toml
  while IFS='|' read -r kind coord version; do
    case "$kind" in
      lib)    group=${coord%%:*}; artifact=${coord#*:}; artifact=${artifact%%:*} ;;
      plugin) group=$coord; artifact="$coord.gradle.plugin" ;;
    esac
    once "$group:$artifact" || continue
    check_coordinate "$([ "$kind" = plugin ] && echo "plugin $coord" || echo "$group:$artifact")" "$group" "$artifact" "$version"
  done < <(awk '
    /^\[/ { section=$0; next }
    section=="[versions]" && match($0, /^[[:space:]]*[A-Za-z0-9_.-]+[[:space:]]*=[[:space:]]*"[^"]+"/) {
      key=$1; val=$0; sub(/^[^"]*"/, "", val); sub(/".*$/, "", val); ver[key]=val
    }
    function resolve(line,   r, v) {
      if (match(line, /version\.ref[[:space:]]*=[[:space:]]*"[^"]+"/)) {
        r=substr(line, RSTART, RLENGTH); sub(/^[^"]*"/, "", r); sub(/"$/, "", r); return ver[r]
      }
      if (match(line, /version[[:space:]]*=[[:space:]]*"[^"]+"/)) {
        v=substr(line, RSTART, RLENGTH); sub(/^[^"]*"/, "", v); sub(/"$/, "", v); return v
      }
      return ""
    }
    { lines[NR]=$0; sec[NR]=section }
    END {
      for (i=1; i<=NR; i++) {
        l=lines[i]
        if (sec[i]=="[libraries]") {
          g=""; n=""; m=""
          if (match(l, /module[[:space:]]*=[[:space:]]*"[^"]+"/)) { m=substr(l,RSTART,RLENGTH); sub(/^[^"]*"/,"",m); sub(/"$/,"",m) }
          else if (match(l, /"[^":]+:[^":]+(:[^"]+)?"/)) { m=substr(l,RSTART+1,RLENGTH-2) }
          v=resolve(l)
          if (m ~ /:[^:]+:[^:]+$/) { split(m, p, ":"); m=p[1] ":" p[2]; if (v=="") v=p[3] }
          if (m != "" && v != "") print "lib|" m "|" v
        } else if (sec[i]=="[plugins]") {
          if (match(l, /id[[:space:]]*=[[:space:]]*"[^"]+"/)) { m=substr(l,RSTART,RLENGTH); sub(/^[^"]*"/,"",m); sub(/"$/,"",m); v=resolve(l); if (v!="") print "plugin|" m "|" v }
        }
      }
    }' "$toml")
fi

# 4. The Gradle wrapper.
if [ -f gradle/wrapper/gradle-wrapper.properties ]; then
  cur=$(sed -nE 's#^distributionUrl=.*gradle-([0-9][^/-]*(-[A-Za-z0-9.]+)?)-(bin|all)\.zip$#\1#p' gradle/wrapper/gradle-wrapper.properties | head -n 1)
  if [ -z "$cur" ]; then
    record "Gradle wrapper" "?" ""
  else
    latest=$("${CURL[@]}" https://services.gradle.org/versions/current 2>/dev/null \
               | sed -nE 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' | head -n 1)
    record "Gradle wrapper" "$cur" "$latest"
  fi
fi

# Print the results.
for i in "${!names[@]}"; do
  printf '%-62s %-22s %-22s %s\n' "${names[$i]}" "${currents[$i]}" "${latests[$i]}" "${states[$i]}"
done

# 5. uBlock Origin on addons.mozilla.org: information only.
ubo=$("${CURL[@]}" https://addons.mozilla.org/api/v5/addons/addon/ublock-origin/ 2>/dev/null \
        | grep -oE '"current_version":.*' | grep -oE '"version": ?"[^"]+"' | head -n 1 \
        | sed -E 's/.*"([^"]+)"$/\1/')
if [ -n "$ubo" ]; then
  printf '%-62s %-22s %-22s %s\n' "uBlock Origin (addons.mozilla.org, installed from there)" "-" "$ubo" "INFO"
else
  printf '%-62s %-22s %-22s %s\n' "uBlock Origin (addons.mozilla.org, installed from there)" "-" "-" "INFO (lookup failed)"
fi

n_out=0; n_err=0
for s in "${states[@]}"; do
  [ "$s" = OUTDATED ] && n_out=$((n_out + 1))
  [ "$s" = ERROR ] && n_err=$((n_err + 1))
done
echo
echo "Checked ${#names[@]} items: $n_out outdated, $n_err lookup failures."
if [ "$failed" -eq 1 ]; then exit 2; fi
if [ "$outdated" -eq 1 ]; then exit 1; fi
exit 0
