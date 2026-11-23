// SecureHome shared crypto. Runs in browsers and in Node 20+ (WebCrypto).
// The Android app must produce byte-identical results; see PROTOCOL.md.

const subtle = globalThis.crypto.subtle;
const te = new TextEncoder();
const td = new TextDecoder();

const ECDH = { name: "ECDH", namedCurve: "P-256" };
const ECDSA = { name: "ECDSA", namedCurve: "P-256" };
const SIGN = { name: "ECDSA", hash: "SHA-256" };

export const INFO = {
  item: "securehome/item/v1",
  pair: "securehome/pair/v1",
  sas: "securehome/sas/v1",
  entry: "securehome/entry/v1",
};

// ---------- encoding ----------

export function b64uEncode(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (const b of u8) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64uDecode(str) {
  const pad = "===".slice((str.length + 3) % 4);
  const s = atob(str.replace(/-/g, "+").replace(/_/g, "/") + pad);
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

export function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

/** Random id: 16 bytes, base64url (22 chars). Used for household, device and pairing ids. */
export function randomId(bytes = 16) {
  return b64uEncode(globalThis.crypto.getRandomValues(new Uint8Array(bytes)));
}

/** Access tokens are 32 random bytes; the relay only ever stores sha256hex(token). */
export function randomToken() {
  return randomId(32);
}

export async function sha256Hex(str) {
  const d = new Uint8Array(await subtle.digest("SHA-256", te.encode(str)));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// ---------- keys ----------

/** Each device has two P-256 key pairs: `enc` (ECDH) and `sig` (ECDSA). */
export async function generateDeviceKeys(extractable = false) {
  const enc = await subtle.generateKey(ECDH, extractable, ["deriveBits"]);
  const sig = await subtle.generateKey(ECDSA, extractable, ["sign", "verify"]);
  return { enc, sig };
}

/** Public key → uncompressed point (65 bytes), base64url. */
export async function exportPub(key) {
  return b64uEncode(await subtle.exportKey("raw", key));
}

export function importEncPub(b64u) {
  return subtle.importKey("raw", b64uDecode(b64u), ECDH, true, []);
}

export function importSigPub(b64u) {
  return subtle.importKey("raw", b64uDecode(b64u), ECDSA, true, ["verify"]);
}

export async function exportKeysJwk(keys) {
  const out = {};
  for (const k of ["enc", "sig"]) {
    out[k] = {
      publicKey: await subtle.exportKey("jwk", keys[k].publicKey),
      privateKey: await subtle.exportKey("jwk", keys[k].privateKey),
    };
  }
  return out;
}

export async function importKeysJwk(jwks) {
  return {
    enc: {
      publicKey: await subtle.importKey("jwk", jwks.enc.publicKey, ECDH, true, []),
      privateKey: await subtle.importKey("jwk", jwks.enc.privateKey, ECDH, true, ["deriveBits"]),
    },
    sig: {
      publicKey: await subtle.importKey("jwk", jwks.sig.publicKey, ECDSA, true, ["verify"]),
      privateKey: await subtle.importKey("jwk", jwks.sig.privateKey, ECDSA, true, ["sign"]),
    },
  };
}

// ---------- primitives ----------

async function hkdf(ikm, salt, info, bits) {
  const key = await subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(
    await subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt, info: te.encode(info) }, key, bits),
  );
}

async function ecdh(privateKey, publicKey) {
  return new Uint8Array(await subtle.deriveBits({ name: "ECDH", public: publicKey }, privateKey, 256));
}

/** Six-digit confirmation code both sides of a pairing compute from the shared secret. */
async function sasFrom(shared) {
  const b = await hkdf(shared, new Uint8Array(0), INFO.sas, 32);
  const n = new DataView(b.buffer).getUint32(0) % 1_000_000;
  const s = String(n).padStart(6, "0");
  return `${s.slice(0, 3)} ${s.slice(3)}`;
}

function signedBytes(aad, env) {
  return concat(te.encode(aad), b64uDecode(env.epk), b64uDecode(env.iv), b64uDecode(env.ct));
}

// ---------- envelopes ----------

/**
 * Encrypt `payload` (JSON) to one recipient and sign it.
 * ECDH(ephemeral, recipient.enc) → HKDF-SHA256(salt = epk, info) → AES-256-GCM(aad).
 * Signature: ECDSA-P256-SHA256 (IEEE P1363, r||s) over aad || epk || iv || ct.
 * Returns { env, sas }.
 */
export async function seal({ recipientEncPub, signerSigPriv, info, aad, payload }) {
  const eph = await subtle.generateKey(ECDH, true, ["deriveBits"]);
  const shared = await ecdh(eph.privateKey, recipientEncPub);
  const epk = new Uint8Array(await subtle.exportKey("raw", eph.publicKey));
  const keyBytes = await hkdf(shared, epk, info, 256);
  const key = await subtle.importKey("raw", keyBytes, "AES-GCM", false, ["encrypt"]);
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(
    await subtle.encrypt({ name: "AES-GCM", iv, additionalData: te.encode(aad) }, key, te.encode(JSON.stringify(payload))),
  );
  const env = { v: 1, epk: b64uEncode(epk), iv: b64uEncode(iv), ct: b64uEncode(ct), sig: "" };
  env.sig = b64uEncode(await subtle.sign(SIGN, signerSigPriv, signedBytes(aad, env)));
  return { env, sas: await sasFrom(shared) };
}

export async function verifyEnvelope(env, aad, signerSigPub) {
  try {
    return await subtle.verify(SIGN, signerSigPub, b64uDecode(env.sig), signedBytes(aad, env));
  } catch {
    return false;
  }
}

/** Decrypt an envelope. Does NOT check the signature; call verifyEnvelope too. Returns { payload, sas }. */
export async function openEnvelope(env, aad, recipientEncPriv, info) {
  if (env?.v !== 1) throw new Error("unsupported envelope");
  const epk = b64uDecode(env.epk);
  const ephPub = await subtle.importKey("raw", epk, ECDH, true, []);
  const shared = await ecdh(recipientEncPriv, ephPub);
  const keyBytes = await hkdf(shared, epk, info, 256);
  const key = await subtle.importKey("raw", keyBytes, "AES-GCM", false, ["decrypt"]);
  const pt = await subtle.decrypt(
    { name: "AES-GCM", iv: b64uDecode(env.iv), additionalData: te.encode(aad) },
    key,
    b64uDecode(env.ct),
  );
  return { payload: JSON.parse(td.decode(pt)), sas: await sasFrom(shared) };
}

export const itemAad = (hid, from, to) => `${INFO.item}|${hid}|${from}|${to}`;
export const pairAad = (pid) => `${INFO.pair}|${pid}`;

// ---------- roster ----------

/**
 * A roster entry describes one device and is signed by the phone that added it
 * (the founder signs its own). body = base64url(JSON), sig over "securehome/entry/v1|" || body bytes.
 */
export async function signEntry(body, signerSigPriv) {
  const bodyBytes = te.encode(JSON.stringify(body));
  const sig = await subtle.sign(SIGN, signerSigPriv, concat(te.encode(`${INFO.entry}|`), bodyBytes));
  return { body: b64uEncode(bodyBytes), sig: b64uEncode(sig) };
}

export function decodeEntry(entry) {
  return JSON.parse(td.decode(b64uDecode(entry.body)));
}

async function verifyEntry(entry, signerSigPubB64u) {
  try {
    const pub = await importSigPub(signerSigPubB64u);
    return await subtle.verify(SIGN, pub, b64uDecode(entry.sig), concat(te.encode(`${INFO.entry}|`), b64uDecode(entry.body)));
  } catch {
    return false;
  }
}

/**
 * Returns Map(id → body) of entries that chain back to the founder through phones.
 * Anything the relay added or altered without a valid signature is dropped.
 */
export async function verifyRoster(entries, founderSigPub) {
  const bodies = entries.map((e) => ({ e, b: decodeEntry(e) }));
  const trusted = new Map();
  const founder = bodies.find(({ b }) => b.addedBy === b.id && b.sigPub === founderSigPub);
  if (!founder || !(await verifyEntry(founder.e, founderSigPub))) return trusted;
  trusted.set(founder.b.id, founder.b);
  let grew = true;
  while (grew) {
    grew = false;
    for (const { e, b } of bodies) {
      if (trusted.has(b.id)) continue;
      const signer = trusted.get(b.addedBy);
      if (!signer || signer.kind !== "phone") continue;
      if (await verifyEntry(e, signer.sigPub)) {
        trusted.set(b.id, b);
        grew = true;
      }
    }
  }
  return trusted;
}

// ---------- pairing QR ----------

/** QR text a joining device shows: securehome:1:<pairingId>:<encPub>:<sigPub>:<kind> */
export function pairingQr({ pid, encPub, sigPub, kind }) {
  return `securehome:1:${pid}:${encPub}:${sigPub}:${kind}`;
}

export function parsePairingQr(text) {
  const p = String(text).trim().split(":");
  if (p.length !== 6 || p[0] !== "securehome" || p[1] !== "1") throw new Error("not a SecureHome pairing code");
  const [, , pid, encPub, sigPub, kind] = p;
  if (!/^[A-Za-z0-9_-]{22}$/.test(pid)) throw new Error("bad pairing id");
  if (kind !== "phone" && kind !== "laptop") throw new Error("bad device kind");
  return { pid, encPub, sigPub, kind };
}
