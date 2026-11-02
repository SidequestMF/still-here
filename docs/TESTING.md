# What's tested (and what isn't)

Last run: November 2, 2026.

## Summary

| Area | How it's tested | Result |
| --- | --- | --- |
| Detection logic (heart rate, falls, stillness, tap test) | 15 simulated nights, each run 100 times with different random data, plus 9 tap-test rule cases | All pass |
| Web prototype | 31 browser scenarios in Chrome, each in light and dark mode (62 runs) | All pass |
| Text-message relay | 16 tests: message wording, bad input, wrong token, duplicates, partial failures | All pass |
| Android/Wear OS shared logic (Kotlin) | Compiled and run: 15 rule and message checks, 24 night-mode state checks, and a replay of 75 recorded simulated nights | All pass; identical decisions to the reference on all 75 nights |
| Android alert format | Kotlin-built alert sent through the relay's own validation and message builder | Accepted; correct text |
| Swift shared logic | 8 XCTest cases written | **Not yet run.** Needs a Mac or a Linux machine with Swift: `cd apple && swift test` |
| Apple Watch and iPhone apps | — | **Not yet built or run.** Needs Xcode and real devices |
| Wear OS watch and Android phone apps | — | **Not yet built or run.** Needs Android Studio and real devices |

## Run the tests yourself

```sh
node tests/simulate-nights.mjs                 # detection logic, one run each
SEEDS=100 node tests/simulate-nights.mjs       # each scenario 100 times
node relay/test.js                             # relay
bash tests/run-kotlin-checks.sh                # Android shared code + replay of recorded nights
python3 tests/prototype_test.py                # web prototype (needs: pip install playwright)
cd apple && swift test                         # Swift logic (Mac, or Linux with Swift)
```

`tests/detection-mirror.mjs` is a line-by-line JavaScript copy of `apple/Shared/Detection.swift`, so long simulations run anywhere. If you change one, change the other.

## Simulated nights (100 runs each)

Simulated data is a model, not real people. These results show the logic behaves as designed, not that it works on real bodies.

| Scenario | Expected | Result |
| --- | --- | --- |
| Normal 4-hour night (talk, dance, sit, walk) | No alerts; at most one extra check-in | 100/100 |
| Drugged: heart rate falls 95→58, slumps (symptoms start at 75 min) | Check-in by 105 min | 100/100; median 95 min, latest 103 min |
| Drugged with a small heart-rate change | Check-in from stillness | 100/100; median 99 min |
| Drugged after 2 hours of dancing | Check-in by 160 min | 100/100; median 153 min |
| Falls while dancing and stays down | Check-in within seconds | 100/100; 10 s after the fall |
| Hard bumps while dancing | Nothing | 100/100 |
| Trips and gets right up | Nothing | 100/100 |
| Heart-rate sensor glitches | Nothing | 100/100 |
| Loose watch: one minute of bad readings | Nothing | 100/100 |
| Racing heart while sitting still | Check-in | 100/100; about 5 min after it starts |
| High heart rate from dancing | Nothing | 100/100 |
| Nervous arrival, then relaxed | Nothing | 100/100 |
| Calm evening with low heart rate | At most 2 check-ins | 100/100 (one check-in, then accepted as normal) |
| Sitting very still on a phone for an hour | At most 3 check-ins | 100/100 |
| Watch taken off | Check-in | 100/100 at 15 min from motion alone; the app also checks after 3 min without heart rate |

## Problems the tests found and fixed

1. **A nervous start caused repeated false alarms.** The first version learned "normal" from the first 10 minutes. If you arrived with a racing heart, a relaxed heart rate later looked like a dangerous drop (6 false check-ins in one simulated night). Now the baseline only uses calm moments, from 20 to 60 minutes earlier.
2. **A slow drop could hide itself.** When the baseline followed recent readings too closely, a 20-minute decline dragged the baseline down with it and was never flagged. The 20-minute lag fixes this.
3. **Noisy readings reset the timer.** One normal reading in a run of low ones cancelled detection. Now 80% of readings over 2 minutes must be out of range.
4. **The same calm heart rate kept re-triggering.** After you pass a heart-rate check, that level is now accepted as your normal for an hour.
5. **Long dancing left no baseline.** After two hours of dancing there were no calm readings to compare with, so drops went unnoticed. The last known calm baseline is now kept.
6. **Stillness was too eager.** At 10 minutes, a normal night produced extra check-ins in 5 of 40 runs. It's now 15 minutes; scheduled check-ins still cover anyone who is slumped.
7. **Prototype bug:** a simulated heart-rate event could start a check-in after night mode had ended. Fixed and covered by two new tests.
8. **Map links:** the relay now sends Google Maps links, which open on both iPhone and Android.
9. **Relay hardening:** names are stripped of characters that could form links, and the access token is compared in constant time.

## Not yet tested

- Anything on a real Apple Watch: sensor behavior, battery life, the app staying alive all night, vibration strength in a loud room.
- Real human data of any kind.
- Text delivery through a real SMS provider.
