# IBlocker

System-wide ad blocking for iPhone, iPad, and Android — including **ads inside
apps** (AdMob and friends), which is the measure this project is built against.

No subscriptions. No accounts. No servers of our own. You build it once
yourself, and it keeps working because you own it.

[![CI](https://github.com/AlmoutasemNabil/IBlocker/actions/workflows/ci.yml/badge.svg)](https://github.com/AlmoutasemNabil/IBlocker/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## Privacy is the whole point

Most ad blockers ask you to trust them with the single most revealing stream of
data your phone produces: every domain every app on it talks to. A DNS filter
sees all of it. That is an enormous amount of trust to hand to a binary you
didn't build, funded by a subscription you can't audit.

This project's answer is to never be in a position to abuse it.

**There is no server.** IBlocker has no backend, no account system, no
license check, no crash reporter, no analytics, no "anonymous usage
statistics." Not disabled by default — *absent*. There is no code path that
sends anything about you anywhere, because there is nowhere for it to go.

**Your DNS queries go to the resolver you chose, and nowhere else.** The VPN
is local. It terminates on the device, in the tunnel extension. It intercepts
exactly one thing — DNS — answers ad and tracker lookups itself with
`0.0.0.0`, and forwards the rest to the encrypted upstream *you* picked. Your
traffic is never proxied through anything belonging to this project.

**Your query log never leaves the phone.** It's a fixed-size ring buffer
(65,536 records × 64 bytes ≈ 4 MB) in the app's private container — iOS App
Group, Android app-private storage. It overwrites itself and is capped by
construction, so it cannot grow into a history of your life. You can turn it
off entirely in Settings. Nothing reads it but the app's own Log tab.

### Verify all of that yourself

Don't take the paragraphs above on faith — they're checkable in about a
minute, and this is the whole reason the source is here.

Every outbound connection the apps can make, exhaustively:

| Destination | When | Why |
|---|---|---|
| The DoH/UDP resolver **you** selected | Every non-blocked DNS lookup | Resolving names. Encrypted by default. |
| `big.oisd.nl`, `raw.githubusercontent.com`, `easylist.to`, `adguardteam.github.io` | Blocklist refresh only | Downloading the filter lists you enabled, straight from their publishers. |

That's the complete list. Dump every URL in the source and check for yourself:

```bash
git grep -ohE 'https?://[a-zA-Z0-9./_%-]+' -- ios/ android/ | sort -u
```

You'll get ~32 lines, because that also catches things that are not network
calls: XML namespaces (`schemas.android.com`), the plist DTD, Gradle's own
doc links, license URLs, `example.com` test fixtures, and `bundled.invalid`
(the sentinel for rules compiled into the binary, which is deliberately not a
resolvable host). Strip those and what remains is the table above.

Then check what's linked. On iOS the answer is nothing — the package declares
no dependencies at all, so it's first-party code plus Apple's frameworks:

```bash
grep -A3 'dependencies:' ios/IBlockerKit/Package.swift
```

On Android it's AndroidX, Compose, and OkHttp (used for DoH and list
downloads) — no analytics or crash-reporting SDK in the list:

```bash
grep implementation android/app/build.gradle.kts android/core/build.gradle.kts
```

### Disclosed on purpose

A privacy claim is only worth as much as the things it admits to.

- **Android requests `QUERY_ALL_PACKAGES`.** It's a sensitive permission and
  we'd rather explain it than hide it: it populates the per-app exclusion
  screen, and it is read at exactly one call site
  ([`ExcludedAppsScreen.kt`](android/app/src/main/kotlin/com/iblocker/android/ui/settings/ExcludedAppsScreen.kt)).
  The list never leaves the device. If you don't use per-app exclusions, you
  can delete the permission from the manifest and the app still builds.
- **`allowBackup="false"`** on Android — your blocklist and query log are
  deliberately excluded from cloud backup.
- **Your upstream resolver still sees your unblocked queries.** IBlocker
  removes *ad networks* from the picture; it does not make you invisible to
  whoever runs the DNS server you pointed it at. Choose accordingly — the
  bundled options (Quad9, Mullvad, AdGuard, Cloudflare, NextDNS) have
  published policies, and you can enter any resolver you like.
- **DNS blocking has hard limits.** It can't touch YouTube's in-stream ads
  (served from the same domains as the video — nothing on iOS can split
  them), and iOS never tells a tunnel which app sent a packet, so per-app
  rules aren't possible there.

---

## How it blocks

A local VPN — a Network Extension packet tunnel on iOS, `VpnService` on
Android — that never sends your traffic anywhere. It intercepts DNS lookups,
answers the ones belonging to ad/tracker domains with `0.0.0.0`, and forwards
everything else to an encrypted upstream resolver of your choice. Every app on
the device benefits, not just the browser.

**Two platforms, one design.** The iOS app lives in [`ios/`](ios/), the
Android app in [`android/`](android/). Both compile the same memory-mapped
blocklist format, write the same query-log ring, and are held to the same
acceptance test. See [docs/ANDROID.md](docs/ANDROID.md) for the
component-by-component mapping between them.

| Layer | What it does |
|---|---|
| **VPN mode** (main) | On-device DNS filtering, all apps, Wi‑Fi + cellular, survives reboots |
| **Built-in core rules** | Google/AdMob + major mobile ad SDKs compiled into the binary — blocking works from first launch, offline, and through list outages |
| **Apple relay handling** | Three strategies for iOS's tracker relay — the loophole that lets in-app ads bypass naive DNS blockers |
| **DNS profile** (`.mobileconfig`) | Blocking via a public DNS provider whenever the VPN is off |
| **System encrypted DNS** | Same idea, installed in-app via `NEDNSSettingsManager` |
| **Safari content blocker** | Hides in-page leftovers — works even under Private Relay |

Filter lists (OISD on by default; HaGeZi, StevenBlack, AdGuard DNS one tap
away; any custom URL) are downloaded from their sources, compiled to a
memory-mapped blob, and auto-updated.

### Quick controls

- **Pause** — 5 min / 15 min / 1 hour for a checkout, captcha, or a link a
  list breaks. Resumes by itself.
- **Home/Lock Screen widget** — blocked-today counter + protection status.
- **Control Center toggle** (iOS 18+) / **Quick Settings tile** (Android).
- **Siri & Shortcuts** — "Turn on IBlocker", "Pause IBlocker".

## The acceptance test: in-app Google ads

**Settings ▸ Verify blocking** resolves the canonical AdMob domains through
the live system resolver — the exact path every app's ad SDK uses — and gives
a pass/fail verdict, with `apple.com` as the must-not-be-blocked control. The
verdict is decided by the ad domains; the Apple-relay probes report separately
as warnings.

### Why "Apple relay handling" exists

iOS ships with "Limit IP Address Tracking" ON by default. It routes
connections to known trackers (including Google's ad servers) through
**Apple's relay**, where the tracker's hostname is resolved remotely — those
connections never touch on-device DNS, so in-app ads can load while the ad
domains sit "Blocked" in the log. Pick a strategy in **Settings ▸ Apple
Private Relay**:

1. **Auto-suspend while protected** — the tunnel presents itself as a full
   VPN so iOS pauses the relay by itself while protection runs. Relay returns
   when protection stops.
2. **Block relay domains** — strongest guarantee; Private Relay shows
   "unavailable" while protection is on.
3. **Keep Private Relay** — nothing Apple is blocked; you turn off "Limit IP
   Address Tracking" per network instead.

Full trade-offs in [docs/BLOCKING-MODES.md](docs/BLOCKING-MODES.md).

---

## Build it yourself

### iOS (~5 minutes)

**Requirements:** a paid Apple Developer account ($99/yr) — Apple only grants
the Network Extension entitlement to paid accounts. Xcode 26+, iOS 26+ on the
device. (The Safari blocker and profile export work with free signing.)

1. Clone and open `ios/IBlocker.xcodeproj`.
2. Edit **[`ios/Config/Signing.xcconfig`](ios/Config/Signing.xcconfig)** —
   your Team ID and a bundle prefix you control:
   ```
   DEVELOPMENT_TEAM = ABCDE12345
   BUNDLE_ID_PREFIX = com.yourname
   ```
   (Or drop the same two lines in `ios/Config/Signing.local.xcconfig`, which
   is gitignored and overrides the tracked file.)
3. Select the **IBlocker** scheme, pick your iPhone, hit **Run**. Automatic
   signing creates the App IDs, App Group, and VPN entitlement on first build.
4. On the phone: onboarding downloads the blocklist → **Enable protection** →
   **Allow** the VPN prompt.

### Android

```bash
cd android && ./gradlew :app:assembleDebug
```

Install the APK from `android/app/build/outputs/apk/debug/`. No developer
account needed. Details in [android/README.md](android/README.md).

### Verifying it works

- **Settings ▸ Verify blocking** → "In-app Google ads are BLOCKED".
- Open a free ad-supported app or game: ad slots stay empty / "no fill".
  (Force-quit it once first — ad SDKs cache one prefetched ad.)
- The Log tab fills with live decisions; Dashboard counters climb.
- Reboot: protection resumes by itself. Tunnel memory stays under ~15 MB even
  with huge lists — the blocklist is memory-mapped, not loaded.

## Things worth knowing

- **Precedence**: while the VPN is connected its DNS wins; an installed DNS
  profile takes over whenever the VPN is off. Running both is the recommended
  belt-and-suspenders setup.
- **Toggling off**: the app disables on-demand before stopping, so it stays
  off until you turn it back on.
- **Apps with hardcoded DoH** (some browsers) can bypass any DNS filter; the
  default lists block the major DoH endpoints, pushing them back to system DNS.
- **iOS betas**: if the tunnel misbehaves after a beta update — reboot once,
  toggle protection off/on.

## Repository layout

```
ios/                    The iOS/iPadOS app
  IBlockerKit/               Swift package — ALL logic, fully unit-tested
    Sources/IBlockerKit/       DNS wire codec, IP/UDP packets, rule compiler,
                               mmap matcher, seed rules, query-log ring, list
                               updater, mobileconfig builder, EasyList
                               converter, DNS proxy engine, shared settings
    Sources/IBlockerTunnelKit/ DoH + UDP upstream resolvers
    Sources/IBlockerUI/        SwiftUI app: dashboard, log, lists, settings,
                               onboarding, blocking test, App Intents
  App/                       App target shell (@main + assets + plists)
  PacketTunnel/              NEPacketTunnelProvider extension
  ContentBlocker/            Safari content blocker extension
  Widgets/                   WidgetKit (status widget + Control Center toggle)
  Config/                    xcconfigs — Signing.xcconfig is the only edit
android/                The Android app
  core/                      Kotlin/JVM engine — the IBlockerKit port,
                             unit-tested, no Android SDK needed to run
  app/                       VpnService, DoH/UDP upstreams, Compose UI,
                             Quick Settings tile, Glance widget, WorkManager
docs/                   Architecture, signing, blocking modes, privacy
```

## Tests

```bash
swift test --package-path ios/IBlockerKit
```
```bash
cd android && ./gradlew :core:test
```

Both engine suites are pure logic and run anywhere — the Swift one including
Linux, the Kotlin one without an Android SDK. CI additionally does an unsigned
`xcodebuild` of the iOS app and all three extensions, plus an Android
`assembleDebug`, on every push.

## Roadmap

- DNS response cache in the tunnel (speed + battery)
- Temporary allow ("allow for 1 hour" from the log)
- Per-list block attribution and company grouping in stats
- macOS app (the package is already platform-clean)
- Android: full-tunnel mode (userspace TCP/UDP forwarding) to close the
  hardcoded-DoH hole for good

## Contributing

Issues and PRs welcome. One rule, and it is not negotiable: **nothing that
phones home.** No analytics, no crash reporting, no remote config, no
"anonymous" anything. A patch that adds a network call to a destination not
in the table at the top of this README will be rejected on sight.

## License

MIT — see [LICENSE](LICENSE).
