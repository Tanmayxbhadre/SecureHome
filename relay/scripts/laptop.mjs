#!/usr/bin/env node
// A command-line stand-in for the laptop page: prints pairing text, shows the SAS, then prints
// every item it receives. Used to test the Android app.
//   node scripts/laptop.mjs [--relay URL] [--name "CLI laptop"]
import { parseArgs } from "node:util";
import { Device } from "./lib/device.mjs";

const { values: opt } = parseArgs({
  options: { relay: { type: "string", default: "http://127.0.0.1:8787" }, name: { type: "string", default: "CLI laptop" } },
});
const d = await Device.create({ relay: opt.relay, name: opt.name, kind: "laptop" });
const { pid, qr } = await d.startJoin();
console.log(`PAIRING ${qr}`);
const { sas, bundle } = await d.waitForBundle(pid, { timeoutMs: 5 * 60_000 });
console.log(`SAS ${sas} (paired as "${bundle.name}" by ${bundle.pairedBy})`);
await d.waitForConfirm(bundle, { timeoutMs: 5 * 60_000 });
console.log("CONFIRMED");
d.connect((ev) => {
  if (ev.type === "ready") console.log("LIVE");
  if (ev.type === "item") {
    const p = ev.item.payload;
    console.log(`ITEM ${p.code} ${p.kind} ${p.sender} at=${new Date().toISOString().slice(11, 23)} from="${ev.item.from}" how=${p.how} smsFrom=${p.smsFrom} body=${JSON.stringify(p.body)}`);
  }
  if (ev.type === "close") {
    console.log(`CLOSED ${ev.code}`);
    process.exit(0);
  }
});
