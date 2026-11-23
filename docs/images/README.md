# Screenshots

All names, messages, and one-time codes in these images are fictional demo data. No real SMS messages,
credentials, household IDs, or device IDs appear in the captures.

- `android-home.png` — the Android debug app on a 1080 × 2340 emulator, after sharing three synthetic
  messages to Home phone and Work laptop.
- `laptop.png` — the paired browser at a 1440 × 1000 CSS-pixel viewport, receiving the same encrypted
  items from the phone.
- `securehome-hero.webp` — a framed composition of the captures for the project README. The UI captures
  themselves are unchanged apart from resizing and clipping to the device frames.

Demo messages:

| Sender | Code | Type |
| --- | --- | --- |
| HDFCBK | 482913 | OTP |
| SWIGGY | 6174 | Delivery |
| UBERIN | 5832 | Ride |

The messages were injected using the debug-only `com.tanmay.securehome.DEBUG_SMS` receiver and passed through
the app's real OTP detection, encryption, and relay flow. The browser and emulator were paired to an
isolated local Wrangler relay; no production relay or personal device was used.
