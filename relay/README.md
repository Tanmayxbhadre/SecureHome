# SecureHome relay

Cloudflare Worker that relays encrypted OTPs between paired devices, and serves the laptop page.
Protocol: [`../PROTOCOL.md`](../PROTOCOL.md).

| Path | What |
| --- | --- |
| `src/index.ts` | Routes `/api/*`; everything else is the static laptop page |
| `src/household.ts` | Durable Object per household: roster, per-device inboxes, 15-minute expiry, WebSocket push |
| `src/pairing.ts` | Durable Object per pairing attempt: one-time slot, 5-minute expiry |
| `public/` | Laptop page (`index.html`, `app.js`, `style.css`) and the shared crypto (`js/crypto.js`) |
| `scripts/lib/device.mjs` | A simulated phone or laptop in Node |
| `scripts/lib/otp.mjs` | Reference OTP detector (cases in `../fixtures/otp-sms.json`) |

## Run locally

Requires Node.js 22.12 or newer. From this directory:

```sh
npm ci
printf 'FOUNDING_SECRET=local-dev-founding-code\n' > .dev.vars   # once; .dev.vars is git-ignored
npm run dev                       # relay + laptop page on http://localhost:8787
```

Try it by hand with the command-line phone (stand-in for the Android app):

```sh
npm run phone -- init --name "My phone"        # uses local-dev-founding-code for a local relay
# open http://localhost:8787, expand "Can't scan? Show the pairing text", then:
npm run phone -- pair "securehome:1:…" --name "Work laptop"
npm run phone -- send "Your Swiggy order is out for delivery. Share OTP 4821 with the delivery partner." --from VM-SWIGGY
npm run phone -- remove "Work laptop"
```

## Test

```sh
npm run typecheck
npm test                          # OTP detector
npm run e2e                       # starts its own relay on :8799 and runs the full flow
```

## Deploy

```sh
npx wrangler login
npm run deploy
npx wrangler secret put FOUNDING_SECRET   # paste a long random code; you'll type it into the phone once
```

**Who can use the relay.** The page is public, but it holds no secrets. Creating a household needs
`FOUNDING_SECRET`, and a relay without one refuses all new households. Answering a pairing code needs
the token of a phone already in a household. Everything else needs a device token. The open routes
are rate-limited per IP (Cloudflare's rate limiting binding, see `wrangler.jsonc`). The laptop page is
served with a strict Content-Security-Policy and no-framing, no-referrer headers (`public/_headers`). The relay
only ever sees encrypted items (see `../PROTOCOL.md`). Changing the secret later doesn't affect
households that already exist.

The page then lives at `https://securehome.<your-subdomain>.workers.dev`. Some company networks
block `workers.dev`; a custom domain avoids that.
