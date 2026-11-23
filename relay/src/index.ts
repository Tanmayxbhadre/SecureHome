import { Household } from "./household";
import { Pairing } from "./pairing";
import { bearerHash, error, HttpError, ID_RE, json, readJson, respond } from "./util";

export { Household, Pairing };

const PAIR_ROUTE = /^\/api\/pair\/([A-Za-z0-9_-]{22})$/;
const HOUSEHOLD_ROUTE = /^\/api\/h\/([A-Za-z0-9_-]{22})\/([a-z]+)(?:\/([A-Za-z0-9_-]{22}))?$/;

export default {
  async fetch(request, env): Promise<Response> {
    const url = new URL(request.url);
    if (!url.pathname.startsWith("/api/")) return env.ASSETS.fetch(request);
    try {
      return await route(request, env, url);
    } catch (e) {
      if (e instanceof HttpError) return error(e.status, e.message);
      console.error("unhandled", e);
      return error(500, "internal error");
    }
  },
} satisfies ExportedHandler<Env>;

async function route(req: Request, env: Env, url: URL): Promise<Response> {
  const pair = PAIR_ROUTE.exec(url.pathname);
  if (pair) {
    if (!(await allowed(env.PAIRING_LIMIT, req))) return error(429, "too many requests, slow down");
    const slot = env.PAIRING.getByName(pair[1]);
    if (req.method === "PUT") {
      // Only a phone that's already in a household may answer a pairing code, so strangers
      // can't use the slots as free storage.
      const tokenHash = await bearerHash(req);
      if (!tokenHash) return error(401, "missing token");
      const body = await readJson<{ hid?: unknown; env?: unknown }>(req, 32_768);
      if (typeof body?.hid !== "string" || !ID_RE.test(body.hid) || !body.env || typeof body.env !== "object") {
        return error(400, "invalid bundle");
      }
      const who = await env.HOUSEHOLD.getByName(body.hid).whoami(tokenHash);
      if (!who.ok) return error(401, "unauthorized");
      if (who.value.kind !== "phone") return error(403, "only phones can pair devices");
      return (await slot.put(JSON.stringify({ env: body.env }))) ? json({ ok: true }, 201) : error(409, "pairing slot already used");
    }
    if (req.method === "GET") {
      const bundle = await slot.take();
      return bundle === null
        ? new Response(null, { status: 204, headers: { "cache-control": "no-store" } })
        : new Response(bundle, { headers: { "content-type": "application/json", "cache-control": "no-store" } });
    }
    return error(405, "method not allowed");
  }

  const h = HOUSEHOLD_ROUTE.exec(url.pathname);
  if (!h) return error(404, "not found");
  const [, hid, action, sub] = h;
  const household = env.HOUSEHOLD.getByName(hid);
  const key = `${req.method} ${action}${sub ? "/:id" : ""}`;

  // WebSocket: authentication happens in the first message, since browsers can't set headers.
  if (key === "GET ws") {
    if (req.headers.get("upgrade") !== "websocket") return error(426, "expected websocket");
    return household.fetch(req);
  }

  if (key === "POST init") {
    if (!(await allowed(env.FOUNDING_LIMIT, req))) return error(429, "too many attempts, try again in a minute");
    const body = await readJson<{ entry: any; tokenHash: string; foundingCode?: unknown }>(req, 4096);
    const refused = await checkFoundingCode(env, body?.foundingCode);
    if (refused) return refused;
    return respond(await household.init(body?.entry, String(body?.tokenHash)), 201);
  }

  const tokenHash = await bearerHash(req);
  if (!tokenHash) return error(401, "missing token");

  switch (key) {
    case "GET me":
      return respond(await household.whoami(tokenHash));
    case "GET roster":
      return respond(await household.roster(tokenHash));
    case "POST devices": {
      const body = await readJson<{ entry: any; tokenHash: string }>(req, 4096);
      return respond(await household.addDevice(tokenHash, body.entry, String(body.tokenHash)), 201);
    }
    case "DELETE devices/:id":
      if (!sub || !ID_RE.test(sub)) return error(400, "invalid device id");
      return respond(await household.removeDevice(tokenHash, sub));
    case "POST items": {
      const body = await readJson<{ to: string; env: unknown }>(req, 10_240);
      return respond(await household.putItem(tokenHash, String(body.to), body.env), 201);
    }
    case "GET items":
      return respond(await household.items(tokenHash, Number(url.searchParams.get("since") ?? 0)));
    default:
      return error(404, "not found");
  }
}

/**
 * New households need the relay owner's FOUNDING_SECRET (a Worker secret). Fails closed: a relay
 * without one can't create households at all. Compared as SHA-256 digests in constant time.
 */
async function checkFoundingCode(env: Env, given: unknown): Promise<Response | null> {
  const secret = env.FOUNDING_SECRET;
  if (!secret) return error(503, "this relay isn't accepting new households (FOUNDING_SECRET is not set)");
  if (typeof given !== "string" || given.length === 0) return error(403, "founding code required");
  const digest = async (s: string) => crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  const [a, b] = await Promise.all([digest(given.trim()), digest(secret.trim())]);
  return crypto.subtle.timingSafeEqual(a, b) ? null : error(403, "wrong founding code");
}

/** Per-IP rate limit for the routes that need no device token. */
async function allowed(limiter: RateLimit, req: Request): Promise<boolean> {
  const ip = req.headers.get("cf-connecting-ip") ?? "local";
  const { success } = await limiter.limit({ key: ip });
  return success;
}
