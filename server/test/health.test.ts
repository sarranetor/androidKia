import { test } from "node:test";
import assert from "node:assert/strict";
import { evaluateHealth } from "../src/health.ts";
import { dtc, NOW, snap } from "./fakes.ts";

const many = (n: number, over: Parameters<typeof snap>[0] extends infer T ? Partial<T> : never, values: Record<string, number> = {}) =>
  Array.from({ length: n }, (_, i) => snap({ ts: NOW - i * 15000, ...over }, values));

const base = { status: null, codes: [], periodoGiorni: 7 };

test("nessun dato", () => {
  const h = evaluateHealth({ ...base, snapshots: [] });
  assert.equal(h.esito, "nessun_dato");
});

test("liquido: critico, attenzione, norma", () => {
  assert.equal(evaluateHealth({ ...base, snapshots: many(5, { coolant: 108 }) }).esito, "critico");
  const w = evaluateHealth({ ...base, snapshots: many(5, { coolant: 100 }) });
  assert.equal(w.esito, "attenzione");
  assert.match(w.problemi[0].messaggio, /100 °C/);
  assert.equal(evaluateHealth({ ...base, snapshots: many(5, { coolant: 90 }) }).problemi.length, 0);
});

test("batteria: ignorata a motore spento, giudicata a motore acceso", () => {
  const off = evaluateHealth({ ...base, snapshots: many(10, { rpm: 0, battery: 10.7 }) });
  assert.equal(off.problemi.filter((p) => p.area.includes("batteria")).length, 0);
  const low = evaluateHealth({ ...base, snapshots: many(10, { battery: 12.6 }) });
  assert.equal(low.problemi.find((p) => p.area.includes("batteria"))!.livello, "attenzione");
  const crit = evaluateHealth({ ...base, snapshots: [...many(10, { battery: 13.8 }), snap({ ts: NOW - 999999, battery: 10.9 })] });
  assert.equal(crit.problemi.find((p) => p.area.includes("batteria"))!.livello, "critico");
  const high = evaluateHealth({ ...base, snapshots: many(10, { battery: 14.9 }) });
  assert.equal(high.problemi.find((p) => p.area.includes("batteria"))!.livello, "attenzione");
  assert.ok(evaluateHealth({ ...base, snapshots: many(10, { battery: 13.7 }) }).valori_nella_norma.some((s) => /alternatore/.test(s)));
});

test("correzioni carburante: ricca, povera, norma, solo a caldo", () => {
  const rich = evaluateHealth({ ...base, snapshots: many(10, {}, { "06": -3, "07": -12 }) });
  const p = rich.problemi.find((x) => x.area.includes("miscela"))!;
  assert.match(p.messaggio, /ricca/);
  assert.equal(p.livello, "attenzione");
  const veryRich = evaluateHealth({ ...base, snapshots: many(10, {}, { "06": -5, "07": -18 }) });
  assert.equal(veryRich.problemi.find((x) => x.area.includes("miscela"))!.livello, "critico");
  const lean = evaluateHealth({ ...base, snapshots: many(10, {}, { "06": 4, "07": 9 }) });
  assert.match(lean.problemi.find((x) => x.area.includes("miscela"))!.messaggio, /povera/);
  assert.equal(evaluateHealth({ ...base, snapshots: many(10, {}, { "06": 0, "07": 3 }) }).problemi.length, 0);
  // motore freddo: le correzioni non contano
  assert.equal(evaluateHealth({ ...base, snapshots: many(10, { coolant: 40 }, { "06": -30, "07": -30 }) }).problemi.length, 0);
});

test("codici attivi e spia", () => {
  const h = evaluateHealth({ ...base, snapshots: many(3, {}), codes: [dtc("P0172"), dtc("P0420", { kind: "pendente" })] });
  assert.equal(h.esito, "attenzione");
  assert.equal(h.problemi.filter((p) => p.area === "codici errore").length, 2);
  assert.match(h.problemi.map((p) => p.messaggio).join(" "), /ricca/);
  const mil = evaluateHealth({
    ...base, snapshots: many(3, {}), status: { info: null, supported: [], calibration: null, milOn: true, readiness: null, ts: NOW },
  });
  assert.match(mil.problemi[0].messaggio, /spia motore/);
});

test("monitor incompleti: info, non cambiano l'esito", () => {
  const h = evaluateHealth({
    ...base, snapshots: many(3, {}),
    status: { info: null, supported: [], calibration: null, milOn: false, readiness: [{ name: "Catalizzatore", complete: false }, { name: "Sonda O2", complete: true }], ts: NOW },
  });
  assert.equal(h.esito, "ok");
  assert.equal(h.problemi[0].livello, "info");
  assert.match(h.problemi[0].messaggio, /Catalizzatore/);
});

test("problemi ordinati per gravità", () => {
  const h = evaluateHealth({
    ...base, snapshots: many(10, { coolant: 107, battery: 12.5 }),
    codes: [dtc("P0300")],
    status: { info: null, supported: [], calibration: null, milOn: true, readiness: [{ name: "EVAP", complete: false }], ts: NOW },
  });
  const order = h.problemi.map((p) => p.livello);
  assert.deepEqual(order, [...order].sort((a, b) => ({ critico: 0, attenzione: 1, info: 2 }[a] - { critico: 0, attenzione: 1, info: 2 }[b])));
  assert.equal(h.esito, "critico");
});
