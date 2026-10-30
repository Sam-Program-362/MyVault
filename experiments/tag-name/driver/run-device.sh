#!/usr/bin/env bash
set -uo pipefail
root=experiments/tag-name/driver
log="${RUNNER_TEMP:-/tmp}/tag-driver-proof.log"
bash android/gradlew -p "$root" --no-daemon :app:connectedDebugAndroidTest 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
python3 - "$status" "$log" "$root" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
status=int(sys.argv[1]); log=pathlib.Path(sys.argv[2]); root=pathlib.Path(sys.argv[3]); reports=list(root.glob('app/build/outputs/androidTest-results/connected/**/*.xml'))
cases=0
for report in reports:
 try: tree=ET.parse(report)
 except ET.ParseError: continue
 for case in tree.iter('testcase'):
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
 print('::error title=Driver proof::No test results');sys.exit(1)
if not status: print('::notice title=Driver proof::'+str(cases)+' test(s) passed')
PY
report=${PIPESTATUS[0]}
((status == 0)) || exit "$status"
exit "$report"
