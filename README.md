# Titan Conquest — Accessible Android Client

A TalkBack-first Android client for [titanconquest.com](https://titanconquest.com), designed specifically for blind and visually impaired players.

## Why this exists

The official Titan Conquest app is a visual-first web wrapper. This client rebuilds the interface from scratch with:

- **Full TalkBack support** — every element has a meaningful `contentDescription`
- **Live region announcements** — battle results, loot, XP gains are spoken aloud automatically
- **Large touch targets** — all buttons are at least 56dp tall
- **High-contrast theme** — WCAG AA compliant colors in both light and dark mode
- **Logical focus order** — TalkBack navigation follows the natural game flow
- **No gesture-only actions** — everything reachable by single tap

## How it works

This is a native Android app that connects to `titanconquest.com` via HTTPS, just like the browser app. It logs in with your existing account, then parses the game's HTML responses to present them in an accessible UI.

**No separate server or account needed** — use your existing Titan Conquest account.

## Project structure

```
app/src/main/java/com/titanconquest/a11y/
├── MainActivity.kt          # App entry point and navigation
├── accessibility/
│   └── A11yAnnouncer.kt     # TalkBack announcement utilities
├── model/
│   └── GameModels.kt        # Game data classes with a11y descriptions
├── network/
│   └── TitanConquestClient.kt  # HTTP client — talks to titanconquest.com
└── ui/
    ├── screens/
    │   ├── LoginScreen.kt   # Login with accessible form fields
    │   ├── PatrolScreen.kt  # Core battle screen — enemies + actions
    │   └── PlaceholderScreens.kt  # Hero, Locations, Chat, Bounties
    └── theme/
        └── Theme.kt         # High-contrast Material 3 theme
```

## Building

### Requirements
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 35

### Run locally
```bash
git clone <your-repo>
cd TitanConquestA11y
./gradlew assembleDebug
# Install on connected device:
adb install app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI

Every push and pull request automatically:
1. Runs unit tests
2. Builds a debug APK (available as a GitHub Actions artifact)
3. Runs Android Lint including accessibility checks

Pushes to `main` also build a release APK. To enable APK signing, add these secrets in your repo's **Settings → Secrets and Variables → Actions**:

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded `.jks` keystore |
| `KEY_ALIAS` | Key alias in the keystore |
| `KEY_PASSWORD` | Key password |
| `STORE_PASSWORD` | Keystore password |

Generate a base64 keystore: `base64 -i your-keystore.jks | pbcopy`

## Testing with TalkBack

1. Enable TalkBack on your Android device: **Settings → Accessibility → TalkBack**
2. Install the debug APK
3. Navigate the app using TalkBack swipe gestures
4. Check that every screen, button, and game event is announced correctly

## Roadmap

- [ ] Login + session persistence (DataStore)
- [ ] Patrol screen wired to live game data
- [ ] Hero stats screen
- [ ] Locations / travel
- [ ] Global + clan chat
- [ ] Bounties screen
- [ ] Gear / inventory management
- [ ] Sound effects for battle events (optional, user-configurable)

## Contributing

Pull requests welcome. Please test any UI changes with TalkBack before submitting.
