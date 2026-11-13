#!/usr/bin/env bash
# Five-minute clean API 29 emulator observation, before any SQL APK/workload.
set -euo pipefail

evidence_dir="${TAG_PROOF_EVIDENCE_DIR:-${RUNNER_TEMP:-/tmp}}"
mkdir -p "$evidence_dir"
printf 'Idle baseline evidence directory: %s\n' "$evidence_dir"
adb get-state

api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')
printf 'Emulator metadata: api=%s abi=%s fingerprint=%s\n' "$api" "$abi" "$fingerprint"
if [[ "$api" != 29 || "$abi" != x86_64 ]]; then
  echo "Expected API 29 x86_64 emulator; got API $api ABI $abi" >&2
  exit 2
fi

adb logcat -c
{
  echo "host_time_utc=$(date -u +%FT%TZ)"
  echo "api=$api"
  echo "abi=$abi"
  echo "fingerprint=$fingerprint"
  adb shell cat /proc/meminfo
  adb shell dumpsys meminfo system_server
} > "$evidence_dir/tag-proof-api29-idle-device.txt" 2>&1

adb logcat -b all -v threadtime > "$evidence_dir/tag-proof-api29-idle-live.log" 2>&1 &
logcat_pid=$!
for sample in $(seq 1 30); do
  {
    echo "sample=$sample host_time_utc=$(date -u +%FT%TZ)"
    adb shell dumpsys meminfo system_server 2>&1 | grep -E 'Applications Memory Usage|Dalvik Heap|Native Heap|TOTAL PSS|TOTAL RSS|TOTAL SWAP|Objects' || true
  } >> "$evidence_dir/tag-proof-api29-idle-system-memory.txt"
  sleep 10
done
kill "$logcat_pid" 2>/dev/null || true
wait "$logcat_pid" 2>/dev/null || true
adb logcat -d -b crash -v threadtime > "$evidence_dir/tag-proof-api29-idle-crash.log" 2>&1 || true

if grep -q 'FATAL EXCEPTION IN SYSTEM PROCESS' "$evidence_dir/tag-proof-api29-idle-live.log"; then
  grep -n -A50 -B5 'FATAL EXCEPTION IN SYSTEM PROCESS' "$evidence_dir/tag-proof-api29-idle-live.log" || true
  echo '::error title=Idle API 29 emulator::System process crashed during the five-minute SQL-free baseline.'
  exit 1
fi
printf 'No system-process fatal exception observed during the five-minute SQL-free baseline.\n'
ls -lh "$evidence_dir"/tag-proof-api29-idle-*
