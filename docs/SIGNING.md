# Signing setup

## TL;DR

Edit `Config/Signing.xcconfig`:

```
DEVELOPMENT_TEAM = ABCDE12345      // Team ID from developer.apple.com → Membership
BUNDLE_ID_PREFIX = com.yourname
```

Open `IBlocker.xcodeproj`, select your device, Run. Xcode's automatic
signing registers everything (App IDs, App Group, VPN capability) on first
build.

## What gets derived

| Thing | Value |
|---|---|
| App bundle ID | `$(BUNDLE_ID_PREFIX).iblocker` |
| Tunnel extension | `$(BUNDLE_ID_PREFIX).iblocker.tunnel` |
| Safari blocker | `$(BUNDLE_ID_PREFIX).iblocker.blocker` |
| App Group | `group.$(BUNDLE_ID_PREFIX).iblocker` |

Extension IDs are prefixed by the app ID (an Apple requirement for embedded
extensions), and the App Group is stamped into each target's Info.plist as
`AppGroupID`, which is how the code discovers it at runtime — nothing is
hardcoded in Swift.

## Why a paid account is required

The `com.apple.developer.networking.networkextension` entitlement
(`packet-tunnel-provider`) is only issued to paid Apple Developer accounts.
A free "Personal Team" build will fail signing for the app and tunnel
targets. With a free account you can still build a variant without the VPN
(profile export + Safari blocker only), but the system-wide blocking — the
point of this app — needs the paid account.

## Common issues

- **"Cannot create a … provisioning profile" / missing entitlement**: your
  account hasn't finished processing the capability. Xcode ▸ Signing &
  Capabilities for each target → check the errors; sometimes toggling
  "Automatically manage signing" off/on forces a profile refresh.
- **App Group mismatch**: if you change `BUNDLE_ID_PREFIX` after a first
  install, delete the app from the device (the old VPN configuration
  references the old bundle ID) and reinstall.
- **On iOS betas**: after the very first install, reboot the device once if
  the VPN toggle doesn't stick — a long-standing NetworkExtension beta quirk.
- **7-day expiry** doesn't apply here: paid-account development builds last
  a year. Rebuild/redeploy whenever you update the app.
