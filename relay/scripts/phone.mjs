#!/usr/bin/env node
// A command-line stand-in for the Android app, for trying the relay and laptop page by hand.
//
//   npm run phone -- init --name "My phone"            found a household (needs --founding-code or $FOUNDING_SECRET)
//   npm run phone -- pair "securehome:1:..." --name "Laptop"  pair a device from its QR / pairing text
//   npm run phone -- send "<sms text>" --from VM-HDFCBK [--to "Laptop"]
//   npm run phone -- roster | inbox | watch
//   npm run phone -- remove "Laptop"
//   npm run phone -- join --name "Partner's phone" --state .phone2.json   join as a second phone
//
// Options: --relay URL (default http://127.0.0.1:8787), --state FILE (default .phone.json), --yes,
//          --founding-code CODE (default $FOUNDING_SECRET, or local-dev-founding-code for a local relay)
import { readFile, writeFile } from "node:fs/promises";
import { createInterface } from "node:readline/promises";
import { parseArgs } from "node:util";
import { Device, otpPayload } from "./lib/device.mjs";
import { detectOtp } from "./lib/otp.mjs";

const { values: opt, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    relay: { type: "string", default: process.env.RELAY ?? "http://127.0.0.1:8787" },
    state: { type: "string", default: ".phone.json" },
    name: { type: "string" },
    from: { type: "string", default: "VM-HDFCBK" },
    to: { type: "string" },
    yes: { type: "boolean", default: false },
    "founding-code": { type: "string" },
  },
});
const [cmd, arg] = positionals;

async function load() {
  try {
    return await Device.fromJSON(JSON.parse(await readFile(opt.state, "utf8")));
  } catch {
    throw new Error(`no phone state in ${opt.state}; run "init" or "join" first`);
  }
}

async function save(d) {
  await writeFile(opt.state, JSON.stringify(await d.toJSON(), null, 2), { mode: 0o600 });
}

async function ask(q) {
  if (opt.yes) return true;
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const a = await rl.question(q);
  rl.close();
  return /^y(es)?$/i.test(a.trim());
}

function printItem(it) {
  const p = it.payload;
  console.log(`${p.code.padEnd(8)} ${p.kind.padEnd(8)} ${String(p.sender).padEnd(10)} from ${it.from}  (${new Date(p.receivedAt).toLocaleTimeString()})`);
}

const commands = {
  async init() {
    const d = await Device.create({ relay: opt.relay, name: opt.name ?? "My phone", kind: "phone" });
    const local = /^http:\/\/(127\.0\.0\.1|localhost)[:/]/.test(opt.relay);
    const code = opt["founding-code"] ?? process.env.FOUNDING_SECRET ?? (local ? "local-dev-founding-code" : undefined);
    if (!code) throw new Error("this relay needs a founding code: pass --founding-code or set FOUNDING_SECRET");
    await d.init(code);
    await save(d);
    console.log(`Household created. This phone is "${d.name}". State saved to ${opt.state}.`);
  },

  async join() {
    const d = await Device.create({ relay: opt.relay, name: opt.name ?? "Phone", kind: "phone" });
    const { pid, qr } = await d.startJoin();
    console.log(`Scan or paste this on a paired phone:\n\n  ${qr}\n\nWaiting…`);
    const { sas, bundle } = await d.waitForBundle(pid, { timeoutMs: 5 * 60_000 });
    console.log(`\nConfirmation code: ${sas}\nConfirm on the other phone.`);
    await d.waitForConfirm(bundle, { timeoutMs: 3 * 60_000 });
    await save(d);
    console.log(`Joined as "${d.name}".`);
  },

  async pair() {
    if (!arg) throw new Error('usage: pair "<pairing text>" --name "Work laptop"');
    const d = await load();
    const name = opt.name ?? "Work laptop";
    const { sas, confirm } = await d.scan(arg, name);
    console.log(`Confirmation code: ${sas}`);
    if (!(await ask(`Does "${name}" show the same code? [y/N] `))) return console.log("Cancelled. Nothing was registered.");
    await confirm();
    console.log(`Paired "${name}".`);
  },

  async send() {
    if (!arg) throw new Error('usage: send "<sms text>" --from VM-HDFCBK [--to "Work laptop"]');
    const d = await load();
    const detected = detectOtp(opt.from, arg);
    if (!detected) throw new Error("that SMS doesn't look like it carries an OTP");
    const roster = await d.roster();
    const targets = [...roster.values()].filter((b) => b.id !== d.id && (!opt.to || b.name === opt.to));
    if (targets.length === 0) throw new Error(opt.to ? `no device named "${opt.to}"` : "no other devices paired");
    for (const t of targets) {
      await d.send(t, otpPayload({ detected, smsFrom: opt.from, body: arg, how: opt.to ? "manual" : "rule" }));
      console.log(`Sent ${detected.kind} code ${detected.code} to "${t.name}".`);
    }
  },

  async roster() {
    const d = await load();
    for (const b of (await d.roster()).values()) {
      console.log(`${b.id === d.id ? "*" : " "} ${b.name.padEnd(16)} ${b.kind.padEnd(7)} ${b.id}`);
    }
  },

  async remove() {
    const d = await load();
    const target = await d.findDevice(arg);
    const r = await d.remove(target.id);
    console.log(r.status === 204 ? `Removed "${arg}".` : `Failed: ${r.status} ${JSON.stringify(r.data)}`);
  },

  async inbox() {
    const d = await load();
    const got = await d.inbox(0);
    if (got.length === 0) console.log("Nothing shared with this phone in the last 15 minutes.");
    got.forEach(printItem);
  },

  async watch() {
    const d = await load();
    console.log("Watching for shared OTPs (Ctrl+C to stop)…");
    d.connect((ev) => {
      if (ev.type === "item") printItem(ev.item);
      if (ev.type === "close") {
        console.log(`Connection closed (${ev.code}).`);
        process.exit(ev.code === 4001 ? 1 : 0);
      }
    });
  },
};

const run = commands[cmd];
if (!run) {
  console.log(`commands: ${Object.keys(commands).join(", ")}`);
  process.exit(cmd ? 1 : 0);
}
run().catch((e) => {
  console.error(`Error: ${e.message}`);
  process.exit(1);
});
