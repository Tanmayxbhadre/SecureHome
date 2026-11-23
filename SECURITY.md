# Security

SecureHome carries one-time passwords, so security reports matter more than most bugs.

## Reporting a vulnerability

Please **don't open a public issue**. Contact Tanmay Bhadre directly at **tanmaybhadre6@gmail.com** or use GitHub's private reporting on this repository: go to **Security → Report a vulnerability**. Include what an attacker could do, the steps to reproduce, and the version or commit you tested.

You'll get an acknowledgement within a few days. Once a fix is ready we'll publish an advisory and credit
you, unless you'd rather stay anonymous.

## Scope

In scope:

- Reading or forging codes without a household device's keys (crypto, protocol, roster verification).
- Using a relay without its founding secret or a device token, beyond the documented open routes.
- Pairing a device the household didn't confirm, or getting past the 6-digit confirmation code.
- Leaking codes, SMS text, keys or tokens from the app, the laptop page or the relay.
- Script injection on the laptop page (SMS text is attacker-controlled).

Out of scope: what a relay operator can already see (listed in the README's security model), denial of
service against your own relay, attacks that need a rooted or already-compromised phone or laptop, and
the risks of sideloading an APK you built yourself.

## Design notes

The threat model and what the relay can and can't see are in the [README](README.md#security-model).
The exact crypto is in [`PROTOCOL.md`](PROTOCOL.md).
