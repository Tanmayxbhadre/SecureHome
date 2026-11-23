// SecureHome laptop page: pairs by QR, then shows OTPs shared to this browser, live.
// Keys are generated here and stored as non-extractable CryptoKeys in IndexedDB.
import * as C from "./js/crypto.js";

const $ = (id) => document.getElementById(id);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const KIND_LABEL = { otp: "OTP", delivery: "Delivery", ride: "Ride" };

// ---------- storage (IndexedDB, with an in-memory fallback) ----------

const store = (() => {
  const mem = new Map();
  let dbp = null;
  let usable = true;
  const open = () =>
    (dbp ??= new Promise((resolve, reject) => {
      const r = indexedDB.open("securehome", 1);
      r.onupgradeneeded = () => r.result.createObjectStore("kv");
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    }));
  const run = async (mode, fn) => {
    const db = await open();
    return new Promise((resolve, reject) => {
      const t = db.transaction("kv", mode);
      const req = fn(t.objectStore("kv"));
      t.oncomplete = () => resolve(req.result);
      t.onerror = () => reject(t.error);
    });
  };
  return {
    get usable() {
      return usable;
    },
    async get(k) {
      try {
        return await run("readonly", (s) => s.get(k));
      } catch {
        usable = false;
        return mem.get(k);
      }
    },
    async set(k, v) {
      mem.set(k, v);
      try {
        await run("readwrite", (s) => s.put(v, k));
      } catch {
        usable = false;
      }
    },
    async clear() {
      mem.clear();
      try {
        await run("readwrite", (s) => s.clear());
      } catch {
        usable = false;
      }
    },
  };
})();

const prefs = {
  get(k) {
    try {
      return localStorage.getItem(`securehome.${k}`);
    } catch {
      return null;
    }
  },
  set(k, v) {
    try {
      localStorage.setItem(`securehome.${k}`, v);
    } catch {}
  },
};

// ---------- state ----------

let keys = null; // { enc, sig } CryptoKeyPairs
let cfg = null; // { hid, id, token, name, founderSigPub, householdKey }
let roster = new Map();
let ttlMs = 15 * 60_000;
let clockOffset = 0; // server time − local time
let generation = 0; // bumps on every view change; stale loops exit
const items = new Map(); // id → { id, from, storedAt, payload, el }
let ws = null;
let unread = 0;

function show(view) {
  generation++;
  for (const v of ["loading", "pair", "confirm", "live"]) $(`view-${v}`).hidden = v !== view;
  $("bar-live").hidden = view !== "live";
  return generation;
}

async function api(method, path, body, { auth = true } = {}) {
  const headers = { "content-type": "application/json" };
  if (auth && cfg?.token) headers.authorization = `Bearer ${cfg.token}`;
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: "no-store" });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {}
  return { status: res.status, data };
}

// ---------- pairing ----------

async function ensureKeys() {
  keys ??= await store.get("keys");
  if (!keys) {
    keys = await C.generateDeviceKeys(false);
    await store.set("keys", keys);
  }
}

async function startPairing(notice) {
  closeSocket();
  const gen = show("pair");
  $("pair-notice").hidden = !notice;
  $("pair-notice").textContent = notice ?? "";
  await ensureKeys();
  $("storage-warning").hidden = store.usable;

  while (gen === generation) {
    const pid = C.randomId();
    const qr = C.pairingQr({
      pid,
      encPub: await C.exportPub(keys.enc.publicKey),
      sigPub: await C.exportPub(keys.sig.publicKey),
      kind: "laptop",
    });
    renderQr(qr);
    const expires = Date.now() + 5 * 60_000;

    while (gen === generation && Date.now() < expires) {
      const left = Math.max(0, expires - Date.now());
      $("pair-expiry").textContent = `Code refreshes in ${fmtClock(left)}`;
      try {
        const r = await api("GET", `/api/pair/${pid}`, undefined, { auth: false });
        if (r.status === 200 && gen === generation) return handleBundle(pid, r.data);
      } catch {
        $("pair-expiry").textContent = "Can't reach the relay. Retrying…";
      }
      await sleep(1500);
    }
  }
}

function renderQr(text) {
  $("pair-text").textContent = text;
  const el = $("qr");
  if (typeof qrcode !== "function") {
    el.textContent = "QR library failed to load. Use the pairing text below.";
    return;
  }
  const q = qrcode(0, "M");
  q.addData(text);
  q.make();
  el.innerHTML = q.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
}

async function handleBundle(pid, data) {
  let sas, bundle;
  try {
    ({ payload: bundle, sas } = await C.openEnvelope(data.env, C.pairAad(pid), keys.enc.privateKey, C.INFO.pair));
    const trusted = await C.verifyRoster(bundle.roster, bundle.founderSigPub);
    const pairer = trusted.get(bundle.pairedBy);
    if (!pairer || pairer.kind !== "phone") throw new Error("untrusted pairer");
    if (!(await C.verifyEnvelope(data.env, C.pairAad(pid), await C.importSigPub(pairer.sigPub)))) throw new Error("bad signature");
  } catch (e) {
    console.warn("pairing reply rejected", e);
    return startPairing("That pairing attempt couldn't be verified. Scan the new code to try again.");
  }

  const gen = show("confirm");
  $("sas").textContent = sas;
  const candidate = {
    hid: bundle.hid,
    id: bundle.deviceId,
    token: bundle.token,
    name: bundle.name,
    founderSigPub: bundle.founderSigPub,
    householdKey: bundle.householdKey,
  };
  const end = Date.now() + 3 * 60_000;
  while (gen === generation && Date.now() < end) {
    try {
      const r = await fetch(`/api/h/${candidate.hid}/me`, { headers: { authorization: `Bearer ${candidate.token}` }, cache: "no-store" });
      if (r.status === 200) {
        cfg = candidate;
        await store.set("cfg", cfg);
        return startLive();
      }
    } catch {}
    await sleep(1500);
  }
  if (gen === generation) startPairing("The phone didn't confirm in time. Scan the new code to try again.");
}

// ---------- live inbox ----------

async function loadRoster() {
  const r = await api("GET", `/api/h/${cfg.hid}/roster`);
  if (r.status === 401) return revoked();
  if (r.status !== 200) throw new Error(`roster ${r.status}`);
  roster = await C.verifyRoster(r.data.entries, cfg.founderSigPub);
}

async function startLive() {
  show("live");
  $("whoami").textContent = `This browser is paired as “${cfg.name}”.`;
  setNotifyButton();
  renderEmpty();
  try {
    await loadRoster();
  } catch {}
  connect();
}

// After the laptop sleeps, a socket can look open while it's dead. We ping every 20 s and treat
// 45 s without any message (pongs included) as dead.
const PING_MS = 20_000;
const STALE_MS = 45_000;
let lastHeard = 0;
let connectStarted = 0;
let reconnectTimer = null;

function connect(attempt = 0) {
  if (!cfg) return;
  clearTimeout(reconnectTimer);
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const sock = new WebSocket(`${proto}://${location.host}/api/h/${cfg.hid}/ws`);
  ws = sock;
  connectStarted = Date.now();
  let pinger = null;
  setStatus("connecting");

  sock.onopen = () => {
    lastHeard = Date.now();
    sock.send(JSON.stringify({ type: "auth", token: cfg.token }));
    pinger = setInterval(() => {
      if (Date.now() - lastHeard > STALE_MS) return reconnectNow("stale");
      if (sock.readyState === 1) sock.send('{"type":"ping"}');
    }, PING_MS);
  };
  sock.onmessage = async (ev) => {
    lastHeard = Date.now();
    let msg;
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (msg.type === "ready") {
      attempt = 0;
      ttlMs = msg.ttlMs ?? ttlMs;
      clockOffset = (msg.now ?? Date.now()) - Date.now();
      $("ttl-label").textContent = fmtDuration(ttlMs);
      setStatus("live");
      for (const it of msg.items) await addItem(it, { quiet: true });
    } else if (msg.type === "item") {
      await addItem(msg.item, { quiet: false });
    } else if (msg.type === "roster") {
      await loadRoster().catch(() => {});
    }
  };
  sock.onclose = async (ev) => {
    clearInterval(pinger);
    if (ws !== sock) return; // replaced or closed on purpose
    ws = null;
    if (ev.code === 4001) {
      const r = await api("GET", `/api/h/${cfg.hid}/me`).catch(() => null);
      if (r?.status === 401) return revoked();
    }
    setStatus("down");
    const delay = Math.min(30_000, 1000 * 2 ** attempt);
    reconnectTimer = setTimeout(() => cfg && !ws && connect(attempt + 1), delay);
  };
}

/** Drop the current socket (if any) and connect again right away. Missed items arrive in "ready". */
function reconnectNow(why) {
  if (!cfg) return;
  console.debug("[securehome] reconnecting:", why);
  closeSocket();
  connect(0);
}

/** On wake, network change or tab focus: reconnect unless the socket has spoken recently. */
function checkLive(why) {
  if (!cfg || document.hidden) return;
  if (ws?.readyState === 0 && Date.now() - connectStarted < 10_000) return; // still connecting
  if (!ws || ws.readyState > 1 || Date.now() - lastHeard > PING_MS + 5_000) reconnectNow(why);
}
window.addEventListener("online", () => checkLive("online"));
window.addEventListener("focus", () => checkLive("focus"));
window.addEventListener("pageshow", () => checkLive("pageshow"));

function closeSocket() {
  const s = ws;
  ws = null;
  try {
    s?.close(1000);
  } catch {}
}

async function addItem(raw, { quiet }) {
  if (items.has(raw.id)) return;
  let from = roster.get(raw.from);
  if (!from) {
    await loadRoster().catch(() => {});
    from = roster.get(raw.from);
  }
  if (!from) return console.warn("item from unknown device", raw.from);
  const aad = C.itemAad(cfg.hid, raw.from, cfg.id);
  if (!(await C.verifyEnvelope(raw.env, aad, await C.importSigPub(from.sigPub)))) return console.warn("bad signature", raw.id);
  let payload;
  try {
    ({ payload } = await C.openEnvelope(raw.env, aad, keys.enc.privateKey, C.INFO.item));
  } catch (e) {
    return console.warn("could not decrypt", raw.id, e);
  }
  const item = { id: raw.id, fromName: from.name, storedAt: raw.storedAt, payload };
  item.el = renderItem(item, !quiet);
  items.set(item.id, item);
  sortItems();
  renderEmpty();
  if (!quiet) announce(item);
}

// ---------- rendering ----------

function fmtCode(code) {
  return code.length === 6 ? `${code.slice(0, 3)} ${code.slice(3)}` : code.length === 8 ? `${code.slice(0, 4)} ${code.slice(4)}` : code;
}

function fmtClock(ms) {
  const s = Math.ceil(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

function fmtDuration(ms) {
  const m = Math.round(ms / 60_000);
  return m >= 1 ? `${m} minute${m === 1 ? "" : "s"}` : `${Math.round(ms / 1000)} seconds`;
}

function fmtAge(ms) {
  if (ms < 45_000) return "just now";
  const m = Math.round(ms / 60_000);
  return m < 60 ? `${m} min ago` : `${Math.round(m / 60)} h ago`;
}

function renderItem(item, fresh) {
  const p = item.payload;
  const el = $("tpl-item").content.firstElementChild.cloneNode(true);
  const badge = el.querySelector(".badge");
  badge.hidden = p.kind === "otp";
  badge.textContent = KIND_LABEL[p.kind] ?? "OTP";
  badge.classList.add(p.kind);
  el.querySelector(".sender").textContent = p.sender || p.smsFrom || "";
  el.querySelector(".code").textContent = fmtCode(String(p.code));
  el.querySelector(".from").textContent = `from ${item.fromName}${p.how === "manual" ? " · shared by hand" : ""}`;
  el.querySelector(".sms-body").textContent = p.body ?? "";
  const copy = () => copyCode(String(p.code), el.querySelector(".copy"));
  el.querySelector(".code").addEventListener("click", copy);
  el.querySelector(".copy").addEventListener("click", copy);
  if (fresh) {
    el.classList.add("fresh");
    setTimeout(() => el.classList.remove("fresh"), 8000);
  }
  updateItem(item, el);
  return el;
}

function updateItem(item, el = item.el) {
  const now = Date.now() + clockOffset;
  const left = item.storedAt + ttlMs - now;
  if (left <= 0) {
    el.remove();
    items.delete(item.id);
    renderEmpty();
    return;
  }
  el.querySelector(".age").textContent = fmtAge(now - (item.payload.receivedAt ?? item.storedAt));
  el.querySelector(".expires").textContent = `disappears in ${fmtClock(left)}`;
  el.querySelector(".bar-fill").style.transform = `scaleX(${Math.max(0, left / ttlMs)})`;
}

function sortItems() {
  const list = $("items");
  const sorted = [...items.values()].sort((a, b) => (b.payload.receivedAt ?? b.storedAt) - (a.payload.receivedAt ?? a.storedAt));
  for (const it of sorted) list.appendChild(it.el);
}

function renderEmpty() {
  $("empty").hidden = items.size > 0;
}

async function copyCode(code, btn) {
  try {
    await navigator.clipboard.writeText(code);
    btn.textContent = "Copied";
    btn.classList.add("done");
  } catch {
    btn.textContent = "Copy failed";
  }
  setTimeout(() => {
    btn.textContent = "Copy";
    btn.classList.remove("done");
  }, 1500);
}

function setStatus(state) {
  const el = $("status");
  el.classList.toggle("live", state === "live");
  el.classList.toggle("down", state === "down");
  $("status-text").textContent = state === "live" ? "Live" : state === "down" ? "Reconnecting…" : "Connecting…";
}

// ---------- notifications & title ----------

function notifyEnabled() {
  return "Notification" in window && Notification.permission === "granted" && prefs.get("notify") === "1";
}

function setNotifyButton() {
  const b = $("btn-notify");
  if (!("Notification" in window)) return (b.hidden = true);
  b.textContent = notifyEnabled() ? "Notifications on" : "Notify me";
  b.classList.toggle("on", notifyEnabled());
}

$("btn-notify").addEventListener("click", async () => {
  if (notifyEnabled()) {
    prefs.set("notify", "0");
  } else {
    const perm = Notification.permission === "default" ? await Notification.requestPermission() : Notification.permission;
    prefs.set("notify", perm === "granted" ? "1" : "0");
  }
  setNotifyButton();
});

function announce(item) {
  if (document.hidden) {
    unread++;
    document.title = `(${unread}) New OTP · SecureHome`;
  }
  if (notifyEnabled()) {
    try {
      const p = item.payload;
      new Notification(`${fmtCode(String(p.code))} · ${KIND_LABEL[p.kind] ?? "OTP"}`, {
        body: `${p.sender || p.smsFrom || ""} · from ${item.fromName}`,
        tag: `otp-${item.id}`,
      });
    } catch {}
  }
}

document.addEventListener("visibilitychange", () => {
  if (!document.hidden) {
    unread = 0;
    document.title = "SecureHome";
    checkLive("visible");
  }
});

// ---------- unpair ----------

let unpairArmed = null;
$("btn-unpair").addEventListener("click", async () => {
  const b = $("btn-unpair");
  if (!unpairArmed) {
    b.textContent = "Click again to unpair";
    b.classList.add("danger");
    unpairArmed = setTimeout(() => {
      unpairArmed = null;
      b.textContent = "Unpair";
      b.classList.remove("danger");
    }, 4000);
    return;
  }
  clearTimeout(unpairArmed);
  unpairArmed = null;
  b.textContent = "Unpair";
  b.classList.remove("danger");
  await api("DELETE", `/api/h/${cfg.hid}/devices/${cfg.id}`).catch(() => {});
  await wipe();
  startPairing("This browser is unpaired. Its keys have been deleted.");
});

async function revoked() {
  await wipe();
  startPairing("This browser was removed from the household. Pair it again to keep receiving OTPs.");
}

async function wipe() {
  closeSocket();
  cfg = null;
  keys = null;
  roster = new Map();
  for (const it of items.values()) it.el.remove();
  items.clear();
  await store.clear();
}

$("btn-cancel").addEventListener("click", () => startPairing());

$("btn-copy-pair").addEventListener("click", (e) => copyCode($("pair-text").textContent, e.currentTarget));

// ---------- boot ----------

setInterval(() => {
  for (const it of [...items.values()]) updateItem(it);
}, 1000);

(async function boot() {
  keys = await store.get("keys");
  cfg = await store.get("cfg");
  if (keys && cfg) {
    const r = await api("GET", `/api/h/${cfg.hid}/me`).catch(() => null);
    if (r?.status === 401) return revoked();
    return startLive();
  }
  startPairing();
})();
