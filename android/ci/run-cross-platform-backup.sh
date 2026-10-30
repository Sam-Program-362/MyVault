#!/usr/bin/env bash
set -euo pipefail
log="$RUNNER_TEMP/myvault-cross-platform.log"
exec > >(tee "$log") 2>&1
report_failure() {
  status=$?
  if (( status != 0 )); then
    python3 - "$log" <<'PYLOG'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors="replace")[-7000:]
text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title=Cross-platform backup diagnostic::{text}", flush=True)
PYLOG
  fi
}
trap report_failure EXIT
[[ ${MYVAULT_DISPOSABLE_EMULATOR:-0} == 1 && $(adb get-serialno) == emulator-* ]] || { echo 'Disposable emulator required'; exit 1; }
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
instrument() {
  local phase=$1 log="$RUNNER_TEMP/cross-$1.log"
  adb shell am instrument -w -r -e class com.myvault.app.data.CrossPlatformBackupTest -e myvault.crossPhase "$phase" com.myvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  grep -Eq '^OK \(1 test\)' "$log" && ! grep -q 'FAILURES!!!' "$log"
}
instrument export
adb exec-out run-as com.myvault.app.debug cat files/cross-android.json > "$RUNNER_TEMP/cross-android.json"
test -s "$RUNNER_TEMP/cross-android.json"
cd ..
ANDROID_BACKUP_FILE="$RUNNER_TEMP/cross-android.json" WEB_BACKUP_FILE="$RUNNER_TEMP/cross-web.json" node scripts/test-scope-backup.mjs
adb push "$RUNNER_TEMP/cross-web.json" /data/local/tmp/myvault-cross-web.json
adb shell run-as com.myvault.app.debug sh -c 'cat /data/local/tmp/myvault-cross-web.json > files/cross-web.json'
cd android
instrument import
printf '::notice title=Cross-platform backup exchange::Actual Android-exported file imported into web with remapped Space IDs; actual web-exported file imported into Android with different remapped Space IDs.\n'
