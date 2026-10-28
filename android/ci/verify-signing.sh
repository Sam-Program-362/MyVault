#!/usr/bin/env bash
# Verify both APK signature integrity and the identity of the configured key.
set +x
set -euo pipefail

apk=${1:?Usage: verify-signing.sh path/to/app-release.apk}
: "${ANDROID_KEYSTORE_PATH:?ANDROID_KEYSTORE_PATH is required}"
: "${ANDROID_KEYSTORE_PASSWORD:?ANDROID_KEYSTORE_PASSWORD is required}"
: "${ANDROID_KEY_ALIAS:?ANDROID_KEY_ALIAS is required}"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
apksigner=${APKSIGNER:-$ANDROID_HOME/build-tools/34.0.0/apksigner}

if [[ ! -s "$apk" ]]; then
  printf '::error::The signed release APK was not produced.\n' >&2
  exit 1
fi
if ! apk_info=$("$apksigner" verify --verbose --print-certs "$apk" 2>&1); then
  printf '::error::APK signature verification failed.\n' >&2
  exit 1
fi
if ! key_info=$(keytool -J-Duser.language=en -J-Duser.country=US -list -v \
  -keystore "$ANDROID_KEYSTORE_PATH" -storepass:env ANDROID_KEYSTORE_PASSWORD -alias "$ANDROID_KEY_ALIAS" 2>/dev/null); then
  printf '::error::Could not read the configured signing certificate.\n' >&2
  exit 1
fi

expected=$(printf '%s\n' "$key_info" | awk '/^[[:space:]]*SHA256:/{if (!seen++) print $2}' | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
actual=$(printf '%s\n' "$apk_info" | awk -F ': ' '/^Signer #1 certificate SHA-256 digest: /{print $2}' | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
signer_count=$(printf '%s\n' "$apk_info" | awk '/^Signer #[0-9]+ certificate SHA-256 digest: /{count++} END {print count+0}')
if [[ -z "$expected" || "$signer_count" != 1 || "$actual" != "$expected" ]]; then
  printf '::error::The APK signer does not match the configured release key. Refusing to upload it.\n' >&2
  exit 1
fi
printf 'APK signature verified against the configured release certificate (SHA-256 %s).\n' "$expected"
