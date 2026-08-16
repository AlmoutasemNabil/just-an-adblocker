# IBlocker

System-wide ad blocking for iPhone, iPad **and Android** — including **ads
inside apps** (AdMob and friends), which is the measure this project is built
against. No subscriptions, no accounts, no telemetry. You build it once
yourself, and it keeps working because you own it.

**How it blocks:** a local VPN (Network Extension packet tunnel on iOS,
`VpnService` on Android) that never sends your traffic anywhere. It intercepts
exactly one thing — DNS lookups — answers the ones belonging to ad/tracker
domains with `0.0.0.0`, and forwards everything else to an encrypted upstream
resolver of your choice. Every app on the device benefits, not just the
browser.

**Two platforms, one design.** The iOS app lives at the repository root; the
Android app lives in [`android/`](android/). Both compile the same
memory-mapped blocklist format, write the same query-log ring, and are built
against the same acceptance test. Everything below describes the iOS build —
see **[android/README.md](android/README.md)** to build and install the
Android one, and **[docs/ANDROID.md](docs/ANDROID.md)** for the
component-by-component mapping between them.

## What's inside

| Layer | What it does |
|---|---|
| **VPN mode** (main) | On-device DNS filtering, all apps, Wi‑Fi + cellular, survives reboots (on-demand) |
| **Built-in core rules** | Google/AdMob + major mobile ad SDKs compiled into the binary — blocking works from first launch, offline, and through list outages |
| **Apple relay handling** | Three strategies for iOS's tracker relay (see below) — the loophole that lets in-app ads bypass naive DNS blockers |
| **DNS profile** (`.mobileconfig`) | Blocking via a public DNS provider (AdGuard/Mullvad/NextDNS) whenever the VPN is off |
| **System encrypted DNS** | Same idea, installed in-app via `NEDNSSettingsManager` |
| **Safari content blocker** | Hides in-page leftovers (empty frames, cosmetic EasyList rules) — works even under Private Relay |

Filter lists (OISD on by default; HaGeZi, StevenBlack, AdGuard DNS one tap
away; any custom URL) are downloaded from their sources, compiled to a
memory-mapped blob, and auto-updated. A live query log shows every DNS
decision with one-tap allow/block, and the Dashboard charts what got blocked.

## Quick controls

- **Pause** — Dashboard ▸ Pause blocking (5 min / 15 min / 1 hour) for a
  checkout, captcha, or link a list breaks. Resumes by itself.
- **Home/Lock Screen widget** — blocked-today counter + protection status.
- **Control Center toggle** (iOS 18+) — flip protection without opening the app.
- **Siri & Shortcuts** — "Turn on IBlocker", "Pause IBlocker", "Update
  IBlocker lists"; automatable (e.g. pause when a specific app opens).

## The acceptance test: in-app Google ads

**Settings ▸ Verify blocking** (also on the Dashboard) resolves the canonical
AdMob domains through the live system resolver — the exact path every app's
ad SDK uses — and gives a pass/fail verdict, with `apple.com` as the
must-not-be-blocked control. The verdict is decided by the ad domains; the
Apple-relay probes report separately as warnings with guidance.

### Why "Apple relay handling" exists

iOS ships with "Limit IP Address Tracking" ON by default. It routes
connections to known trackers (including Google's ad servers) through
**Apple's relay**, where the tracker's hostname is resolved remotely — those
connections never touch on-device DNS, so in-app ads can load while the ad
domains sit "Blocked" in the log. Pick a strategy in **Settings ▸ Apple
Private Relay**:

1. **Auto-suspend while protected** — the tunnel presents itself as a full
   VPN so iOS pauses the relay by itself while protection runs (this is what
   commercial blockers ride on). Relay returns when protection stops.
2. **Block relay domains** — the relay endpoints are DNS-blocked; strongest
   guarantee, Private Relay shows "unavailable" while protection is on.
3. **Keep Private Relay** — nothing Apple is blocked; you turn off "Limit IP
   Address Tracking" per network instead (Settings ▸ Wi‑Fi ▸ ⓘ, and
   Cellular ▸ Cellular Data Options). Safari keeps Apple's IP masking; Safari
   ads stay blocked by the content blocker.

See [docs/BLOCKING-MODES.md](docs/BLOCKING-MODES.md) for the full trade-offs.

## Requirements

- **Paid Apple Developer account** ($99/yr) — Apple only grants the Network
  Extension entitlement to paid accounts. (Profile export and the Safari
  blocker work with free signing.)
- Xcode 26+, iOS 26+ on the device.

## Build & install (~5 minutes)

1. Clone and open `IBlocker.xcodeproj`.
2. Edit **`Config/Signing.xcconfig`** — your Team ID and a bundle prefix:
   ```
   DEVELOPMENT_TEAM = ABCDE12345
   BUNDLE_ID_PREFIX = com.yourname
   ```
3. Select the **IBlocker** scheme, pick your iPhone, hit **Run**. Automatic
   signing creates the App IDs, App Group, and VPN entitlement on first build.
4. On the phone: onboarding downloads the blocklist → **Enable protection**
   → **Allow** the VPN prompt.
5. Add the widget (long-press Home Screen) and the Control Center toggle
   (Settings ▸ Control Center) if you want them.

### Verifying it works

- **Settings ▸ Verify blocking** → "In-app Google ads are BLOCKED".
- Open a free ad-supported app or game: ad slots stay empty / "no fill".
  (Force-quit it once first — ad SDKs cache one prefetched ad.)
- The Log tab fills with live blocked/allowed queries; Dashboard counters climb.
- Reboot: protection resumes by itself. PacketTunnel memory stays under
  ~15 MB even with huge lists — the blocklist is memory-mapped, not loaded.

## Things worth knowing

- **Precedence**: while the VPN is connected its DNS wins; an installed DNS
  profile takes over automatically whenever the VPN is off. Running both is
  the recommended belt-and-suspenders setup.
- **Toggling off**: the app disables on-demand before stopping, so it stays
  off until you turn it back on.
- **Apps with hardcoded DoH** (some browsers) can bypass any DNS filter; the
  default lists block the major DoH endpoints, pushing them back to system DNS.
- **iOS betas**: if the tunnel misbehaves after a beta update — reboot once,
  toggle protection off/on. The tunnel uses the most spec-minimal Network
  Extension configuration possible on purpose.
- **What DNS blocking can't do**: YouTube's in-stream ads (served from the
  same domains as the videos — nothing on iOS can split them) and per-app
  rules (iOS doesn't tell a tunnel which app sent a packet).
- The query log is a fixed 4 MB ring in the App Group, never leaves the
  device, and can be disabled in Settings.

## Repository layout

```
IBlockerKit/            Swift package — ALL logic, fully unit-tested (85 tests)
  Sources/IBlockerKit/       DNS wire codec, IP/UDP packets, rule compiler,
                             mmap matcher, seed rules, query-log ring, list
                             updater, mobileconfig builder, EasyList converter,
                             DNS proxy engine (with pause), shared settings
  Sources/IBlockerTunnelKit/ DoH + UDP upstream resolvers
  Sources/IBlockerUI/        The SwiftUI app: dashboard, log, lists, settings,
                             onboarding, blocking test, App Intents, VPN control
App/                    App target shell (@main + assets + plists)
PacketTunnel/           NEPacketTunnelProvider extension
ContentBlocker/         Safari content blocker extension
Widgets/                WidgetKit extension (status widget + Control Center toggle)
Config/                 xcconfigs — Signing.xcconfig is the only file to edit
android/                The Android app (see android/README.md)
  core/                      Kotlin/JVM engine — the IBlockerKit port,
                             unit-tested (90 tests), no Android SDK needed
  app/                       VpnService, DoH/UDP upstreams, Compose UI,
                             Quick Settings tile, Glance widget, WorkManager
docs/                   Architecture, signing, blocking-mode, Android docs
```

`swift test --package-path IBlockerKit` runs the full engine suite on any
platform including Linux; `cd android && ./gradlew :core:test` does the same
for the Kotlin engine. CI additionally does an unsigned `xcodebuild` of the
iOS app and all three extensions, plus an Android `assembleDebug`, on every
push.

## Roadmap

- DNS response cache in the tunnel (speed + battery)
- Temporary allow ("allow for 1 hour" from the log)
- Per-list block attribution and company grouping in stats
- macOS app (the package is already platform-clean)
- iCloud sync of allow/deny lists
- Android: full-tunnel mode (userspace TCP/UDP forwarding) to close the
  hardcoded-DoH hole for good

## License

MIT — see [LICENSE](LICENSE).
