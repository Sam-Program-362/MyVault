#!/usr/bin/env bash
# Changes emulator animation settings and display overlays; never run on a real device.
set -uo pipefail
if [[ ${MYVAULT_DISPOSABLE_EMULATOR:-0} != 1 ]] || [[ $(adb get-serialno) != emulator-* ]]; then
  echo '::error::Motion and cutout checks require a disposable emulator.' >&2
  exit 1
fi
app_id=com.myvault.app.debug
runner=com.myvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/myvault-chrome-test"
mkdir -p "$work"
original_animator=$(adb shell settings get global animator_duration_scale | tr -d '\r')
original_window=$(adb shell settings get global window_animation_scale | tr -d '\r')
original_transition=$(adb shell settings get global transition_animation_scale | tr -d '\r')
overlay=com.android.internal.display.cutout.emulation.corner
overlay_enabled=0
restore() {
  if ((overlay_enabled)); then adb shell cmd overlay disable "$overlay" >/dev/null 2>&1 || true; fi
  for setting in animator_duration_scale window_animation_scale transition_animation_scale; do
    case "$setting" in
      animator_duration_scale) value=$original_animator ;;
      window_animation_scale) value=$original_window ;;
      transition_animation_scale) value=$original_transition ;;
    esac
    if [[ "$value" == null || -z "$value" ]]; then
      adb shell settings delete global "$setting" >/dev/null 2>&1 || true
    else
      adb shell settings put global "$setting" "$value" >/dev/null 2>&1 || true
    fi
  done
}
trap restore EXIT
run_test() {
  local label=$1 class=$2
  shift 2
  local log="$work/$label.log"
  adb shell am instrument -w -r -e class "$class" "$@" "$runner" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  if ((status != 0)) || ! grep -Eq '^OK \([0-9]+ tests?\)' "$log" || grep -q 'FAILURES!!!' "$log"; then
    python3 - "$log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')[-8000:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Android motion or inset test failed::{text}', flush=True)
PY
    exit 1
  fi
}
for setting in animator_duration_scale window_animation_scale transition_animation_scale; do
  adb shell settings put global "$setting" 1 || exit 1
done
run_test motion com.myvault.app.ui.MotionTest -e myvault.motion true

overlay_list=$(adb shell cmd overlay list | tr -d '\r')
if grep -Fq "$overlay" <<< "$overlay_list"; then
  adb shell cmd overlay enable "$overlay" || exit 1
  overlay_enabled=1
  adb shell am force-stop "$app_id" || exit 1
  adb shell am start -n "$app_id/com.myvault.app.MainActivity" >/dev/null || exit 1
  run_test cutout com.myvault.app.ui.SystemChromeTest -e myvault.expectCutout true
  printf '::notice title=Android motion and cutout::Motion ran with animations enabled; transparent bars and safe content were checked with an emulated corner cutout.\n'
elif [[ ${MYVAULT_REQUIRE_CUTOUT:-0} == 1 ]]; then
  echo '::error::This emulator does not provide the required corner-cutout overlay.' >&2
  exit 1
else
  echo '::warning title=Android motion and cutout::Motion ran with animations enabled, but the emulator has no corner-cutout overlay; that phase was skipped.'
fi
