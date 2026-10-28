# MyVault side-by-side preview (unsigned)

This is a **non-debuggable, offline test installation**, not a release update. Its package ID is `com.myvault.app.preview`; it does not replace `com.myvault.app.debug` or `com.myvault.app`. The APK in this artifact is **unsigned and cannot be installed as downloaded**. Its purpose is to let you keep an older debug app and safely migrate a verified backup without needing that debug app's lost signing key.

1. In the existing app, export a JSON backup. Store it privately: it can contain your notes and images. Keep the old app and its data intact, and check that the backup is readable. See `android/README.md` in the repository for media and backup limitations.
2. Confirm the preview package is not already installed. If it is, compare its current signing certificate and version with the candidate first; do not overwrite it blindly.
3. On a trusted computer with Android SDK Build Tools 34.0.0, use a **stable keystore and alias you already control** to align and sign the preview locally. Keep that keystore safe for future preview updates. You may use a key already used for another package; that alone does not create an Android conflict. Do not upload the key or its passwords. Example (adjust paths and alias; password prompts are interactive):

   ```sh
   zipalign -f -p 4 app-preview-unsigned.apk app-preview-aligned.apk
   apksigner sign --ks /secure/path/to/your-existing.keystore \
     --ks-key-alias YOUR_EXISTING_ALIAS --out MyVault-preview.apk app-preview-aligned.apk
   apksigner verify --print-certs MyVault-preview.apk
   aapt dump badging MyVault-preview.apk | grep "^package:"
   ```

4. Install **the signed preview**, then import the backup in its Settings and verify your spaces, entries, tags, covers and wallpaper. Some older or external image references may require reselection. Keep the old installation and backup; do not clear its data or uninstall it.

Future preview APKs must use the same package ID and signing certificate, and a higher versionCode. The release package is separate and cannot update the debug or preview package. The repository's `android/ci/check-apk-update.sh` can conservatively compare two signed APKs before an update; the first must be the exact installed APK.
