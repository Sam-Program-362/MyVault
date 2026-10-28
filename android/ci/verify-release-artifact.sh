#!/usr/bin/env bash
# Verify and record the public identity of the actual signed release APK.
set -euo pipefail
apk=${1:?Usage: verify-release-artifact.sh path/to/signed.apk path/to/identity.txt}
report=${2:?Usage: verify-release-artifact.sh path/to/signed.apk path/to/identity.txt}
ci_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
gradle="$ci_dir/../app/build.gradle.kts"
expected_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$/\1/p' "$gradle")
expected_name=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)"[[:space:]]*$/\1/p' "$gradle")
if [[ ! "$expected_code" =~ ^[1-9][0-9]{0,9}$ || -z "$expected_name" ]] || ((expected_code < 5)); then
  echo '::error::The release version is missing, ambiguous, or older than the previous APK.' >&2
  exit 1
fi
identity=$(bash "$ci_dir/apk-identity.sh" "$apk")
app_id=$(cut -f1 <<< "$identity")
code=$(cut -f2 <<< "$identity")
name=$(cut -f3 <<< "$identity")
certificate=$(cut -f4 <<< "$identity")
if [[ "$app_id" != com.myvault.app || "$code" != "$expected_code" || "$name" != "$expected_name" ]]; then
  echo '::error::The signed APK package or version does not match the expected release configuration.' >&2
  exit 1
fi
{
  echo 'MyVault signed release APK (verified against the configured signing key).'
  printf 'Application ID: %s\nVersion name: %s\nVersion code: %s\nSigning certificate SHA-256: %s\n' "$app_id" "$name" "$code" "$certificate"
  echo 'In-place update compatibility is not established unless the installed package, version and certificate were independently compared.'
} > "$report"
printf '::notice title=Signed APK public identity::%s version %s (code %s), certificate SHA-256 %s. Installed-device compatibility is not automatically verified.\n' "$app_id" "$name" "$code" "$certificate"
