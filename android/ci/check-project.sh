#!/usr/bin/env bash
# Fail before packaging an APK that is missing its manifest entry points.
set -euo pipefail

project_dir=${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
main_dir="$project_dir/app/src/main"
missing=0

has_app_class() {
  local class_name=$1 file
  while IFS= read -r -d '' file; do
    if grep -Eq '^[[:space:]]*package[[:space:]]+com\.myvault\.app([[:space:];]|$)' "$file" &&
       grep -Eq "^[[:space:]]*([[:alnum:]_]+[[:space:]]+)*class[[:space:]]+${class_name}([^[:alnum:]_]|$)" "$file"; then
      return 0
    fi
  done < <(find "$main_dir" -type f \( -name '*.kt' -o -name '*.java' \) -print0 2>/dev/null)
  return 1
}

for class_name in MainActivity MyVaultApplication; do
  if ! has_app_class "$class_name"; then
    printf '::error::Missing com.myvault.app.%s declared by AndroidManifest.xml. Restore the Android app sources; see android/README.md.\n' "$class_name" >&2
    missing=1
  fi
done

shopt -s nullglob
for resource in ic_launcher ic_launcher_round; do
  files=("$main_dir"/res/mipmap*/"$resource".*)
  if ((${#files[@]} == 0)); then
    printf '::error::Missing @mipmap/%s declared by AndroidManifest.xml. Restore the launcher resources; see android/README.md.\n' "$resource" >&2
    missing=1
  fi
done

if ((missing)); then
  exit 1
fi
printf 'Android manifest entry points and launcher resources are present.\n'
