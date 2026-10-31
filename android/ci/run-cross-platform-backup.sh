#!/usr/bin/env bash
set -euo pipefail
log="$RUNNER_TEMP/myvault-scope-backup.log"
exec > >(tee "$log") 2>&1
report_failure() {
  status=$?
  if (( status != 0 )); then
    python3 - "$log" <<'PYLOG'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors="replace")[-7000:]
text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title=Scope backup diagnostic::{text}", flush=True)
PYLOG
  fi
}
trap report_failure EXIT
[[ ${MYVAULT_DISPOSABLE_EMULATOR:-0} == 1 && $(adb get-serialno) == emulator-* ]] || { echo 'Disposable emulator required'; exit 1; }
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
instrument_log="$RUNNER_TEMP/scope-backup-export.log"
adb shell am instrument -w -r -e class com.myvault.app.data.CrossPlatformBackupTest com.myvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$instrument_log"
grep -Eq '^OK \(1 test\)' "$instrument_log" && ! grep -q 'FAILURES!!!' "$instrument_log"
adb exec-out run-as com.myvault.app.debug cat files/cross-android.json > "$RUNNER_TEMP/cross-android.json"
test -s "$RUNNER_TEMP/cross-android.json"
cd ..
ANDROID_BACKUP_FILE="$RUNNER_TEMP/cross-android.json" node scripts/test-scope-backup.mjs
printf '::notice title=Scoped backup boundary::Android v2 export preserves Local/Global metadata and IDs; web rejects unknown tag identities transactionally until Unicode 15.1 enforcement is available.\n'
