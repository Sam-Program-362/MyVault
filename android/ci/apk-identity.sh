#!/usr/bin/env bash
# Print public package/version/signer metadata for one verified, single-signer APK.
# Output is TSV: application ID, versionCode, versionName, SHA-256 certificate fingerprint.
set -euo pipefail
apk=${1:?Usage: apk-identity.sh path/to/signed.apk}
if [[ ! -s "$apk" ]]; then
  echo 'APK file is missing or empty.' >&2
  exit 1
fi
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
aapt=${AAPT:-${sdk:+$sdk/build-tools/34.0.0/aapt}}
apksigner=${APKSIGNER:-${sdk:+$sdk/build-tools/34.0.0/apksigner}}
if [[ -z "$aapt" || -z "$apksigner" || ! -x "$aapt" || ! -x "$apksigner" ]]; then
  echo 'Android SDK build-tools 34.0.0 (aapt and apksigner) are required.' >&2
  exit 1
fi
if ! badging=$("$aapt" dump badging "$apk" 2>/dev/null); then
  echo 'Unable to read APK package metadata.' >&2
  exit 1
fi
package_line=$(printf '%s\n' "$badging" | sed -n '/^package: /{p;q;}')
app_id=$(printf '%s\n' "$package_line" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
version_code=$(printf '%s\n' "$package_line" | sed -n "s/.* versionCode='\([0-9][0-9]*\)'.*/\1/p")
version_major=$(printf '%s\n' "$package_line" | sed -n "s/.* versionCodeMajor='\([0-9][0-9]*\)'.*/\1/p")
version_name=$(printf '%s\n' "$package_line" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")
if [[ -z "$app_id" || -z "$version_code" ]]; then
  echo 'APK does not have a readable package ID and version code.' >&2
  exit 1
fi
if [[ -n "$version_major" && "$version_major" != 0 ]]; then
  echo 'APK has a major version code; compare its full longVersionCode separately.' >&2
  exit 1
fi
if ! signer_output=$("$apksigner" verify --print-certs "$apk" 2>/dev/null); then
  echo 'APK is unsigned, invalid, or its signature could not be verified.' >&2
  exit 1
fi
signers=$(printf '%s\n' "$signer_output" | awk '/^Signer #[0-9]+ certificate SHA-256 digest: /{count++} END {print count+0}')
fingerprint=$(printf '%s\n' "$signer_output" | awk -F ': ' '/^Signer #1 certificate SHA-256 digest: /{print $2}' | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
if [[ "$signers" != 1 || ! "$fingerprint" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'APK must have one verifiable SHA-256 signer; signer rotations require separate review.' >&2
  exit 1
fi
printf '%s\t%s\t%s\t%s\n' "$app_id" "$version_code" "$version_name" "$fingerprint"
