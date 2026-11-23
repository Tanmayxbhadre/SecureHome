# Privacy Policy

Last updated: 22 November 2026

SecureHome moves one-time codes (OTPs) from your Android phone to other devices in your own household. It is
open source, it has no accounts, ads, analytics or trackers, and it doesn't sell or share data with anyone.

## Who runs what

- **The SecureHome app** runs on your phone. It's published by Tanmay Bhadre.
- **The relay** passes encrypted messages between your devices. It's a Cloudflare Worker that **you deploy
  and run yourself** (see the [README](README.md#run-your-own)). Whoever runs a relay controls the
  little data it holds. The app connects only to the relay you enter during setup.

## SMS access

SecureHome asks for permission to receive and read SMS so it can find one-time codes.

- Every message is checked **on your phone**. Messages that aren't codes never leave it.
- When a code is found, the app encrypts it on the phone, separately for each device you chose, and
  sends it through your relay. Only those devices can decrypt it.
- You can also share a code from the last two days by hand. The app reads your SMS inbox only for that
  list, and only on the phone.

## What stays on your phone

- Your device's private keys, its relay token, the household's device list, and codes from the last two
  days. These are encrypted with a key held in the Android Keystore and are excluded from backups.
- Uninstalling the app, or clearing its data, deletes all of it.

## What the relay can see

The relay never sees codes or SMS text; it only stores encrypted items. It can see:

- household and device IDs, and the device names you choose;
- when items were sent, how big they are, and which device sent to which;
- IP addresses, and request logs kept by Cloudflare as the hosting provider.

Encrypted items are deleted from the relay 15 minutes after they're sent. Pairing requests expire after
5 minutes. A device's records are deleted when it's removed from the household.

## The laptop page

The relay's web page keeps its keys and device details in your browser's storage (IndexedDB). It uses
no cookies and loads nothing from other sites. Unpairing, or clearing the site's data, removes them.

## Children

SecureHome isn't directed at children.

## A note on sharing OTPs

Banks ask you not to share OTPs. Use SecureHome only between your own devices and people you trust, and
check your bank's terms.

## Changes

Changes to this policy are published in this file, with the date above updated. The full history is in
the repository's commit log.

## Contact

Questions or requests: **tanmaybhadre6@gmail.com**. Security problems: see [`SECURITY.md`](SECURITY.md).
