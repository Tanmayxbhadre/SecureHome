import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { detectOtp } from "./lib/otp.mjs";

const fixtures = JSON.parse(readFileSync(new URL("../../fixtures/otp-sms.json", import.meta.url), "utf8"));

for (const f of fixtures) {
  test(`${f.from}: ${f.body.slice(0, 60)}`, () => {
    assert.deepEqual(detectOtp(f.from, f.body), f.expect);
  });
}
