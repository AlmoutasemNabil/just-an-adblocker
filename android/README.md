# IBlocker for Android

The Android port of IBlocker: the same on-device DNS ad blocker, the same
compiled-blocklist format, the same acceptance test — **in-app Google ads must
not load** — rebuilt on `VpnService` and Compose.

No subscriptions, no accounts, no telemetry. You build it once and it keeps
working because you own it.

**How it blocks:** a local VPN (`VpnService`) that never sends your traffic
anywhere. Only the two synthetic resolver addresses (`198.18.0.2`, `fd00::2`)
are routed into the tun device, and those are also the DNS servers handed to
the system — so every app's DNS lookups arrive here, everything else stays on
the physical interface. Ad and tracker names are answered locally with
`0.0.0.0`; the rest go to an encrypted upstream resolver of your choice.

## What's inside

| Layer | What it does |
|---|---|
| **VPN mode** (main) | On-device DNS filtering, all apps, Wi-Fi + mobile data, restarts after reboot |
| **Built-in core rules** | Google/AdMob + major mobile ad SDKs compiled into the APK — blocking works from first launch, offline, and through list outages |
| **DNS-bypass handling** | Blocks the DoH/DoT endpoints apps use to skip the system resolver (see below) |
| **Private DNS guide** | Provider hostnames + a deep link, for blocking while the VPN is off |
| **Per-app exclusions** | Any app can be dropped out of the tunnel entirely (iOS cannot do this) |

Filter lists (OISD on by default; HaGeZi, StevenBlack, AdGuard DNS one tap
away; any custom URL) are downloaded from their sources, compiled to a
memory-mapped blob, and refreshed daily by WorkManager. A live query log shows
every DNS decision with one-tap allow/block, and the Dashboard charts what got
blocked.

## Quick controls

- **Pause** — Dashboard ▸ Pause blocking (5 min / 15 min / 1 hour) for a
  checkout, captcha, or link a list breaks. Resumes by itself.
- **Home-screen widget** — blocked-today counter + protection status, tap to
  toggle.
- **Quick Settings tile** — flip protection from the shade without opening the
  app.
- **Launcher shortcuts + automation** — long-press the icon for "Turn on",
  "Pause 15 min", "Update lists". The same actions are plain intents, so
  Tasker/Automate (or `adb shell am start -a
  com.iblocker.android.action.PAUSE_15`) can drive them.

## The acceptance test: in-app Google ads

**Settings ▸ Verify blocking** (also on the Dashboard) resolves the canonical
AdMob domains through the live system resolver — the exact path every app's ad
SDK uses — and gives a pass/fail verdict, with `android.com` as the
must-not-be-blocked control. The DNS-bypass probes report separately as
warnings with guidance.

### Why "DNS-bypass handling" exists

A DNS filter only ever sees lookups that reach the system resolver. An app
that speaks DoH straight to a hardcoded endpoint (some browsers do) never
asks, so its ads keep loading while the ad domains sit "Blocked" in the log —
the same class of hole as Apple's tracker relay on iOS, by a different route.

Pick a strategy in **Settings ▸ Encrypted-DNS bypass**:

1. **Block DoH/DoT endpoints** (default) — the bundled ruleset blocks the
   known endpoints, so those apps fall back to system DNS where the filter
   sees them. Firefox's canary domain is answered with NXDOMAIN, which is what
   tells it to leave DNS to the system. Trade-off: if you set one of those
   hostnames as your *system* Private DNS, that setting stops working while
   protection is on.
2. **Leave encrypted DNS alone** — nothing DNS-related is blocked; apps with
   their own resolver keep it, and keep their ads.

Two things no DNS filter on Android can fix: an app pinned to raw IPs with no
lookup at all, and YouTube/Facebook in-feed ads (served from the same domains
as the content).

## Requirements

- Android 8.0 (API 26) or newer.
- JDK 17+ and the Android SDK (Android Studio, or `sdkmanager` with
  `platforms;android-35` and `build-tools;35.0.0`).
- No developer account, no signing setup, no store — it is your APK.

## Build & install (~5 minutes)

```bash
cd android
./gradlew :app:assembleRelease          # or :app:assembleDebug
adb install -r app/build/outputs/apk/release/app-release.apk
```

Both variants are signed with the local debug key, so the APK installs
directly. (Open `android/` in Android Studio and hit Run if you prefer.)

On the phone: onboarding downloads the blocklist → **Enable protection** →
**OK** on Android's VPN prompt. Add the widget (long-press the home screen)
and the Quick Settings tile (edit the shade's tiles) if you want them.

### Verifying it works

- **Settings ▸ Verify blocking** → "In-app Google ads are BLOCKED".
- Open a free ad-supported app or game: ad slots stay empty / "no fill".
  (Force-stop it once first — ad SDKs cache one prefetched ad.)
- The Log tab fills with live blocked/allowed queries; Dashboard counters
  climb.
- Reboot: protection comes back by itself (Settings ▸ Startup, on by default).

## Things worth knowing

- **One VPN slot.** Android allows one VPN at a time; starting another one
  revokes IBlocker (`onRevoke`) and the shield goes off.
- **Always-on VPN.** For a guarantee that no query escapes before the app
  starts, turn on Android's own always-on switch: Settings ▸ Network &
  internet ▸ VPN ▸ IBlocker ▸ gear ▸ Always-on VPN. The app's "start after
  reboot" is the lighter-weight version of that.
- **Precedence**: while the tunnel is connected its DNS wins; system Private
  DNS takes over whenever the tunnel is off. Running both is the recommended
  belt-and-suspenders setup.
- **Memory**: the blocklist is memory-mapped, not loaded — 800k domains is
  6.4 MB of file, of which only touched pages are resident.
- The query log is a fixed 4 MB ring in the app's private storage, never
  leaves the device, and can be disabled in Settings.

## Repository layout

```
android/
  core/     Pure Kotlin/JVM engine — ALL logic, fully unit-tested (90 tests):
            DNS wire codec, IP/UDP packets, rule compiler, mmap matcher, seed
            rules, query-log ring, list updater, settings store, JSON codec,
            DNS proxy engine (with pause)
  app/      Android app: VpnService, DoH/UDP upstreams, Compose UI (dashboard,
            log, lists, settings, onboarding, blocking test), Quick Settings
            tile, Glance widget, WorkManager refresh, boot receiver
```

`./gradlew :core:test` runs the whole engine suite — raw IP packet in, raw IP
packet out — on any machine, with no Android SDK and no emulator. CI runs that
plus the app's unit tests and an `assembleDebug` on every push.

## How this maps to the iOS build

See [../docs/ANDROID.md](../docs/ANDROID.md) for the full component-by-
component mapping, what is byte-compatible between the two (the compiled
blocklist, the log ring, the stats file), and where the platforms genuinely
differ.

## License

MIT — see [../LICENSE](../LICENSE).
