#!/usr/bin/env bash
# Run every independent check on the disposable CI emulator, preserving failures.
set -uo pipefail
if [[ ${MYVAULT_DISPOSABLE_EMULATOR:-0} != 1 || $(adb get-serialno) != emulator-* ]]; then
  echo '::error::The emulator suite requires an explicitly authorized disposable emulator.' >&2
  exit 1
fi
: "${MYVAULT_BASELINE_APK:?Set MYVAULT_BASELINE_APK}"
: "${MYVAULT_PREVIOUS_APK:?Set MYVAULT_PREVIOUS_APK}"
: "${MYVAULT_CONFLICT_APK:?Set MYVAULT_CONFLICT_APK}"
: "${MYVAULT_PREVIEW_APK:?Set MYVAULT_PREVIEW_APK}"
: "${MYVAULT_API_LEVEL:?Set MYVAULT_API_LEVEL}"
status=0
run_check() {
  local label=$1
  shift
  echo "Running $label"
  if ! "$@"; then
    echo "::error title=Emulator check failed::$label failed. Other independent checks will still run."
    status=1
  fi
}
run_check 'Room, backup and Compose tests' bash ci/run-device-tests.sh
run_check 'Motion and cutout' env MYVAULT_REQUIRE_CUTOUT=1 bash ci/run-motion-inset-tests.sh
if [[ $MYVAULT_API_LEVEL == 30 ]]; then
  run_check 'Synthetic screenshots and launcher' bash ci/capture-synthetic-ui.sh
fi
# Each scenario starts with the old APK, which cannot open the current version's
# Room database. Reset ONLY this suite's synthetic debug fixture on the explicitly
# authorized disposable emulator. Never clear between old-APK seed and upgrade.
run_with_fresh_debug_fixture() {
  adb shell pm clear com.myvault.app.debug || return 1
  "$@"
}
run_check 'Real-window comparison and recording' run_with_fresh_debug_fixture bash ci/run-native-window-evidence.sh
run_check 'Offline upgrade, restart and migration' run_with_fresh_debug_fixture bash ci/run-offline-restart-test.sh
exit "$status"
