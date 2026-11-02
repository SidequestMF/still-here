# Still Here

**An open-source Apple Watch safety app for nights out.** It checks on you, and if you stop responding normally, it texts the people you chose with your location.

Drink spiking is hard to detect. Test strips miss many drugs, and the most common substance in drug-facilitated assault is plain alcohol. Still Here doesn't test the drink. It watches for the *effect*, whatever the cause:

- your heart rate drifting far from your own normal,
- a stumble or fall where you stay down,
- long unusual stillness, or the watch coming off,
- or simply a missed scheduled check-in.

When something looks wrong, the watch buzzes and asks **"Are you OK?"** with a 5-second tap test (tap 1, 2, 3, 4 in order), compared against your own speed from earlier in the night. Pass it and nothing happens. Fail it twice, or don't answer, and your trusted contacts get a text with your location.

> **Status: prototype.** The decision logic is tested in simulation; the Apple Watch app has **not yet been built or run on a real watch.** Please don't rely on it for safety yet. See [What's tested](docs/TESTING.md).

## Try it now (no watch needed)

The web prototype runs on any phone and walks through the whole flow with simulated sensors: [web-prototype/index.html](web-prototype/index.html). Nothing is sent.

## What's in this repository

| Folder | What it is |
| --- | --- |
| `web-prototype/` | Single-page demo of the check-in and alert flow |
| `apple/` | Swift source for the Apple Watch app and its iPhone companion, plus the shared detection logic and its unit tests |
| `relay/` | Tiny Node.js service that sends the alert texts (iPhone apps can't text silently) |
| `tests/` | Simulated-night tests of the detection logic and browser tests of the prototype |
| `docs/` | Safety limits, test results, and open research questions |

## How you can help

- **iOS / watchOS developers:** build the app on a real watch and report what breaks. See [apple/README.md](apple/README.md).
- **Researchers:** the detection thresholds are educated guesses. Real-world data (with ethics review) is the most valuable contribution. See [docs/RESEARCH.md](docs/RESEARCH.md).
- **Survivors, advocates, campus safety teams, bar staff:** tell us what would actually be used on a night out, and what would make it worse. Open an issue.

## Important limits

Still Here is not a medical device and cannot tell whether a drink was spiked. It can miss things and it can raise false alarms. It is one layer, alongside drink covers, staying with friends, and venues that take this seriously. In an emergency, call 911. Full details: [docs/SAFETY.md](docs/SAFETY.md).

## License

MIT. See [LICENSE](LICENSE).
