# Contributing

Thanks for looking. Issues and pull requests are welcome.

## The one rule

**Nothing phones home.** No analytics, no crash reporting, no remote config,
no "anonymous" telemetry, no license checks. A patch that adds a network call
to a destination not already listed in the README's outbound-connection table
will be closed, regardless of how useful the feature is.

This isn't a style preference. The project's entire claim is that it cannot
abuse your DNS queries because there is nowhere for them to go. One exception
would make that claim false.

The same applies to dependencies: a library that reports usage is a network
call with extra steps. iOS currently has **zero** third-party dependencies and
that is a deliberate target, not an accident.

## Before you open a PR

Run both engine suites. They're pure logic, fast, and need no device:

```bash
swift test --package-path ios/IBlockerKit
```
```bash
cd android && ./gradlew :core:test :app:testDebugUnitTest
```

CI additionally does an unsigned `xcodebuild` of the iOS app and all three
extensions plus an Android `assembleDebug`, so make sure both platforms still
build if you touched shared behaviour.

## Keeping the two platforms in step

The iOS Swift engine (`ios/IBlockerKit`) and the Kotlin engine
(`android/core`) are deliberate ports of each other. They share the
memory-mapped blocklist format, the query-log ring layout, and the blocking
semantics. [docs/ANDROID.md](docs/ANDROID.md) maps them component by
component.

If you change one of those shared formats on one side, change it on the other
in the same PR, or the two apps stop being able to read each other's
documented format. If you can't do both, say so in the PR and it can be split.

## Where logic goes

Business logic belongs in the engine packages (`ios/IBlockerKit`,
`android/core`), not in the UI or the platform shells. Both engines are
unit-tested off-device precisely because they contain no platform imports —
keep it that way and your change gets test coverage for free.

## Filter lists

Please don't add new default-on blocklists. Lists are a matter of taste and
breakage tolerance, and any list can already be added by URL in the app. Rules
compiled into the binary (`SeedRules`) are limited to core mobile ad networks
that must work offline and on first launch.

## Commit messages

Explain why, not what — the diff already says what. Present tense, no trailing
period on the subject line.

## Scope

Some things are deliberately out of scope:

- Per-app rules on iOS — the OS doesn't tell a tunnel which app sent a packet.
- Splitting YouTube's in-stream ads — same domains as the video.
- Anything that requires a server, an account, or a subscription.

## License

By contributing you agree your work is licensed under the MIT License, the
same as the rest of the project.
