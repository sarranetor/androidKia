// Prova end-to-end contro un server in esecuzione (locale o pubblicato):
//   BASE=http://localhost:8787 TOKEN=... npx tsx scripts/smoke.ts
import assert from "node:assert/strict";

const BASE = process.env.BASE ?? "http://localhost:8787";
const TOKEN = process.env.TOKEN ?? "dev-token-solo-locale";
const auth = { Authorization: `Bearer ${TOKEN}` };

async function call(path: string, init: RequestInit = {}) {
  const res = await fetch(BASE + path, init);
  return { status: res.status, body: await res.json() as any };
}

const now = Date.now();
// senza token / con token sbagliato
assert.equal((await call("/api/ping")).status, 401);
assert.equal((await call("/api/ping", { headers: { Authorization: "Bearer sbagliato" } })).status, 401);
assert.equal((await call("/api/ingest", { method: "POST", body: "{}" })).status, 401);
assert.equal((await call("/api/latest")).status, 401);

// ping ok
assert.equal((await call("/api/ping", { headers: auth })).body.ok, true);

// JSON rotto e forma sbagliata
assert.equal((await call("/api/ingest", { method: "POST", headers: auth, body: "{non json" })).status, 400);
assert.equal((await call("/api/ingest", { method: "POST", headers: auth, body: "{}" })).status, 400);

const batch = {
  events: [
    { type: "snapshot", ts: now - 15000, battery: 13.7, lph: 5.1, trip: { km: 2.4 },
      values: { "0C": 2000, "0D": 70, "05": 85, "04": 33, "0B": 48, "0F": 21, "11": 14 } },
    { type: "snapshot", ts: now, battery: 13.8, lph: 4.8, trip: { km: 2.5 },
      values: { "0C": 2100, "0D": 72, "05": 86, "04": 35, "0B": 50, "0F": 21, "11": 15 } },
    { type: "trip", startMs: now - 600000, endMs: now, km: 8.2, fuelL: 0.6, maxSpeed: 95 },
    { type: "dtc", code: "P0172", kind: "memorizzato", firstSeenMs: now - 86400000, lastSeenMs: now, clearedMs: null, freeze: "P0172;0C=2100.0" },
    { type: "drive_report", startMs: now - 1200000, endMs: now, text: "TEST DI GUIDA — prova" },
    { type: "status", ts: now, info: "ELM327 v2.2 · CAN 11/500", supported: [1, 4, 5, 12, 13], calibration: 1.08, milOn: true,
      readiness: [{ name: "Catalizzatore", complete: false }, { name: "Sonda O2", complete: true }] },
    { type: "sconosciuto" },
  ],
};
const r1 = await call("/api/ingest", { method: "POST", headers: auth, body: JSON.stringify(batch) });
assert.equal(r1.status, 200);
assert.equal(r1.body.accepted, 6);
assert.deepEqual(r1.body.rejected.map((x: any) => x.index), [6]);

// invio ripetuto (coda offline reinviata): nessun errore e nessun doppione
const r2 = await call("/api/ingest", { method: "POST", headers: auth, body: JSON.stringify(batch) });
assert.equal(r2.body.accepted, 6);

const latest = (await call("/api/latest", { headers: auth })).body;
assert.equal(latest.snapshot.ts, now);
assert.equal(latest.snapshot.rpm, 2100);
assert.equal(latest.snapshot.trip_km, 2.5);
assert.ok(latest.ageSeconds >= 0 && latest.ageSeconds < 30);
assert.equal(latest.trip.km, 8.2);
assert.equal(latest.status.calibration, 1.08);
assert.equal(latest.status.milOn, true);
assert.equal(latest.activeCodes.length, 1);
assert.equal(latest.activeCodes[0].code, "P0172");

// aggiornamento: il codice viene cancellato → sparisce dagli attivi
const cleared = { events: [{ type: "dtc", code: "P0172", kind: "memorizzato", firstSeenMs: now - 86400000, lastSeenMs: now, clearedMs: now + 1000, freeze: null }] };
assert.equal((await call("/api/ingest", { method: "POST", headers: auth, body: JSON.stringify(cleared) })).body.accepted, 1);
const after = (await call("/api/latest", { headers: auth })).body;
assert.equal(after.activeCodes.length, 0);

assert.equal((await call("/api/boh", { headers: auth })).status, 404);
console.log("smoke ok");
