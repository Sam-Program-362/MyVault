# Android app and APK builds

## Native app

The Android app uses native Compose screens, the existing Room database and DataStore settings; it does not wrap the website. The dark welcome screen uses the saved app name, accent and optional wallpaper. The dashboard, editor and settings honor the saved light/dark/system appearance. Create a space, add an entry, and use the editor's preview button for the sliding Markdown preview. Changes are autosaved and are flushed before the editor closes. Spaces, tags, pinning, cover images, text/Markdown import, search, theme settings and JSON import/export are available in the app. Android and the web app keep separate data; there is no automatic sync.

The entry screen has Cards and List controls. Cards remain the default; List shows a title, a one-line text preview, a count of stored logical lines, and an independent expand button for each entry. A trailing newline counts as a blank final line; empty content has zero lines. The selected layout is an Android-only DataStore preference, persists offline across restarts, and is intentionally not added to the web-compatible JSON settings.

Images selected with the Android document picker are copied to private app storage (up to 12 MB per image). Android JSON exports embed readable local covers and wallpaper as data URIs. Imports merge matching spaces and tags by name and add entries without deleting existing entries; appearance settings are restored only if selected. Importing the same backup twice adds the entries twice. Text imports are limited to 4 MB and JSON imports to 128 MB. When importing into the web app, its existing importer assigns new timestamps and creates new tags as reusable; server request-size limits can also affect large image-containing backups.

Backups from older versions or from the web can contain external URLs, temporary document URIs, or server-relative paths such as `/uploads/...` instead of the image bytes. Those references may not work on Android or another device and will need to be re-selected; the app warns when exporting or importing external image references. Keep the original images and verify a backup before relying on it. The app itself has no internet permission, so remote image URLs are not fetched. Images replaced or removed from entries can remain as unused private files until the app is uninstalled. Android system backup/device-transfer rules also include the database and private files; whether they run depends on the device's settings. Wallpaper blur requires Android 12 or later.

## APK workflow

`.github/workflows/android-apk.yml` runs unit tests, builds a **debug APK**, verifies a release build with a disposable test key (never uploaded), and runs Room, backup and Compose UI tests on API 30 and 34 emulators for Android-related pull requests and pushes to `main`. It also checks adaptive icon layers, edge-to-edge insets, an emulated display cutout when supported, and list animations with system motion enabled. Its offline upgrade check builds the previous native APK from commit `8f28127`, seeds a synthetic vault in airplane mode, updates in place to the new APK with the same disposable debug key, force-stops and restarts it, then checks the saved notes, tags, settings, private image, JSON backup and list preference. These are emulator checks, not physical-phone or actual-release-key tests. The workflow can also be started manually from **Actions → Android APK → Run workflow**, choosing `debug` or `release`. Manual signed releases are restricted to the default branch; the workflow must first be merged into that branch before the Run workflow button is available.

After a successful run, download `myvault-debug-apk`, `myvault-unsigned-release-apk`, or (from a trusted release run) `myvault-release-apk` from the run's **Artifacts** section. Artifacts expire after 14 days. The unsigned release candidate cannot be installed until it is signed; it is provided for private local signing, not as a finished release. Debug APKs use Android's debug key and the `.debug` application ID; they are for testing, not publishing, and do not update the release app. Debug keys can differ between workflow runs. If an old debug installation must be uninstalled to install a new one, export its data first: uninstalling deletes its private data. No signing secrets are required for debug or unsigned builds.

A release run requires all four secrets below. It validates the keystore, builds with required release signing, verifies the APK signature and certificate against the provided key, deletes the temporary keystore, and only then uploads the signed APK. It does not publish a GitHub Release or upload to Google Play. The workflow uses GitHub's automatically supplied read-only token; no personal access token is required.

## Release signing secrets

In the repository, open **Settings → Secrets and variables → Actions → New repository secret** and add these four **repository secrets**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The **entire keystore file** encoded as a single-line Base64 string (not a path). Base64 is not encryption; protect this value like the private key. |
| `ANDROID_KEYSTORE_PASSWORD` | The password that opens the keystore. |
| `ANDROID_KEY_ALIAS` | The alias of the private key entry inside the keystore. |
| `ANDROID_KEY_PASSWORD` | The password for that private key. It can be the same as the keystore password. |

`ANDROID_KEYSTORE_PATH` is created automatically on the runner and is **not** a GitHub secret. Do not put the keystore, Base64 value, passwords or alias in chat, issues, commits or workflow files.

### Obtain a keystore

If this app has already been distributed, use the same signing key required to update those installations. With Google Play App Signing, the Play upload key and the app-signing key can differ; an APK signed only with the upload key cannot necessarily update a Play-installed app. Keep a secure backup of the correct key and passwords: changing or losing the signing key can prevent updates.

Only if you are starting a new app and do not already have a key, generate one **on your own computer** with Java's `keytool`:

```sh
keytool -genkeypair -keystore myvault-release.p12 -storetype PKCS12 \
  -alias myvault -keyalg RSA -keysize 3072 -validity 10000
```

Follow the interactive prompts; do not put passwords on the command line. For this PKCS12 keytool example, use the same password for `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD`; `ANDROID_KEY_ALIAS` is `myvault`. For an existing keystore, use its actual alias and key password. To confirm an alias, run `keytool -list -v -keystore /path/to/your-keystore` and check that its entry type is `PrivateKeyEntry`.

To create the value for `ANDROID_KEYSTORE_BASE64`, run this on your own computer, replacing the path:

```sh
python3 -c "import base64,pathlib; print(base64.b64encode(pathlib.Path('/path/to/your-keystore.p12').read_bytes()).decode())"
```

On Windows, use `py` instead of `python3` and a Windows file path. Paste the resulting single line only into the GitHub secret form. Never share it in chat. The repository ignores common keystore and encoded-keystore filenames, but a safe backup should be stored outside the repository.

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

Replace the path and alias locally; never share the keystore or passwords. Verify the displayed certificate is the one required by the existing installation before attempting an update. The default-branch release workflow is the alternative that signs with the configured GitHub secrets.

The workflow's source and signing helper checks can be run separately with a JDK on `PATH`:

```sh
bash android/ci/test.sh
```

Those helper tests use a disposable key and a mocked `apksigner`; they do not replace a real Android build, signature verification or device test.

## Physical-phone verification

A CI emulator is not a substitute for testing your actual phone and signing key. Before updating an existing installation, export a JSON backup, keep a separate copy of any original images, and verify the backup. **Do not uninstall or clear app data to resolve a signing error.** Uninstalling removes that installation's local vault.

1. For an isolated trial, install the debug artifact. It has package ID `com.myvault.app.debug` and cannot see or update the release app's data. Import a copy of a backup if you want to test with realistic content.
2. To test preservation on a release installation, use a release APK with the same `com.myvault.app` package ID, a suitable version code, and the **same signing certificate** as the installed app. This branch's APK is version `1.0.2` (version code `3`); verify the installed version if its origin is unknown. If Android refuses the update, stop and check the signing key and version rather than uninstalling. A CI debug key or disposable release-test key is not suitable for updating a release installation.
3. Check the welcome screen and both empty and populated spaces. Switch Cards/List, expand individual rows, check the line counts, and open an entry from a row. Edit its title and Markdown, cover, tags and pin; try preview, Back, search, settings and dialogs. Let the save indicator finish. Check the system bars/cutout, launcher icon and motion on your phone.
4. Export and verify a JSON backup; try importing a copy into an isolated test vault. Enable airplane mode and disable Wi-Fi, reopen the app, and confirm reading and editing still work.
5. After a save finishes, force-stop the app from Android's App info screen and launch it again. Check entries, tags, appearance, images and the selected Cards/List layout. Test your phone's keyboard, gesture navigation and screen rotation as well.

The extra CI scripts change emulator network, motion and cutout settings and write synthetic vault data. They refuse a physical device and require `MYVAULT_DISPOSABLE_EMULATOR=1`; use them only on an emulator whose data can be discarded.
