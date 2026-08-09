# IBlocker

System-wide ad blocking for iPhone and iPad. No subscriptions, no accounts, no
telemetry — you build it yourself with your own Apple Developer account, and it
keeps working because you own it.

**How it blocks:** a local VPN (Network Extension packet tunnel) that never
sends your traffic anywhere. It intercepts exactly one thing — DNS lookups —
answers the ones belonging to ad/tracker domains with `0.0.0.0`, and forwards
everything else to an encrypted upstream resolver of your choice. Every app on
the device benefits, not just Safari.

| Layer | What it does |
|---|---|
| **VPN mode** (main) | On-device DNS filtering, all apps, works on cellular + Wi‑Fi |
| **DNS profile** (`.mobileconfig`) | Blocking via a public DNS provider (AdGuard/Mullvad/NextDNS) when the VPN is off |
| **System encrypted DNS** | Same as the profile, installed in-app via `NEDNSSettingsManager` |
| **Safari content blocker** | Hides in-page leftovers (empty ad frames, cosmetic rules from EasyList) |

Filter lists (OISD, HaGeZi, StevenBlack, AdGuard DNS, plus any custom URL) are
downloaded straight from their sources, compiled to a memory-mapped binary
blob, and auto-updated. A live query log shows every DNS decision with
one-tap allow/block.

## Requirements

- **Paid Apple Developer account** ($99/yr). Not optional for the VPN mode:
  Apple only grants the Network Extension entitlement to paid accounts.
  (The profile-export and Safari-blocker parts work with free signing.)
- Xcode 26 or newer, iOS 26 or newer on the device.

## Build & install (one-time, ~5 minutes)

1. Clone this repo and open `IBlocker.xcodeproj` in Xcode.
2. Edit **`Config/Signing.xcconfig`** — two values:
   ```
   DEVELOPMENT_TEAM = ABCDE12345      // your Team ID (developer.apple.com → Membership)
   BUNDLE_ID_PREFIX = com.yourname    // any reverse-DNS prefix you own
   ```
3. Xcode ▸ Settings ▸ Accounts: make sure you're signed in with that team.
4. Select the **IBlocker** scheme, pick your iPhone, hit **Run**.
   Automatic signing creates the App IDs, App Group and VPN entitlement
   profiles on first build.
5. On the phone: open the app → onboarding downloads the blocklist → tap
   **Enable protection** → iOS asks to add a VPN configuration → **Allow**.

That's it. The VPN icon appears in the status bar; ads stop resolving.

### Verifying it works

- Safari: previously ad-filled sites render without ads.
- In the app: **Log** tab fills with blocked/allowed queries; Dashboard
  counters climb.
- Terminal test (any DNS tool app, or `dig` on a Mac using the phone's
  hotspot): `dig doubleclick.net` → answer `0.0.0.0`.
- Reboot the phone: protection resumes by itself (on-demand VPN).
- Memory check (Xcode ▸ Debug Navigator, PacketTunnel process): should sit
  well under 15 MB even with large lists — the blocklist is memory-mapped,
  not loaded.

## Things worth knowing

- **On-demand VPN**: the tunnel auto-starts on any network activity and
  survives reboots. Toggling it off in-app disables on-demand first
  (otherwise iOS would instantly reconnect it).
- **Precedence**: while the VPN is connected, its DNS wins. An installed DNS
  profile takes over automatically whenever the VPN is off — they compose
  nicely as belt + suspenders.
- **Apps with their own DoH** (some browsers) can bypass any DNS filter; the
  default lists block the well-known DoH provider domains, which makes those
  apps fall back to system DNS.
- **iOS betas**: this project exists because a 5-year-old blocker died on a
  beta. If the tunnel misbehaves right after an iOS beta update: reboot once,
  then toggle protection off/on. The tunnel uses the most spec-minimal
  Network Extension configuration possible on purpose.
- **Known limitation**: TCP:53 is not served (we never set TC on our own
  answers, so nothing should retry over TCP; with the default DoH upstream,
  truncation can't happen upstream either). Fragmented UDP and DNS over
  non-standard ports are dropped.
- **Query log** is a fixed 4 MB ring file in the App Group. It never leaves
  the device and can be disabled in Settings.

## Repository layout

```
IBlockerKit/            Swift package — ALL logic lives here, fully unit-tested
  Sources/IBlockerKit/       DNS wire codec, IP/UDP packets, rule compiler,
                             mmap matcher, query-log ring, list updater,
                             mobileconfig builder, EasyList converter, engine
  Sources/IBlockerTunnelKit/ DoH + UDP upstream resolvers
  Sources/IBlockerUI/        The complete SwiftUI app (iOS)
App/                    App target shell (@main + assets + plists)
PacketTunnel/           NEPacketTunnelProvider extension shell
ContentBlocker/         Safari content blocker extension shell
Config/                 xcconfigs — Signing.xcconfig is the only file to edit
docs/                   Architecture, signing, blocking-mode docs
```

`swift test --package-path IBlockerKit` runs the full engine test suite on
any platform, including Linux — CI does exactly that plus an unsigned
`xcodebuild` of the app.

## Roadmap

- macOS app (the package is already platform-clean; needs a Mac app target +
  system-extension packaging)
- Punycode/IDN rule input, per-list block attribution in the log
- Optional local HTTP server for one-tap profile install

## License

MIT — see [LICENSE](LICENSE).
