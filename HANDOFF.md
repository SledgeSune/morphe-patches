# Handoff: SledgeSune's Morphe patches

State of the project as of **2026-11-10**, for whoever (human or Claude) picks it up next.
Update this file at the end of every working session (the `morphe-handoff` skill says how).

Read [CLAUDE.md](CLAUDE.md) for build commands, architecture and rules. This file is about **state**:
what exists, how each patch works inside, what is unverified, and what is open.

---

## 1. The owner and how to work with them

- The owner says they don't code and don't want to. Explain results in plain words; skip jargon.
- They often follow along from their phone (Remote Control). Keep closing messages self-contained and
  end with one clear question when a decision is needed.
- They want **commits as work happens**, one per patch on `dev`, and a release only when they say so.
- Standing decisions (don't reopen them):
  - **No paywall bypasses.** They asked once for Premium unlocks and to list client-side Premium features;
    both were declined. Ads, upsells, tracking and annoyances are fair game.
  - The repo is **public** (needed for Morphe Manager) with a big "MADE BY AI, NOT BY ME" warning and
    "personal use only" at the top of the README. Keep it.
  - This is the **"any patch I make goes here"** repo: new apps get their own folder, not a new repo.
- Privacy: never put their real name, email, Windows user folder or tokens in the repo.

## 2. Where things are

| What | Where |
|---|---|
| GitHub repo | https://github.com/SledgeSune/morphe-patches (public, default branch `main`) |
| Local checkout | `C:\Programs\Claude\syncme-patches` (folder name kept from before the rename) |
| Git remotes | `origin` = the repo above; `template` = MorpheApp/morphe-patches-template |
| Branches | `dev` (work) and `main` (releases); both at the same commit after each release |
| Latest release | **v1.1.0** (2026-11-10) — 7 patches. v1.0.0 (2026-11-08) — 5 Sync.ME patches |
| Morphe source link | `https://morphe.software/add-source?github=SledgeSune/morphe-patches` |
| Waze APK used | `C:\Programs\Claude\com.waze_5.27.4.900-…apkmirror.com.apkm` |
| Sync.ME APK used | the owner's Downloads folder, `com.syncme.syncmeapp_4.71.2.1-642_…apkmirror.com.apkm` |
| Skills | `.claude/skills/` — `morphe-handoff`, `new-morphe-patch`, `update-app-version`, `release-morphe-patches` |

Decompiled sources, dex dumps and unpacked libraries lived in a session scratchpad and are **gone**.
Re-create them with the steps in the `new-morphe-patch` skill.

## 3. Tools installed on this PC

All installed during the first session; nothing else is needed.

- **JDK 21** — Eclipse Temurin (winget `EclipseAdoptium.Temurin.21.JDK`). `JAVA_HOME` is set user-wide.
- **Android SDK** — command-line tools in `%LOCALAPPDATA%\Android\Sdk` (licenses accepted, `ANDROID_HOME` set).
  Gradle downloads platforms/build-tools itself. `local.properties` points at it (git-ignored).
- **jadx 1.5.6** — winget `Skylot.jadx`. Only the GUI jar is installed; run the CLI with
  `java -cp <jadx-gui-*-all.jar> jadx.cli.JadxCLI`.
- **dexdump** — in the SDK's `build-tools/*/`.
- **Python 3.14** — `%LOCALAPPDATA%\Programs\Python\Python314\python.exe` (`python3` is a Windows Store stub).
  Capstone isn't installed; pip-install it into a scratch folder when disassembling native code.
- **GitHub token** with `read:packages` in `~/.gradle/gradle.properties` (`gpr.user`/`gpr.key`), needed to
  download the Morphe Gradle plugin. `gh` CLI is signed in as SledgeSune (scopes: repo, workflow, …).
- Git identity is set **per repo** to `SledgeSune <57393035+SledgeSune@users.noreply.github.com>`.

## 4. Patches and how each one works

The obfuscated names are for the tested version only. Fingerprints don't depend on them.

### Sync.ME 4.71.2.1 (`com.syncme.syncmeapp`) — `patches/.../syncme/`

| Patch | Hook | Notes |
|---|---|---|
| **Hide ads** (`ads/`) | `PremiumAccess.isAdFree()` (`Lcvc;->j()Z`) → `return true` | Matched by reading `BillingProduct.GIFT_NO_ADS` then `NO_ADS` (only method that reads both) + `EnumSet.of`. Every ad surface and the in-house ads SDK (`com.sync.me.adssdk`, bridge `e.a.f()`) consult it. Same effect as the paid "No Ads" product; other Premium features stay locked. **Confirmed working on the owner's phone.** |
| **Hide upgrade prompts** (`promotions/`) | 1) `RegistrationActivity` onboarding paywall (`Z1()`, string `"the trial screen"`) now calls onboarding-complete (`Y1()`, reads `EventToProvider.onboarding_complete` + `startActivity`). 2) `PromotionalHeaderManager.getActivePromotion` → null. 3) `AfterSmsPromoPolicy.shouldShowPromoOnClose` → false. 4) `PromoNotificationWorker.execute` → `Result.success()`. 5) Call-history banners filtered after each `getFilteredPromoBanners` result in `PromoBannerUtil.getNextPromoBanner` (extension `HideUpgradePromptsPatch.filterPromoBanners` drops `UPGRADE_PRO`, `REMOVE_ADS`, `FREE_GIFT`, `ENABLE_SMS_FRAUD`). | Not tested on a phone. |
| **Skip app promotions** (`promotions/`) | `AppPromotionManager$Companion.prepareIntentForAppBannerAd` → null; `FreeGiftChecker.isToShow` → false | Mostly already hidden by Hide ads (they also check isAdFree). Not tested. |
| **Keep navigation bar** (`navbar/`) | `AggressiveManager` (`Lzr;->a(Activity)V`, string `"is_enable_aggressive_mode_for_premium"`) → `return-void` | Stops the missed-call screen hiding the system nav bar. Not tested. |
| **Disable tracking** (`tracking/`) | `com.syncme.utils.analytics.Analytics` `init/track/identify/userProperty/flush` → no-op; `init` instead calls extension `DisableTrackingPatch.disableSdkCollection` (Firebase `setAnalyticsCollectionEnabled(false)`, Facebook auto-log + advertiser-ID off, via reflection); `SingularAnalytics.initOrSchedule` → no-op; FacebookAppInitializer (`Lu86;->a(Application)`) → no-op | Facebook fingerprint excludes `Lcom/facebook/` classes (the SDK has the same shape). Crashlytics left on. TikTok isn't in the app. Not tested. |

### Waze 5.27.4.900 (`com.waze`) — `patches/.../waze/`

| Patch | Hook | Notes |
|---|---|---|
| **Disable reroute auto-accept** (`reroute/`) | In the native-alert converter `Lcom/b/b/a/u;->d(AlerterInfo)Lcom/waze/i;`, the `AlerterInfo.getTimerType()` call is replaced by extension `DisableRerouteAutoAcceptPatch.getTimerType`. For provider id `"RerouteSuggestion"` it returns `AlerterTimerType.UNRECOGNIZED` (→ no timeout object, no button countdown) and sets config `CONFIG_VALUE_REROUTE_SUGGESTION_FALLBACK_TIMER_SECONDS` (id 758) to 24 h via `ConfigManager.setConfigValueLong`. | The native core (`libwaze.so`, `reroute_suggestion_alert_provider.cc`) runs its own fallback timer that applies the default action; that's why the config is raised. **Unverified:** if the server locks config 758 the route may still switch by itself. Not tested on a phone. |
| **Trip history** (`history/`) | `WazeMobileApplication.onCreate` → extension init; `NavigationInfoNativeManagerBase.onNavigationStateChangedJNI(ZI)` → record start/stop; `onCurrentEtaSecondsChangedJNI(I)` → "still driving" heartbeat (saved once a minute); Settings › Privacy › Navigation history action (`Lcom/waze/settings/gl;->a()V`, reads `CONFIG_VALUE_PRIVACY_DRIVE_HISTORY_URL` + string `"drive_history"`) shows the extension's dialog instead (Waze page still reachable from a button). | **Primary source:** `GET https://www.waze.com/{row-,,il-}Descartes/app/Archive/List?minDistance=0&offset=N&count=50` → `archives.objects[]` with `startTime`, `endTime` (ms), `totalRoadMeters`. Needs website sign-in (guest → HTTP 403); the dialog sends the app's `X-Waze-Mobile-*` web headers plus WebView cookies, and offers an in-app waze.com sign-in. Entries without `endTime` are app launches, not drives. **Fallback:** local trips in SharedPreferences `sledgesune_trip_history` (only drives with a destination set; interrupted drives end at the last heartbeat, shown with `~`). "Copy CSV" button. **Unverified:** whether the app headers alone authenticate; never run on a phone. |

## 5. Open issues

1. **GitHub Actions is blocked** — every run fails: "account is locked due to a billing issue" /
   "recent account payments have failed". The account is GitHub Free with no subscriptions, no payment
   method, no payment history, $0 usage, no budgets, no Education benefits; the lock is on GitHub's side.
   The owner was told to open a billing ticket at support.github.com asking for a manual review.
   Until it's lifted, **release by hand** (`release-morphe-patches` skill). Check with
   `gh run list -R SledgeSune/morphe-patches --limit 3`.
2. **Untested patches** — only Sync.ME "Hide ads" is confirmed on a real phone. Ask the owner how the
   others behave before building on them.
3. **Waze download is damaged** — `split_config.armeabi_v7a.apk` inside the .apkm fails its CRC check.
   `base.apk` and the arm64 split are fine. If patching fails, re-download from APKMirror.
4. **Cosmetic:** the v1.0.0 release commit message lacks the notes text (release page and CHANGELOG are fine).
   Don't rewrite pushed history for it.
5. Issues are still enabled on the repo although the owner offers no support (Settings › General to turn off).

## 6. Ideas not started

- Waze: record drives without a destination set (would need a location/driving signal, not navigation state).
- Waze: export trip history to a file instead of the clipboard.
- Re-check all fingerprints when Sync.ME or Waze update (`update-app-version` skill).

## 7. Session log

- **2026-11-08** — Template parsed; tools installed; Sync.ME analysed; Hide ads, Hide upgrade prompts,
  Skip app promotions, Keep navigation bar, Disable tracking; repo created, made public, README warning;
  billing lock investigated; v1.0.0 released by hand.
- **2026-11-10** — Waze analysed (Java + native); Disable reroute auto-accept and Trip history; owner's
  online drive list exported once to a CSV (outside the repo); v1.1.0 released by hand; skills and this
  handoff doc added.
