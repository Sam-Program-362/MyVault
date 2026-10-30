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
checked() {
  local label=$1
  shift
  local log="$work/setup.log"
  printf '%s\n' "$label" > "$log"
  if ! "$@" >> "$log" 2>&1; then
    cat "$log"
    fail_with_log "$log"
  fi
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
# The normal test run may already have installed the newer debug version on this emulator.
# -d is allowed only for debuggable APKs; no uninstall or data clearing is performed.
checked 'Install previous debug APK' adb install -r -d "$seed_apk"
checked 'Install instrumentation APK' adb install -r "$test_apk"

restore_network() {
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
}
trap restore_network EXIT
# ConnectivityService performs the protected broadcast with system privileges (API 30+).
checked 'Enable airplane mode' adb shell cmd connectivity airplane-mode enable
checked 'Disable Wi-Fi' adb shell svc wifi disable
adb shell svc data disable >/dev/null 2>&1 || true
if [[ $(adb shell settings get global airplane_mode_on | tr -d '\r') != 1 ]]; then
  echo '::error::The emulator did not enter airplane mode.' >&2
  exit 1
fi
run_instrumentation seed com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase seed
checked 'Force-stop the previous APK' adb shell am force-stop "$app_id"
# Both debug APKs are signed by this runner's same disposable debug key. -r retains app data.
checked 'Update the debug APK without clearing data' adb install -r "$app_apk"
checked 'Force-stop the updated APK' adb shell am force-stop "$app_id"
checked 'Restart the updated APK' adb shell am start -n "$app_id/com.myvault.app.MainActivity"
run_instrumentation verify com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase verify
checked 'Force-stop after choosing list layout' adb shell am force-stop "$app_id"
checked 'Restart with the saved list layout' adb shell am start -n "$app_id/com.myvault.app.MainActivity"
run_instrumentation verify-layout com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase verifyLayout
if [[ -n ${MYVAULT_PREVIOUS_APK:-} ]]; then
  printf '::notice title=Offline upgrade and restart::The previous native APK wrote a vault in airplane mode; an in-place update and force-stop preserved its entries, settings, images and the new list preference.\n'
else
  printf '::notice title=Offline reinstall and restart::The debug APK wrote a vault in airplane mode; an in-place reinstall and force-stop preserved its data.\n'
fi
if [[ -n ${MYVAULT_CONFLICT_APK:-} ]]; then
  # The candidate has the same package ID but a different disposable certificate.
  # The emulator must reject it without uninstalling or clearing the existing fixture.
  conflict_log="$work/conflicting-update.log"
  if adb install -r "$MYVAULT_CONFLICT_APK" > "$conflict_log" 2>&1; then
    echo '::error::The emulator unexpectedly accepted an APK signed with the wrong key.' >&2
    exit 1
  fi
  if ! grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' "$conflict_log"; then
    fail_with_log "$conflict_log"
  fi
  checked 'Force-stop after rejected incompatible update' adb shell am force-stop "$app_id"
  checked 'Restart after rejected incompatible update' adb shell am start -n "$app_id/com.myvault.app.MainActivity"
  run_instrumentation conflict-retained com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase verifyLayout
  printf '::notice title=Incompatible update rejected safely::A different-key APK with the same package was rejected and the existing offline vault remained readable.\n'
fi
if [[ -n ${MYVAULT_PREVIEW_APK:-} ]]; then
  checked 'Install separately signed preview alongside debug' adb install -r "$MYVAULT_PREVIEW_APK"
  checked 'Verify debug package remains installed' adb shell pm path "$app_id"
  checked 'Verify preview package is separate' adb shell pm path com.myvault.app.preview
  run_instrumentation export-preview com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase exportForPreview
  if ! python3 ci/run-preview-migration-test.py; then
    exit 1
  fi
  checked 'Force-stop the preview' adb shell am force-stop com.myvault.app.preview
  checked 'Restart the retained debug app' adb shell am start -n "$app_id/com.myvault.app.MainActivity"
  run_instrumentation preview-side-by-side com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase verifyLayout
  printf '::notice title=Side-by-side preview::The distinct preview package installed and launched without replacing the existing debug vault.\n'
fi
