# Android app and APK builds

## Native app

The Android app uses native Compose screens, the existing Room database and DataStore settings; it does not wrap the website. The dark welcome screen uses the saved app name, accent and optional wallpaper. The dashboard, editor and settings honor the saved light/dark/system appearance. Create a space, add an entry, and use the editor's preview button for the sliding Markdown preview. Changes are autosaved and are flushed before the editor closes. Spaces, tags, pinning, cover images, text/Markdown import, search, theme settings and JSON import/export are available in the app. Android and the web app keep separate data; there is no automatic sync.

The entry screen has Cards and List controls. Cards remain the default; List shows a title, a one-line text preview, a count of stored logical lines, and an independent expand button for each entry. A trailing newline counts as a blank final line; empty content has zero lines. The selected layout is an Android-only DataStore preference, persists offline across restarts, and is intentionally not added to the web-compatible JSON settings. Android draws the background behind the system bars and keeps controls inside the safe insets. On Android 7 in light mode, a translucent navigation-bar scrim preserves the older system's white icon contrast.

Images selected with the Android document picker are copied to private app storage (up to 12 MB per image). Android JSON exports embed readable local covers and wallpaper as data URIs. Imports merge matching spaces and tags by name and add entries without deleting existing entries; appearance settings are restored only if selected. Importing the same backup twice adds the entries twice. Text imports are limited to 4 MB and JSON imports to 128 MB. When importing into the web app, its existing importer assigns new timestamps and creates new tags as reusable; server request-size limits can also affect large image-containing backups.

Backups from older versions or from the web can contain external URLs, temporary document URIs, or server-relative paths such as `/uploads/...` instead of the image bytes. Those references may not work on Android or another device and will need to be re-selected; the app warns when exporting or importing external image references. Keep the original images and verify a backup before relying on it. The app itself has no internet permission, so remote image URLs are not fetched. Images replaced or removed from entries can remain as unused private files until the app is uninstalled. Android system backup/device-transfer rules also include the database and private files; whether they run depends on the device's settings. Wallpaper blur requires Android 12 or later.

## APK workflow

`.github/workflows/android-apk.yml` runs unit tests, builds a **debug APK**, verifies a release build with a disposable test key (never uploaded), and runs Room, backup and Compose UI tests on API 30 and 34 emulators for Android-related pull requests and pushes to `main`. It also checks adaptive icon layers, edge-to-edge insets, an emulated display cutout when supported, and list animations with system motion enabled. Its offline upgrade check builds the previous native APK from commit `8f28127`, seeds a synthetic vault in airplane mode, updates in place to the new APK with the same disposable debug key, force-stops and restarts it, then checks the saved notes, tags, settings, private image, JSON backup and list preference. It also attempts a same-package update signed with a different disposable key, checks that the emulator rejects it without losing the vault, and exercises a separately signed preview and synthetic backup migration. The emulator jobs also compare real `MainActivity` screenshots from the pre-fix and current APKs, using the same synthetic Room fixtures, and record real-time touch interactions with motion enabled. The evidence includes window/system-gesture inset measurements, welcome, empty/populated dashboard, Cards/List, editor, keyboard, settings and a dialog. The API 30 run additionally uploads synthetic composable and launcher fixtures. These are emulator checks, not physical-phone tests. Once the workflow exists on the default branch it can be started from **Actions → Android APK → Run workflow**, choosing `debug` or `release`. On the current assigned PR branch, before that workflow exists on the default branch, an owner push with the exact commit subject `build: package signed Android APK` also requests the same secret-backed release job. Other pushes and pull requests do not trigger signing.

After a successful run, download `myvault-debug-apk`, `myvault-preview-unsigned-apk`, `myvault-unsigned-release-apk`, or (from a trusted release run) `myvault-signed-apk` from the run's **Artifacts** section. Artifacts expire after 14 days. The signed artifact contains the installable `app-release.apk` and its public `SIGNED-APK-IDENTITY.txt`; the two separately labeled unsigned candidates are not installable as-is. Debug APKs are labeled **MyVault (Debug)** and use the separate `com.myvault.app.debug` ID; they are for testing, not publishing, and do not update a release app. Each debug artifact includes its public package, version and signing-certificate fingerprint in `DEBUG-APK-IDENTITY.txt`. No production signing secrets are required for debug or unsigned builds.

GitHub-hosted runners independently generate debug signing keys. For this project, all 20 available earlier debug APKs inspected had `com.myvault.app.debug` but a different certificate from run `40683365336`, so Android would reject their update with that APK. The exact installed run on a particular phone must still be identified. Reusing a keystore for another package is not itself a conflict, and the release-keystore secrets are not used by debug builds. **Do not uninstall the existing app or clear its data.**

If you still possess the **exact original debug signing key**, you may re-sign the newer debug APK locally with that key, then compare the installed APK and the re-signed candidate using `check-apk-update.sh` below. The package and certificate must match and the versionCode must be higher; a release key does not help unless its certificate actually matches that debug installation. GitHub-hosted debug keys are usually discarded with the runner, so do not assume they are recoverable. With an existing original debug key, `apksigner` can replace the candidate's signature without putting passwords on the command line:

```sh
"$ANDROID_HOME/build-tools/34.0.0/apksigner" sign --ks /secure/path/to/original-debug.keystore \
  --ks-key-alias ORIGINAL_DEBUG_ALIAS --out myvault-debug-update.apk app-debug.apk
bash android/ci/check-apk-update.sh installed-debug-base.apk myvault-debug-update.apk
```

If the old debug signing key is unavailable, export and verify a private JSON backup from the old app and keep the old installation intact. The unsigned **MyVault (Preview)** artifact has its own `com.myvault.app.preview` ID and can be signed locally with a stable key you control, installed alongside the old app, and used to import the backup. See `PREVIEW-README.md` in that artifact for the steps and limitations. The preview, debug and release packages are separate installations, not updates of one another. Verify any already-installed preview's certificate and version before trying to update it.

A release run requires the four existing secrets below. It validates the keystore and alias, builds with required release signing, verifies the APK signature against the configured key, checks the package and version, removes the temporary keystore even on failure, and uploads the signed APK. An independent installed-app reference is optional, but when supplied all three fields are required and a mismatch blocks upload. Without it the artifact is signed but update compatibility is **unverified**. CI cannot inspect your phone or prove that a supplied reference actually came from it. The job does not publish a GitHub Release or upload to Google Play. The workflow uses GitHub's automatically supplied read-only token; no personal access token is required.

## Release signing secrets

In the repository, open **Settings → Secrets and variables → Actions → New repository secret** and add these four **repository secrets**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The **entire keystore file** encoded as a single-line Base64 string (not a path). Base64 is not encryption; protect this value like the private key. |
| `ANDROID_KEYSTORE_PASSWORD` | The password that opens the keystore. |
| `ANDROID_KEY_ALIAS` | The alias of the private key entry inside the keystore. |
| `ANDROID_KEY_PASSWORD` | The password for that private key. It can be the same as the keystore password. |

`ANDROID_KEYSTORE_PATH` is created automatically on the runner and is **not** a GitHub secret. Do not put the keystore, Base64 value, passwords or alias in chat, issues, commits or workflow files. The SHA-256 certificate fingerprint included with the signed artifact is public metadata, not the private signing key.

### Use the existing signing key

For an already distributed app, use the original signing key and its actual alias and passwords. Never replace a lost original release key with a new one and claim it will update the existing installation. Reusing the same key for another package is not itself a conflict. With Google Play App Signing, the Play upload key and app-signing key can differ; an APK signed only with the upload key cannot necessarily update a Play-installed app. Keep a secure backup of the correct key and passwords.

To inspect an existing keystore locally, run `keytool -list -v -keystore /path/to/your-keystore` and check that the intended alias is a `PrivateKeyEntry`. This prompts for the password; do not put passwords on the command line or share the output if it contains private operational details. A keystore's own certificate alone is **not** evidence that it matches the phone: obtain and compare the phone's public certificate independently.

To create the value for `ANDROID_KEYSTORE_BASE64`, run this on your own computer, replacing the path:

```sh
python3 -c "import base64,pathlib; print(base64.b64encode(pathlib.Path('/path/to/your-keystore.p12').read_bytes()).decode())"
```

On Windows, use `py` instead of `python3` and a Windows file path. Paste the resulting single line only into the GitHub secret form. Never share it in chat. The repository ignores common keystore and encoded-keystore filenames, but a safe backup should be stored outside the repository.

### Verify an existing installation

Android in-place updates require the same package ID and a compatible signing identity. This project's automated check conservatively requires the **same current certificate** and a **strictly higher** versionCode; signing-key rotation needs separate expert review. For a release update, identify the exact `com.myvault.app` currently on the phone and export a backup first. If you have its exact original signed APK, inspect that; otherwise, on a trusted computer with Android platform-tools and build-tools 34.0.0, connect the phone and obtain its installed base APK without touching the vault data:

```sh
adb shell pm path com.myvault.app
# Copy the exact base.apk path from the output, then:
adb pull '/data/app/.../base.apk' installed-release-base.apk
bash android/ci/apk-identity.sh installed-release-base.apk
```

Replace the example path with the one returned by `pm path`. If there is no such installed package, do not assume another package is its predecessor: check `.debug` or `.preview` separately. The tool verifies the APK and prints its package ID, versionCode, versionName and public SHA-256 signing-certificate fingerprint. Compare the installed APK with a candidate using:

```sh
bash android/ci/check-apk-update.sh installed-release-base.apk myvault-release.apk
```

The first file must truly be the currently installed base APK; comparing an arbitrary downloaded artifact does not establish what is on the phone. A failed, unsigned or multi-signer check is not permission to uninstall or clear data. If the original signing key or installed identity cannot be confirmed, keep the old installation and use the backup/side-by-side migration path instead.

For a trusted manual signed-release run, enter the independently verified package ID `com.myvault.app` in `release_installed_package_id`, its public certificate SHA-256 in `release_cert_sha256`, and its versionCode in `release_installed_version_code`. These are public metadata inputs, not additional secrets. If any reference field is supplied, the job requires all three and refuses upload for incompatible values. Leave all three blank to produce a signed APK without claiming update compatibility. Enter package `none` and versionCode `0` **only** after confirming that `com.myvault.app` is not installed and this is a fresh installation; in that case use the owner-selected key's public fingerprint and do not describe the APK as an update. This branch's release version is `1.0.4` / code `5`; a device already on code `5` or higher needs a higher future versionCode. The exact physical phone and original key remain the owner's responsibility to verify.

## Local builds and checks

Install JDK 17 and Android SDK 34, then:

```sh
cd android
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

With an Android emulator or device connected, also run the Room, backup and Compose UI tests:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

For a signed local release, set `ANDROID_KEYSTORE_PATH` to the **absolute path** of your keystore and set the three password/alias environment variables above privately, or use an ignored `android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Then run:

```sh
./gradlew --no-daemon -PrequireReleaseSigning=true :app:assembleRelease
```

The `requireReleaseSigning` flag makes missing or incomplete credentials fail the build. Without signing credentials, a local release build is unsigned; it never falls back to the debug key.

Alternatively, sign the downloaded **unsigned** candidate on your own computer using the Android SDK build tools and your existing keystore. `apksigner` prompts for passwords when they are not supplied on the command line:

```sh
"$ANDROID_HOME/build-tools/34.0.0/zipalign" -f -p 4 app-release-unsigned.apk app-aligned.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" sign --ks /secure/path/to/keystore --ks-key-alias YOUR_ALIAS --out myvault-release.apk app-aligned.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs myvault-release.apk
```

Replace the path and alias locally; never share the keystore or passwords. Compare the signed candidate with the exact APK installed on the phone using `check-apk-update.sh` above before attempting an update. The secret-backed release workflow already produces a signed artifact; an independent reference is checked when supplied.

The workflow's source and signing helper checks can be run separately with a JDK on `PATH`:

```sh
bash android/ci/test.sh
```

Those helper tests use a disposable key and a mocked `apksigner`; they do not replace a real Android build, signature verification or device test.

## Physical-phone verification

A CI emulator is not a substitute for testing your actual phone and signing key. Before updating an existing installation, export a JSON backup, keep a separate copy of any original images, and verify the backup. **Do not uninstall or clear app data to resolve a signing error.** Uninstalling removes that installation's local vault.

1. For an isolated trial while retaining an old debug installation, follow the locally signed `com.myvault.app.preview` backup/import instructions above. A new CI debug APK may conflict with an older `.debug` installation and cannot see the release app's data.
2. To test preservation on a release installation, use a release APK with the same `com.myvault.app` package ID, a newer version code, and the **same signing certificate** as the installed app. This branch's release APK is version `1.0.4` (version code `5`); verify the installed version if its origin is unknown. If Android refuses the update, stop and check the signing key and version rather than uninstalling. A CI debug key or disposable release-test key is not suitable for updating a release installation.
3. Check the welcome screen and both empty and populated spaces. Switch Cards/List, expand individual rows, check the line counts, and open an entry from a row. Edit its title and Markdown, cover, tags and pin; try preview, Back, search, settings and dialogs. Let the save indicator finish. Check the system bars/cutout, launcher icon and motion on your phone.
4. Export and verify a JSON backup; try importing a copy into an isolated test vault. Enable airplane mode and disable Wi-Fi, reopen the app, and confirm reading and editing still work.
5. After a save finishes, force-stop the app from Android's App info screen and launch it again. Check entries, tags, appearance, images and the selected Cards/List layout. Test your phone's keyboard, gesture navigation and screen rotation as well.

The extra CI scripts change emulator network, motion and cutout settings and write synthetic vault data. They refuse a physical device and require `MYVAULT_DISPOSABLE_EMULATOR=1`; use them only on an emulator whose data can be discarded.
