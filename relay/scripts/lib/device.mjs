// A simulated SecureHome device (phone or laptop) for Node. Mirrors what the Android
// app and the laptop page do, so the relay can be tested end to end. See PROTOCOL.md.
import * as C from "../../public/js/crypto.js";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class Device {
  /** @param {{relay: string, name: string, kind: "phone"|"laptop", keys: any, hid?: string, id?: string, token?: string, founderSigPub?: string, householdKey?: string}} s */
  constructor(s) {
    Object.assign(this, s);
    this.relay = s.relay.replace(/\/$/, "");
  }

  static async create({ relay, name, kind }) {
    return new Device({ relay, name, kind, keys: await C.generateDeviceKeys(true) });
  }

  async toJSON() {
    const { relay, name, kind, hid, id, token, founderSigPub, householdKey } = this;
    return { relay, name, kind, hid, id, token, founderSigPub, householdKey, keys: await C.exportKeysJwk(this.keys) };
  }

  static async fromJSON(j) {
    return new Device({ ...j, keys: await C.importKeysJwk(j.keys) });
  }

  get paired() {
    return Boolean(this.hid && this.id && this.token);
  }

  async api(method, path, body, { auth = true } = {}) {
    const headers = { "content-type": "application/json" };
    if (auth && this.token) headers.authorization = `Bearer ${this.token}`;
    const res = await fetch(`${this.relay}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = text;
    }
    return { status: res.status, data };
  }

  hpath(rest) {
    return `/api/h/${this.hid}/${rest}`;
  }

  async entryBody(id, name, kind, encPub, sigPub, addedBy) {
    return { v: 1, id, name, kind, encPub, sigPub, addedBy, addedAt: Date.now() };
  }

  // ---------- founding a household (first phone) ----------

  /** @param {string} foundingCode the relay's FOUNDING_SECRET */
  async init(foundingCode) {
    if (this.kind !== "phone") throw new Error("only a phone can found a household");
    this.hid = C.randomId();
    this.id = C.randomId();
    this.token = C.randomToken();
    this.householdKey = C.randomToken();
    const sigPub = await C.exportPub(this.keys.sig.publicKey);
    this.founderSigPub = sigPub;
    const body = await this.entryBody(this.id, this.name, "phone", await C.exportPub(this.keys.enc.publicKey), sigPub, this.id);
    const entry = await C.signEntry(body, this.keys.sig.privateKey);
    const r = await this.api("POST", this.hpath("init"), { entry, tokenHash: await C.sha256Hex(this.token), foundingCode }, { auth: false });
    if (r.status !== 201) throw new Error(`init failed: ${r.status} ${JSON.stringify(r.data)}`);
    return this;
  }

  // ---------- joining (the device that shows the QR) ----------

  async startJoin() {
    const pid = C.randomId();
    const qr = C.pairingQr({
      pid,
      encPub: await C.exportPub(this.keys.enc.publicKey),
      sigPub: await C.exportPub(this.keys.sig.publicKey),
      kind: this.kind,
    });
    return { pid, qr };
  }

  /** Polls the pairing slot, decrypts and checks the reply. Returns { sas, bundle }. */
  async waitForBundle(pid, { timeoutMs = 60_000, intervalMs = 300 } = {}) {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const r = await this.api("GET", `/api/pair/${pid}`, undefined, { auth: false });
      if (r.status === 200) {
        const { payload: bundle, sas } = await C.openEnvelope(r.data.env, C.pairAad(pid), this.keys.enc.privateKey, C.INFO.pair);
        const roster = await C.verifyRoster(bundle.roster, bundle.founderSigPub);
        const pairer = roster.get(bundle.pairedBy);
        if (!pairer || pairer.kind !== "phone") throw new Error("pairing reply is not from a trusted phone");
        const ok = await C.verifyEnvelope(r.data.env, C.pairAad(pid), await C.importSigPub(pairer.sigPub));
        if (!ok) throw new Error("pairing reply signature is invalid");
        return { sas, bundle };
      }
      await sleep(intervalMs);
    }
    throw new Error("pairing timed out");
  }

  /** After the bundle arrives, wait until the phone confirms (registers our token). */
  async waitForConfirm(bundle, { timeoutMs = 60_000, intervalMs = 300 } = {}) {
    Object.assign(this, {
      hid: bundle.hid,
      id: bundle.deviceId,
      token: bundle.token,
      name: bundle.name,
      founderSigPub: bundle.founderSigPub,
      householdKey: bundle.householdKey,
    });
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const r = await this.api("GET", this.hpath("me"));
      if (r.status === 200) return this;
      await sleep(intervalMs);
    }
    throw new Error("phone never confirmed the pairing");
  }

  // ---------- pairing someone else (phone scans their QR) ----------

  /** Returns { sas, confirm } — show sas, call confirm() once the user says the codes match. */
  async scan(qrText, name) {
    if (this.kind !== "phone") throw new Error("only a phone can pair devices");
    const q = C.parsePairingQr(qrText);
    const deviceId = C.randomId();
    const token = C.randomToken();
    const body = await this.entryBody(deviceId, name, q.kind, q.encPub, q.sigPub, this.id);
    const entry = await C.signEntry(body, this.keys.sig.privateKey);
    const roster = await this.api("GET", this.hpath("roster"));
    if (roster.status !== 200) throw new Error(`roster failed: ${roster.status}`);
    const bundle = {
      v: 1,
      hid: this.hid,
      deviceId,
      token,
      name,
      kind: q.kind,
      founderSigPub: this.founderSigPub,
      pairedBy: this.id,
      roster: [...roster.data.entries, entry],
      householdKey: this.householdKey,
    };
    const { env, sas } = await C.seal({
      recipientEncPub: await C.importEncPub(q.encPub),
      signerSigPriv: this.keys.sig.privateKey,
      info: C.INFO.pair,
      aad: C.pairAad(q.pid),
      payload: bundle,
    });
    const put = await this.api("PUT", `/api/pair/${q.pid}`, { hid: this.hid, env });
    if (put.status !== 201) throw new Error(`pairing slot write failed: ${put.status}`);
    return {
      sas,
      deviceId,
      confirm: async () => {
        const r = await this.api("POST", this.hpath("devices"), { entry, tokenHash: await C.sha256Hex(token) });
        if (r.status !== 201) throw new Error(`register failed: ${r.status} ${JSON.stringify(r.data)}`);
        return deviceId;
      },
    };
  }

  // ---------- roster ----------

  async roster() {
    const r = await this.api("GET", this.hpath("roster"));
    if (r.status !== 200) throw new Error(`roster failed: ${r.status}`);
    return C.verifyRoster(r.data.entries, this.founderSigPub);
  }

  async findDevice(name) {
    for (const d of (await this.roster()).values()) if (d.name === name) return d;
    throw new Error(`no device named "${name}"`);
  }

  async remove(id) {
    return this.api("DELETE", this.hpath(`devices/${id}`));
  }

  // ---------- items ----------

  /** Encrypt `payload` for device `to` (a verified roster body) and upload it. */
  async send(to, payload) {
    const { env } = await C.seal({
      recipientEncPub: await C.importEncPub(to.encPub),
      signerSigPriv: this.keys.sig.privateKey,
      info: C.INFO.item,
      aad: C.itemAad(this.hid, this.id, to.id),
      payload,
    });
    const r = await this.api("POST", this.hpath("items"), { to: to.id, env });
    if (r.status !== 201) throw new Error(`send failed: ${r.status} ${JSON.stringify(r.data)}`);
    return r.data;
  }

  /** Verify and decrypt one relay item. Throws if the sender isn't trusted or the signature fails. */
  async openItem(item, roster) {
    const from = roster.get(item.from);
    if (!from) throw new Error("item from unknown device");
    const aad = C.itemAad(this.hid, item.from, this.id);
    if (!(await C.verifyEnvelope(item.env, aad, await C.importSigPub(from.sigPub)))) throw new Error("bad signature");
    const { payload } = await C.openEnvelope(item.env, aad, this.keys.enc.privateKey, C.INFO.item);
    return { id: item.id, from: from.name, storedAt: item.storedAt, payload };
  }

  async inbox(since = 0) {
    const r = await this.api("GET", `${this.hpath("items")}?since=${since}`);
    if (r.status !== 200) throw new Error(`inbox failed: ${r.status}`);
    const roster = await this.roster();
    return Promise.all(r.data.items.map((it) => this.openItem(it, roster)));
  }

  /** Live inbox. onEvent receives {type:'item', item} (decrypted), {type:'ready'}, {type:'close', code}. */
  connect(onEvent) {
    const ws = new WebSocket(`${this.relay.replace(/^http/, "ws")}${this.hpath("ws")}`);
    ws.addEventListener("open", () => ws.send(JSON.stringify({ type: "auth", token: this.token })));
    // Like the laptop page: keep the verified roster and refetch it only when a sender is unknown
    // or the relay says it changed, rather than on every message.
    let roster = null;
    const fresh = async () => (roster = await this.roster().catch(() => roster ?? new Map()));
    const open = async (it) => {
      if (!roster?.has(it.from)) await fresh();
      return this.openItem(it, roster);
    };
    ws.addEventListener("message", async (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.type === "ready") {
        onEvent({ type: "ready" });
        for (const it of msg.items) onEvent({ type: "item", item: await open(it) });
      } else if (msg.type === "item") {
        onEvent({ type: "item", item: await open(msg.item) });
      } else if (msg.type === "roster") {
        await fresh();
        onEvent(msg);
      } else onEvent(msg);
    });
    ws.addEventListener("close", (ev) => onEvent({ type: "close", code: ev.code }));
    return ws;
  }
}

/** The item payload a phone sends for an SMS. */
export function otpPayload({ detected, smsFrom, body, receivedAt = Date.now(), how = "rule" }) {
  return { v: 1, kind: detected.kind, code: detected.code, sender: detected.sender, smsFrom, body, receivedAt, sharedAt: Date.now(), how };
}
