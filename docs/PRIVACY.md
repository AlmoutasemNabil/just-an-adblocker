# Privacy Policy

**AdBlocker**

_Last updated: [DATE]_

## The short version

AdBlocker has no servers. There is no account to create, nothing to sign in to,
and no analytics, telemetry, crash reporting, or advertising SDK anywhere in the
app. We — the developer — never receive your browsing activity, your device
identifiers, or anything else about you, because there is no "we" for the data to
go to. Everything the app does happens on your iPhone or iPad.

The one thing worth reading below is the section on **your DNS resolver**, because
that is the only place your browsing activity leaves your device — and it goes to
a company you pick, not to us.

## What we collect

Nothing.

AdBlocker collects no personal data, no usage data, no diagnostics, and no
identifiers. It does not sell, share, rent, or transmit data to the developer or
to any third-party analytics or advertising service. There is no login, no email
capture, no subscription, and no in-app purchase.

## How the app works

AdBlocker runs a VPN on your device. It is a *local* VPN: it does not connect to
a remote VPN server, it does not tunnel your traffic anywhere, and it does not
route your web browsing through a third party. Its only purpose is to see the DNS
lookups your device makes — the moment an app or website asks "what is the
address for `example.com`?" — and answer the ones belonging to ad and tracking
networks with "nothing here."

Your actual traffic — the pages, messages, videos, and files you send and
receive — is never inspected, recorded, or intercepted. Only DNS queries pass
through the app.

## What stays on your device

**Query log.** So you can see what is being blocked, the app keeps a local log of
DNS lookups. Each entry holds the domain, a timestamp, the query type, whether it
was blocked or allowed, and which filter list matched.

- It is stored only in the app's private container on your device.
- It is a fixed-size ring: it holds a bounded number of recent entries and
  automatically overwrites the oldest ones. It cannot grow without limit.
- It is never uploaded, backed up to any service of ours, or shared.
- You can turn it off entirely in **Settings ▸ Query log**, and you can clear it
  at any time.

**Settings, filter lists, and statistics.** Your chosen filter lists, allow and
deny entries, blocking counts, and preferences are stored on your device in a
container shared between the app and its extensions.

**Deleting the app deletes all of it.** Removing AdBlocker removes the query log,
your lists, your settings, and your statistics. There is no copy anywhere else.

## What leaves your device

Three things, and nothing else.

### 1. DNS queries that are not blocked — to the resolver you choose

When a lookup is not on a blocklist, the app has to ask a real DNS resolver for
the answer. That resolver sees the domain being looked up and the IP address it
came from. **This is true of all internet use, with or without this app** — but
it is the one point where your activity reaches a third party, so it deserves to
be stated plainly.

You choose the resolver in **Settings ▸ DNS**. Options include Cloudflare,
AdGuard DNS, Quad9, Mullvad, NextDNS, or any custom resolver you enter. By
default the app uses DNS-over-HTTPS, which encrypts these lookups so your network
operator and internet provider cannot read them.

**Each of these providers has its own privacy policy and its own data retention
practices, and we have no control over and no visibility into what they do with
your queries.** Please read the policy of whichever resolver you select. If you
prefer, you can point the app at a resolver you run yourself.

### 2. Filter list downloads

To keep blocking effective, the app periodically downloads blocklists over HTTPS
from their publishers — for example `oisd.nl`, `easylist.to`,
`raw.githubusercontent.com`, and `adguardteam.github.io`, plus any list URL you
add yourself.

These are plain file downloads. No account, identifier, or information about you
is attached. As with any web request, the host serving the file can see the IP
address that requested it and the time of the request. We receive nothing from
these downloads.

### 3. Configuration profiles you export

The app can generate a `.mobileconfig` configuration profile that sets encrypted
DNS system-wide. The profile is created entirely on your device and is only
installed if you choose to install it. Nothing is transmitted in creating it.

## Safari content blocking

The app includes a Safari content blocker extension. Apple's design keeps this
extension isolated: it hands Safari a list of rules, and Safari applies them.
The extension cannot see the pages you visit, and it reports nothing back to the
app or to us.

## Blocking test

The in-app blocking test resolves a short, fixed list of known ad and tracking
domains to show you whether blocking is working. It performs DNS lookups only, no
data is sent to us, and results are displayed on screen and not stored.

## Permissions the app requests

- **VPN configuration.** Required for on-device DNS filtering, as described
  above. It creates a local tunnel, not a connection to a remote server.
- **Background refresh.** Used to update filter lists on a schedule.
- **Notifications** (if you enable them). Used only for local status messages
  from the app itself.

## Children

AdBlocker collects no data from anyone, including children. It has no accounts,
no user-generated content, no messaging, and no advertising.

## Your rights

Data protection laws such as the GDPR and CCPA give you rights to access,
correct, export, and delete personal data a company holds about you. We hold no
personal data about you, so there is nothing for us to retrieve, correct, or
delete. Your on-device data is under your control at all times: clear the query
log in Settings, or delete the app to remove everything.

## Changes to this policy

If the app's data behavior ever changes, this policy will be updated and the
"Last updated" date above revised. Material changes will be noted in the app's
release notes.

## Contact

Questions about this policy: [YOUR CONTACT EMAIL]
