# SecureHome protocol (v1)

The wire format shared by the relay, the laptop page and the Android app. The reference
implementation is `relay/public/js/crypto.js`; the Android app must produce byte-identical
results. Overview, threat model and design choices: [`README.md`](README.md).

## Encoding

- Binary values are **base64url without padding**.
- IDs (household, device, pairing) are 16 random bytes → 22 chars.
- Access tokens are 32 random bytes → 43 chars. The relay stores only `sha256hex(utf8(token))`.

## Keys

Each device has two P-256 key pairs:

| Pair | Algorithm | Used for |
| --- | --- | --- |
| `enc` | ECDH P-256 | receiving envelopes |
| `sig` | ECDSA P-256 / SHA-256 | signing envelopes and roster entries |

Public keys are exported as the 65-byte uncompressed point (`04 || X || Y`) → 87 chars.
Signatures are IEEE P1363 (`r || s`, 64 bytes). **Android's `Signature("SHA256withECDSA")` emits
DER; convert to and from P1363.** ECDH output is the 32-byte X coordinate (same in WebCrypto and
Java `KeyAgreement("ECDH")`).

## Envelope

Encrypts a JSON payload to one recipient and signs it.

```
eph        = new ECDH P-256 key pair
shared     = ECDH(eph.private, recipient.enc.public)            // 32 bytes
key        = HKDF-SHA256(ikm = shared, salt = epk, info = INFO, L = 32)
iv         = 12 random bytes
ct         = AES-256-GCM(key, iv, aad = utf8(AAD), utf8(JSON(payload)))   // tag appended
sig        = ECDSA-SHA256(sender.sig.private, utf8(AAD) || epk || iv || ct)
envelope   = { "v": 1, "epk": b64u(epk), "iv": b64u(iv), "ct": b64u(ct), "sig": b64u(sig) }
```

Receivers verify `sig` with the sender's `sigPub` **before** trusting the content.

| Use | INFO | AAD |
| --- | --- | --- |
| Shared item | `securehome/item/v1` | `securehome/item/v1\|<hid>\|<fromDeviceId>\|<toDeviceId>` |
| Pairing reply | `securehome/pair/v1` | `securehome/pair/v1\|<pairingId>` |

**Confirmation code (SAS):** `HKDF-SHA256(ikm = shared, salt = empty, info = "securehome/sas/v1", L = 4)`,
read as a big-endian uint32, `mod 1_000_000`, zero-padded to 6 digits, shown as `123 456`.

## Roster entries

```
body  = { "v": 1, "id", "name", "kind": "phone"|"laptop", "encPub", "sigPub", "addedBy", "addedAt" }
entry = { "body": b64u(utf8(JSON(body))), "sig": b64u(ECDSA(addedBy.sig.private, utf8("securehome/entry/v1|") || bodyBytes)) }
```

The founder phone signs its own entry (`addedBy == id`). Every other entry is signed by the phone
that paired it. A device trusts an entry only if it chains back to `founderSigPub` through phones
(`verifyRoster`). The relay can't add devices that others will trust.

## Pairing

1. Joining device shows QR text `securehome:1:<pairingId>:<encPub>:<sigPub>:<kind>` (valid 5 minutes).
2. A paired phone scans it, makes `deviceId`, `token` and a signed entry, and `PUT /api/pair/<pairingId>`
   with `{ "hid": <its household>, "env": envelope }` sealed to the QR's `encPub`, authenticated with its own
   token (only phones already in a household can write pairing slots). The payload (the "bundle"):
   `{ v, hid, deviceId, token, name, kind, founderSigPub, pairedBy, roster: [entries…, newEntry], householdKey }`.
3. Joining device polls `GET /api/pair/<pairingId>` (200 = bundle, 204 = not yet; one-time read),
   decrypts, verifies the roster and the pairer's signature, and shows the SAS.
4. The user compares the SAS on both screens and confirms **on the phone**, which then
   `POST /api/h/<hid>/devices` `{ entry, tokenHash }`.
5. Joining device polls `GET /api/h/<hid>/me` with its token until 200.

## Item payload (v1)

```json
{ "v": 1, "kind": "otp" | "delivery" | "ride", "code": "845210", "sender": "HDFCBK",
  "smsFrom": "VM-HDFCBK", "body": "<full SMS>", "receivedAt": 1795000000000,
  "sharedAt": 1795000000500, "how": "rule" | "manual" }
```

Receivers render `body` as plain text only (SMS content is attacker-controlled).
OTP detection rules: `relay/scripts/lib/otp.mjs`, test cases in `fixtures/otp-sms.json`.

## Relay API

All household calls except `init` and `ws` need `Authorization: Bearer <token>`, and so does writing a
pairing slot. Founding a household needs the relay owner's `FOUNDING_SECRET` (a Worker secret) as
`foundingCode`; a relay without one creates no households. Only the joining device's `GET /api/pair/<pid>`
and the static page are open to anyone.

| Method and path | Who | Body / result |
| --- | --- | --- |
| `POST /api/h/<hid>/init` | founder phone | `{ entry, tokenHash, foundingCode }` → 201; 403 wrong/missing code; 409 if the household exists; 503 if the relay has no `FOUNDING_SECRET` |
| `GET /api/h/<hid>/me` | any device | `{ id, kind }` |
| `GET /api/h/<hid>/roster` | any device | `{ entries }` |
| `POST /api/h/<hid>/devices` | phones | `{ entry, tokenHash }` → 201 |
| `DELETE /api/h/<hid>/devices/<id>` | phones, or a device removing itself | 204 |
| `POST /api/h/<hid>/items` | any device | `{ to, env }` → `{ id, storedAt }` |
| `GET /api/h/<hid>/items` | any device | `[ { id, env, storedAt } ]` |
| `GET /api/h/<hid>/ws` | any device | WebSocket upgrade (`Authorization` in Sec-WebSocket-Protocol) |
