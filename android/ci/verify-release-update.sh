#!/usr/bin/env bash
# Fail closed unless the signed release matches independently supplied public metadata.
set -euo pipefail
apk=${1:?Usage: verify-release-update.sh path/to/signed-release.apk}
: "${ANDROID_EXPECTED_CERT_SHA256:?Provide the installed release certificate SHA-256 (or the chosen key fingerprint for a confirmed fresh install).}"
: "${ANDROID_INSTALLED_VERSION_CODE:?Provide the installed release versionCode, or 0 only for a confirmed fresh install.}"
expected=$(printf '%s' "$ANDROID_EXPECTED_CERT_SHA256" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo '::error::The supplied release certificate SHA-256 must contain exactly 64 hexadecimal digits.' >&2
  exit 1
fi
if [[ ! "$ANDROID_INSTALLED_VERSION_CODE" =~ ^(0|[1-9][0-9]{0,9})$ ]] ||
   ((ANDROID_INSTALLED_VERSION_CODE > 2147483647)); then
  echo '::error::The supplied installed release versionCode must be a valid non-negative integer.' >&2
  exit 1
fi
ci_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
identity=$(bash "$ci_dir/apk-identity.sh" "$apk")
app_id=$(cut -f1 <<< "$identity")
version_code=$(cut -f2 <<< "$identity")
actual=$(cut -f4 <<< "$identity")
if [[ "$app_id" != com.myvault.app ]]; then
  echo '::error::The signed release package ID is not com.myvault.app.' >&2
  exit 1
fi
if [[ "$actual" != "$expected" ]]; then
  echo '::error::The signed release certificate does not match the independently supplied fingerprint.' >&2
  exit 1
fi
if [[ ! "$version_code" =~ ^(0|[1-9][0-9]{0,9})$ ]] ||
   ((version_code > 2147483647 || version_code <= ANDROID_INSTALLED_VERSION_CODE)); then
  echo '::error::The signed release versionCode is not newer than the supplied installed versionCode.' >&2
  exit 1
fi
printf 'Release package com.myvault.app, versionCode %s and public signer SHA-256 %s match the supplied reference. Confirm that reference against the actual phone; CI cannot inspect it.\n' "$version_code" "$actual"
