// End-to-end test against a local relay (wrangler dev). Usage: npm run e2e
// Starts its own relay on port 8799 with a 4-second item lifetime so expiry can be checked.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import * as C from "../public/js/crypto.js";
import { Device, otpPayload } from "./lib/device.mjs";
import { detectOtp } from "./lib/otp.mjs";

const PORT = 8799;
const RELAY = process.env.RELAY ?? `http://127.0.0.1:${PORT}`;
const TTL_MS = 4000;
const FOUNDING = process.env.FOUNDING_SECRET ?? "e2e-founding-code";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let dev;
async function startRelay() {
  if (process.env.RELAY) return;
  // Run wrangler directly in its own process group, so cleanup can stop it and the runtime it starts.
  const wrangler = new URL("../node_modules/.bin/wrangler", import.meta.url).pathname;
  dev = spawn(wrangler, ["dev", "--port", String(PORT), "--ip", "127.0.0.1", "--var", `ITEM_TTL_MS:${TTL_MS}`, "--var", `FOUNDING_SECRET:${FOUNDING}`], {
    cwd: new URL("..", import.meta.url).pathname,
    stdio: ["ignore", "pipe", "pipe"],
    detached: true,
  });
  let log = "";
  dev.stdout.on("data", (d) => (log += d));
  dev.stderr.on("data", (d) => (log += d));
  for (let i = 0; i < 120; i++) {
    try {
      const r = await fetch(`${RELAY}/api/pair/AAAAAAAAAAAAAAAAAAAAAA`);
      if (r.status === 204) return;
    } catch {}
    await sleep(500);
  }
  throw new Error(`relay did not start:\n${log}`);
}

let passed = 0;
async function step(name, fn) {
  await fn();
  passed++;
  console.log(`  ok  ${name}`);
}

/** Pair `joiner` into the household through `phone`, checking both sides see the same code. */
async function pair(phone, joiner, name) {
  const { pid, qr } = await joiner.startJoin();
  const waiting = joiner.waitForBundle(pid);
  const scanned = await phone.scan(qr, name);
  const { sas, bundle } = await waiting;
  assert.equal(sas, scanned.sas, "confirmation codes match");
  const confirmed = joiner.waitForConfirm(bundle);
  await scanned.confirm();
  await confirmed;
  return scanned;
}

async function main() {
  await startRelay();
  console.log(`relay: ${RELAY}`);

  const mine = await Device.create({ relay: RELAY, name: "My phone", kind: "phone" });
  const laptop = await Device.create({ relay: RELAY, name: "Work laptop", kind: "laptop" });
  const partner = await Device.create({ relay: RELAY, name: "Partner's phone", kind: "phone" });

  await step("a household can't be founded without the founding code", async () => {
    const stranger = await Device.create({ relay: RELAY, name: "Stranger", kind: "phone" });
    await assert.rejects(() => stranger.init(undefined), /init failed: 403/);
    await assert.rejects(() => stranger.init("guess"), /init failed: 403/);
  });

  await step("founder phone creates a household with the founding code", async () => {
    await mine.init(FOUNDING);
    const again = await mine.api("POST", mine.hpath("init"), { entry: {}, tokenHash: "x", foundingCode: FOUNDING }, { auth: false });
    assert.equal(again.status, 400);
  });

  await step("household can't be founded twice", async () => {
    const other = await Device.create({ relay: RELAY, name: "Intruder", kind: "phone" });
    const id = C.randomId();
    const sigPub = await C.exportPub(other.keys.sig.publicKey);
    const entry = await C.signEntry(
      { v: 1, id, name: "Intruder", kind: "phone", encPub: await C.exportPub(other.keys.enc.publicKey), sigPub, addedBy: id, addedAt: Date.now() },
      other.keys.sig.privateKey,
    );
    const r = await other.api("POST", `/api/h/${mine.hid}/init`, { entry, tokenHash: await C.sha256Hex(C.randomToken()), foundingCode: FOUNDING }, { auth: false });
    assert.equal(r.status, 409);
  });

  await step("laptop pairs by QR; both sides show the same code", async () => {
    await pair(mine, laptop, "Work laptop");
    assert.equal(laptop.hid, mine.hid);
  });

  await step("partner's phone pairs by QR", async () => {
    await pair(mine, partner, "Partner's phone");
  });

  await step("every device verifies the same signed roster", async () => {
    for (const d of [mine, laptop, partner]) {
      const names = [...(await d.roster()).values()].map((b) => b.name).sort();
      assert.deepEqual(names, ["My phone", "Partner's phone", "Work laptop"]);
    }
  });

  await step("only a household's phone can answer a pairing code", async () => {
    const { pid } = await laptop.startJoin();
    const body = { hid: mine.hid, env: { v: 1 } };
    assert.equal((await laptop.api("PUT", `/api/pair/${pid}`, body, { auth: false })).status, 401);
    assert.equal((await laptop.api("PUT", `/api/pair/${pid}`, body)).status, 403); // laptops can't pair
    const forged = { ...body, hid: C.randomId() }; // a real token, but for another household
    assert.equal((await mine.api("PUT", `/api/pair/${pid}`, forged)).status, 401);
    assert.equal((await laptop.api("GET", `/api/pair/${pid}`, undefined, { auth: false })).status, 204);
  });

  await step("pairing slot is one-time", async () => {
    const { pid } = await laptop.startJoin();
    const a = await mine.api("PUT", `/api/pair/${pid}`, { hid: mine.hid, env: { v: 1 } });
    const b = await mine.api("PUT", `/api/pair/${pid}`, { hid: mine.hid, env: { v: 1 } });
    assert.equal(a.status, 201);
    assert.equal(b.status, 409);
    assert.equal((await laptop.api("GET", `/api/pair/${pid}`, undefined, { auth: false })).status, 200);
    assert.equal((await laptop.api("GET", `/api/pair/${pid}`, undefined, { auth: false })).status, 204);
  });

  // Laptop watches live.
  const live = [];
  let closeCode = null;
  let ready;
  const readyP = new Promise((r) => (ready = r));
  const ws = laptop.connect((ev) => {
    if (ev.type === "ready") ready();
    if (ev.type === "item") live.push(ev.item);
    if (ev.type === "close") closeCode = ev.code;
  });
  await readyP;

  await step("bank OTP: my phone → laptop arrives live and decrypts", async () => {
    const sms = { from: "VM-HDFCBK", body: "OTP for txn of Rs 2,499.00 at AMAZON on HDFC Bank card ending 4321 is 845210. Valid till 10:42." };
    const detected = detectOtp(sms.from, sms.body);
    await mine.send(await mine.findDevice("Work laptop"), otpPayload({ detected, smsFrom: sms.from, body: sms.body }));
    for (let i = 0; i < 40 && live.length === 0; i++) await sleep(50);
    assert.equal(live.length, 1);
    assert.equal(live[0].payload.code, "845210");
    assert.equal(live[0].payload.kind, "otp");
    assert.equal(live[0].from, "My phone");
  });

  await step("delivery OTP: my phone → partner's phone (Get OTP pull)", async () => {
    const sms = { from: "VM-SWIGGY", body: "Your Swiggy order is out for delivery. Share OTP 4821 with the delivery partner." };
    const detected = detectOtp(sms.from, sms.body);
    await mine.send(await mine.findDevice("Partner's phone"), otpPayload({ detected, smsFrom: sms.from, body: sms.body }));
    const got = await partner.inbox(Date.now() - 5 * 60_000);
    assert.equal(got.length, 1);
    assert.equal(got[0].payload.code, "4821");
    assert.equal(got[0].payload.kind, "delivery");
  });

  await step("inboxes are private: laptop doesn't see partner's item", async () => {
    const got = await laptop.inbox(0);
    assert.deepEqual(got.map((i) => i.payload.code), ["845210"]);
  });

  await step("two-way: partner's phone → laptop, trusted via my phone's signature", async () => {
    const sms = { from: "VK-UBERIN", body: "Your Uber ride PIN is 5832. Share it with your driver to start the trip." };
    const detected = detectOtp(sms.from, sms.body);
    await partner.send(await partner.findDevice("Work laptop"), otpPayload({ detected, smsFrom: sms.from, body: sms.body, how: "manual" }));
    for (let i = 0; i < 40 && live.length < 2; i++) await sleep(50);
    assert.equal(live[1].payload.code, "5832");
    assert.equal(live[1].from, "Partner's phone");
  });

  await step("tampered item is rejected", async () => {
    const r = await laptop.api("GET", laptop.hpath("items"));
    const item = structuredClone(r.data.items[0]);
    const ct = item.env.ct;
    item.env.ct = (ct[0] === "A" ? "B" : "A") + ct.slice(1);
    const roster = await laptop.roster();
    await assert.rejects(() => laptop.openItem(item, roster), /bad signature/);
  });

  await step("bad and missing tokens are refused", async () => {
    const r1 = await fetch(`${RELAY}${mine.hpath("items")}`);
    assert.equal(r1.status, 401);
    const r2 = await fetch(`${RELAY}${mine.hpath("items")}`, { headers: { authorization: "Bearer AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" } });
    assert.equal(r2.status, 401);
  });

  await step("a laptop can't pair new devices", async () => {
    const r = await laptop.api("POST", laptop.hpath("devices"), { entry: {}, tokenHash: "0".repeat(64) });
    assert.equal(r.status, 403);
  });

  await step(`items expire after ${TTL_MS / 1000}s`, async () => {
    await sleep(TTL_MS + 1500);
    assert.equal((await laptop.inbox(0)).length, 0);
    assert.equal((await partner.inbox(0)).length, 0);
  });

  await step("removing the laptop revokes it immediately and closes its socket", async () => {
    const r = await mine.remove(laptop.id);
    assert.equal(r.status, 204);
    for (let i = 0; i < 40 && closeCode === null; i++) await sleep(50);
    assert.equal(closeCode, 4001);
    assert.equal((await laptop.api("GET", laptop.hpath("me"))).status, 401);
    const names = [...(await partner.roster()).values()].map((b) => b.name).sort();
    assert.deepEqual(names, ["My phone", "Partner's phone"]);
  });

  await step("partner's phone can unpair itself", async () => {
    assert.equal((await partner.remove(partner.id)).status, 204);
    assert.equal((await partner.api("GET", partner.hpath("me"))).status, 401);
  });

  await step("flooding the pairing routes gets rate-limited (429)", async () => {
    const pid = C.randomId();
    // Its own client address (TEST-NET-3), so the flood doesn't throttle anything else hitting this relay.
    // Cloudflare sets this header itself in production; locally it passes through.
    const headers = { "cf-connecting-ip": "203.0.113.9" };
    const codes = await Promise.all(Array.from({ length: 150 }, () => fetch(`${RELAY}/api/pair/${pid}`, { headers }).then((r) => r.status)));
    assert.ok(codes.includes(429), `expected some 429s, got ${[...new Set(codes)]}`);
    assert.ok(codes.includes(204));
  });

  ws.close();
  console.log(`\n${passed} checks passed`);
}

main()
  .catch((e) => {
    console.error("\nFAILED:", e);
    process.exitCode = 1;
  })
  .finally(() => {
    // Stop the whole group (wrangler and its workerd children), then exit: open pipes or sockets
    // left behind would otherwise keep Node alive and hang CI.
    if (dev?.pid) {
      try {
        process.kill(-dev.pid, "SIGTERM");
      } catch {}
    }
    setTimeout(() => process.exit(process.exitCode ?? 0), 500);
  });
