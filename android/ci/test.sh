#!/usr/bin/env bash
# Tests use a disposable key and a fake apksigner. They never need repository secrets.
set -euo pipefail
ci_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/myvault-android-ci.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT

expect_failure() {
  if "$@" >"$tmp/output" 2>&1; then
    printf 'Expected command to fail: %s\n' "$*" >&2
    exit 1
  fi
}

# The source preflight should recognize Kotlin and Java files in the app package.
fixture="$tmp/project"
mkdir -p "$fixture/app/src/main/java/com/myvault/app" "$fixture/app/src/main/res/mipmap-hdpi"
expect_failure "$ci_dir/check-project.sh" "$fixture"
grep -q 'Missing com.myvault.app.MainActivity' "$tmp/output"
cat > "$fixture/app/src/main/java/com/myvault/app/MainActivity.kt" <<'KOTLIN'
package com.myvault.app
class MainActivity : ComponentActivity()
KOTLIN
cat > "$fixture/app/src/main/java/com/myvault/app/MyVaultApplication.java" <<'JAVA'
package com.myvault.app;
public final class MyVaultApplication extends Application {}
JAVA
touch "$fixture/app/src/main/res/mipmap-hdpi/ic_launcher.png"
expect_failure "$ci_dir/check-project.sh" "$fixture"
grep -q 'Missing @mipmap/ic_launcher_round' "$tmp/output"
touch "$fixture/app/src/main/res/mipmap-hdpi/ic_launcher_round.png"
"$ci_dir/check-project.sh" "$fixture" >/dev/null
sed -i 's/package com.myvault.app/package com.example.wrong/' "$fixture/app/src/main/java/com/myvault/app/MainActivity.kt"
expect_failure "$ci_dir/check-project.sh" "$fixture"
grep -q 'Missing com.myvault.app.MainActivity' "$tmp/output"
printf 'Android source preflight checks passed.\n'

# The following password and key are generated only for this test and deleted on exit.
export ANDROID_KEYSTORE_PASSWORD='temporary-ci-test-only'
export ANDROID_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
export ANDROID_KEY_ALIAS='test-key'
export RUNNER_TEMP="$tmp/runner"
export GITHUB_ENV="$tmp/github-env"
mkdir -p "$RUNNER_TEMP"
: > "$GITHUB_ENV"
keytool -genkeypair -alias "$ANDROID_KEY_ALIAS" -keystore "$tmp/test.p12" -storetype PKCS12 \
  -storepass:env ANDROID_KEYSTORE_PASSWORD -keypass:env ANDROID_KEY_PASSWORD \
  -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=Disposable CI test' >/dev/null 2>&1
export ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_BASE64=$(base64 < "$tmp/test.p12" | tr -d '\n')

expect_failure env -u ANDROID_KEY_PASSWORD "$ci_dir/prepare-signing.sh"
grep -q 'ANDROID_KEY_PASSWORD' "$tmp/output"
expect_failure env ANDROID_KEYSTORE_BASE64='not valid base64' "$ci_dir/prepare-signing.sh"
[[ ! -e "$RUNNER_TEMP/myvault-release.keystore" ]]
expect_failure env ANDROID_KEY_ALIAS='wrong-alias' "$ci_dir/prepare-signing.sh"
[[ ! -e "$RUNNER_TEMP/myvault-release.keystore" ]]
expect_failure env ANDROID_KEYSTORE_PASSWORD='wrong-password' "$ci_dir/prepare-signing.sh"
if grep -q 'wrong-password' "$tmp/output"; then
  printf 'A signing password appeared in the validation output.\n' >&2
  exit 1
fi
[[ ! -e "$RUNNER_TEMP/myvault-release.keystore" ]]

# A keystore containing only a trusted certificate is not a signing key.
keytool -exportcert -rfc -alias "$ANDROID_KEY_ALIAS" -keystore "$tmp/test.p12" \
  -storepass:env ANDROID_KEYSTORE_PASSWORD -file "$tmp/test.crt" >/dev/null 2>&1
keytool -importcert -noprompt -alias 'cert-only' -file "$tmp/test.crt" \
  -keystore "$tmp/cert-only.p12" -storetype PKCS12 -storepass:env ANDROID_KEYSTORE_PASSWORD >/dev/null 2>&1
cert_only=$(base64 < "$tmp/cert-only.p12" | tr -d '\n')
expect_failure env ANDROID_KEYSTORE_BASE64="$cert_only" ANDROID_KEY_ALIAS='cert-only' "$ci_dir/prepare-signing.sh"
[[ ! -e "$RUNNER_TEMP/myvault-release.keystore" ]]

"$ci_dir/prepare-signing.sh" >/dev/null
export ANDROID_KEYSTORE_PATH="$RUNNER_TEMP/myvault-release.keystore"
cmp "$tmp/test.p12" "$ANDROID_KEYSTORE_PATH"
grep -Fxq "ANDROID_KEYSTORE_PATH=$ANDROID_KEYSTORE_PATH" "$GITHUB_ENV"
[[ $(stat -c '%a' "$ANDROID_KEYSTORE_PATH") == 600 ]]
printf 'Signing secret validation and keystore decoding checks passed.\n'

# Exercise signer matching and failure handling with deterministic tool output.
expected=$(keytool -J-Duser.language=en -J-Duser.country=US -list -v -keystore "$tmp/test.p12" \
  -storepass:env ANDROID_KEYSTORE_PASSWORD -alias "$ANDROID_KEY_ALIAS" |
  awk '/^[[:space:]]*SHA256:/{if (!seen++) print $2}' | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
[[ ${#expected} == 64 ]]
cat > "$tmp/apksigner" <<'SIGNER'
#!/usr/bin/env bash
set -eu
if [[ ${FAKE_INVALID:-0} == 1 ]]; then exit 1; fi
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$FAKE_DIGEST"
if [[ ${FAKE_EXTRA_SIGNER:-0} == 1 ]]; then
  printf 'Signer #2 certificate SHA-256 digest: %s\n' "$FAKE_DIGEST"
fi
SIGNER
chmod +x "$tmp/apksigner"
printf 'fake APK used only to test control flow\n' > "$tmp/app-release.apk"
export ANDROID_HOME="$tmp/android-sdk"
export APKSIGNER="$tmp/apksigner"
export FAKE_DIGEST="$expected"
"$ci_dir/verify-signing.sh" "$tmp/app-release.apk" >/dev/null
expect_failure env FAKE_DIGEST='0000000000000000000000000000000000000000000000000000000000000000' \
  "$ci_dir/verify-signing.sh" "$tmp/app-release.apk"
expect_failure env FAKE_INVALID=1 "$ci_dir/verify-signing.sh" "$tmp/app-release.apk"
expect_failure env FAKE_EXTRA_SIGNER=1 "$ci_dir/verify-signing.sh" "$tmp/app-release.apk"
expect_failure "$ci_dir/verify-signing.sh" "$tmp/nonexistent.apk"
printf 'APK signature verification checks passed (apksigner output mocked).\n'

# Exercise package/version/signer checks with distinct synthetic APK identities.
cat > "$tmp/aapt" <<'AAPT'
#!/usr/bin/env bash
set -eu
file=${3##*/}
case "$file" in
  debug-*) app_id=com.myvault.app.debug ;;
  preview-*) app_id=com.myvault.app.preview ;;
  *) app_id=com.myvault.app ;;
esac
case "$file" in
  *-old.apk|*-same.apk) code=2 ;;
  high.apk) code=2147483648 ;;
  *-current.apk) code=5 ;;
  *) code=3 ;;
esac
major=''
if [[ "$file" == major.apk ]]; then major=" versionCodeMajor='1'"; fi
version_name='1.0.2'
if [[ "$file" == *-current.apk ]]; then version_name='1.0.4'; fi
printf "package: name='%s' versionCode='%s'%s versionName='%s'\n" "$app_id" "$code" "$major" "$version_name"
AAPT
cat > "$tmp/identity-signer" <<'SIGNER'
#!/usr/bin/env bash
set -eu
file=${@: -1}
case "$file" in *unsigned.apk) exit 1 ;; esac
case "$file" in *-other.apk) digest=$(printf 'b%.0s' {1..64}) ;; *) digest=$(printf 'a%.0s' {1..64}) ;; esac
if [[ "$file" == *malformed.apk ]]; then digest=not-a-fingerprint; fi
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$digest"
if [[ "$file" == *multi.apk ]]; then printf 'Signer #2 certificate SHA-256 digest: %s\n' "$digest"; fi
SIGNER
chmod +x "$tmp/aapt" "$tmp/identity-signer"
export AAPT="$tmp/aapt" APKSIGNER="$tmp/identity-signer"
for name in release-old release-new release-same release-other release-current debug-current debug-old debug-new debug-other preview-new unsigned multi malformed high major; do
  printf 'synthetic %s APK\n' "$name" > "$tmp/$name.apk"
done
export ANDROID_EXPECTED_CERT_SHA256
ANDROID_EXPECTED_CERT_SHA256=$(printf 'A:%.0s' {1..63}; printf A)
export ANDROID_INSTALLED_PACKAGE_ID=com.myvault.app ANDROID_INSTALLED_VERSION_CODE=2
"$ci_dir/verify-release-update.sh" "$tmp/release-new.apk" >/dev/null
env ANDROID_INSTALLED_PACKAGE_ID=none ANDROID_INSTALLED_VERSION_CODE=0 \
  "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk" >/dev/null
expect_failure env -u ANDROID_INSTALLED_PACKAGE_ID "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure env ANDROID_INSTALLED_PACKAGE_ID=com.myvault.app.debug "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure env ANDROID_INSTALLED_PACKAGE_ID=none "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure env ANDROID_INSTALLED_VERSION_CODE=0 "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure env -u ANDROID_EXPECTED_CERT_SHA256 "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure env -u ANDROID_INSTALLED_VERSION_CODE "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure env ANDROID_EXPECTED_CERT_SHA256=bad "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure env ANDROID_INSTALLED_VERSION_CODE=02 "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure env ANDROID_INSTALLED_VERSION_CODE=2147483648 "$ci_dir/verify-release-update.sh" "$tmp/release-new.apk"
expect_failure "$ci_dir/verify-release-update.sh" "$tmp/debug-new.apk"
expect_failure "$ci_dir/verify-release-update.sh" "$tmp/release-other.apk"
expect_failure "$ci_dir/verify-release-update.sh" "$tmp/release-same.apk"
expect_failure "$ci_dir/verify-release-update.sh" "$tmp/high.apk"
expect_failure "$ci_dir/verify-release-update.sh" "$tmp/major.apk"
expect_failure "$ci_dir/verify-release-update.sh" "$tmp/unsigned.apk"
expect_failure "$ci_dir/verify-release-update.sh" "$tmp/multi.apk"
expect_failure "$ci_dir/verify-release-update.sh" "$tmp/malformed.apk"
"$ci_dir/check-apk-update.sh" "$tmp/debug-old.apk" "$tmp/debug-new.apk" >/dev/null
expect_failure "$ci_dir/check-apk-update.sh" "$tmp/debug-old.apk" "$tmp/debug-other.apk"
grep -q 'Signing certificates differ' "$tmp/output"
expect_failure "$ci_dir/check-apk-update.sh" "$tmp/debug-old.apk" "$tmp/preview-new.apk"
grep -q 'separate installations' "$tmp/output"
expect_failure "$ci_dir/check-apk-update.sh" "$tmp/release-old.apk" "$tmp/release-same.apk"
expect_failure "$ci_dir/check-apk-update.sh" "$tmp/release-old.apk" "$tmp/unsigned.apk"
expect_failure "$ci_dir/check-apk-update.sh" "$tmp/release-old.apk" "$tmp/multi.apk"
bash "$ci_dir/verify-release-artifact.sh" "$tmp/release-current.apk" "$tmp/release-identity.txt" >/dev/null
grep -q '^Application ID: com.myvault.app$' "$tmp/release-identity.txt"
grep -q '^Version code: 5$' "$tmp/release-identity.txt"
expect_failure bash "$ci_dir/verify-release-artifact.sh" "$tmp/debug-current.apk" "$tmp/release-identity.txt"
expect_failure bash "$ci_dir/verify-release-artifact.sh" "$tmp/release-old.apk" "$tmp/release-identity.txt"
expect_failure bash "$ci_dir/verify-release-artifact.sh" "$tmp/unsigned.apk" "$tmp/release-identity.txt"
printf 'APK identity, incompatible-update and release reference checks passed (tools mocked).\n'

# Unit-test failures should be visible in CI annotations, not only in runner logs.
(
  fixture="$tmp/gradle-fixture"
  mkdir -p "$fixture/app/build/test-results/testDebugUnitTest"
  printf '#!/usr/bin/env bash\necho "* What went wrong:"\nexit 1\n' > "$fixture/gradlew"
  chmod +x "$fixture/gradlew"
  cat > "$fixture/app/build/test-results/testDebugUnitTest/TEST-fixture.xml" <<'XML'
<testsuite><testcase classname="Fixture" name="broken"><failure message="expected value">details</failure></testcase></testsuite>
XML
  cd "$fixture"
  expect_failure env RUNNER_TEMP="$fixture" bash "$ci_dir/run-gradle-check.sh" :app:testDebugUnitTest
  grep -q 'Fixture.broken: expected value' "$tmp/output"
  grep -q '::error title=Android build failed::' "$tmp/output"
)
printf 'Gradle unit-test diagnostics passed (Gradle mocked).\n'

# android-emulator-runner invokes every script input line separately using sh -c.
workflow="$ci_dir/../../.github/workflows/android-apk.yml"
commands=$(awk '/^          script: \|/ {inside=1; next} inside && /^      - name:/ {exit} inside && /^            / {sub(/^            /, ""); print}' "$workflow")
[[ -n "$commands" ]] || { echo 'Emulator workflow commands were not found.' >&2; exit 1; }
while IFS= read -r line; do
  # shellcheck disable=SC2016 # Match the literal GitHub expression before sh parses it.
  line=${line//'${{ matrix.api }}'/30}
  sh -n -c "$line" || { printf 'Incomplete emulator command: %s\n' "$line" >&2; exit 1; }
done <<< "$commands"
printf 'Emulator workflow commands are independently valid shell.\n'

# The offline runner must never alter a phone or an emulator without explicit opt-in.
# A fake adb exercises its guards, diagnostics, and two-phase control flow without a device.
(
  fixture="$tmp/offline-fixture"
  mkdir -p "$fixture/bin" "$fixture/ci" "$fixture/runner" "$fixture/app/build/outputs/apk/debug" \
    "$fixture/app/build/outputs/apk/androidTest/debug"
  printf 'print("mock preview migration")\n' > "$fixture/ci/run-preview-migration-test.py"
  touch "$fixture/app/build/outputs/apk/debug/app-debug.apk" \
    "$fixture/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$fixture/old.apk"
  cat > "$fixture/bin/adb" <<'ADB'
#!/usr/bin/env bash
set -u
if [[ ${1:-} == get-serialno ]]; then printf '%s\n' "${MOCK_SERIAL:-emulator-5554}"; exit 0; fi
if [[ ${1:-} == install && ${MOCK_INSTALL_FAILURE:-0} == 1 ]]; then
  echo 'simulated install error' >&2
  exit 4
fi
if [[ ${1:-} == install && $* == *conflict.apk && ${MOCK_ACCEPT_CONFLICT:-0} != 1 ]]; then
  echo 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: different signer]' >&2
  exit 1
fi
if [[ $* == 'shell settings get global airplane_mode_on' ]]; then printf '1\n'; exit 0; fi
if [[ $* == 'shell settings get global '* ]]; then printf '0\n'; exit 0; fi
if [[ $* == 'shell cmd overlay list' ]]; then
  if [[ ${MOCK_NO_OVERLAY:-0} != 1 ]]; then printf '[ ] com.android.internal.display.cutout.emulation.corner\n'; fi
  exit 0
fi
if [[ $* == 'shell am broadcast '* ]]; then echo 'protected broadcast denied' >&2; exit 4; fi
if [[ $* == 'shell am instrument '* ]]; then printf 'OK (1 test)\n'; exit 0; fi
if [[ $* == 'exec-out run-as '* ]]; then printf 'synthetic screenshot bytes'; exit 0; fi
if [[ $* == 'shell run-as '*launcher-home-method.txt ]]; then printf 'pinned shortcut'; exit 0; fi
exit 0
ADB
  chmod +x "$fixture/bin/adb"
  cd "$fixture"
  unset MYVAULT_PREVIOUS_APK MYVAULT_CONFLICT_APK MYVAULT_PREVIEW_APK MYVAULT_DISPOSABLE_EMULATOR MYVAULT_REQUIRE_CUTOUT MOCK_SERIAL MOCK_INSTALL_FAILURE MOCK_ACCEPT_CONFLICT MOCK_NO_OVERLAY
  export PATH="$fixture/bin:$PATH" RUNNER_TEMP="$fixture/runner"
  expect_failure env -u MYVAULT_DISPOSABLE_EMULATOR bash "$ci_dir/run-offline-restart-test.sh"
  grep -q 'MYVAULT_DISPOSABLE_EMULATOR=1' "$tmp/output"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MOCK_SERIAL=physical-123 \
    bash "$ci_dir/run-offline-restart-test.sh"
  grep -q 'not a physical device' "$tmp/output"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MOCK_INSTALL_FAILURE=1 \
    bash "$ci_dir/run-offline-restart-test.sh"
  grep -q 'simulated install error' "$tmp/output"
  grep -q '::error title=Offline emulator test failed::' "$tmp/output"
  env MYVAULT_DISPOSABLE_EMULATOR=1 MYVAULT_PREVIOUS_APK="$fixture/old.apk" \
    bash "$ci_dir/run-offline-restart-test.sh" > "$fixture/success"
  grep -q 'Offline upgrade and restart' "$fixture/success"
  env MYVAULT_DISPOSABLE_EMULATOR=1 MYVAULT_PREVIOUS_APK="$fixture/old.apk" \
    MYVAULT_CONFLICT_APK="$fixture/conflict.apk" MYVAULT_PREVIEW_APK="$fixture/preview.apk" \
    bash "$ci_dir/run-offline-restart-test.sh" > "$fixture/conflict-success"
  grep -q 'Incompatible update rejected safely' "$fixture/conflict-success"
  grep -q 'Side-by-side preview' "$fixture/conflict-success"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MYVAULT_CONFLICT_APK="$fixture/conflict.apk" \
    MOCK_ACCEPT_CONFLICT=1 bash "$ci_dir/run-offline-restart-test.sh"
  grep -q 'unexpectedly accepted' "$tmp/output"
  expect_failure env -u MYVAULT_DISPOSABLE_EMULATOR python3 "$ci_dir/run-preview-migration-test.py"
  grep -q 'disposable emulator' "$tmp/output"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MOCK_SERIAL=physical-123 \
    python3 "$ci_dir/run-preview-migration-test.py"
  grep -q 'disposable emulator' "$tmp/output"
  expect_failure env -u MYVAULT_DISPOSABLE_EMULATOR bash "$ci_dir/run-motion-inset-tests.sh"
  grep -q 'disposable emulator' "$tmp/output"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MOCK_SERIAL=physical-123 \
    bash "$ci_dir/run-motion-inset-tests.sh"
  grep -q 'disposable emulator' "$tmp/output"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MOCK_INSTALL_FAILURE=1 \
    bash "$ci_dir/run-motion-inset-tests.sh"
  grep -q 'simulated install error' "$tmp/output"
  env MYVAULT_DISPOSABLE_EMULATOR=1 MYVAULT_REQUIRE_CUTOUT=1 \
    bash "$ci_dir/run-motion-inset-tests.sh" > "$fixture/motion-success"
  grep -q 'Android motion and cutout' "$fixture/motion-success"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MYVAULT_REQUIRE_CUTOUT=1 MOCK_NO_OVERLAY=1 \
    bash "$ci_dir/run-motion-inset-tests.sh"
  grep -q 'required corner-cutout overlay' "$tmp/output"
  expect_failure env -u MYVAULT_DISPOSABLE_EMULATOR bash "$ci_dir/capture-synthetic-ui.sh"
  grep -q 'disposable emulator' "$tmp/output"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MOCK_SERIAL=physical-123 \
    bash "$ci_dir/capture-synthetic-ui.sh"
  grep -q 'disposable emulator' "$tmp/output"
  env MYVAULT_DISPOSABLE_EMULATOR=1 bash "$ci_dir/capture-synthetic-ui.sh" > "$fixture/capture-success"
  grep -q 'Launcher home screen::The emulator home screen displayed the MyVault pinned shortcut' "$fixture/capture-success"
  [[ -s app/build/reports/synthetic-screenshots/welcome.png ]]
  expect_failure env -u MYVAULT_DISPOSABLE_EMULATOR bash "$ci_dir/run-native-window-evidence.sh"
  grep -q 'disposable emulator' "$tmp/output"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MOCK_SERIAL=physical-123 \
    bash "$ci_dir/run-native-window-evidence.sh"
  grep -q 'not a physical device' "$tmp/output"
  expect_failure env -u MYVAULT_DISPOSABLE_EMULATOR python3 "$ci_dir/record-native-motion.py"
  grep -q 'disposable emulator' "$tmp/output"
  expect_failure env MYVAULT_DISPOSABLE_EMULATOR=1 MOCK_SERIAL=physical-123 \
    python3 "$ci_dir/record-native-motion.py"
  grep -q 'disposable emulator' "$tmp/output"
)
printf 'Offline, motion, cutout and capture guards passed (adb mocked).\n'
