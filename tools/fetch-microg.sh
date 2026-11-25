#!/usr/bin/env bash
# Zenix Container - microG payload fetcher.
#
# Downloads the three APKs the container installs into a space and drops them in
# app/src/main/assets/zenix_payload/ so they ride along inside the host APK.
#
# Why not commit them: GmsCore alone is ~110 MB. That does not belong in a git
# history. Fetch at build time instead.
#
# Assets are picked by EXACT name pattern from the current upstream release, so a
# new upstream version is picked up without editing this script, and a renamed
# asset fails loudly instead of silently shipping the wrong flavour:
#
#   com.google.android.gms-<code>.apk   microG Services   (com.google.android.gms)
#   com.android.vending-<code>.apk      microG Companion (com.android.vending)
#   GsfProxy-<tag>.apk                  GSF Proxy         (com.google.android.gsf)
#
# Rejected on purpose:
#   *-hw.apk     Huawei/HMS flavour (different maps backend, wrong for AOSP)
#   org.microg.gms-*  dev preview, different applicationId, not what apps query
#
# Usage:
#   tools/fetch-microg.sh            # latest release
#   tools/fetch-microg.sh --verify   # also verify the detached .asc signatures
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/zenix_payload"
# Signatures / provenance live outside assets: they must not ride inside the APK.
META="$ROOT/build/zenix-payload-meta"
API_GMS="https://api.github.com/repos/microg/GmsCore/releases/latest"
API_GSF="https://api.github.com/repos/SaeedDev94/GsfProxy/releases/latest"
VERIFY=0
[[ "${1:-}" == "--verify" ]] && VERIFY=1

mkdir -p "$DEST" "$META"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

have() { command -v "$1" >/dev/null 2>&1; }
have curl || { echo "curl required" >&2; exit 1; }
have python3 || { echo "python3 required" >&2; exit 1; }

# fetch_release <api-url> <regex> <outfile> <label>
fetch_release() {
  local api="$1" pattern="$2" outfile="$3" label="$4"
  echo "-- $label"
  curl -fsSL "$api" -o "$TMP/release.json"
  local url
  url="$(python3 - "$TMP/release.json" "$pattern" <<'PY'
import json, re, sys
data = json.load(open(sys.argv[1]))
pattern = re.compile(sys.argv[2])
matches = [a for a in data.get("assets", []) if pattern.fullmatch(a["name"])]
if len(matches) != 1:
    sys.stderr.write("asset match count %d for %s; assets=%s\n"
                     % (len(matches), sys.argv[2], [a["name"] for a in data.get("assets", [])]))
    sys.exit(2)
print(matches[0]["browser_download_url"])
PY
)"
  echo "   $url"
  curl -fL --retry 3 --retry-delay 2 -o "$TMP/$outfile" "$url"
  mv "$TMP/$outfile" "$DEST/$outfile"
  echo "$url" > "$META/$outfile.source"
  if curl -fsL --retry 2 -o "$META/$outfile.asc" "$url.asc" 2>/dev/null; then
    echo "   signature: $(basename "$url").asc"
  else
    rm -f "$META/$outfile.asc"
    echo "   signature: none published"
  fi
  echo "   -> $(du -h "$DEST/$outfile" | cut -f1)  $DEST/$outfile"
}

fetch_release "$API_GMS" 'com\.google\.android\.gms-[0-9]+\.apk' gmscore.apk "microG Services (gmscore)"
fetch_release "$API_GMS" 'com\.android\.vending-[0-9]+\.apk'    fakestore.apk "microG Companion (fakestore)"
fetch_release "$API_GSF" 'GsfProxy-v[0-9.]+\.apk'              gsfproxy.apk "GSF Proxy (gsfproxy)"

# Identity check: a payload whose package name is wrong is worse than no payload,
# because the container records a successful install of the wrong app.
BT=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/zenix-toolchain/sdk; do
  [[ -n "$candidate" && -x "$candidate/build-tools/29.0.2/aapt" ]] && BT="$candidate/build-tools/29.0.2" && break
done
if [[ -n "$BT" ]]; then
  echo "-- package identity"
  for pair in "gmscore.apk:com.google.android.gms" "fakestore.apk:com.android.vending" "gsfproxy.apk:com.google.android.gsf"; do
    file="${pair%%:*}"; want="${pair##*:}"
    got="$("$BT/aapt" dump badging "$DEST/$file" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
    if [[ "$got" != "$want" ]]; then
      echo "   MISMATCH $file declares '$got', expected '$want'" >&2
      exit 1
    fi
    echo "   $file -> $got OK"
  done
else
  echo "!! aapt not found; skipped package identity check" >&2
fi

if [[ "$VERIFY" == "1" ]]; then
  have gpg || { echo "gpg required for --verify" >&2; exit 1; }
  echo "-- signature verification (microG publishes detached .asc per asset)"
  if ! gpg --list-keys 9BDB70D9D9E5A3C8 >/dev/null 2>&1 && ! gpg --list-keys >/dev/null 2>&1; then
    echo "   no keyring yet. import the microG signing key first:" >&2
    echo "   gpg --keyserver keyserver.ubuntu.com --recv-keys 9BDB70D9D9E5A3C8" >&2
    exit 1
  fi
  for f in "$DEST"/gmscore.apk "$DEST"/fakestore.apk; do
    asc="$META/$(basename "$f").asc"
    [[ -f "$asc" ]] || { echo "   no signature for $(basename "$f"), skipped" >&2; continue; }
    if gpg --verify "$asc" "$f" 2>/dev/null; then
      echo "   $(basename "$f") signature OK"
    else
      echo "   $(basename "$f") SIGNATURE FAILED" >&2
      exit 1
    fi
  done
  # GsfProxy (SaeedDev94 fork) publishes no detached signature upstream.
  [[ -f "$META/gsfproxy.apk.asc" ]] || echo "   gsfproxy.apk: no upstream signature published" >&2
fi

echo
echo "payload staged in $DEST:"
ls -lh "$DEST"/*.apk
echo
echo "next: ./gradlew :app:assembleBlackBox64Release"
