import { DurableObject } from "cloudflare:workers";

const PAIRING_TTL_MS = 5 * 60_000;

/**
 * One short-lived slot per pairing attempt. The scanning phone writes one encrypted
 * reply; the joining device reads it once. Everything is gone after 5 minutes.
 */
export class Pairing extends DurableObject<Env> {
  /** First write wins. Returns false if the slot was already used. */
  async put(bundle: string): Promise<boolean> {
    const used = await this.ctx.storage.get<boolean>("used");
    if (used) return false;
    await this.ctx.storage.put({ bundle, used: true });
    await this.ctx.storage.setAlarm(Date.now() + PAIRING_TTL_MS);
    return true;
  }

  /** One-time read: returns the reply and deletes it. */
  async take(): Promise<string | null> {
    const bundle = await this.ctx.storage.get<string>("bundle");
    if (bundle === undefined) return null;
    await this.ctx.storage.delete("bundle");
    return bundle;
  }

  async alarm(): Promise<void> {
    await this.ctx.storage.deleteAll();
  }
}
