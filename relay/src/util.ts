export const ID_RE = /^[A-Za-z0-9_-]{22}$/;

export type Result<T> = { ok: true; value: T } | { ok: false; status: number; error: string };

export const ok = <T>(value: T): Result<T> => ({ ok: true, value });
export const fail = (status: number, error: string): Result<never> => ({ ok: false, status, error });

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" },
  });
}

export function error(status: number, message: string): Response {
  return json({ error: message }, status);
}

export function respond<T>(r: Result<T>, status = 200): Response {
  if (!r.ok) return error(r.status, r.error);
  return r.value === undefined ? new Response(null, { status: 204 }) : json(r.value, status);
}

export class HttpError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

export async function readJson<T = any>(req: Request, maxBytes: number): Promise<T> {
  const len = Number(req.headers.get("content-length") ?? "0");
  if (len > maxBytes) throw new HttpError(413, "body too large");
  const text = await req.text();
  if (text.length > maxBytes) throw new HttpError(413, "body too large");
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new HttpError(400, "invalid json");
  }
}

export async function sha256Hex(s: string): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Bearer token → its SHA-256 hex, which is all the Durable Objects ever see. */
export async function bearerHash(req: Request): Promise<string | null> {
  const m = /^Bearer ([A-Za-z0-9_-]{20,128})$/.exec(req.headers.get("authorization") ?? "");
  return m ? sha256Hex(m[1]) : null;
}
