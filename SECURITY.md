# Security Policy

This app runs a VPN and sees every DNS lookup on the device. Security reports
are taken seriously.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem.

Use GitHub's private vulnerability reporting: the **Security** tab of this
repository → **Report a vulnerability**. It's private between you and the
maintainer, and it works even if you don't have a way to reach me otherwise.

Please include what you'd need if you received the report: what the flaw is,
how to reproduce it, which platform and version, and what an attacker gets out
of it. A proof of concept helps.

Expect a first response within about a week. This is a personally maintained
project, not a company with an on-call rotation — if something is actively
being exploited, say so prominently and I'll prioritise it.

## Scope

In scope — anything that breaks the guarantees the README makes:

- DNS queries, the query log, or any user data leaving the device.
- A way to make the tunnel forward traffic somewhere it shouldn't.
- Blocklist compilation or the memory-mapped matcher mishandling hostile
  input (a malicious filter list causing a crash, overread, or rule bypass).
- DNS response parsing bugs reachable from a hostile upstream resolver.
- Anything letting another app on the device read the App Group / app-private
  container.
- A way to silently disable protection while the UI still reports "protected".

Out of scope:

- **Ads that get through.** DNS filtering has documented limits — YouTube
  in-stream ads, apps with hardcoded DoH, per-app rules on iOS. Those are
  bugs or feature requests, not vulnerabilities. Open a normal issue.
- Your chosen upstream resolver seeing your unblocked queries. That's
  documented behaviour, and picking the resolver is the point.
- Findings that require an already-rooted or jailbroken device, or physical
  access with the device unlocked.
- Vulnerabilities in the third-party blocklists themselves — report those to
  their publishers.

## Supported versions

The latest release and `main`. This project has no long-term support
branches; fixes land on `main` and go out in the next release.

## Verifying what you run

Every outbound connection the apps can make is listed in the README, and the
commands to verify that list are there too. If you find a network call that
isn't in that table, that is a security report, and a serious one.

Release APKs are signed; check that an APK's signing certificate matches
previous releases before installing an update from anywhere other than this
repository's Releases page.
