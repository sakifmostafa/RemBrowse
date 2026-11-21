#!/usr/bin/env bash
# Changes the password of the release keystore (signing/rembrowse.jks) and saves the new one in
# signing/keystore.properties, which the build reads. The key itself is not changed, so builds
# signed afterwards still update installed copies.
#
#   bash scripts/change-keystore-password.sh             # asks for the new password (not shown)
#   bash scripts/change-keystore-password.sh --generate  # makes a random one; it is not printed
#
# The current password is read from signing/keystore.properties, then REMBROWSE_STORE_PASSWORD,
# and asked for if neither has it. The keystore is PKCS12, where the key's password is always the
# store's, so one password covers both (keytool -keypasswd is not supported for PKCS12).
#
# Passwords never appear on a command line (keytool reads them from the environment) and are
# never printed. The keystore is changed on a copy, checked, and only then put in place.
set -euo pipefail

cd "$(dirname "$0")/.."
KEYSTORE="signing/rembrowse.jks"
PROPS="signing/keystore.properties"
ALIAS="rembrowse"
EXPECTED_SHA256="F1:A5:30:6A:47:E4:BE:78:AD:7F:B0:C1:41:88:71:E3:A6:C9:A8:35:13:49:E2:42:39:DB:20:B4:75:B2:BE:06"

KEYTOOL="keytool"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then KEYTOOL="$JAVA_HOME/bin/keytool"; fi
command -v "$KEYTOOL" > /dev/null || { echo "keytool not found: install a JDK or set JAVA_HOME" >&2; exit 1; }
[ -f "$KEYSTORE" ] || { echo "$KEYSTORE not found" >&2; exit 1; }

umask 077

# The value of a key in a .properties file this script wrote (one key=value per line)
read_prop() {
  [ -f "$PROPS" ] || return 0
  sed -n "s/^$1=//p" "$PROPS" | head -n 1
}

# A password the build can read back exactly: Java's .properties format treats a backslash as an
# escape and drops leading spaces, and keytool needs at least 6 ASCII characters.
check_password() {
  local p="$1"
  if [ "${#p}" -lt 6 ]; then echo "The password must be at least 6 characters." >&2; return 1; fi
  case "$p" in
    *\\*) echo "The password can't contain a backslash." >&2; return 1 ;;
    " "*|*" ") echo "The password can't start or end with a space." >&2; return 1 ;;
  esac
  # Anything left after removing printable ASCII is a control or non-ASCII character, which
  # keytool refuses
  if [ -n "$(printf '%s' "$p" | LC_ALL=C tr -d ' -~')" ]; then
    echo "The password can only use plain ASCII letters, digits, spaces and symbols." >&2; return 1
  fi
}

OLD_PW="$(read_prop storePassword)"
[ -n "$OLD_PW" ] || OLD_PW="${REMBROWSE_STORE_PASSWORD:-}"
if [ -z "$OLD_PW" ]; then
  IFS= read -r -s -p "Current keystore password: " OLD_PW; echo
fi
export REMBROWSE_OLD_PW="$OLD_PW"
if ! "$KEYTOOL" -list -keystore "$KEYSTORE" -storepass:env REMBROWSE_OLD_PW -alias "$ALIAS" > /dev/null 2>&1; then
  echo "The current password doesn't open $KEYSTORE." >&2
  exit 1
fi

if [ "${1:-}" = "--generate" ]; then
  # 32 letters and digits, about 190 bits. Each read is a fixed size, so nothing in the pipe is
  # cut off early (which pipefail would treat as a failure).
  NEW_PW=""
  while [ "${#NEW_PW}" -lt 32 ]; do
    NEW_PW+="$(head -c 64 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')"
  done
  NEW_PW="${NEW_PW:0:32}"
elif [ -n "${1:-}" ]; then
  echo "Unknown option: $1 (the only option is --generate)" >&2
  exit 1
else
  IFS= read -r -s -p "New keystore password: " NEW_PW; echo
  IFS= read -r -s -p "Type it again: " NEW_PW2; echo
  [ "$NEW_PW" = "$NEW_PW2" ] || { echo "The two passwords don't match." >&2; exit 1; }
fi
check_password "$NEW_PW"
[ "$NEW_PW" != "$OLD_PW" ] || { echo "That is the current password." >&2; exit 1; }
export REMBROWSE_NEW_PW="$NEW_PW"

WORK_JKS="$(mktemp "signing/.rembrowse.jks.XXXXXX")"
WORK_PROPS="$(mktemp "signing/.keystore.properties.XXXXXX")"
trap 'rm -f "$WORK_JKS" "$WORK_PROPS"' EXIT

cp "$KEYSTORE" "$WORK_JKS"
if ! KEYTOOL_OUT="$("$KEYTOOL" -storepasswd -keystore "$WORK_JKS" -storepass:env REMBROWSE_OLD_PW -new:env REMBROWSE_NEW_PW 2>&1)"; then
  echo "keytool couldn't change the password. Nothing was changed. It said: $KEYTOOL_OUT" >&2
  exit 1
fi

# The changed copy must open with the new password and hold the same certificate
SHA256="$("$KEYTOOL" -list -v -keystore "$WORK_JKS" -storepass:env REMBROWSE_NEW_PW -alias "$ALIAS" 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1)" || true
if [ "$SHA256" != "$EXPECTED_SHA256" ]; then
  echo "The changed keystore didn't check out (certificate SHA-256: ${SHA256:-none}). Nothing was changed." >&2
  exit 1
fi

{
  echo "# RemBrowse release signing. Private: never commit, paste or upload this file."
  echo "# Written by scripts/change-keystore-password.sh on $(date -u +%Y-%m-%d)."
  printf 'storePassword=%s\n' "$NEW_PW"
  printf 'keyPassword=%s\n' "$NEW_PW"
} > "$WORK_PROPS"
chmod 600 "$WORK_PROPS" "$WORK_JKS"

# The new password is saved first, then the keystore that needs it is put in place. The cleanup
# is switched off before either step, so an interruption or failure from here on never deletes the
# working copy: if the second step doesn't complete, the keystore with the new password is still
# there to move into place by hand. (Leftover working files are private and gitignored.)
trap - EXIT
mv -f "$WORK_PROPS" "$PROPS"
if ! mv -f "$WORK_JKS" "$KEYSTORE"; then
  echo "The new password is saved in $PROPS, but the keystore that uses it couldn't be put in place." >&2
  echo "Move it there yourself: mv -f '$WORK_JKS' '$KEYSTORE'" >&2
  exit 1
fi

echo "Done. The keystore password was changed and saved in $PROPS (readable only by you)."
echo "Keep a copy of the password and of $KEYSTORE somewhere off this machine."
