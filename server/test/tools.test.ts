import { test } from "node:test";
import assert from "node:assert/strict";
import { describeCode } from "../src/dtc.ts";
import { decodeFreeze, intArg, makeTools } from "../src/tools.ts";
import { dtc, FakeRepo, NOW, snap } from "./fakes.ts";

const tool = (repo: FakeRepo, name: string) => makeTools(repo, () => NOW).find((t) => t.name === name)!;
const run = async (repo: FakeRepo, name: string, args: Record<string, unknown> = {}) => (await tool(repo, name).run(args)) as any;

test("descrizioni dei codici della Cee'd", () => {
  assert.match(describeCode("P0172").descrizione!, /ricca/);
  assert.match(describeCode("P0420").descrizione!, /catalizzatore/i);
  assert.match(describeCode("P0650").descrizione!, /spia motore/i);
  assert.match(describeCode("p0133").descrizione!, /lenta/);
});

test("codice sconosciuto: nessuna descrizione ma categoria dedotta", () => {
  const d = describeCode("P0745");
  assert.equal(d.descrizione, null);
  assert.match(d.categoria, /cambio/);
  assert.match(describeCode("P1234").categoria, /costruttore/);
  assert.match(describeCode("U0999").categoria, /comunicazione/);
  assert.match(describeCode("B0001").categoria, /carrozzeria/);
});

test("freeze frame decodificato con nomi e unità", () => {
  const f = decodeFreeze("P0172;0C=2100.0;05=88.0;ZZ=1;0D=abc")!;
  assert.equal(f.codice, "P0172");
  assert.equal(f.valori["giri motore"], "2100 rpm");
  assert.equal(f.valori["temperatura liquido"], "88 °C");
  assert.equal(Object.keys(f.valori).length, 3); // ZZ è sconosciuto ma numerico → "PID ZZ"; 0D=abc scartato
  assert.equal(decodeFreeze(null), null);
  assert.equal(decodeFreeze(""), null);
});

test("intArg: default, limiti, tipi sbagliati", () => {
  assert.equal(intArg({}, "n", 10, 1, 50), 10);
  assert.equal(intArg({ n: 100 }, "n", 10, 1, 50), 50);
  assert.equal(intArg({ n: -5 }, "n", 10, 1, 50), 1);
  assert.equal(intArg({ n: "7" }, "n", 10, 1, 50), 10);
  assert.equal(intArg({ n: 7.6 }, "n", 10, 1, 50), 8);
  assert.equal(intArg({ n: NaN }, "n", 10, 1, 50), 10);
});

test("stato_attuale: nessun dato", async () => {
  const r = await run(new FakeRepo(), "stato_attuale");
  assert.equal(r.dati_disponibili, false);
});

test("stato_attuale: dati recenti e vecchi", async () => {
  const repo = new FakeRepo();
  repo.snapshots.push(snap({ ts: NOW - 10_000 }));
  repo.statusDoc = { info: "x", supported: [], calibration: 1, milOn: true, readiness: null, ts: NOW };
  const r = await run(repo, "stato_attuale");
  assert.equal(r.dati_recenti, true);
  assert.equal(r.aggiornato_secondi_fa, 10);
  assert.equal(r.motore_acceso_nell_ultimo_dato, true);
  assert.equal(r.valori.giri_motore_rpm, 2000);
  assert.equal(r.valori.tensione_batteria_v, 13.7);
  assert.equal(r.spia_motore_accesa, true);
  assert.equal(r.nota, undefined);

  const old = new FakeRepo();
  old.snapshots.push(snap({ ts: NOW - 3 * 3600_000, rpm: 0 }));
  const o = await run(old, "stato_attuale");
  assert.equal(o.dati_recenti, false);
  assert.equal(o.motore_acceso_nell_ultimo_dato, false);
  assert.match(o.nota, /vecchio/);
  assert.match(o.aggiornato_da, /ore/);
});

test("codici_errore: attivi con descrizione e freeze; cancellati solo se richiesti", async () => {
  const repo = new FakeRepo();
  repo.codes.push(dtc("P0172", { freeze: "P0172;0C=2100.0" }), dtc("P0420", { kind: "pendente" }), dtc("P0133", { cleared_ms: NOW - 1000 }));
  const r = await run(repo, "codici_errore");
  assert.equal(r.numero_codici_attivi, 2);
  const c = r.codici_attivi.find((x: any) => x.codice === "P0172");
  assert.match(c.descrizione, /ricca/);
  assert.match(c.presente_da, /giorni/);
  assert.equal(c.freeze_frame.valori["giri motore"], "2100 rpm");
  assert.equal(r.cancellati_di_recente, undefined);
  const r2 = await run(repo, "codici_errore", { includi_cancellati: true });
  assert.equal(r2.cancellati_di_recente.length, 1);
  assert.equal(r2.cancellati_di_recente[0].codice, "P0133");
});

test("codici_errore: nessun codice", async () => {
  const r = await run(new FakeRepo(), "codici_errore");
  assert.equal(r.numero_codici_attivi, 0);
  assert.match(r.nota, /Nessun codice/);
});

const trip = (daysAgo: number, km: number, fuel: number) => ({
  start_ms: NOW - daysAgo * 86400000, end_ms: NOW - daysAgo * 86400000 + 1800_000, km, fuel_l: fuel, max_speed: 90,
});

test("consumi: media pesata e andamento per giorno", async () => {
  const repo = new FakeRepo();
  repo.tripRows.push(trip(0.1, 10, 0.8), trip(1, 20, 1.6), trip(1.01, 10, 0.9), trip(40, 50, 5), trip(0.2, 0.2, 0.1));
  const r = await run(repo, "consumi", { giorni: 30 });
  assert.equal(r.media_periodo.viaggi, 3); // il viaggio di 0.2 km e quello di 40 giorni fa non contano
  assert.equal(r.media_periodo.km_totali, 40);
  assert.equal(r.media_periodo.litri_stimati, 3.3);
  assert.equal(r.media_periodo.consumo_l_100km, 8.3); // 3.3/40*100 = 8.25
  assert.equal(r.ultimo_viaggio.consumo_l_100km, 8);
  assert.ok(r.andamento_giornaliero.length >= 2);
  assert.match(r.nota_consumi, /stima/);
  const long = await run(repo, "consumi", { giorni: 365 });
  assert.equal(long.media_periodo.viaggi, 4);
});

test("consumi: nessun viaggio", async () => {
  const r = await run(new FakeRepo(), "consumi");
  assert.equal(r.viaggi, 0);
});

test("storico_viaggi: ordine, limite", async () => {
  const repo = new FakeRepo();
  for (let i = 0; i < 15; i++) repo.tripRows.push(trip(i + 0.5, 5 + i, 0.5));
  const r = await run(repo, "storico_viaggi", { limite: 3 });
  assert.equal(r.viaggi.length, 3);
  assert.equal(r.viaggi[0].km, 5); // il più recente
  assert.equal((await run(repo, "storico_viaggi")).viaggi.length, 10);
  assert.equal((await run(repo, "storico_viaggi", { limite: 999 })).viaggi.length, 15);
});

test("controllo_salute: tutto nella norma", async () => {
  const repo = new FakeRepo();
  for (let i = 0; i < 20; i++) repo.snapshots.push(snap({ ts: NOW - i * 15000 }, { "06": 1, "07": 2 }));
  repo.statusDoc = { info: null, supported: [], calibration: 1, milOn: false, readiness: [{ name: "Catalizzatore", complete: true }], ts: NOW };
  const r = await run(repo, "controllo_salute");
  assert.equal(r.esito, "ok");
  assert.equal(r.problemi.length, 0);
  assert.ok(r.valori_nella_norma.length >= 3);
});

test("rapporto_test_guida: assente e presente", async () => {
  const repo = new FakeRepo();
  assert.equal((await run(repo, "rapporto_test_guida")).disponibile, false);
  repo.reports.push({ start_ms: NOW - 86400000, end_ms: NOW - 86400000 + 1200_000, text: "vecchio" }, { start_ms: NOW - 3600000, end_ms: NOW - 3600000 + 1100_000, text: "TEST DI GUIDA nuovo" });
  const r = await run(repo, "rapporto_test_guida");
  assert.equal(r.disponibile, true);
  assert.equal(r.rapporto, "TEST DI GUIDA nuovo");
  assert.equal(r.durata_minuti, 18);
  assert.equal(r.rapporti_precedenti.length, 1);
});

test("tutti gli strumenti hanno schema e descrizione", () => {
  const tools = makeTools(new FakeRepo());
  assert.deepEqual(tools.map((t) => t.name), ["stato_attuale", "codici_errore", "consumi", "storico_viaggi", "controllo_salute", "rapporto_test_guida"]);
  for (const t of tools) {
    assert.ok(t.description.length > 30);
    assert.equal((t.inputSchema as any).type, "object");
  }
});
