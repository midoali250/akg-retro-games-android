# Independent signed android update channel

The application checks the latest **non-prerelease** release in
`midoali250/akg-retro-games-android` for `akg-update-manifest.json`. The signed manifest
must match this product, platform, pinned Ed25519 public key, and a newer
integer version. Downloads are size- and SHA-256-verified; no GitHub token or
private signing key is embedded in the application.

The Android installer asks for user confirmation. APK package ID, version and signing certificate are checked; old debug-signed builds cannot be upgraded by the dedicated release-signed APK and may require reinstall.

## Publishing an update

1. Increase `updates/version.json` to the new integer version **before** the
   package is built; it must be greater than the installed version.
2. Build and test the correct platform package. For Android, use the dedicated
   release-signed APK (not a debug APK) with the same application ID and key.
3. Create a **draft** GitHub Release with tag `vN` (where N is the version
   code), upload the single appropriate platform asset, then publish it as a
   normal release, not a prerelease.
4. The `Sign platform release updates` workflow hashes that asset, signs the
   manifest using this repository's `AKG_UPDATE_SIGNING_SEED` secret and
   uploads it. It refuses to overwrite an existing manifest. Check that the
   workflow succeeded before expecting the app to detect the update.

The private update signing seed is stored as an Actions secret; its separate
backup is in the shared engine's Actions secrets. Android also has a distinct
APK-signing identity and backup. Do not rotate/delete these without a tested
migration and secure backup. `public-key.json` is intentionally public.

No complete old-build-to-new-build GitHub release update has yet been verified
end to end. Older preview packages may lack this updater. The historic
`channel.template.json` is an inactive template and is not this channel.
