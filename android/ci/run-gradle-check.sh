#!/usr/bin/env bash
# Run a non-secret CI build and surface Kotlin/Gradle errors in check annotations.
set -uo pipefail
log_file="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/myvault-gradle-check.log"
./gradlew --no-daemon --console=plain "$@" 2>&1 | tee "$log_file"
status=${PIPESTATUS[0]}
if ((status != 0)); then
  python3 - "$log_file" <<'PY'
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
errors = [line for line in lines if line.startswith("e: ") or line.startswith("* What went wrong:")]
if not errors:
    errors = lines[-70:]
message = "\n".join(errors)[-8000:].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title=Android build failed::{message}", flush=True)
PY
fi
exit "$status"
