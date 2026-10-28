#!/usr/bin/env bash
# Compare the unchanged previous APK and this build on the same disposable emulator.
set -uo pipefail
if [[ ${MYVAULT_DISPOSABLE_EMULATOR:-0} != 1 ]]; then
  echo '::error::Real-window evidence is permitted only on a disposable emulator.' >&2
  exit 1
fi
serial=$(adb get-serialno)
if [[ $serial != emulator-* ]]; then
  echo '::error::Real-window evidence requires a disposable emulator, not a physical device.' >&2
  exit 1
fi
baseline=${MYVAULT_BASELINE_APK:?Set MYVAULT_BASELINE_APK to the previous debug APK}
current=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for file in "$baseline" "$current" "$test_apk"; do
  [[ -s "$file" ]] || { echo "::error::Required evidence APK is missing: $file" >&2; exit 1; }
done
app_id=com.myvault.app.debug
runner=com.myvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner
output=app/build/reports/native-window-evidence
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/myvault-native-window"
mkdir -p "$output" "$work"
old_window=$(adb shell settings get global window_animation_scale | tr -d '\r')
old_transition=$(adb shell settings get global transition_animation_scale | tr -d '\r')
old_animator=$(adb shell settings get global animator_duration_scale | tr -d '\r')
overlays=$(adb shell cmd overlay list 2>/dev/null | tr -d '\r')
old_nav=$(awk '/\[x\] com\.android\.internal\.systemui\.navbar\./ {print $2; exit}' <<< "$overlays")
nav_changed=0
recorder=''
cleanup() {
  if [[ -n "$recorder" ]]; then
    pid=$(adb shell pidof screenrecord 2>/dev/null | tr -d '\r' | awk '{print $1}')
    [[ -z "$pid" ]] || adb shell kill -2 "$pid" >/dev/null 2>&1 || true
    wait "$recorder" 2>/dev/null || true
  fi
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    case "$setting" in
      window_animation_scale) value=$old_window ;;
      transition_animation_scale) value=$old_transition ;;
      animator_duration_scale) value=$old_animator ;;
    esac
    if [[ -z "$value" || "$value" == null ]]; then
      adb shell settings delete global "$setting" >/dev/null 2>&1 || true
    else
      adb shell settings put global "$setting" "$value" >/dev/null 2>&1 || true
    fi
  done
  if ((nav_changed)) && [[ -n "$old_nav" ]]; then
    adb shell cmd overlay enable-exclusive --category "$old_nav" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# Match the supplied phone screenshots' aspect ratio while retaining a common dp scale.
adb shell wm size 1080x2340 || exit 1
adb shell wm density 440 || exit 1
adb shell settings put global window_animation_scale 1 || exit 1
adb shell settings put global transition_animation_scale 1 || exit 1
adb shell settings put global animator_duration_scale 1 || exit 1
if grep -q 'com.android.internal.systemui.navbar.gestural' <<< "$overlays" && [[ -n "$old_nav" ]]; then
  if adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural; then
    nav_changed=1
    echo 'Gesture navigation enabled for the real-window comparison.'
  fi
else
  echo '::warning::Gesture-navigation overlay unavailable; the report will show the measured navigation insets.'
fi
sleep 3

capture_phase() {
  local phase=$1 apk=$2 log="$work/$1.log" name
  if [[ $phase == before ]]; then
    # This is a debug-only, data-preserving downgrade on the disposable emulator.
    adb install -r -d "$apk" || return 1
  else
    adb install -r "$apk" || return 1
  fi
  adb install -r "$test_apk" || return 1
  adb shell am force-stop "$app_id" || return 1
  adb shell am instrument -w -r -e class com.myvault.app.ui.NativeWindowEvidenceTest \
    -e myvault.evidence "$phase" "$runner" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  if ((status != 0)) || ! grep -Eq '^OK \([0-9]+ tests?\)' "$log" || grep -q 'FAILURES!!!' "$log"; then
    python3 - "$log" "$phase" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')[-8000:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Real-window {sys.argv[2]} capture failed::{text}')
PY
    return 1
  fi
  mkdir -p "$output/$phase"
  for name in welcome space-dialog dashboard-empty dashboard-cards dashboard-list dashboard-list-expanded editor editor-keyboard settings; do
    if ! adb exec-out run-as "$app_id" cat "files/native-window-evidence/$phase/$name.png" > "$output/$phase/$name.png" || [[ ! -s "$output/$phase/$name.png" ]]; then
      echo "::error::Missing actual-activity $phase/$name screenshot." >&2
      return 1
    fi
  done
  adb exec-out run-as "$app_id" cat "files/native-window-evidence/$phase/window-metrics.txt" > "$output/$phase/window-metrics.txt" || return 1
  cat "$output/$phase/window-metrics.txt"
  while IFS= read -r line; do
    if [[ "$line" == welcome:* || "$line" == dashboard:* ]]; then
      printf '::notice title=Actual %s window measurement::%s\n' "$phase" "$line"
    fi
  done < "$output/$phase/window-metrics.txt"
}

capture_phase before "$baseline" || exit 1
capture_phase after "$current" || exit 1

# Use real Android input and the platform's real frame clock, not Compose's test clock.
adb shell am force-stop "$app_id" || exit 1
adb shell screenrecord --size 720x1560 --bit-rate 3000000 --time-limit 120 /sdcard/myvault-ci-motion.mp4 > "$work/screenrecord.log" 2>&1 &
recorder=$!
sleep 1
if ! python3 ci/record-native-motion.py | tee "$output/interaction-log.txt"; then
  echo '::error::Real-activity motion interactions failed.' >&2
  exit 1
fi
pid=$(adb shell pidof screenrecord | tr -d '\r' | awk '{print $1}')
if [[ -z "$pid" ]]; then
  echo '::error::The emulator screen recorder stopped before interactions completed.' >&2
  exit 1
fi
adb shell kill -2 "$pid" || exit 1
wait "$recorder" || true
recorder=''
adb pull /sdcard/myvault-ci-motion.mp4 "$output/after-interactions.mp4" >/dev/null || exit 1
[[ -s "$output/after-interactions.mp4" ]] || { echo '::error::The interaction recording is empty.' >&2; exit 1; }
if command -v ffprobe >/dev/null 2>&1; then
  ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1 "$output/after-interactions.mp4" | tee "$output/recording-duration.txt"
fi
echo 'Before/after actual-activity screenshots, window measurements and a real-time interaction recording are ready.'
