#!/usr/bin/env bash
# Zenix Container - release keystore generator.
#
# Creates the signing identity used for release builds. The keystore is NOT
# committed: keystore.properties is gitignored, and the .jks is written outside
# the source tree by default so it cannot be swept into a commit.
#
# Usage:
#   tools/make-keystore.sh [out_dir]
#
# Environment overrides (non-interactive use):
#   ZENIX_STORE_PASS, ZENIX_KEY_PASS, ZENIX_KEY_ALIAS, ZENIX_DNAME
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$HOME/.zenix-keys}"
ALIAS="${ZENIX_KEY_ALIAS:-zenix}"
STORE_PASS="${ZENIX_STORE_PASS:-}"
KEY_PASS="${ZENIX_KEY_PASS:-}"
DNAME="${ZENIX_DNAME:-CN=Zenix Container, OU=Zenix, O=Zenix, L=Benoni, ST=Gauteng, C=ZA}"
JKS="$OUT_DIR/zenix-release.jks"

if [[ -z "$STORE_PASS" ]]; then
  read -r -s -p "keystore password: " STORE_PASS; echo
  read -r -s -p "repeat password:   " CONFIRM; echo
  [[ "$STORE_PASS" == "$CONFIRM" ]] || { echo "passwords differ" >&2; exit 1; }
fi
[[ -n "$KEY_PASS" ]] || KEY_PASS="$STORE_PASS"

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

if [[ -f "$JKS" ]]; then
  echo "keystore already exists: $JKS (delete it yourself to rotate)" >&2
else
  keytool -genkeypair \
    -keystore "$JKS" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "$DNAME"
  chmod 600 "$JKS"
  echo "created $JKS"
fi

# keystore.properties lives in the repo root but is gitignored. storeFile is
# resolved relative to app/, so an absolute path is written here on purpose.
cat > "$ROOT/keystore.properties" <<EOF
storeFile=$JKS
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 "$ROOT/keystore.properties"
echo "wrote $ROOT/keystore.properties (gitignored)"
echo
echo "fingerprint:"
keytool -list -v -keystore "$JKS" -storepass "$STORE_PASS" -alias "$ALIAS" \
  | grep -E "SHA1:|SHA256:" || true
