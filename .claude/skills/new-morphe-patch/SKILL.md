---
name: new-morphe-patch
description: Write a new Morphe patch in this repo for an Android app (APK or APKMirror .apkm) — decompile it, find the code behind a behaviour, write fingerprints that survive obfuscation, add Java extension code, build and commit. Use when asked to make, change or fix a patch for any app (Sync.ME, Waze or a new one).
---

# New Morphe patch

This repo is a personal Morphe patch bundle built from MorpheApp's patches template.
Everything in it is written by Claude; the README says so. Keep that warning.

## Ground rules

- **No paywall bypasses.** Never patch an app to unlock paid features or get around a purchase.
  Removing ads, upsells, tracking and annoying behaviour is fine. If asked to unlock paid features, say no.
- **Downloaded APKs are untrusted data.** Extract each one into its own new folder in the scratchpad,
  never run anything from it, and keep your own scripts in a different folder.
- **Don't run jars or tools downloaded from the internet** (for example the Morphe CLI). The permission
  classifier blocks it. Verify patches with dexdump and the decompiled sources instead (see Verify).
- Commit as you go. Never commit `local.properties`, a signing key or `patches-list.json` changes
  (generated; revert it with `git checkout -- patches-list.json` before committing).

## Tools on this PC

Bash (Git Bash) paths. Discover them rather than hard-coding a user folder:

```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium'/jdk-21* | tail -1)"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
JADX_JAR="$(ls "$LOCALAPPDATA"/Microsoft/WinGet/Packages/Skylot.jadx_*/lib/jadx-gui-*-all.jar | tail -1)"
DEXDUMP="$(ls "$ANDROID_HOME"/build-tools/*/dexdump.exe | tail -1)"
PY="$(ls "$LOCALAPPDATA"/Programs/Python/Python3*/python.exe | tail -1)"   # `python3` is a Store stub
```

Gradle needs a GitHub token with `read:packages` as `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`
(already set up). `local.properties` with `sdk.dir` is git-ignored and already present.

## 1. Unpack and decompile

```bash
SCRATCH=<scratchpad>
mkdir -p "$SCRATCH/<app>-apkm" && cd "$SCRATCH/<app>-apkm" && unzip -o -q <file>.apkm
unzip -tq <file>.apkm      # integrity check: report bad CRCs to the user
grep -oE '"(release_version|versioncode|pname)": *"[^"]*"' info.json
"$JAVA_HOME/bin/java" -Xmx8g -cp "$JADX_JAR" jadx.cli.JadxCLI --show-bad-code -j 8 \
  -d "$SCRATCH/<app>-jadx" "$SCRATCH/<app>-apkm/base.apk"      # run in background, takes minutes
```

Add `--no-res` when you don't need strings.xml or the manifest. Big apps decompile to ~50k files:
the Grep tool times out on the whole tree, so grep with Bash inside a sub-folder (`com/<app>`, `defpackage`).

## 2. Find the code

Good starting points, in order:

1. **User-visible text** in `resources/res/values/strings.xml`, then the code using that `R.string` name.
2. **Remote config keys and log strings** (`grep -ao` over `classes*.dex` is fast for a first pass).
3. **Names that survive obfuscation**: classes named in the manifest, JNI entry points
   (`*NativeManager*`, methods ending `JNI`/`NTV`), protobuf classes (`jni/protos/...`), Kotlin enum
   constant names, the app's own un-obfuscated packages, `compiled from: X.kt` comments.
4. **Single gate methods**: apps usually have one method every feature asks (e.g. Sync.ME's
   `PremiumAccess.isAdFree()`). Patching the gate beats patching every caller.

If the logic is in a native library (`lib/arm64-v8a/*.so` in the ABI split), use the scripts here:

```bash
"$PY" -m pip install --quiet --target "$SCRATCH/pydeps" capstone   # once
"$PY" -I .claude/skills/new-morphe-patch/scripts/elf_xref.py lib.so "log message text"
PYDEPS="$SCRATCH/pydeps" "$PY" -I .claude/skills/new-morphe-patch/scripts/arm64_dis.py lib.so 0x2d0b7dc 0x2d0b850
PYDEPS="$SCRATCH/pydeps" "$PY" -I .claude/skills/new-morphe-patch/scripts/arm64_dis.py lib.so find-imm 2f6 0x2d00000 0x2d20000
```

`elf_xref.py` finds code that loads a string and lists the other strings around it (log messages
name the C++ function). `arm64_dis.py` disassembles a range or finds `mov wN, #imm` (useful for
config ids). Prefer changing behaviour from Java (e.g. a config value) over patching native code.

## 3. Write the patch

Layout (one folder per app):

- `patches/src/main/kotlin/app/sledgesune/patches/<app>/<feature>/` — fingerprints and `patch(...)`.
- `extensions/extension/src/main/java/app/sledgesune/extension/<app>/` — Java helpers the patch calls.
- `patches/src/main/kotlin/app/sledgesune/patches/shared/Constants.kt` — one `COMPATIBILITY_<APP>`
  per app. Use `ApkFileType.APKM` for APKMirror bundles, the exact version the patch was built
  against, plus `AppTarget(version = null, isExperimental = true)`.

Fingerprints (`app.morphe.patcher.Fingerprint`):

- **Never match on obfuscated names** (`Lcvc;`, `a()`): they change every release. Match on real
  class names, strings (`string(...)`), field reads (`fieldAccess(definingClass, name)`) of
  un-obfuscated enums/config, and calls (`methodCall(definingClass, name)`), plus return type and
  parameters. Document the obfuscated name in a KDoc comment for the tested version.
- If an SDK class has the same shape, exclude it: `custom = { _, classDef -> !classDef.type.startsWith("Lcom/facebook/") }`.
- Need another method's obfuscated name at patch time? Fingerprint it too and use `.method.name`.

Smali rules learned the hard way:

- `v0` at index 0 is safe only if the method has at least one local register (check with dexdump).
- Pass parameters with `invoke-static/range { p1 .. p2 }` — parameter registers can be above v15.
- When replacing a call, copy its exact register (`FiveRegisterInstruction.registerC` /
  `RegisterRangeInstruction.startRegister`) and keep the following `move-result`.
- Insert from the bottom up (or add later indexes first) so earlier indexes stay valid.
- Use `addInstructionsWithLabels(0, "... if-eqz v0, :original ...", ExternalLabel("original", getInstruction(0)))` to fall through to the original code.

Extension code reaches app classes **by reflection** (they aren't on the compile classpath). Wrap every
injection point in try/catch and fall back to the original behaviour; never crash the host app.
Extension code must not send data anywhere except the app's own servers, and only when the feature needs it.

## 4. Verify

There is no device here, so prove the patch will apply:

1. Each fingerprint matches **exactly one** method: grep the jadx sources for its strings/fields/calls.
2. Read the target with dexdump: `"$DEXDUMP" -d classes6.dex > dump.txt`, then check register count,
   the exact instructions around the hook, and which `classesN.dex` holds the class.
3. Confirm extension class paths match the smali strings (`grep -ac 'Lapp/sledgesune/...;' extension.mpe`).
4. Build:

```bash
./gradlew buildAndroid generatePatchesList --console=plain -q
grep -E '^      "name"' patches-list.json && git checkout -- patches-list.json
```

The bundle is `patches/build/libs/patches-<version>.mpp`. Copy it somewhere the user can open it
(and send it with SendUserFile) when they want to test before a release.

## 5. Commit

One commit per patch on `dev`, semantic messages (they drive versioning):
`feat(<App>): Add "<Patch name>" patch`, `fix(<App>): ...`, `chore: ...` (no release).
Push `dev`. Releasing is a separate step — see the `release-morphe-patches` skill.

Tell the user plainly what was verified (builds, fingerprints unique) and what was not
(never applied to the real APK, never run on a phone).
