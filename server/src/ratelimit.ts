// Freno ai tentativi di password sulla pagina di autorizzazione: dopo MAX_FAILS errori
// da uno stesso indirizzo si blocca per WINDOW_S secondi. Contatori in KV (scadono da soli).

export interface KvLike {
  get(key: string): Promise<string | null>;
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>;
  delete(key: string): Promise<void>;
}

export const MAX_FAILS = 5;
export const WINDOW_S = 900;

const key = (ip: string) => `authfail:${ip}`;

export async function isLocked(kv: KvLike, ip: string): Promise<boolean> {
  return Number((await kv.get(key(ip))) ?? "0") >= MAX_FAILS;
}

export async function recordFail(kv: KvLike, ip: string): Promise<number> {
  const n = Number((await kv.get(key(ip))) ?? "0") + 1;
  await kv.put(key(ip), String(n), { expirationTtl: WINDOW_S });
  return n;
}

export async function clearFails(kv: KvLike, ip: string): Promise<void> {
  await kv.delete(key(ip));
}
