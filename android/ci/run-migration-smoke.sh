#!/usr/bin/env bash
# Temporary smoke test: disposable emulator and synthetic data only.
set -euo pipefail
[[ ${MYVAULT_DISPOSABLE_EMULATOR:-0} == 1 && $(adb get-serialno) == emulator-* ]] || exit 1
app=com.myvault.app.debug
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r "$RUNNER_TEMP/myvault-smoke-preview.apk"
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
for phase in seed exportForPreview; do
  adb shell am instrument -w -r -e class com.myvault.app.ui.OfflineRestartTest -e myvault.restartPhase "$phase" "$runner" | tee "$RUNNER_TEMP/myvault-$phase.log"
  grep -Eq '^OK \([0-9]+ tests?\)' "$RUNNER_TEMP/myvault-$phase.log"
done
python3 ci/run-preview-migration-test.py
