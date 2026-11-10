---
name: update-app-version
description: Move this repo's patches to a new version of an app they target (Sync.ME, Waze or later ones) — check every fingerprint still matches exactly one method in the new APK, fix the ones that broke, update the supported version and release. Use when the owner says an app updated, a patch "doesn't work anymore", Morphe Manager shows a patch as failing, or drops a newer APK/.apkm in the folder.
---

# Update patches for a new app version

Fingerprints in this repo avoid obfuscated names, so most survive an app update. This skill checks
that they do and fixes those that don't. Read `HANDOFF.md` section 4 first: it lists every hook with
the strings, fields and calls each fingerprint relies on.

## 1. Get the new build

- The owner downloads it from APKMirror (`.apkm` bundle). Put nothing of yours next to it.
- Unpack into a new scratchpad folder and check integrity, as in `new-morphe-patch` step 1:
  `unzip -tq <file>.apkm` and read `release_version` / `versioncode` from `info.json`.
- Decompile `base.apk` with jadx (background, a few minutes). Dump the dex files you need with dexdump.

## 2. Check every patch for that app

For each fingerprint of the app's patches (files under `patches/src/main/kotlin/app/sledgesune/patches/<app>/`):

1. Find the anchors in the new sources: the strings (`string(...)`), field reads (`fieldAccess`),
   calls (`methodCall`), class and method names when they are fixed (`definingClass`/`name`).
2. Confirm **exactly one** method matches all of them, with the same return type and parameters.
   Grep with Bash inside a sub-folder (the Grep tool times out on the whole tree).
3. Read the matched method. Check the code shape the patch assumes still holds:
   - instruction after a hooked call is still `move-result-object` (see the Waze reroute patch),
   - the method still has a free register for `v0` (dexdump `registers` > `ins`),
   - `getFilteredPromoBanners` style call sites still exist.
4. Check extension code that uses **reflection** still finds its targets: class, method and field
   names (e.g. `com.waze.ConfigManager.setConfigValueLong`, `FirebaseAnalytics`,
   `AlerterInfo.getAlertId().getProviderId()`, enum constants like `UNRECOGNIZED`).
5. If behaviour moved (e.g. a new paywall screen, a new ad path), re-trace it with the
   `new-morphe-patch` skill rather than forcing the old hook.

Write down, per patch: still matches / fixed / dropped.

## 3. Fix and record

- Change fingerprints to the new anchors, still never using obfuscated names. Update the KDoc with
  the new obfuscated name for the tested version.
- In `patches/src/main/kotlin/app/sledgesune/patches/shared/Constants.kt`, set the tested
  `AppTarget(version = "<new>")` for that app. Keep the old version too **only** if every patch
  still matches it; otherwise replace it. Keep the experimental `AppTarget(version = null, ...)`.
- If the app now ships as a different file type on APKMirror (APK vs APKM), change `apkFileType`,
  or Morphe Manager won't send users to the download.
- Build (`./gradlew buildAndroid generatePatchesList`), confirm all patch names are listed, revert
  `patches-list.json`.
- Commit on `dev`:
  - fingerprint or hook changes → `fix(<App>): Support <App> <version>`
  - only the version list changed → `fix(<App>): Add <App> <version> to supported versions`
  (`fix:` makes a patch release; `chore:` would not reach Morphe Manager users.)

## 4. Release and hand off

- Ask the owner before publishing; then use `release-morphe-patches`.
- Update `HANDOFF.md` (`morphe-handoff` skill): new tested version, new obfuscated names, anything
  that broke, and that the new version is untested on a phone until the owner says otherwise.
