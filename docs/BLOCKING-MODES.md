# Blocking modes and how they interact

IBlocker gives you three independent ways to block ads, plus a Safari
cosmetic layer. They can coexist; iOS picks the resolver by fixed precedence.

## 1. VPN mode (the main one)

On-device packet tunnel. Blocking decisions are made locally against your
compiled filter lists; non-blocked queries go to the upstream you chose
(encrypted DoH by default).

A built-in "Core mobile ad networks" ruleset ships inside the app binary and
is always compiled in (toggleable in Lists). It guarantees the floor — Google
in-app ads (AdMob) and the major mobile ad SDKs are blocked even before the
first list download, offline, or during a list-server outage.

- ✅ Works in every app, blocklist is yours, query log, allow/deny
- ✅ Nothing is routed through anyone's server (only DNS goes to the upstream)
- ⚠️ Occupies the device's one active VPN slot — can't run another VPN at
  the same time

## 2. DNS profile (.mobileconfig)

Settings ▸ Export a profile pointing system DNS at a filtering provider
(AdGuard DNS, Mullvad ad-blocking, NextDNS with your config ID…). The
*provider* does the blocking, not your lists.

- ✅ No VPN slot used; survives anything; zero battery cost
- ✅ Works even if you later stop sideloading the app
- ⚠️ Blocking policy is the provider's (NextDNS gives you control)
- Install flow: generate → share to yourself → open → Settings ▸ General ▸
  VPN & Device Management ▸ Profile Downloaded ▸ Install

## 3. System encrypted DNS (NEDNSSettingsManager)

Same effect as the profile, but installed programmatically from the app —
no file shuffling. Activate it once in Settings ▸ General ▸ VPN & Device
Management ▸ DNS.

## Precedence (what actually resolves your DNS)

```
active VPN tunnel DNS  >  DNS profile / NEDNSSettings  >  network's DHCP DNS
```

So: while IBlocker's VPN is connected, its filtering wins. The moment the
VPN is off (you toggled it, or iOS killed it), an installed profile takes
over automatically. Running mode 1 + mode 2 together is the recommended
belt-and-suspenders setup — there is never a moment with unfiltered DNS.

## Safari content blocker (cosmetic layer)

DNS blocking stops requests, but pages sometimes keep empty ad frames or
"ad blocker detected" leftovers. The Safari extension applies EasyList's
element-hiding rules (the supported subset) inside Safari. Enable it in
Settings ▸ Apps ▸ Safari ▸ Extensions, refresh its rules from IBlocker's
Settings tab.

## The Apple tracker-relay leak (and why we block it)

iOS "Limit IP Address Tracking" (ON by default per network) routes
connections to known trackers through **Apple's relay** (`mask.icloud.com`,
`apple-relay.fastly-edge.com`, …). The tracker's hostname is resolved
*remotely by the relay*, so those connections never touch on-device DNS —
in-app ads load while the ad domains sit "Blocked" in the query log.

Three supported configurations — pick in Settings ▸ Apple Private Relay:

**Auto-suspend (the AdGuard way):** the tunnel claims the default route
(while excluding all real traffic via more-specific routes, so nothing
actually flows through it). iOS treats it as a full VPN and pauses Private
Relay and tracker relaying on its own while protection runs; the relay
returns the moment protection stops. No Apple domains blocked, no Settings
changes needed. This is what commercial blockers ride on — their users'
relays were always silently off while "protected".

**Max blocking (default):** "Block Apple tracker relay" ON. The relay
endpoints are blocked, iOS falls back to direct connections, everything
goes through the filter. Trade-off: paid iCloud+ Private Relay reports
"unavailable".

**Keep Private Relay:** toggle OFF, then turn OFF "Limit IP Address
Tracking" in Settings ▸ Wi-Fi ▸ ⓘ (each network) and Settings ▸ Cellular ▸
Cellular Data Options. Result: Safari browsing keeps Apple's IP masking
(Private Relay carries Safari, not in-app ads), apps' tracker traffic goes
direct and gets filtered, and Safari ads stay blocked by the content
blocker, which works inside WebKit regardless of the network path. The only
delta vs. max blocking: trackers *not* on any of your lists connect
directly with your real IP instead of a masked one — enabling HaGeZi Pro
narrows that set.

The Blocking Test knows about both modes: with the relay allowed, the relay
probes turn informational and remind you about the Limit IP Address
Tracking switches instead of failing the verdict.

## What none of these can do

- Apps with **hardcoded DoH** (some browsers) can skip system DNS entirely.
  The default lists block major DoH endpoints, which pushes those apps back
  to system DNS, but it's an arms race.
- **YouTube/Facebook in-app ads** are served from the same domains as
  content — DNS-level blocking cannot separate them. (Nothing on iOS can,
  short of MITM proxies with their own problems.)
- Traffic pinned to IP addresses without DNS lookups.
