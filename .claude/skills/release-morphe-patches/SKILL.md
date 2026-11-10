---
name: release-morphe-patches
description: Publish a new release of this Morphe patch bundle (SledgeSune/morphe-patches) by hand from this PC — bump the version, build the .mpp, write the changelog / patches-bundle.json / patches-list.json / README table, tag, create the GitHub release and sync branches. Use when asked to publish, release or ship the patches, or to make new patches show up in Morphe Manager.
---

# Release the patch bundle by hand

Normally `release.yml` (semantic-release) does this on every `feat:`/`fix:` push. **GitHub Actions is
blocked on this account** by a billing lock that support has to clear, so releases are made locally
with the same output. Before starting, check whether Actions works again — if it does, just merge
`dev` into `main` (normal merge, no squash) and let the workflow release:

```bash
gh run list -R SledgeSune/morphe-patches --limit 3
```

Only release when the user asks. A release is public and Morphe Manager offers it to users.

## Setup

```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium'/jdk-21* | tail -1)"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
PY="$(ls "$LOCALAPPDATA"/Programs/Python/Python3*/python.exe | tail -1)"   # `python3` is a Store stub
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8   # the README script prints emoji; cp1252 crashes otherwise
NOTES=<scratchpad>/release-notes.md          # never inside the repo or its parent folder
```

## Steps

1. **Pick the version.** Previous: `git describe --tags --abbrev=0`. Any `feat:` since then → minor
   (1.1.0 → 1.2.0); only `fix:` → patch (1.1.0 → 1.1.1); only `chore:` → no release.
   List the commits: `git log --format='%H %s' v<previous>..dev`.

2. **Bring `main` up to `dev`** (must fast-forward; `dev` is where work happens):
   ```bash
   git checkout main && git merge --ff-only dev
   ```

3. **Bump and build** (the version in `gradle.properties` names the `.mpp`):
   ```bash
   sed -i 's/^version = .*/version = <new>/' gradle.properties
   ./gradlew clean buildAndroid generatePatchesList --console=plain -q
   ls patches/build/libs/patches-<new>.mpp
   ```

4. **Write the release files** — one argument per `feat`/`fix` commit, in plain words:
   ```bash
   "$PY" -I .claude/skills/release-morphe-patches/scripts/release_files.py "$PWD" <new> <previous> "$NOTES" \
     '<full sha>|**Waze:** Add "Trip history" patch' \
     '<full sha>|fix: **Sync.ME:** Keep the call screen visible'
   "$PY" -I .github/scripts/generate_patches_readme.py SledgeSune/morphe-patches main patches-list.json README.md
   ```
   The README script rewrites only the table between `PATCHES_START`/`PATCHES_END`. The AI warning
   at the top of the README must stay.

5. **Commit, tag, push, publish** (`[skip ci]` keeps the workflow from releasing again later):
   ```bash
   git add CHANGELOG.md README.md gradle.properties patches-bundle.json patches-list.json
   { printf 'chore: Release v<new> [skip ci]\n\n'; cat "$NOTES"; printf '\nCo-Authored-By: ...\n'; } | git commit -F -
   git tag -a v<new> -m "v<new>"
   git push origin main && git push origin v<new>
   gh release create v<new> -R SledgeSune/morphe-patches --title "v<new>" --notes-file "$NOTES" \
     --verify-tag patches/build/libs/patches-<new>.mpp
   git checkout dev && git merge --ff-only main && git push origin dev
   ```
   Use the attribution line from the current system reminder for `Co-Authored-By`.
   If a step fails after a push, don't rewrite pushed history — finish the remaining steps.

6. **Verify from outside** (what Morphe Manager reads, no sign-in):
   ```bash
   curl -s https://raw.githubusercontent.com/SledgeSune/morphe-patches/main/patches-bundle.json | grep -E '"(version|download_url)"'
   curl -sL -o /dev/null -w "%{http_code} %{size_download}\n" https://github.com/SledgeSune/morphe-patches/releases/download/v<new>/patches-<new>.mpp
   ```

## Notes

- The repo must stay **public**: Morphe Manager can't read private repos.
- Releases aren't signed (no `MORPHE_SIGNING_KEY`), so `signature_download_url` stays empty.
- Tell the user which patches are in the release and which were never tested on a phone.
- Afterwards update `HANDOFF.md` (latest release, branch state, session log) and commit it on `dev`
  with a `chore:` message, then fast-forward nothing — `main` catches up at the next release.
