import { DurableObject } from "cloudflare:workers";
import { fail, ID_RE, ok, type Result, sha256Hex } from "./util";

/** A roster entry as stored: signed by the phone that added the device. The relay can't verify trust; clients do. */
export interface SignedEntry {
  body: string; // base64url(JSON EntryBody)
  sig: string;
}

interface EntryBody {
  v: 1;
  id: string;
  name: string;
  kind: "phone" | "laptop";
  encPub: string;
  sigPub: string;
  addedBy: string;
  addedAt: number;
}

export interface Item {
  id: number;
  from: string;
  storedAt: number;
  env: unknown;
}

interface Caller {
  id: string;
  kind: "phone" | "laptop";
}

interface SocketState {
  deviceId?: string;
}

const MAX_DEVICES = 20;
const MAX_ITEMS = 1000;
const MAX_ENV_CHARS = 8192;
const PUB_RE = /^[A-Za-z0-9_-]{87}$/; // 65-byte uncompressed P-256 point
const HASH_RE = /^[0-9a-f]{64}$/;

function decodeBody(entry: SignedEntry): EntryBody | null {
  try {
    if (typeof entry?.body !== "string" || typeof entry?.sig !== "string" || entry.sig.length > 200) return null;
    const s = atob(entry.body.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((entry.body.length + 3) % 4));
    const b = JSON.parse(new TextDecoder().decode(Uint8Array.from(s, (c) => c.charCodeAt(0)))) as EntryBody;
    const valid =
      b.v === 1 &&
      ID_RE.test(b.id) &&
      ID_RE.test(b.addedBy) &&
      typeof b.name === "string" &&
      b.name.length > 0 &&
      b.name.length <= 60 &&
      (b.kind === "phone" || b.kind === "laptop") &&
      PUB_RE.test(b.encPub) &&
      PUB_RE.test(b.sigPub) &&
      Number.isFinite(b.addedAt);
    return valid ? b : null;
  } catch {
    return null;
  }
}

/**
 * One per household. Holds the device roster, a short-lived inbox per device,
 * and the WebSockets of devices that are watching their inbox live.
 */
export class Household extends DurableObject<Env> {
  private sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    ctx.blockConcurrencyWhile(async () => {
      this.sql.exec(`
        CREATE TABLE IF NOT EXISTS devices (
          id TEXT PRIMARY KEY,
          token_hash TEXT NOT NULL UNIQUE,
          kind TEXT NOT NULL,
          entry TEXT NOT NULL,
          created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS items (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          to_id TEXT NOT NULL,
          from_id TEXT NOT NULL,
          stored_at INTEGER NOT NULL,
          env TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS items_to ON items (to_id, stored_at);
      `);
    });
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"type":"ping"}', '{"type":"pong"}'));
  }

  private get ttl(): number {
    return Number(this.env.ITEM_TTL_MS) || 15 * 60_000;
  }

  private caller(tokenHash: string): Caller | null {
    const row = this.sql
      .exec<{ id: string; kind: string }>("SELECT id, kind FROM devices WHERE token_hash = ?", tokenHash)
      .toArray()[0];
    return row ? { id: row.id, kind: row.kind as Caller["kind"] } : null;
  }

  // ---------- roster ----------

  /** Creates the household with its founding phone. Only works on an empty household. */
  async init(entry: SignedEntry, tokenHash: string): Promise<Result<{ id: string }>> {
    const b = decodeBody(entry);
    if (!b || !HASH_RE.test(tokenHash)) return fail(400, "invalid entry");
    if (b.kind !== "phone" || b.addedBy !== b.id) return fail(400, "founder must be a self-signed phone");
    const count = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM devices").one().n;
    if (count > 0) return fail(409, "household already exists");
    this.sql.exec(
      "INSERT INTO devices (id, token_hash, kind, entry, created_at) VALUES (?, ?, ?, ?, ?)",
      b.id,
      tokenHash,
      b.kind,
      JSON.stringify(entry),
      Date.now(),
    );
    return ok({ id: b.id });
  }

  async whoami(tokenHash: string): Promise<Result<Caller>> {
    const c = this.caller(tokenHash);
    return c ? ok(c) : fail(401, "unauthorized");
  }

  async roster(tokenHash: string): Promise<Result<{ entries: SignedEntry[] }>> {
    if (!this.caller(tokenHash)) return fail(401, "unauthorized");
    const rows = this.sql.exec<{ entry: string }>("SELECT entry FROM devices ORDER BY created_at").toArray();
    return ok({ entries: rows.map((r) => JSON.parse(r.entry) as SignedEntry) });
  }

  /** A phone registers a device it just paired with. */
  async addDevice(tokenHash: string, entry: SignedEntry, newTokenHash: string): Promise<Result<{ id: string }>> {
    const c = this.caller(tokenHash);
    if (!c) return fail(401, "unauthorized");
    if (c.kind !== "phone") return fail(403, "only phones can pair devices");
    const b = decodeBody(entry);
    if (!b || !HASH_RE.test(newTokenHash)) return fail(400, "invalid entry");
    if (b.addedBy !== c.id) return fail(400, "entry must be added by the caller");
    const count = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM devices").one().n;
    if (count >= MAX_DEVICES) return fail(429, "too many devices");
    const clash = this.sql
      .exec<{ n: number }>("SELECT COUNT(*) AS n FROM devices WHERE id = ? OR token_hash = ?", b.id, newTokenHash)
      .one().n;
    if (clash > 0) return fail(409, "device already exists");
    this.sql.exec(
      "INSERT INTO devices (id, token_hash, kind, entry, created_at) VALUES (?, ?, ?, ?, ?)",
      b.id,
      newTokenHash,
      b.kind,
      JSON.stringify(entry),
      Date.now(),
    );
    this.broadcast({ type: "roster" });
    return ok({ id: b.id });
  }

  /** A phone removes any device; any device can remove itself (unpair). */
  async removeDevice(tokenHash: string, id: string): Promise<Result<undefined>> {
    const c = this.caller(tokenHash);
    if (!c) return fail(401, "unauthorized");
    if (c.kind !== "phone" && c.id !== id) return fail(403, "forbidden");
    const n = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM devices WHERE id = ?", id).one().n;
    if (n === 0) return fail(404, "no such device");
    this.sql.exec("DELETE FROM devices WHERE id = ?", id);
    this.sql.exec("DELETE FROM items WHERE to_id = ?", id);
    for (const ws of this.ctx.getWebSockets()) {
      const st = ws.deserializeAttachment() as SocketState | null;
      if (st?.deviceId === id) ws.close(4001, "device removed");
    }
    this.broadcast({ type: "roster" });
    return ok(undefined);
  }

  // ---------- items ----------

  async putItem(tokenHash: string, to: string, env: unknown): Promise<Result<{ id: number; storedAt: number }>> {
    const c = this.caller(tokenHash);
    if (!c) return fail(401, "unauthorized");
    if (!ID_RE.test(to)) return fail(400, "invalid recipient");
    if (to === c.id) return fail(400, "cannot send to yourself");
    const exists = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM devices WHERE id = ?", to).one().n;
    if (exists === 0) return fail(404, "no such device");
    const envText = JSON.stringify(env);
    if (!env || typeof env !== "object" || envText.length > MAX_ENV_CHARS) return fail(400, "invalid envelope");
    const total = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM items").one().n;
    if (total >= MAX_ITEMS) return fail(429, "too many items");

    const storedAt = Date.now();
    const id = this.sql
      .exec<{ id: number }>(
        "INSERT INTO items (to_id, from_id, stored_at, env) VALUES (?, ?, ?, ?) RETURNING id",
        to,
        c.id,
        storedAt,
        envText,
      )
      .one().id;

    if ((await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(storedAt + this.ttl);
    }

    const item: Item = { id, from: c.id, storedAt, env };
    for (const ws of this.ctx.getWebSockets()) {
      const st = ws.deserializeAttachment() as SocketState | null;
      if (st?.deviceId === to) this.safeSend(ws, { type: "item", item });
    }
    return ok({ id, storedAt });
  }

  async items(tokenHash: string, since: number): Promise<Result<{ items: Item[]; ttlMs: number; now: number }>> {
    const c = this.caller(tokenHash);
    if (!c) return fail(401, "unauthorized");
    return ok({ items: this.inbox(c.id, since), ttlMs: this.ttl, now: Date.now() });
  }

  private inbox(deviceId: string, since: number): Item[] {
    const floor = Math.max(Number.isFinite(since) ? since : 0, Date.now() - this.ttl);
    return this.sql
      .exec<{ id: number; from_id: string; stored_at: number; env: string }>(
        "SELECT id, from_id, stored_at, env FROM items WHERE to_id = ? AND stored_at >= ? ORDER BY id LIMIT 200",
        deviceId,
        floor,
      )
      .toArray()
      .map((r) => ({ id: r.id, from: r.from_id, storedAt: r.stored_at, env: JSON.parse(r.env) }));
  }

  async alarm(): Promise<void> {
    const cutoff = Date.now() - this.ttl;
    this.sql.exec("DELETE FROM items WHERE stored_at <= ?", cutoff);
    const next = this.sql.exec<{ t: number | null }>("SELECT MIN(stored_at) AS t FROM items").one().t;
    if (next !== null) await this.ctx.storage.setAlarm(next + this.ttl);
  }

  // ---------- live inbox (WebSocket hibernation) ----------

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({} satisfies SocketState);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const st = (ws.deserializeAttachment() as SocketState | null) ?? {};
    let msg: { type?: string; token?: string; since?: number };
    try {
      msg = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message));
    } catch {
      ws.close(1003, "invalid message");
      return;
    }
    if (st.deviceId) return; // authenticated sockets only receive; pings are auto-answered

    if (msg.type !== "auth" || typeof msg.token !== "string" || msg.token.length > 128) {
      ws.close(4001, "unauthorized");
      return;
    }
    const c = this.caller(await sha256Hex(msg.token));
    if (!c) {
      ws.close(4001, "unauthorized");
      return;
    }
    ws.serializeAttachment({ deviceId: c.id } satisfies SocketState);
    this.safeSend(ws, {
      type: "ready",
      deviceId: c.id,
      ttlMs: this.ttl,
      now: Date.now(),
      items: this.inbox(c.id, Number(msg.since) || 0),
    });
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    try {
      ws.close(code === 1005 ? 1000 : code, "closing");
    } catch {
      // already closed
    }
  }

  private broadcast(msg: unknown): void {
    for (const ws of this.ctx.getWebSockets()) {
      const st = ws.deserializeAttachment() as SocketState | null;
      if (st?.deviceId) this.safeSend(ws, msg);
    }
  }

  private safeSend(ws: WebSocket, msg: unknown): void {
    try {
      ws.send(JSON.stringify(msg));
    } catch {
      // socket went away; it will be cleaned up by the runtime
    }
  }
}
