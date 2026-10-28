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

# Check the store independently from the alias. This distinguishes a file/store
# password problem from a missing alias without displaying any keytool output.
if ! store_result=$(keytool -J-Duser.language=en -J-Duser.country=US -list \
  -keystore "$keystore" -storepass:env ANDROID_KEYSTORE_PASSWORD 2>&1); then
  # A coarse, allowlisted file classification and error category do not reveal
  # the keystore bytes, passwords, aliases, certificate or keytool output.
  kind=$(python3 - "$keystore" <<'PY'
import pathlib, sys
raw = pathlib.Path(sys.argv[1]).read_bytes()
if raw.startswith(bytes.fromhex('feedfeed')):
    print('JKS')
elif raw.startswith(bytes.fromhex('cececece')):
    print('JCEKS')
elif len(raw) > 1 and raw[0] == 0x30 and raw[1] >= 0x80:
    print('DER/PKCS12 candidate')
elif raw.startswith(b'-----BEGIN'):
    print('PEM text')
elif raw.startswith(b'PK\x03\x04'):
    print('ZIP archive')
elif raw and all(c in b'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\r\n\t ' for c in raw):
    print('Base64 text')
else:
    print('other or unrecognized format')
PY
  )
  case "$store_result" in
    *'java.io.EOFException'*) reason='unexpected end of file' ;;
    *'toDerInputStream'*|*'DerInputStream'*|*'DerValue'*) reason='DER parsing failed' ;;
    *'NoSuchAlgorithmException'*) reason='algorithm/provider unavailable' ;;
    *'java.io.IOException'*) reason='I/O or keystore parsing error' ;;
    *) reason='keystore open failed' ;;
  esac
  rm -f "$keystore"
  printf '::notice title=Keystore format diagnostic::Decoded input classification: %s; Java category: %s.\n' "$kind" "$reason"
  if [[ "$store_result" == *'password was incorrect'* || "$store_result" == *'Keystore was tampered with'* ]]; then
    printf '::error::The decoded keystore was recognized, but could not be opened with the configured store password. Check that the Actions keystore and store password belong together.\n' >&2
  elif [[ "$store_result" == *'Unrecognized keystore format'* || "$store_result" == *'Invalid keystore format'* ]]; then
    printf '::error::Java did not recognize the decoded Actions keystore format. Check that the secret contains the complete encoded keystore file.\n' >&2
  else
    printf '::error::The decoded keystore could not be opened with the configured store password. Check the Actions keystore and store-password values.\n' >&2
  fi
  exit 1
fi
if ! listing=$(keytool -J-Duser.language=en -J-Duser.country=US -list -v \
  -keystore "$keystore" -storepass:env ANDROID_KEYSTORE_PASSWORD -alias "$ANDROID_KEY_ALIAS" 2>/dev/null); then
  rm -f "$keystore"
  printf '::error::The configured alias could not be found in the opened keystore. Check the Actions alias value.\n' >&2
  exit 1
fi
if [[ "$listing" != *'Entry type: PrivateKeyEntry'* ]]; then
  rm -f "$keystore"
  printf '::error::The configured alias is not a private-key entry. Check the Actions alias value.\n' >&2
  exit 1
fi
# The key password is checked separately by Gradle while signing.

printf 'ANDROID_KEYSTORE_PATH=%s\n' "$keystore" >> "$GITHUB_ENV"
printf 'Release signing keystore is ready.\n'
