#!/usr/bin/env bash
# Preserve Gradle's exit status and surface device-test failures as GitHub annotations.
set -uo pipefail
log_file="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/myvault-device-tests.log"
./gradlew --no-daemon --console=plain :app:connectedDebugAndroidTest 2>&1 | tee "$log_file"
status=${PIPESTATUS[0]}
# Optional, synthetic screenshot for inspecting this PR's emulator rendering.
if [[ "${MYVAULT_CAPTURE_UI:-false}" == true ]]; then
  screenshot="${log_file}.jpg"
  if adb exec-out cat /data/local/tmp/myvault-preview-test.jpg > "$screenshot" 2>/dev/null &&
     [[ "$(od -An -tx1 -N2 "$screenshot" | tr -d ' \n')" == ffd8 ]]; then
    encoded=$(base64 -w 0 "$screenshot")
    if ((${#encoded} < 45000)); then printf '::notice title=Preview screenshot::%s\n' "$encoded"; fi
  fi
fi
if ((status != 0)); then
  adb logcat -d -s AndroidRuntime:E ActivityManager:E > "${log_file}.logcat" 2>/dev/null || true
fi
python3 - "$status" "$log_file" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

status = int(sys.argv[1])
log = pathlib.Path(sys.argv[2])
reports = list(pathlib.Path("app/build/outputs/androidTest-results/connected").rglob("*.xml"))

def annotate(level, title, text):
    escaped = str(text)[:8000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::{level} title={title}::{escaped}", flush=True)

failures = 0
tests = 0
for path in reports:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        tests += 1
        for failure in list(case.findall("failure")) + list(case.findall("error")):
            failures += 1
            details = f"{case.get('classname', '')}.{case.get('name', '')}: {failure.get('message', '')}\n{failure.text or ''}"
            annotate("error", "Android device test failed", details[:8000])
if reports:
    annotate("notice", "Android device test summary", f"{tests} test(s), {failures} failure(s).")
if not status and not tests:
    annotate("error", "No Android device tests found", "Gradle succeeded without producing any instrumentation test cases.")
    sys.exit(1)
if status and not failures:
    excerpt = "\n".join(log.read_text(errors="replace").splitlines()[-100:])
    logcat = pathlib.Path(f"{log}.logcat")
    if logcat.exists():
        excerpt += "\nAndroid runtime log:\n" + "\n".join(logcat.read_text(errors="replace").splitlines()[-80:])
    annotate("error", "Android device tests failed", excerpt[-8000:])
PY
report_status=$?
if ((report_status != 0)); then exit "$report_status"; fi
exit "$status"
