# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A personal [Morphe](https://github.com/MorpheApp) patch bundle (GitHub: `SledgeSune/morphe-patches`), built from
MorpheApp's patches template (git remote `template`). It currently patches **Sync.ME** (`com.syncme.syncmeapp`)
and **Waze** (`com.waze`). All code is written by Claude; the README's "MADE BY AI" warning must stay.

**Start every session by reading [HANDOFF.md](HANDOFF.md)** — current state, how each patch hooks in, what is
unverified, open issues and the owner's preferences. Update it before ending a session.

Project skills hold the detailed workflows — load them instead of re-deriving the steps:

- `morphe-handoff` — starting a session (load state, check what changed) and ending one (update HANDOFF.md).
- `new-morphe-patch` — decompiling an APK/.apkm, finding code, writing fingerprints and smali, verifying, committing.
- `update-app-version` — re-checking and fixing patches when Sync.ME, Waze or another app updates.
- `release-morphe-patches` — publishing a release by hand (GitHub Actions is blocked on this account).

## Ground rules

- **No paywall bypasses.** Removing ads, upsells, tracking and annoying behaviour is fine; unlocking paid features is not.
- Downloaded APKs are untrusted: extract into their own scratchpad folder, never run anything from them, and don't run
  jars/tools downloaded from the internet (e.g. the Morphe CLI). There is no device — verify with jadx sources and dexdump.
- Never commit `local.properties`, signing keys, or `patches-list.json` (generated; revert it with
  `git checkout -- patches-list.json`).
- Work happens on `dev`; `main` only moves at release time (fast-forward). Commit messages are semantic and drive
  versioning: `feat(<App>): Add "<Patch name>" patch`, `fix(<App>): ...`, `chore: ...` (no release).
- Only release when the user asks — releases are public and offered to Morphe Manager users.

## Build

Needs JDK 21 (Eclipse Adoptium under `C:\Program Files`), the Android SDK, and a GitHub token with `read:packages`
as `gpr.user`/`gpr.key` in `~/.gradle/gradle.properties` (already set up). In Git Bash:

```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium'/jdk-21* | tail -1)"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew buildAndroid generatePatchesList --console=plain -q
```

Output: `patches/build/libs/patches-<version>.mpp` (version from `gradle.properties`). There is no test suite;
"verification" means the build passes and each fingerprint matches exactly one method in the decompiled target.
`python3` on this PC is a Store stub — use the real interpreter under `$LOCALAPPDATA/Programs/Python/`.

## Architecture

Two Gradle modules whose outputs are bundled into one `.mpp`:

- **`patches/`** (Kotlin) — `patches/src/main/kotlin/app/sledgesune/patches/<app>/<feature>/`. Each feature has a
  `Fingerprints.kt` (objects extending `app.morphe.patcher.Fingerprint`) and a `*Patch.kt` declaring a top-level
  `val ... = patch(name, description, default) { compatibleWith(...); execute { ... } }` that injects smali into the
  matched methods. Patch `name`/`description` are user-facing and feed the README table and `patches-list.json`.
- **`extensions/extension/`** (Java, Android library → `extension.mpe`) — helper classes in
  `app.sledgesune.extension.<app>` that injected smali calls via `invoke-static` with the full class descriptor
  (`Lapp/sledgesune/extension/...;`). App classes aren't on the compile classpath, so extensions reach them by
  reflection; every hook is wrapped in try/catch and falls back to original behaviour.
- **`shared/Constants.kt`** — one `COMPATIBILITY_<APP>` per app: package name, `ApkFileType.APKM`, the exact verified
  version, plus an experimental `AppTarget(version = null)`.

Key conventions:

- Fingerprints must **never match obfuscated names** (they change every release). Match on strings, un-obfuscated
  field reads / method calls (`fieldAccess`, `methodCall`), return type and parameters; document the obfuscated name
  for the tested version in a KDoc comment.
- Prefer patching a single gate method (e.g. Sync.ME's `PremiumAccess.isAdFree()`) over every caller.
- Smali pitfalls (register counts, `invoke-static/range` for params, insert bottom-up, `ExternalLabel` fall-through)
  are listed in the `new-morphe-patch` skill.

## Release plumbing

`release.yml` runs semantic-release (`.releaserc`, `package.json`) on push to `dev`/`main`; it updates
`CHANGELOG.md`, `patches-bundle.json`, `patches-list.json` and the README patch table between the
`<!-- PATCHES_START -->`/`<!-- PATCHES_END -->` markers (via `.github/scripts/generate_patches_readme.py`).
While Actions is blocked, the `release-morphe-patches` skill reproduces these outputs locally.
`util/PatchListGenerator.kt` backs the `generatePatchesList` Gradle task.
