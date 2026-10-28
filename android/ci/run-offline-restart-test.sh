#!/usr/bin/env bash
# This test changes airplane mode and force-stops the app: run ONLY on a disposable emulator.
set -uo pipefail
if [[ ${MYVAULT_DISPOSABLE_EMULATOR:-0} != 1 ]]; then
  echo '::error::Set MYVAULT_DISPOSABLE_EMULATOR=1 only for a disposable emulator; this test writes data and changes network settings.' >&2
  exit 1
fi
serial=$(adb get-serialno)
if [[ "$serial" != emulator-* ]]; then
  echo '::error::Offline restart test requires a disposable emulator, not a physical device.' >&2
  exit 1
fi
app_id=com.myvault.app.debug
runner=com.myvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/myvault-offline-test"
mkdir -p "$work"

fail_with_log() {
  python3 - "$1" <<'PY'
import pathlib
import sys
text = pathlib.Path(sys.argv[1]).read_text(errors="replace")[-8000:]
text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title=Offline emulator test failed::{text}", flush=True)
PY
  exit 1
}
run_instrumentation() {
  local label=$1 test_class=$2
  shift 2
  local log="$work/$label.log"
  adb shell am instrument -w -r -e class "$test_class" "$@" "$runner" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  if ((status != 0)) || ! grep -Eq '^OK \([0-9]+ tests?\)' "$log" || grep -q 'FAILURES!!!' "$log"; then
    fail_with_log "$log"
  fi
}

seed_apk=${MYVAULT_PREVIOUS_APK:-$app_apk}
[[ -f "$seed_apk" ]] || { echo '::error::The APK for the restart fixture is missing.' >&2; exit 1; }
adb install -r "$seed_apk" >/dev/null || exit 1
adb install -r "$test_apk" >/dev/null || exit 1

restore_network() {
  adb shell settings put global airplane_mode_on 0 >/dev/null 2>&1 || true
  adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1 || true
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
}
trap restore_network EXIT
adb shell settings put global airplane_mode_on 1 || exit 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null || exit 1
adb shell svc wifi disable || exit 1
adb shell svc data disable >/dev/null 2>&1 || true
[[ $(adb shell settings get global airplane_mode_on | tr -d '\r') == 1 ]] || exit 1
run_instrumentation seed com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase seed
adb shell am force-stop "$app_id" || exit 1
# Both debug APKs are signed by this runner's same disposable debug key. -r retains app data.
adb install -r "$app_apk" >/dev/null || exit 1
adb shell am force-stop "$app_id" || exit 1
adb shell am start -n "$app_id/com.myvault.app.MainActivity" >/dev/null || exit 1
run_instrumentation verify com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase verify
if [[ -n ${MYVAULT_PREVIOUS_APK:-} ]]; then
  printf '::notice title=Offline upgrade and restart::The previous native APK wrote a vault in airplane mode; an in-place update and force-stop preserved its entries, tags, settings and images.\n'
else
  printf '::notice title=Offline reinstall and restart::The debug APK wrote a vault in airplane mode; an in-place reinstall and force-stop preserved its data.\n'
fi

# Optional visual diagnostics use only the fixed, synthetic fixtures in VisualParityTest.
# This is off by default; it never captures data from a real vault.
if [[ ${MYVAULT_CAPTURE_FIXTURES:-0} == 1 ]]; then
  run_instrumentation visual com.myvault.app.ui.VisualParityTest -e myvault.capture true
  for name in welcome dashboard settings editor-short preview; do
    adb exec-out cat "/sdcard/Android/data/$app_id/files/myvault-$name.jpg" > "$work/$name.jpg" || exit 1
    python3 - "$work/$name.jpg" "$name" <<'PY'
import base64
import pathlib
import sys
raw = pathlib.Path(sys.argv[1]).read_bytes()
if not raw.startswith(b"\xff\xd8"):
    raise SystemExit("Synthetic UI capture is not a JPEG")
encoded = base64.b64encode(raw).decode()
chunks = [encoded[i:i+7000] for i in range(0, len(encoded), 7000)]
for index, chunk in enumerate(chunks, 1):
    print(f"::notice title=UI fixture {sys.argv[2]} {index:02d} of {len(chunks):02d}::{chunk}", flush=True)
PY
  done
fi
