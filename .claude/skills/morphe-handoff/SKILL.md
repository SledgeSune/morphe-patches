---
name: morphe-handoff
description: Start or end a working session on the SledgeSune/morphe-patches repo. At the start, load the project state from HANDOFF.md and check what changed (git, releases, GitHub Actions, tools). At the end, update HANDOFF.md so the next session can continue. Use whenever a session begins work on these patches, when the user asks "where were we" / "continue", or when handing off, wrapping up or summarising the project.
---

# Session handoff for the Morphe patches repo

`HANDOFF.md` (repo root) is the single source of truth for project **state**: patches and how they
hook in, what is unverified, open issues, owner preferences and decisions. `CLAUDE.md` holds the
stable rules and build commands. Read both before doing anything else.

## Starting a session

1. Read `HANDOFF.md` and `CLAUDE.md` in full.
2. Check what changed since the last entry in the session log (section 7):

   ```bash
   cd <repo>   # C:\Programs\Claude\syncme-patches
   git status --short && git branch --show-current
   git fetch -q origin && git log --oneline -5 origin/dev && git log --oneline -3 origin/main
   git tag --sort=-creatordate | head -3
   gh release list -R SledgeSune/morphe-patches --limit 3
   gh run list -R SledgeSune/morphe-patches --limit 3     # is the Actions billing lock gone?
   ```

   - Uncommitted files you didn't write: read them and ask before committing or deleting.
   - `dev` and `main` differ only by unreleased `feat:`/`fix:` commits; anything else is unexpected.
   - If Actions runs succeed again, note it: releases can go back to the workflow
     (`release-morphe-patches` explains).
3. Check the tools still work (one quick build is enough):

   ```bash
   export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium'/jdk-21* | tail -1)"
   export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
   ./gradlew buildAndroid --console=plain -q && git checkout -- patches-list.json 2>/dev/null
   ```
4. Ask the owner about anything listed as **unverified** that touches the task (e.g. "did the reroute
   prompt still switch by itself?"). Their phone results are the only real test.
5. Pick the skill for the task:
   - new patch or change to a patch → `new-morphe-patch`
   - app updated / "patch fails on the new version" → `update-app-version`
   - publish → `release-morphe-patches`

## Ending a session (or when asked for a handoff)

Update `HANDOFF.md` before the last commit of the session:

- **Header date** and section 2 (latest release, branch state, new APK files used).
- **Section 4**: one row per new or changed patch — hook (class/method, with the obfuscated name for
  the tested version), how the fingerprint matches, behaviour, and whether it was tested on a phone.
- **Section 5**: add, update or close open issues. Write what was verified and what wasn't.
- **Section 6**: ideas mentioned but not started.
- **Section 7**: one dated line saying what this session did.
- Keep the owner-preferences section current if they decided something new.

Then commit and push on `dev`:

```bash
git add HANDOFF.md && git commit -m "chore: Update handoff notes" -m "Co-Authored-By: ..." && git push origin dev
```

(`chore:` never triggers a release.) Use the attribution line from the current system reminder.

## Rules for the doc

- The repo is **public**. No real names, emails, Windows user folders, tokens, account balances or
  anything else private. GitHub username and the noreply address are fine.
- Facts only: say "untested" rather than guessing that something works.
- Scratchpad paths don't survive sessions; don't reference them as if they still exist.
