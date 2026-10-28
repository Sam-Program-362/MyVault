#!/usr/bin/env bash
# Conservative, read-only comparison of an installed app's exact APK and a proposed update.
set -euo pipefail
if (($# != 2)); then
  echo 'Usage: check-apk-update.sh installed-base.apk candidate.apk' >&2
  exit 1
fi
ci_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
old=$(bash "$ci_dir/apk-identity.sh" "$1")
new=$(bash "$ci_dir/apk-identity.sh" "$2")
old_id=$(cut -f1 <<< "$old")
new_id=$(cut -f1 <<< "$new")
old_version=$(cut -f2 <<< "$old")
new_version=$(cut -f2 <<< "$new")
old_signer=$(cut -f4 <<< "$old")
new_signer=$(cut -f4 <<< "$new")
printf 'Installed APK: package=%s versionCode=%s signerSHA256=%s\n' "$old_id" "$old_version" "$old_signer"
printf 'Candidate APK: package=%s versionCode=%s signerSHA256=%s\n' "$new_id" "$new_version" "$new_signer"
if [[ "$old_id" != "$new_id" ]]; then
  echo 'Package IDs differ: these are separate installations, not an in-place update.' >&2
  exit 1
fi
if [[ "$old_signer" != "$new_signer" ]]; then
  echo 'Signing certificates differ: do not attempt an in-place update without further signing-lineage review.' >&2
  exit 1
fi
if [[ ! "$old_version" =~ ^(0|[1-9][0-9]{0,9})$ || ! "$new_version" =~ ^(0|[1-9][0-9]{0,9})$ ]] ||
   ((old_version > 2147483647 || new_version > 2147483647 || new_version <= old_version)); then
  echo 'Candidate versionCode must be greater than the installed versionCode.' >&2
  exit 1
fi
printf 'Package and current signer match; candidate versionCode is newer. Confirm the first APK is the one currently installed before updating.\n'
