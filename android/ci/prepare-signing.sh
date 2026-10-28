#!/usr/bin/env bash
# Secrets are read only from the environment; never echo them or enable tracing.
set +x
set -euo pipefail

required=(ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD RUNNER_TEMP GITHUB_ENV)
missing=0
for name in "${required[@]}"; do
  if [[ -z ${!name:-} ]]; then
    printf '::error::Missing required signing variable: %s\n' "$name" >&2
    missing=1
  fi
done
if ((missing)); then exit 1; fi

umask 077
keystore="$RUNNER_TEMP/myvault-release.keystore"
if ! printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$keystore" 2>/dev/null || [[ ! -s "$keystore" ]]; then
  rm -f "$keystore"
  printf '::error::ANDROID_KEYSTORE_BASE64 is not a valid, nonempty base64-encoded keystore.\n' >&2
  exit 1
fi

# Verify the store password and that the alias contains a private key. The key
# password is checked by Gradle while signing. Do not print keytool output.
if ! listing=$(keytool -J-Duser.language=en -J-Duser.country=US -list -v \
  -keystore "$keystore" -storepass:env ANDROID_KEYSTORE_PASSWORD -alias "$ANDROID_KEY_ALIAS" 2>/dev/null) ||
  [[ "$listing" != *'Entry type: PrivateKeyEntry'* ]]; then
  rm -f "$keystore"
  printf '::error::Could not open the keystore private key. Check the keystore, store password, and alias.\n' >&2
  exit 1
fi

printf 'ANDROID_KEYSTORE_PATH=%s\n' "$keystore" >> "$GITHUB_ENV"
printf 'Release signing keystore is ready.\n'
