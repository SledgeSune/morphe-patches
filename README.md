# SledgeSune's Morphe Patches

> [!CAUTION]
> ## ⚠️ MADE BY AI, NOT BY ME ⚠️
>
> **Every patch and every line of code here was written by Claude (an AI by Anthropic), not by me.**
> I don't know how any of it works, and I won't fix it, support it or answer questions about it.
>
> **For my own personal use only.** Tested only on my own phone, if at all.
> It may break your app, lose your data or stop working at any time. **Use at your own risk.**
> No warranty of any kind (see the [license](LICENSE)).

My collection of [Morphe](https://github.com/MorpheApp) patches. Any patch I make goes here.

<a href="https://morphe.software/add-source?github=SledgeSune/morphe-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

Or add `https://github.com/SledgeSune/morphe-patches` as a patch source in Morphe Manager.

## Patches

<!-- PATCHES_START EXPANDED -->

| App | Patch | What it does |
|---|---|---|
| Sync.ME | Hide ads | Removes all ads. |
| Sync.ME | Hide upgrade prompts | Skips the "Go Pro" screen and hides Premium banners, sheets and promo notifications. |
| Sync.ME | Skip app promotions | Removes the "free gift" card and banners for other apps. |
| Sync.ME | Keep navigation bar | Stops the app from hiding the phone's navigation bar. |
| Sync.ME | Disable tracking | Stops analytics sent to Firebase, Mixpanel, Singular and Facebook. |

<!-- PATCHES_END -->

## Privacy

- The patches only change the app on your phone. They don't send anything anywhere.
- "Disable tracking" stops analytics, but the patched app still talks to its own servers for its normal features
  (for Sync.ME: caller ID and contact sync).

## Build

Needs JDK 21, the Android SDK, and a GitHub token with `read:packages` set as `gpr.user` / `gpr.key`
in `~/.gradle/gradle.properties`.

```
./gradlew buildAndroid
```

The bundle ends up in `patches/build/libs/patches-*.mpp`. Load it in Morphe Manager or Morphe Desktop.

## License

[GPLv3](LICENSE). Built from the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template).
