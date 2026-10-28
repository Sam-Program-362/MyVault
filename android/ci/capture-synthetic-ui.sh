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
  for extension in png jpg; do
    if ! adb exec-out run-as "$app_id" cat "files/synthetic-captures/$name.$extension" > "$output/$name.$extension" || [[ ! -s "$output/$name.$extension" ]]; then
      echo "::error::Missing synthetic $name.$extension screenshot." >&2
      exit 1
    fi
  done
done
# Small thumbnails are annotated solely so CI diagnostics can be reviewed without artifact access.
# Full-resolution PNGs are uploaded as an artifact. The fixtures never contain a user's data.
python3 - "$output" <<'PY'
import base64, pathlib, sys
for name in ('welcome', 'cards', 'list', 'launcher'):
    encoded = base64.b64encode((pathlib.Path(sys.argv[1]) / f'{name}.jpg').read_bytes()).decode()
    chunks = [encoded[i:i+3500] for i in range(0, len(encoded), 3500)]
    for index, chunk in enumerate(chunks, 1):
        print(f'::notice title=Synthetic {name} {index}/{len(chunks)}::{chunk}', flush=True)
PY
