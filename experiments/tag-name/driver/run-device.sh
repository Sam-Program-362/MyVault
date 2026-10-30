#!/usr/bin/env bash
# Run each isolated experiment in a fresh instrumentation process, preserving
# all failures. A native driver failure must not contaminate the platform test.
set -uo pipefail
root=experiments/tag-name/driver
status=0
for class in PlatformSqlProofTest BundledDriverProofTest; do
  log="${RUNNER_TEMP:-/tmp}/tag-driver-proof-$class.log"
  bash android/gradlew -p "$root" --no-daemon :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=com.myvault.driverproof.$class" 2>&1 | tee "$log"
  result=${PIPESTATUS[0]}
  python3 - "$result" "$log" "$root" "$class" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
status=int(sys.argv[1]); log=pathlib.Path(sys.argv[2]); root=pathlib.Path(sys.argv[3]); target=sys.argv[4]; reports=list(root.glob('app/build/outputs/androidTest-results/connected/**/*.xml'))
cases=0
for report in reports:
 try: tree=ET.parse(report)
 except ET.ParseError: continue
 for case in tree.iter('testcase'):
  if not case.attrib.get('classname','').endswith('.'+target): continue
  cases+=1
  failures=list(case.findall('failure'))+list(case.findall('error'))
  if not failures: print('::notice title=Driver proof case passed::'+case.attrib.get('name',''))
  for failure in failures:
   text=(case.attrib.get('name','')+': '+failure.attrib.get('message','')+'\n'+(failure.text or ''))[-6000:]
   print('::error title=Driver proof device failure::'+text.replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
if status and not cases:
 text=log.read_text(errors='replace')[-6000:]
 print('::error title=Driver proof execution failure::'+text.replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
if not status and not cases:
 print('::error title=Driver proof::No test results for '+target);sys.exit(1)
PY
  report=$?
  ((result == 0 && report == 0)) || status=1
done
exit "$status"
