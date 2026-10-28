# Android app and APK builds

## Native app

The Android app uses native Compose screens, the existing Room database and DataStore settings; it does not wrap the website. Create a space, add an entry, and use the editor's preview button for the sliding Markdown preview. Changes are autosaved and are flushed before the editor closes. Spaces, tags, pinning, cover images, text/Markdown import, search, theme settings and JSON import/export are available in the app. Android and the web app keep separate data; there is no automatic sync.

Images selected with the Android document picker are copied to private app storage (up to 12 MB per image). Android JSON exports embed readable local covers and wallpaper as data URIs. Imports merge matching spaces and tags by name and add entries without deleting existing entries; appearance settings are restored only if selected. Importing the same backup twice adds the entries twice. Text imports are limited to 4 MB and JSON imports to 128 MB.

Backups from older versions or from the web can contain external URLs, temporary document URIs, or server-relative paths such as `/uploads/...` instead of the image bytes. Those references may not work on Android or another device and will need to be re-selected; the app warns when exporting or importing external image references. Keep the original images and verify a backup before relying on it. The app itself has no internet permission, so remote image URLs are not fetched. Android system backup/device-transfer rules also include the database and private files; whether they run depends on the device's settings. Wallpaper blur requires Android 12 or later.

## APK workflow

`.github/workflows/android-apk.yml` runs Android unit tests and builds a **debug APK** on Android-related pull requests and pushes to `main`. It can also be started manually from **Actions → Android APK → Run workflow**, choosing `debug` or `release`. Manual signed releases are restricted to the default branch; the workflow must first be merged into that branch before the Run workflow button is available.

After a successful run, download `myvault-debug-apk` or `myvault-release-apk` from the run's **Artifacts** section. Artifacts expire after 14 days. Debug APKs use Android's debug key and the `.debug` application ID; they are for testing, not publishing, and do not update the release app. No signing secrets are required for debug builds.

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

The workflow's source and signing helper checks can be run separately with a JDK on `PATH`:

```sh
bash android/ci/test.sh
```

Those helper tests use a disposable key and a mocked `apksigner`; they do not replace a real Android build, signature verification or device test.
