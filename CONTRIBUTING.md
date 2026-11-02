# Contributing to Still Here

Thank you. This project exists because someone who isn't a programmer wanted it to, and had an AI (Claude) write the first version. It needs people who know what they're doing.

## Most useful right now

1. **Build it on a real watch.** Neither watch app has been compiled yet. Start with [apple/README.md](apple/README.md) or [android/README.md](android/README.md), and open an issue with whatever breaks.
2. **Add your campus.** Use the [Add my campus form](https://github.com/SidequestMF/still-here/issues/new?template=add-campus.yml), or edit `campuses/campuses.json` directly (see [campuses/README.md](campuses/README.md)).
3. **Check the thresholds.** If you work in health sensing or toxicology, [docs/RESEARCH.md](docs/RESEARCH.md) lists the open questions.

## Ground rules

- **Safety first.** A change that could make an alert fail to send, or send to the wrong number, needs tests that show it doesn't.
- **Keep the shared rules in sync.** `apple/Shared/Detection.swift`, `android/shared/.../Detection.kt` and `tests/detection-mirror.mjs` must make identical decisions. `tests/run-kotlin-checks.sh` checks Kotlin against the JavaScript mirror.
- **Run the tests.** Every pull request runs them automatically; see [docs/TESTING.md](docs/TESTING.md) to run them yourself.
- **Plain language** in anything a person in trouble might read.
- **Be kind.** Some people here have lived through what this app is about.

## Reviews

The project owner isn't a programmer, so pull requests are merged only after review by a trusted developer. If you'd like to help maintain the project, say so in an issue.
