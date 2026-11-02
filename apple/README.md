# Apple Watch and iPhone apps

**Status:** written but not yet built. This needs a developer with a Mac. Expect small compile fixes on the first build.

## Files

| File | Runs on | What it does |
| --- | --- | --- |
| `Shared/Models.swift` | both | Contacts, settings, alert message, and every tuning threshold |
| `Shared/Detection.swift` | both | Heart-rate, motion, and tap-test rules (no Apple frameworks; unit-tested) |
| `WatchApp/MonitorEngine.swift` | watch | Keeps the app running with a workout session, reads heart rate (HealthKit) and motion (Core Motion), runs check-ins |
| `WatchApp/WatchViews.swift` | watch | Screens: start, night mode, hold-for-help, "Are you OK?" tap test, alerting |
| `WatchApp/WatchPhoneLink.swift` | watch | Gets settings from the phone; sends alerts to the phone and directly to the relay |
| `PhoneApp/PhoneApp.swift` | iPhone | Setup screen, contacts, location on alert, sends to the relay, fallback notification |
| `Tests/` | Mac/Linux | XCTest cases for the shared logic (`swift test`) |

## Building

1. Xcode 15 or later. New project → **watchOS → App**, with "Watch App with New Companion iOS App". Name it Still Here.
2. Delete the generated Swift files. Drag in `Shared/` (check both targets), `WatchApp/` (watch target), and `PhoneApp/` (iOS target).
3. Watch target → Signing & Capabilities: add **HealthKit** and **Background Modes → Workout processing**.
4. Add to the watch Info.plist: `NSHealthShareUsageDescription`, `NSHealthUpdateUsageDescription`, `NSMotionUsageDescription`.
5. Add to the iOS Info.plist: `NSLocationAlwaysAndWhenInUseUsageDescription`, `NSLocationWhenInUseUsageDescription`. Enable **Background Modes → Location updates** on the iOS target.
6. Run the iOS app on your iPhone, add a contact, then run the watch app on your watch.

Plain-language text for the permission prompts is a good first contribution.

## Known gaps

- All-clear doesn't yet require a passcode.
- Apple's fall-detection API needs a special entitlement from Apple; this version uses its own impact-plus-stillness rule instead.
- The response window and check interval aren't yet adjustable on the watch itself.
- Battery use over a full night is unknown.
