import { test } from "node:test";
import assert from "node:assert/strict";
import { buildBatch, buildStatement, MAX_EVENTS, SNAPSHOT_RETENTION_MS } from "../src/ingest.ts";

const NOW = 1_800_000_000_000;

test("snapshot valido: colonne dai PID", () => {
  const s = buildStatement({
    type: "snapshot", ts: 1000, battery: 13.7, lph: 4.2, trip: { km: 3.5 },
    values: { "0C": 2100, "0D": 80, "05": 88, "0B": 45, "0F": 20, "04": 30, "11": 12, "14": 0.5 },
  });
  assert.ok(typeof s !== "string");
  assert.match(s.sql, /INSERT OR IGNORE INTO snapshots/);
  assert.deepEqual(s.params.slice(0, 11), [1000, 2100, 80, 88, 30, 45, 20, 12, 13.7, 4.2, 3.5]);
  assert.deepEqual(JSON.parse(s.params[11] as string), { "0C": 2100, "0D": 80, "05": 88, "0B": 45, "0F": 20, "04": 30, "11": 12, "14": 0.5 });
  assert.equal(s.params[12], 0); // dato vero
});

test("eventi simulati: marcati, e lo stato va in una chiave separata", () => {
  const snap = buildStatement({ type: "snapshot", ts: 1, values: {}, simulated: true });
  assert.ok(typeof snap !== "string");
  assert.equal(snap.params.at(-1), 1);
  const trip = buildStatement({ type: "trip", startMs: 1, endMs: 2, km: 1, fuelL: 0.1, simulated: true });
  assert.ok(typeof trip !== "string");
  assert.equal(trip.params.at(-1), 1);
  const st = buildStatement({ type: "status", ts: 5, simulated: true });
  assert.ok(typeof st !== "string");
  assert.equal(st.params[0], "status_sim");
  const real = buildStatement({ type: "status", ts: 5 });
  assert.ok(typeof real !== "string");
  assert.equal(real.params[0], "status");
  // un valore diverso da true non rende "simulato"
  const notSim = buildStatement({ type: "drive_report", startMs: 1, endMs: 2, text: "x", simulated: "si" });
  assert.ok(typeof notSim !== "string");
  assert.equal(notSim.params.at(-1), 0);
});

test("snapshot: chiavi non-PID e valori non numerici scartati, mancanti = null", () => {
  const s = buildStatement({ type: "snapshot", ts: 5, values: { "0C": 900, zz: 1, "0D": "veloce" } });
  assert.ok(typeof s !== "string");
  assert.equal(s.params[1], 900);
  assert.equal(s.params[2], null);
  assert.deepEqual(JSON.parse(s.params[11] as string), { "0C": 900 });
});

test("snapshot senza ts o values rifiutato", () => {
  assert.equal(typeof buildStatement({ type: "snapshot", values: {} }), "string");
  assert.equal(typeof buildStatement({ type: "snapshot", ts: 1 }), "string");
  assert.equal(typeof buildStatement({ type: "snapshot", ts: NaN, values: {} }), "string");
});

test("trip valido e incompleto", () => {
  const ok = buildStatement({ type: "trip", startMs: 1, endMs: 2, km: 10.5, fuelL: 0.7, maxSpeed: 90 });
  assert.ok(typeof ok !== "string");
  assert.deepEqual(ok.params, [1, 2, 10.5, 0.7, 90, 0]);
  assert.equal(typeof buildStatement({ type: "trip", startMs: 1, endMs: 2, km: 1 }), "string");
});

test("dtc: formato codice controllato", () => {
  const base = { type: "dtc", firstSeenMs: 1, lastSeenMs: 2, kind: "memorizzato" };
  assert.ok(typeof buildStatement({ ...base, code: "P0172" }) !== "string");
  assert.equal(typeof buildStatement({ ...base, code: "X0172" }), "string");
  assert.equal(typeof buildStatement({ ...base, code: "P01" }), "string");
  assert.equal(typeof buildStatement({ ...base, code: "P0172; DROP TABLE dtc" }), "string");
});

test("dtc cancellato e freeze", () => {
  const s = buildStatement({
    type: "dtc", code: "P0420", kind: "pendente", firstSeenMs: 10, lastSeenMs: 20, clearedMs: 30, freeze: "P0420;0C=1500.0",
  });
  assert.ok(typeof s !== "string");
  assert.deepEqual(s.params, ["P0420", 10, "pendente", 20, 30, "P0420;0C=1500.0", 0]);
});

test("drive_report e status", () => {
  const r = buildStatement({ type: "drive_report", startMs: 1, endMs: 2, text: "TEST" });
  assert.ok(typeof r !== "string");
  assert.deepEqual(r.params, [1, 2, "TEST", 0]);

  const st = buildStatement({
    type: "status", ts: 99, info: "ELM327 v2.2", supported: [1, 4, "x"], calibration: 1.1, milOn: true,
    readiness: [{ name: "Catalizzatore", complete: false }, { name: 5 }],
    extra: "ignorato",
  });
  assert.ok(typeof st !== "string");
  assert.equal(st.params[0], "status");
  const saved = JSON.parse(st.params[1] as string);
  assert.deepEqual(saved.supported, [1, 4]);
  assert.deepEqual(saved.readiness, [{ name: "Catalizzatore", complete: false }]);
  assert.equal("extra" in saved, false);
  assert.equal(saved.monitorTests, null);

  const withTests = buildStatement({
    type: "status", ts: 99,
    monitorTests: [
      { name: "Catalizzatore", tid: 160, value: 150, min: 0, max: 300, passed: true, extra: 1 },
      { name: "Sonda", tid: 1, value: 0, min: 0, max: 0, passed: null },
      { name: "rotto", tid: "x" },
    ],
  });
  assert.ok(typeof withTests !== "string");
  assert.deepEqual(JSON.parse(withTests.params[1] as string).monitorTests, [
    { name: "Catalizzatore", tid: 160, value: 150, min: 0, max: 300, passed: true },
    { name: "Sonda", tid: 1, value: 0, min: 0, max: 0, passed: null },
  ]);
});

test("tipo sconosciuto e non-oggetti", () => {
  assert.equal(typeof buildStatement({ type: "boh" }), "string");
  assert.equal(typeof buildStatement(null), "string");
  assert.equal(typeof buildStatement([1]), "string");
  assert.equal(typeof buildStatement("snapshot"), "string");
});

test("batch: accetta i buoni, scarta i cattivi con indice, aggiunge last_ingest", () => {
  const r = buildBatch({
    events: [
      { type: "trip", startMs: 1, endMs: 2, km: 1, fuelL: 0.1 },
      { type: "nope" },
      { type: "drive_report", startMs: 1, endMs: 2, text: "x" },
    ],
  }, NOW);
  assert.ok(!("error" in r));
  assert.equal(r.accepted, 2);
  assert.deepEqual(r.rejected.map((x) => x.index), [1]);
  assert.equal(r.statements.length, 3); // 2 eventi + last_ingest
  assert.match(r.statements[2].sql, /last_ingest/);
});

test("batch con snapshot pulisce i vecchi (30 giorni)", () => {
  const r = buildBatch({ events: [{ type: "snapshot", ts: NOW, values: {} }] }, NOW);
  assert.ok(!("error" in r));
  const del = r.statements.at(-1)!;
  assert.match(del.sql, /DELETE FROM snapshots/);
  assert.deepEqual(del.params, [NOW - SNAPSHOT_RETENTION_MS]);
});

test("batch senza eventi validi non scrive nulla", () => {
  const r = buildBatch({ events: [{ type: "nope" }] }, NOW);
  assert.ok(!("error" in r));
  assert.equal(r.statements.length, 0);
});

test("batch: forma e limiti", () => {
  assert.ok("error" in buildBatch({}, NOW));
  assert.ok("error" in buildBatch([], NOW));
  assert.ok("error" in buildBatch({ events: "x" }, NOW));
  assert.ok("error" in buildBatch({ events: new Array(MAX_EVENTS + 1).fill({}) }, NOW));
  assert.ok(!("error" in buildBatch({ events: [] }, NOW)));
});
