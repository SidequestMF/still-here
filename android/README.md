# Samsung Galaxy Watch and other Wear OS watches

Galaxy Watch 4 and newer, Pixel Watch, and other watches running Wear OS 3 or later, paired with an Android phone.

**Status:** written by Claude, not yet built into an app. The shared logic compiles and is tested; the watch and phone apps need Android Studio for their first build. Expect small fixes on that first build.

## What's where

| Folder | Runs on | What it does |
| --- | --- | --- |
| `shared/` | both | Detection rules, the night-mode state machine, alert message and relay format. Plain Kotlin, tested on any computer |
| `wear/` | watch | Keeps live heart rate coming with a Health Services exercise session, reads motion, shows the check-in and tap test, sends alerts |
| `phone/` | phone | Setup screen, syncs settings to the watch, adds location to alerts, sends them through the relay |

The rules are the same as the Apple Watch version. Tests replay 75 recorded simulated nights through the Kotlin code and require the exact same decisions at the same moments as the tested reference.

## Differences from the Apple version

- **Texts:** alerts go through the same relay (`relay/`). Android phones can also send texts directly, as an opt-in fallback when the relay can't be reached. Google Play limits that permission to default texting apps, so it works only in builds installed outside the Play Store.
- **Galaxy Watch with LTE:** the watch also sends straight to the relay, in case the phone is out of range.
- **Samsung Health:** this uses Google's Health Services, which Galaxy Watch 4+ supports. Samsung's own Health SDK could add more sensors later.

## Building

1. Install Android Studio (2024.2 or newer) and open the `android` folder.
2. Let it download the Android SDK and dependencies.
3. Run `phone` on your Android phone and save your name and contacts.
4. Turn on developer mode and Wi-Fi debugging on the watch, connect it in Android Studio, and run `wear`.
5. Allow the body-sensor, activity, notification and location permissions when asked.

## Testing the shared code

From the repository root: `bash tests/run-kotlin-checks.sh` (needs Java, Node, and `kotlinc` or Gradle).

## Known gaps

- All-clear doesn't yet require a PIN.
- No app icons yet; the system alarm icon stands in.
- Battery use over a full night on Galaxy Watch is unknown.
- Samsung may stop background services on some battery settings; the foreground service and ongoing notification are meant to prevent that, but it needs testing.
