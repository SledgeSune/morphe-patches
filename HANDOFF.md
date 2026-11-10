# Handoff

Project **state** as of **2026-11-10**. Rules, build and architecture are in [CLAUDE.md](CLAUDE.md).
Update this file at the end of every session (`morphe-handoff` skill).

## Owner decisions (don't reopen)

- Commits as work happens, one per patch on `dev`; release only when they say so.
- Repo stays **public** (Morphe Manager needs it), with the "MADE BY AI, NOT BY ME" / personal-use warning on top of the README.
- One repo for every patch they make; new apps get a new folder.

## Where things are

| What | Where |
|---|---|
| Repo | https://github.com/SledgeSune/morphe-patches (`dev` = work, `main` = releases) |
| Local checkout | `C:\Programs\Claude\Morphe-Patches\morphe-patches` (template reference clone next to it) |
| Latest release | **v1.1.0** (2026-11-10), 7 patches. v1.0.0 (2026-11-08), 5 Sync.ME patches |
| Add to Morphe | `https://morphe.software/add-source?github=SledgeSune/morphe-patches` |
| Tested APKs | APKMirror `.apkm`: Waze 5.27.4.900, Sync.ME 4.71.2.1 (kept outside the repo) |
| Tools | JDK 21 (Temurin), Android SDK + dexdump, jadx 1.5.6 (GUI jar only: `java -cp <jar> jadx.cli.JadxCLI`), Python 3.14, `gh` signed in as SledgeSune |

Decompiled sources from earlier sessions are gone; recreate them with the `new-morphe-patch` skill.

## Patches

Obfuscated names are for the tested version only; fingerprints don't depend on them.

### Sync.ME 4.71.2.1 — `patches/.../syncme/`

| Patch | Hook | Status |
|---|---|---|
| **Hide ads** (`ads/`) | `PremiumAccess.isAdFree()` (`Lcvc;->j()Z`) → `return true`. Matched by reads of `BillingProduct.GIFT_NO_ADS` then `NO_ADS` + `EnumSet.of`. Every ad surface and the ads SDK bridge (`e.a.f()`) check it. | **Works** (owner's phone) |
| **Hide upgrade prompts** (`promotions/`) | Onboarding paywall (`"the trial screen"`) jumps to onboarding-complete; `PromotionalHeaderManager.getActivePromotion` → null; `AfterSmsPromoPolicy.shouldShowPromoOnClose` → false; `PromoNotificationWorker.execute` → `Result.success()`; extension filters `UPGRADE_PRO`, `REMOVE_ADS`, `FREE_GIFT`, `ENABLE_SMS_FRAUD` from `getFilteredPromoBanners`. | Untested |
| **Skip app promotions** (`promotions/`) | `AppPromotionManager$Companion.prepareIntentForAppBannerAd` → null; `FreeGiftChecker.isToShow` → false. Mostly covered by Hide ads already. | Untested |
| **Keep navigation bar** (`navbar/`) | `AggressiveManager` (`Lzr;->a(Activity)V`, string `"is_enable_aggressive_mode_for_premium"`) → `return-void`. | Untested |
| **Disable tracking** (`tracking/`) | `com.syncme.utils.analytics.Analytics` init/track/identify/userProperty/flush → no-op (init turns off Firebase + Facebook collection by reflection); `SingularAnalytics.initOrSchedule` and the Facebook initializer (`Lu86;->a(Application)`, fingerprint excludes `Lcom/facebook/`) → no-op. Crashlytics left on. | Untested |

### Waze 5.27.4.900 — `patches/.../waze/`

| Patch | Hook | Status |
|---|---|---|
| **Disable reroute auto-accept** (`reroute/`) | In the AlerterInfo converter (`Lcom/b/b/a/u;->d(AlerterInfo)`), `getTimerType()` goes through the extension: for provider `"RerouteSuggestion"` it returns `UNRECOGNIZED` (no countdown) and raises config 758 (`CONFIG_VALUE_REROUTE_SUGGESTION_FALLBACK_TIMER_SECONDS`, the native fallback timer in `libwaze.so`) to 24 h. | Untested; may fail if the server locks config 758 |
| **Trip history** (`history/`) | Extension init in `WazeMobileApplication.onCreate`; records drives from `onNavigationStateChangedJNI` + a once-a-minute heartbeat from `onCurrentEtaSecondsChangedJNI`; Settings › Privacy › Navigation history (`Lcom/waze/settings/gl;->a()V`) opens the extension's dialog. Primary source: `GET https://www.waze.com/{row-,,il-}Descartes/app/Archive/List?minDistance=0&offset=N&count=50` (needs website sign-in; entries without `endTime` are app launches). Fallback: local trips in SharedPreferences `sledgesune_trip_history` (only drives with a destination). "Copy CSV" button. | Untested; unknown if app headers alone authenticate |

## Open issues

1. **GitHub Actions blocked** by a billing lock on GitHub's side (account is Free, nothing owed). Owner was told to
   open a ticket at support.github.com. Release by hand until `gh run list -R SledgeSune/morphe-patches --limit 3` succeeds.
2. **Only "Hide ads" is confirmed** on a phone. Ask the owner about the others before building on them.
3. Issues are still enabled on the repo although the owner offers no support.

## Ideas not started

- Waze: record drives without a destination (needs a driving/location signal).
- Waze: export trip history to a file instead of the clipboard.

## Session log

- **2026-11-08** — Tools installed; Sync.ME patches (5); repo made public; v1.0.0 released by hand.
- **2026-11-10** — Waze patches (reroute, trip history); v1.1.0 released by hand; skills and handoff added.
- **2026-11-10** — New local checkout `Morphe-Patches/morphe-patches`; CLAUDE.md, HANDOFF.md and skills condensed.
