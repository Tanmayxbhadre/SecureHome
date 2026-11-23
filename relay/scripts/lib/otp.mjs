// Reference OTP detector. The Android app ports this logic and must pass ../../fixtures/otp-sms.json.

const KEYWORD =
  /\b(?:otp|one[\s-]?time[\s-]?(?:password|passcode|pin)|verification\s+(?:code|pin)|security\s+code|auth(?:entication)?\s+code|login\s+code|passcode|pin|code)\b/gi;
const NOT_KEYWORD = /\bpin\s*code\b/gi; // postal "PIN code 560001"

// Words just before a number that mean it's an amount, account, card or reference, not a code.
const BEFORE_EXCLUDE = /(?:rs\.?|inr|₹|a\/c|acct|ending(?:\s+with)?|no\.?|ref\.?|pin\s*code)\s*[:\-]?\s*$/i;

const RIDE = /\b(?:uber|ola|rapido|namma\s*yatri|blusmart|indrive)\b|\b(?:ride|trip|start)\s+(?:pin|otp|code)\b|\bride\s+otp\b/i;
const RIDE_SENDER = /^(?:UBER|OLA|RAPIDO|NYATRI|BLUSMT|INDRIV)/;
const DELIVERY = /\bdeliver(?:y|ed|ing)?\b|\bcourier\b|\bparcel\b|\bpackage\b|\bshipment\b/i;

/** "AX-HDFCBK-S" → "HDFCBK"; anything else is returned as-is. */
export function normalizeSender(from) {
  const m = /^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[A-Z])?$/i.exec(String(from).trim());
  return m ? m[1].toUpperCase() : String(from).trim();
}

function candidates(body) {
  const out = [];
  for (const m of body.matchAll(/\d{4,8}/g)) {
    const i = m.index;
    const j = i + m[0].length;
    const prev = body[i - 1] ?? "";
    const prev2 = body.slice(Math.max(0, i - 2), i);
    const next = body[j] ?? "";
    const after = body.slice(j, j + 4);
    if (/[A-Za-z0-9]/.test(next) || (/[A-Za-z0-9]/.test(prev) && prev2 !== "G-")) continue; // part of a longer token
    if (/[\/:]/.test(prev) || (prev === "-" && !/^G-$/i.test(prev2)) || /^[\-\/:]\d/.test(after)) continue; // dates, times
    if (/^[.,]\d/.test(after) || /^\s*(?:%|\/-)/.test(after)) continue; // decimals, thousands, percent, "/-"
    if (BEFORE_EXCLUDE.test(body.slice(Math.max(0, i - 16), i))) continue;
    out.push({ code: m[0], at: i });
  }
  return out;
}

/** Returns { code, kind: "otp" | "delivery" | "ride", sender } or null if the SMS doesn't carry an OTP. */
export function detectOtp(from, body) {
  const text = String(body ?? "");
  const masked = text.replace(NOT_KEYWORD, (s) => " ".repeat(s.length));
  const keywords = [...masked.matchAll(KEYWORD)].map((m) => m.index + m[0].length / 2);
  if (keywords.length === 0) return null;

  let best = null;
  for (const c of candidates(text)) {
    const centre = c.at + c.code.length / 2;
    const dist = Math.min(...keywords.map((k) => Math.abs(k - centre)));
    if (!best || dist < best.dist) best = { ...c, dist };
  }
  if (!best || best.dist > 80) return null;

  const sender = normalizeSender(from);
  const kind = RIDE.test(text) || RIDE_SENDER.test(sender) ? "ride" : DELIVERY.test(text) ? "delivery" : "otp";
  return { code: best.code, kind, sender };
}
