import { isAuthorized } from "./auth.ts";
import { buildBatch, MAX_BODY_BYTES } from "./ingest.ts";

export interface Env {
  DB: D1Database;
  /** Segreto: `wrangler secret put INGEST_TOKEN` (in locale: file .dev.vars) */
  INGEST_TOKEN: string;
}

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8" } });

const unauthorized = () => json({ error: "non autorizzato" }, 401);

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);

    if (pathname === "/api/ping" && request.method === "GET") {
      if (!(await isAuthorized(request, env.INGEST_TOKEN))) return unauthorized();
      return json({ ok: true, now: Date.now() });
    }

    if (pathname === "/api/ingest" && request.method === "POST") {
      if (!(await isAuthorized(request, env.INGEST_TOKEN))) return unauthorized();
      const text = await request.text();
      if (new TextEncoder().encode(text).length > MAX_BODY_BYTES) return json({ error: "corpo troppo grande" }, 413);
      let body: unknown;
      try {
        body = JSON.parse(text);
      } catch {
        return json({ error: "JSON non valido" }, 400);
      }
      const batch = buildBatch(body, Date.now());
      if ("error" in batch) return json({ error: batch.error }, 400);
      if (batch.statements.length > 0) {
        await env.DB.batch(batch.statements.map((s) => env.DB.prepare(s.sql).bind(...s.params)));
      }
      return json({ accepted: batch.accepted, rejected: batch.rejected });
    }

    if (pathname === "/api/latest" && request.method === "GET") {
      if (!(await isAuthorized(request, env.INGEST_TOKEN))) return unauthorized();
      return json(await latest(env.DB, Date.now()));
    }

    return json({ error: "non trovato" }, 404);
  },
} satisfies ExportedHandler<Env>;

/** Ultimo stato noto: serve a verificare l'invio; gli strumenti per Claude (fase 4) useranno queste stesse query. */
export async function latest(db: D1Database, now: number) {
  const snapshot = await db.prepare(`SELECT * FROM snapshots ORDER BY ts DESC LIMIT 1`).first<Record<string, unknown>>();
  const trip = await db.prepare(`SELECT * FROM trips ORDER BY start_ms DESC LIMIT 1`).first();
  const status = await db.prepare(`SELECT value FROM meta WHERE key='status'`).first<{ value: string }>();
  const codes = await db.prepare(`SELECT * FROM dtc WHERE cleared_ms IS NULL ORDER BY last_seen_ms DESC`).all();
  return {
    snapshot,
    ageSeconds: snapshot ? Math.round((now - (snapshot.ts as number)) / 1000) : null,
    trip,
    status: status ? JSON.parse(status.value) : null,
    activeCodes: codes.results,
  };
}
