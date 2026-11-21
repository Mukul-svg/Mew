#!/usr/bin/env bash
# Is this APK signed with the release key this fork publishes? Run it on every build BEFORE it is uploaded.
#
#   tools/check-apk-signature.sh app/build/outputs/apk/release/app-release.apk
#
# Two things pass:
#  1. the APK is signed with the release key itself, or
#  2. the APK carries an APK Signature Scheme v3 lineage back to it (a deliberate rotation, see
#     docs/signing-and-key-rotation.md), which installed copies accept as an update.
# Anything else cannot install over an existing copy (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and must never be released.
#
# 2026-11-21: the old upstream published key (38d9d337...) is not available, so this fork starts a new signing
# line with the key whose fingerprint is below. Builds signed with the old key must be uninstalled first.
set -eu
APK="${1:?usage: tools/check-apk-signature.sh <apk>}"
PUBLISHED=bb7c20567a9df530964119726aad4cc999c1fbaf7fd8e6bcbc01f71a78fa6985
PUBLISHED_COLONS=$(echo "$PUBLISHED" | sed 's/../&:/g; s/:$//' | tr 'a-f' 'A-F')

APKSIGNER="${APKSIGNER:-}"
if [ -z "$APKSIGNER" ]; then
  for candidate in "${ANDROID_HOME:-}/build-tools"/*/apksigner "$HOME/Android/Sdk/build-tools"/*/apksigner; do
    [ -x "$candidate" ] && APKSIGNER="$candidate"
  done
fi
[ -n "$APKSIGNER" ] || { echo "apksigner not found: set APKSIGNER or ANDROID_HOME"; exit 2; }

OUT=$("$APKSIGNER" verify --print-certs "$APK")
SIGNER=$(echo "$OUT" | grep -m1 'SHA-256 digest' | sed 's/.*SHA-256 digest: *//' | tr -d ': ' | tr 'A-F' 'a-f')

if [ "$SIGNER" = "$PUBLISHED" ]; then
  echo "ok: $APK is signed with the release key"
  exit 0
fi

if echo "$OUT" | grep -qi "$PUBLISHED_COLONS" && echo "$OUT" | grep -qiE 'lineage|rotated'; then
  echo "ok: $APK is signed with a rotated key ($SIGNER)"
  echo "    its lineage goes back to the release key, which installed copies accept"
  exit 0
fi

echo "REFUSED: $APK is signed with $SIGNER"
echo "expected the release key  $PUBLISHED"
echo "or a key with a v3 lineage back to it (docs/signing-and-key-rotation.md)."
echo "This file cannot install over an existing copy. Sign it with the right key and check again."
exit 1
