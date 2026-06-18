# Bloodwar 2 — Accessible Android Client

An accessible Android client for [titanconquest.com](https://titanconquest.com), built for blind and visually impaired players. It is an Android port of the **Bloodwar 2** desktop (Electron) client.

## How it works

The Bloodwar 2 desktop client doesn't reimplement the game — it loads the **real** titanconquest.com in a Chromium window and injects a single accessibility/performance script, `enhancer.js`, into the live page. That script is where every feature lives.

This Android app does exactly the same thing. A full-screen `WebView` (which *is* Chrome on Android) loads the live site, and after each page load we inject the **same, unchanged `enhancer.js`**. Because it's the identical code running in the same browser engine, every feature comes along automatically:

- **Semantic ARIA roles & labels** → spoken correctly by TalkBack
- **Enemy-list memory, decoy safety & smart area navigation**
- **Battle sound effects** (the bundled `audio/` library)
- **Battle hooks** — skip victory screen, spoken battle/loot/XP announcements
- **Keyboard shortcuts** for battle actions (requires a hardware/Bluetooth keyboard)
- **Performance** — lazy image loading, DOM cleanup, request dedup
- **Bulk infuse, stats-zone labels, focus management**, and more

**No separate server or account needed** — log in once with your existing Titan Conquest account; the session cookie persists across launches.

### The native contract

`enhancer.js` depends on only two things from its host, both provided here:

| Global | Provided by |
|--------|-------------|
| `window.__bw2Audio` — map of `name → data:audio/…;base64,…` | `NativeBridge.audioJson()`, built from bundled `assets/audio/` |
| `window.__bw2Log(level, …)` — optional diagnostic logging | `NativeBridge.log()` → Android `Logcat` (tag `BW2`) |

Everything else `enhancer.js` references (`__bw2Audio`, `__tcEnhancerLoaded`, etc.) it sets on `window` itself. The desktop client's `preload.js` is almost entirely anti-bot fingerprint spoofing to make *Electron* look like Chrome — unnecessary here because Android WebView already is Chrome. We do match the desktop client's **desktop-Chrome user-agent** so titanconquest.com serves the desktop DOM that `enhancer.js` targets.

## Project structure

```
app/src/main/
├── java/com/titanconquest/a11y/
│   └── MainActivity.kt        # WebView shell + JS bridge + enhancer injection
└── assets/
    ├── enhancer.js            # The Bloodwar 2 enhancer, verbatim
    └── audio/                 # Battle SFX (ogg/wav), injected as data URIs
```

## Building

### Requirements
- JDK 17, Android SDK 35 (AGP 8.5.2, Kotlin 2.0.0, Gradle 8.9)

### Run locally
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI
Every push and pull request builds a debug APK and runs lint. Pushes to `main` publish the APK to the **`latest-debug`** GitHub Release, so it can be downloaded directly onto a phone and sideloaded — no cable needed.

## Installing on a phone
1. On the phone, open the `latest-debug` release and download `TitanConquestA11y-debug.apk`.
2. Tap it; allow installs from this source when prompted; tap **Install**.
3. Enable TalkBack (**Settings → Accessibility → TalkBack**) and open the app.

## Known limitations / things to verify on-device
- **Desktop layout assumption** — if titanconquest.com reflows to a mobile layout despite the desktop UA, some enhancer selectors may need adjustment.
- **Keyboard shortcuts** require a physical/Bluetooth keyboard. A future on-screen control bar could expose those actions to touch.
- Desktop-only dev tooling from the Electron client (F2/F4/F12 debug keys, the watchdog, HTML dumps) is intentionally not ported.

## Keeping the enhancer in sync
`assets/enhancer.js` and `assets/audio/` are copied from the Bloodwar 2 desktop project. When the enhancer changes there, copy the updated files over and rebuild — no Kotlin changes needed.
