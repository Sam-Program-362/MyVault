#!/usr/bin/env bash
# Capture only synthetic test fixtures and the disposable emulator's system launcher.
set -uo pipefail
if [[ ${MYVAULT_DISPOSABLE_EMULATOR:-0} != 1 ]]; then
  echo '::error::Synthetic UI capture is permitted only on a disposable emulator.' >&2
  exit 1
fi
serial=$(adb get-serialno)
if [[ $serial != emulator-* ]]; then
  echo '::error::Synthetic UI capture requires a disposable emulator, not a physical device.' >&2
  exit 1
fi
app_id=com.myvault.app.debug
runner=com.myvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/myvault-synthetic-ui"
output=app/build/reports/synthetic-screenshots
mkdir -p "$work" "$output"

run_fixture() {
  local class=$1 label=$2 log="$work/$2.log"
  adb shell am instrument -w -r -e class "$class" -e myvault.capture true "$runner" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  if ((status != 0)) || ! grep -Eq '^OK \([0-9]+ tests?\)' "$log" || grep -q 'FAILURES!!!' "$log"; then
    python3 - "$log" "$label" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')[-7000:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Synthetic {sys.argv[2]} capture failed::{text}')
PY
    exit 1
  fi
}

run_fixture com.myvault.app.ui.VisualParityTest visual
run_fixture com.myvault.app.ui.LauncherAppearanceTest launcher
for name in welcome cards list launcher; do
  if ! adb exec-out run-as "$app_id" cat "files/synthetic-captures/$name.png" > "$output/$name.png" || [[ ! -s "$output/$name.png" ]]; then
    echo "::error::Missing synthetic $name screenshot." >&2
    exit 1
  fi
done
# A launcher may disallow creating a home shortcut. Preserve that screenshot only if verified.
if adb shell run-as "$app_id" test -f files/synthetic-captures/launcher-home.png; then
  adb exec-out run-as "$app_id" cat files/synthetic-captures/launcher-home.png > "$output/launcher-home.png"
  method=$(adb shell run-as "$app_id" cat files/synthetic-captures/launcher-home-method.txt | tr -d '\r\n')
  printf '::notice title=Launcher home screen::The emulator home screen displayed the MyVault %s.\n' "$method"
else
  echo '::warning title=Launcher home screen::The app drawer was checked, but adding the icon to this emulator home screen was not confirmed.'
fi
