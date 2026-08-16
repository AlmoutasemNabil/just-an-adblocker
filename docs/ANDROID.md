# The Android port

`android/` is a full port of the iOS app, not a wrapper. Same design, same
on-disk formats, same acceptance test; the platform layer is rewritten against
`VpnService` and Compose, and a few features change shape because the two
operating systems expose different holes and different levers.

## Component map

| iOS | Android | Notes |
|---|---|---|
| `IBlockerKit` (Swift package) | `android/core` (Kotlin/JVM library) | Line-for-line port. No platform imports, so the whole packet path is unit-tested off-device on both sides. |
| `NEPacketTunnelProvider` | `IBlockerVpnService` | Same split-route design: only `198.18.0.2` / `fd00::2` routed in, and set as the system DNS servers. |
| `NEPacketTunnelNetworkSettings` | `VpnService.Builder` | `addAddress`/`addRoute`/`addDnsServer`/`setMtu` instead of `NEIPv4Settings` + `NEDNSSettings`. |
| App Group container | `context.filesDir` | Android runs the UI and the service in one app, so the shared container is just app-private storage. Same file names. |
| `sendProviderMessage` IPC | direct calls + `StateFlow` | Same-process, so control (`reloadRules`, `setUpstream`, `setPause`) is a service intent, and live stats are a flow instead of a request/response. |
| shared `UserDefaults` | `settings.json` (`SettingsStore`) | A small JSON document, so it stays readable across processes and is testable without a device. |
| `URLSession` DoH | OkHttp DoH over a `protect()`ed socket | Same RFC 8484 POST to an IP-literal endpoint. |
| `NWConnection` UDP:53 | `DatagramSocket` + receive thread | Same transaction-ID remapping and demultiplexing. |
| SwiftUI app | Compose (Material 3) | Same four tabs, same onboarding, same blocking test. |
| WidgetKit status widget | Glance app widget | Blocked-today + status, tap to toggle. |
| Control Center toggle | Quick Settings tile | Same one-tap protection switch. |
| App Intents / Siri phrases | Launcher shortcuts + exported intents | Any automation app (or `adb shell am start`) can fire them; the assistant layer belongs to the OS, not the app. |
| `BGAppRefreshTask` | WorkManager periodic work | Daily list refresh, both best-effort, both backed by a foreground staleness check. |
| On-demand VPN rules | Boot receiver (+ system Always-on VPN) | Android's stronger guarantee is the system's own always-on switch; the app documents it. |
| `.mobileconfig` export + `NEDNSSettingsManager` | Private DNS guide | Android has encrypted DNS in Settings, so the app hands over the provider hostname and a deep link instead of generating a profile. |
| Safari content blocker | *(none)* | Android has no system-wide content-blocker API. Cosmetic in-page filtering would only work inside a browser that supports it, so the port leaves it out rather than ship a stub. |
| *(impossible on iOS)* | Per-app exclusions | `addDisallowedApplication` lets a chosen app skip the tunnel entirely. |
| Apple tracker-relay ruleset | Encrypted-DNS bypass ruleset | Same problem — traffic that never asks the system resolver — with a different cause. See below. |

## What is byte-compatible

These three formats are identical across the two apps, and the tests that
cover them are ports of each other:

- `blocklist.bin` / `allowlist.bin` / `denylist.bin` — `IBK1` header + sorted
  u64 FNV-1a hashes, memory-mapped and binary-searched by the packet path.
- `querylog.ring` — `IBLG` header + 64-byte records in a fixed ring.
- `stats.json` — cumulative and per-day counters.

A blob compiled by either app reads correctly in the other. That is deliberate:
one format means one set of edge cases, and the Kotlin suite exercises the same
vectors as the Swift one (0x20 case preservation, HTTPS/SVCB NODATA answers,
checksum round-trips, torn-record rejection, 50k-domain property test).

## Where the platforms genuinely differ

**The bypass hole is different.** iOS routes known-tracker connections through
Apple's relay, which resolves the tracker's hostname remotely — on-device DNS
never sees it. Android has no relay, but any app can ship its own DoH client
and skip the system resolver the same way. Both apps therefore ship a bundled
ruleset for the bypass path (`bundled-apple-relay` on iOS,
`bundled-dns-bypass` on Android), default on, with the same trade-off framing
in Settings.

Android adds one trick iOS cannot: Firefox's canary domain
(`use-application-dns.net`) is answered with **NXDOMAIN** rather than a
blackhole address, which is the specific answer that makes the browser hand
DNS back to the system.

**No "auto-suspend" mode.** On iOS, claiming the default route makes the OS
suspend Private Relay by itself, which is why the iOS build offers it as a
strategy. Android has no equivalent — claiming the default route there would
mean forwarding every packet in userspace (a full TCP/UDP proxy), which is a
different product. The port says so rather than pretending.

**Per-app control exists.** iOS never tells a tunnel which app sent a packet.
Android's `VpnService.Builder` takes an allow/deny list of packages, so the
port has an Excluded apps screen — useful for a banking app that refuses to
run under a VPN.

**Foreground service, not a system extension.** Android requires an ongoing
notification while the tunnel runs; the port makes it useful (blocked-today
count, Pause 5 min, Turn off).

## The packet path (identical logic, different edges)

```
Any app ──DNS query──► Android resolver ──► tun (198.18.0.2:53)
                                             │
                                    IBlockerVpnService
                                    ┌────────▼──────────────┐
                                    │ PacketParser (v4/v6)  │
                                    │ DnsParser (wire)      │
                                    │ DomainMatcher ◄── mmap│
                                    │   │        blocklist  │
                             blocked│   │allowed            │
                                    ▼   ▼                   │
                        DnsResponseBuilder   DoH/UDP upstream ──► internet
                        (0.0.0.0 / :: /      (protect()ed socket,   (encrypted)
                         NODATA / NXDOMAIN)   IP-literal endpoint)
                                    │   │                   │
                                    └───┴─► UdpReplyBuilder ─► tun ─► app
```

Key decisions carried over unchanged: split tunnel (no throughput cost),
HTTPS/SVCB answered with NODATA so type-65 queries cannot leak, 0x20 case
echoed byte-exactly, TC never set on synthesized answers, and the blocklist
memory-mapped so the filter's resident memory stays tiny.

## Testing

```bash
cd android
./gradlew :core:test              # 90 engine tests, no Android SDK needed
./gradlew :app:testDebugUnitTest  # app-level unit tests
./gradlew :app:assembleDebug      # unsigned-ish debug APK (debug key)
```

The service class itself is a thin shell — settings, read loop, control
actions — verified by the build and on-device, exactly like the iOS provider.
