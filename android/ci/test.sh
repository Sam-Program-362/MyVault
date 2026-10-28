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

# The offline runner must never alter a phone or an emulator without explicit opt-in.
# A fake adb exercises its guards, diagnostics, and two-phase control flow without a device.
(
  fixture="$tmp/offline-fixture"
  mkdir -p "$fixture/bin" "$fixture/runner" "$fixture/app/build/outputs/apk/debug" \
    "$fixture/app/build/outputs/apk/androidTest/debug"
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
if [[ $* == 'shell settings get global airplane_mode_on' ]]; then printf '1\n'; exit 0; fi
if [[ $* == 'shell am broadcast '* ]]; then echo 'protected broadcast denied' >&2; exit 4; fi
if [[ $* == 'shell am instrument '* ]]; then printf 'OK (1 test)\n'; exit 0; fi
exit 0
ADB
  chmod +x "$fixture/bin/adb"
  cd "$fixture"
  unset MYVAULT_PREVIOUS_APK MYVAULT_DISPOSABLE_EMULATOR MOCK_SERIAL MOCK_INSTALL_FAILURE
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
)
printf 'Offline emulator runner guards and diagnostics passed (adb mocked).\n'
