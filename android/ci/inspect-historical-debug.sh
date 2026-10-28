#!/usr/bin/env bash
# One-time public APK metadata audit; does not use any signing keys or secrets.
set -euo pipefail
: "${GITHUB_REPOSITORY:?}"
: "${GH_TOKEN:?}"
: "${RUNNER_TEMP:?}"
latest=40683365336
work="$RUNNER_TEMP/myvault-apk-evidence"
mkdir -p "$work"
# Only inspect this branch's prior published debug artifacts; do not access vault data.
gh api "repos/$GITHUB_REPOSITORY/actions/artifacts?per_page=100" --paginate \
  --jq ".artifacts[] | select(.name == \"myvault-debug-apk\" and .expired == false and .workflow_run.head_branch == \"arena/e04112a5-myvault\" and .workflow_run.id <= $latest) | [.workflow_run.id, .id] | @tsv" > "$work/artifacts.tsv"
[[ -s "$work/artifacts.tsv" ]] || { echo '::error::No earlier debug artifacts were available for the audit.' >&2; exit 1; }
: > "$work/results.tsv"
while IFS=$'\t' read -r run_id artifact_id; do
  [[ "$run_id" =~ ^[0-9]+$ && "$artifact_id" =~ ^[0-9]+$ ]] || continue
  mkdir -p "$work/$run_id"
  if ! gh run download "$run_id" --repo "$GITHUB_REPOSITORY" --name myvault-debug-apk --dir "$work/$run_id" > "$work/download.log" 2>&1; then
    printf '::warning::Could not inspect debug artifact %s from run %s.\n' "$artifact_id" "$run_id"
    continue
  fi
  apk="$work/$run_id/app-debug.apk"
  if ! identity=$(bash android/ci/apk-identity.sh "$apk"); then
    printf '::warning::Could not verify debug artifact %s from run %s.\n' "$artifact_id" "$run_id"
    continue
  fi
  printf '%s\t%s\n' "$run_id" "$identity" >> "$work/results.tsv"
done < "$work/artifacts.tsv"
python3 - "$work/results.tsv" "$latest" <<'PY'
import pathlib, sys
rows = [line.split('\t') for line in pathlib.Path(sys.argv[1]).read_text().splitlines()]
latest = next((row for row in rows if row[0] == sys.argv[2]), None)
if not latest:
    print('::error::The exact APK from the reported run could not be verified.')
    raise SystemExit(1)
others = [row for row in rows if row[0] != sys.argv[2]]
if not others:
    print('::error::No earlier debug APK could be verified for comparison.')
    raise SystemExit(1)
lines = ['Run | package ID | version code | version | public signing SHA-256']
lines += [' | '.join(row) for row in sorted(rows, key=lambda r: int(r[0]))]
lines.append('Earlier APKs with a different signer from the reported APK: ' + str(sum(row[4] != latest[4] for row in others)) + '/' + str(len(others)))
text = '\n'.join(lines).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::notice title=Historical debug APK identity comparison::{text}', flush=True)
PY
