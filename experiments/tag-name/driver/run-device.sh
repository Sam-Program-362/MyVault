#!/usr/bin/env bash
# Run each isolated experiment in a fresh instrumentation process, preserving
# failures and system-process evidence. A native driver failure must not
# contaminate the platform test.
set -uo pipefail
root=experiments/tag-name/driver
runner_temp=${TAG_PROOF_EVIDENCE_DIR:-${RUNNER_TEMP:-/tmp}}
mkdir -p "$runner_temp"
status=0
read -r -a classes <<< "${PROOF_CLASSES:-PlatformSqlProofTest BundledDriverProofTest}"

snapshot_device() {
  local target=$1
  {
    echo "host_time_utc=$(date -u +%FT%TZ)"
    echo "api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
    echo "fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
    echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
    echo "emulator=$(adb shell getprop ro.kernel.qemu | tr -d '\r')"
    adb shell cat /proc/meminfo
  } > "$target.device"
  adb shell dumpsys meminfo system_server > "$target.system-before.txt" 2>&1 || true
}

for class in "${classes[@]}"; do
  repeats=1
  if [[ "$class" == PlatformSqlProofTest ]]; then repeats=${PROOF_REPEATS:-1}; fi
  for ((attempt=1; attempt<=repeats; attempt++)); do
    echo "::notice title=Running isolated proof class::$class attempt=$attempt/$repeats"
    log="$runner_temp/tag-driver-proof-$class-run-$attempt.log"
    base="${log%.log}"
    adb logcat -c || true
    snapshot_device "$base"
    adb logcat -b all -v threadtime > "$base.logcat-live.txt" 2>&1 &
    logcat_pid=$!

    (
      bash android/gradlew -p "$root" --no-daemon :app:connectedDebugAndroidTest \
        "-Pandroid.testInstrumentationRunnerArguments.class=com.myvault.driverproof.$class" 2>&1 | tee "$log"
    ) &
    gradle_pid=$!
    if [[ "$class" == PlatformSqlProofTest ]]; then
      (
        while kill -0 "$gradle_pid" 2>/dev/null; do
          {
            echo "host_time_utc=$(date -u +%FT%TZ)"
            adb shell dumpsys meminfo system_server 2>&1 | grep -E 'Applications Memory Usage|Dalvik Heap|Native Heap|TOTAL PSS|TOTAL RSS|TOTAL SWAP|Objects' || true
          } >> "$base.system-monitor.txt"
          sleep 10
        done
      ) &
      monitor_pid=$!
    else
      monitor_pid=
    fi

    wait "$gradle_pid"
    result=$?
    if [[ -n "$monitor_pid" ]]; then wait "$monitor_pid" || true; fi
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
    adb logcat -d -b crash -v threadtime > "$base.crash-buffer.txt" 2>&1 || true
    adb logcat -d -b all -v threadtime -s AndroidRuntime:E ActivityManager:E ConfigUpdateInstallReceiver:V > "$base.system-events.txt" 2>&1 || true
    adb shell dumpsys meminfo system_server > "$base.system-after.txt" 2>&1 || true

    python3 - "$result" "$log" "$root" "$class" "$attempt" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
status=int(sys.argv[1]); log=pathlib.Path(sys.argv[2]); root=pathlib.Path(sys.argv[3]); target=sys.argv[4]; attempt=sys.argv[5]
reports=list(root.glob('app/build/outputs/androidTest-results/connected/**/*.xml'))
cases=0
for report in reports:
 try: tree=ET.parse(report)
 except ET.ParseError: continue
 for case in tree.iter('testcase'):
  if not case.attrib.get('classname','').endswith('.'+target): continue
  cases+=1
  failures=list(case.findall('failure'))+list(case.findall('error'))
  if not failures: print('::notice title=Driver proof case passed::'+case.attrib.get('name','')+' attempt='+attempt)
  for failure in failures:
   text=(case.attrib.get('name','')+': '+failure.attrib.get('message','')+'\n'+(failure.text or ''))[-6000:]
   print('::error title=Driver proof device failure::'+text.replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
if status:
 text=log.read_text(errors='replace')[-6000:]
 print('::error title=Driver proof execution failure::'+text.replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
if not status and not cases:
 print('::error title=Driver proof::No test results for '+target);sys.exit(1)
PY
    report=$?
    python3 - "$base.system-events.txt" "$base.crash-buffer.txt" "$base.logcat-live.txt" <<'PYSYSTEM'
import pathlib,sys
for name in sys.argv[1:]:
 p=pathlib.Path(name)
 if not p.exists(): continue
 text=p.read_text(errors='replace')
 if 'FATAL EXCEPTION IN SYSTEM PROCESS' in text or ('OutOfMemoryError' in text and 'ConfigUpdateInstallReceiver' in text):
  print('::error title=Android system-process crash::System/OOM evidence captured in '+str(p))
  print('::error title=Android system-process exception::'+text[-12000:].replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
  sys.exit(1)
PYSYSTEM
    system_result=$?

    if [[ "$class" == PlatformSqlProofTest ]]; then
      adb logcat -d -v brief -s MVSqlProof:I > "$base.sql-trace.txt" 2>/dev/null || true
      python3 - "$base.sql-trace.txt" <<'PYTRACE'
import pathlib,sys
text=pathlib.Path(sys.argv[1]).read_text(errors='replace')
for line in text.splitlines():
 if any(key in line for key in ('Device API=', 'SELECT sqlite_version()', 'INPUT rows=', 'DEVICE totalRamKb=', 'DB page', 'MEASURE ', 'BENCH ', 'BEGIN SQL: SELECT identity FROM keys', 'END SQL: SELECT identity FROM keys')):
  print('::notice title=Platform SQL measurement::'+line.replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
PYTRACE
    fi

    if ((result != 0 || report != 0 || system_result != 0)); then
      python3 - "$base.system-events.txt" "$base.crash-buffer.txt" <<'PYLOG'
import pathlib,sys
for name in sys.argv[1:]:
 p=pathlib.Path(name); s=p.read_text(errors='replace')
 if 'FATAL EXCEPTION IN SYSTEM PROCESS' in s or 'OutOfMemoryError' in s:
  print('::error title=System process failure evidence::'+s[-12000:].replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
PYLOG
      status=1
    fi
  done
done
exit "$status"
