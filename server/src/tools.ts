// Strumenti MCP: ognuno legge dal `Repo` e ritorna un oggetto JSON in italiano.

import { describeCode } from "./dtc.ts";
import { eta, giorno, minutiDa, quando, round } from "./format.ts";
import { evaluateHealth, SOGLIE } from "./health.ts";
import { PIDS } from "./pids.ts";
import type { DtcRow, Repo, SnapshotRow } from "./repo.ts";

export type Tool = {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  run(args: Record<string, unknown>): Promise<unknown>;
};

const DAY = 24 * 3600 * 1000;

/** Legge un intero opzionale da args, con valore predefinito e limiti. */
export function intArg(args: Record<string, unknown>, key: string, def: number, min: number, max: number): number {
  const v = args[key];
  if (typeof v !== "number" || !Number.isFinite(v)) return def;
  return Math.min(max, Math.max(min, Math.round(v)));
}

/** "P0172;0C=2100.0;05=88.0" → { codice, valori: {"giri motore": "2100 rpm", …} } */
export function decodeFreeze(text: string | null): { codice: string; valori: Record<string, string> } | null {
  if (!text) return null;
  const [codice, ...rest] = text.split(";");
  if (!codice) return null;
  const valori: Record<string, string> = {};
  for (const part of rest) {
    const [pid, raw] = part.split("=");
    const v = Number(raw);
    if (!pid || !Number.isFinite(v)) continue;
    const info = PIDS[pid.toUpperCase()];
    valori[info?.nome ?? `PID ${pid}`] = `${round(v, 2)}${info?.unita ? " " + info.unita : ""}`;
  }
  return { codice, valori };
}

function codeView(c: DtcRow, now: number) {
  const d = describeCode(c.code);
  return {
    codice: c.code,
    tipo: c.kind,
    descrizione: d.descrizione ?? "descrizione non in elenco: spiegala tu partendo dal codice",
    categoria: d.categoria,
    visto_la_prima_volta: quando(c.first_seen_ms),
    presente_da: eta((now - c.first_seen_ms) / 1000),
    ultima_conferma: quando(c.last_seen_ms),
    ...(c.cleared_ms ? { cancellato: quando(c.cleared_ms) } : {}),
    freeze_frame: decodeFreeze(c.freeze),
  };
}

const tripView = (t: { start_ms: number; end_ms: number; km: number; fuel_l: number; max_speed: number }) => ({
  inizio: quando(t.start_ms),
  durata_minuti: minutiDa(t.start_ms, t.end_ms),
  km: round(t.km, 1),
  litri_stimati: round(t.fuel_l, 2),
  consumo_l_100km: t.km >= 0.5 ? round((t.fuel_l / t.km) * 100, 1) : null,
  velocita_massima_kmh: round(t.max_speed, 0),
});

export function makeTools(repo: Repo, now: () => number = Date.now): Tool[] {
  const NOTA_CONSUMI = "Consumo stimato dai sensori (nessun debimetro): è una stima, tarata con i rifornimenti inseriti nell'app.";

  return [
    {
      name: "stato_attuale",
      description:
        "Ultimi valori letti dall'auto (giri, velocità, temperature, tensione batteria, consumo) e da quanto tempo sono stati ricevuti. " +
        "Usalo per domande tipo «com'è la macchina adesso?». Se i dati sono vecchi, l'auto è spenta o il telefono non sta inviando.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
      async run() {
        const s = await repo.latestSnapshot();
        const status = await repo.status();
        if (!s) return { dati_disponibili: false, nota: "Nessun dato ricevuto dall'app finora." };
        const age = Math.max(0, Math.round((now() - s.ts) / 1000));
        const acceso = (s.rpm ?? 0) >= SOGLIE.motoreAcceso;
        return {
          dati_disponibili: true,
          ultimo_aggiornamento: quando(s.ts),
          aggiornato_da: eta(age),
          aggiornato_secondi_fa: age,
          dati_recenti: age <= 120,
          motore_acceso_nell_ultimo_dato: acceso,
          valori: {
            giri_motore_rpm: round(s.rpm, 0),
            velocita_kmh: round(s.speed, 0),
            temperatura_liquido_c: round(s.coolant, 0),
            carico_motore_pct: round(s.load, 0),
            pressione_collettore_kpa: round(s.map, 0),
            temperatura_aria_aspirata_c: round(s.iat, 0),
            farfalla_pct: round(s.throttle, 0),
            tensione_batteria_v: round(s.battery, 1),
            consumo_istantaneo_l_h: round(s.lph, 1),
          },
          km_viaggio_in_corso: round(s.trip_km, 1),
          spia_motore_accesa: status?.milOn ?? null,
          nota: age > 600 ? "Dato vecchio: l'auto è probabilmente spenta o il telefono non invia." : undefined,
        };
      },
    },
    {
      name: "codici_errore",
      description:
        "Codici errore (DTC) attivi della centralina, con descrizione in italiano, da quanto sono presenti e freeze frame " +
        "(valori al momento del guasto). Opzionale: includi quelli cancellati di recente.",
      inputSchema: {
        type: "object",
        properties: { includi_cancellati: { type: "boolean", description: "Includi gli ultimi codici cancellati (default no)" } },
        additionalProperties: false,
      },
      async run(args) {
        const t = now();
        const [active, status] = await Promise.all([repo.activeCodes(), repo.status()]);
        const out: Record<string, unknown> = {
          spia_motore_accesa: status?.milOn ?? null,
          numero_codici_attivi: active.length,
          codici_attivi: active.map((c) => codeView(c, t)),
        };
        if (args.includi_cancellati === true) {
          out.cancellati_di_recente = (await repo.recentClearedCodes(10)).map((c) => codeView(c, t));
        }
        if (active.length === 0) out.nota = "Nessun codice attivo registrato (se l'app non è collegata da tempo, l'informazione potrebbe non essere aggiornata).";
        return out;
      },
    },
    {
      name: "consumi",
      description:
        "Consumi dell'auto: ultimo viaggio, media sugli ultimi N giorni e andamento giorno per giorno (litri/100 km).",
      inputSchema: {
        type: "object",
        properties: { giorni: { type: "integer", minimum: 1, maximum: 365, description: "Periodo in giorni (default 30)" } },
        additionalProperties: false,
      },
      async run(args) {
        const giorni = intArg(args, "giorni", 30, 1, 365);
        const trips = await repo.trips(now() - giorni * DAY, 500);
        const last = trips[0];
        if (!last) return { periodo_giorni: giorni, viaggi: 0, nota: "Nessun viaggio registrato nel periodo.", nota_consumi: NOTA_CONSUMI };
        const valid = trips.filter((t) => t.km >= 0.5);
        const km = valid.reduce((a, t) => a + t.km, 0);
        const litri = valid.reduce((a, t) => a + t.fuel_l, 0);
        const perGiorno = new Map<string, { km: number; litri: number; viaggi: number }>();
        for (const t of valid) {
          const k = giorno(t.start_ms);
          const e = perGiorno.get(k) ?? { km: 0, litri: 0, viaggi: 0 };
          e.km += t.km; e.litri += t.fuel_l; e.viaggi += 1;
          perGiorno.set(k, e);
        }
        return {
          periodo_giorni: giorni,
          ultimo_viaggio: tripView(last),
          media_periodo: {
            viaggi: valid.length,
            km_totali: round(km, 1),
            litri_stimati: round(litri, 1),
            consumo_l_100km: km >= 1 ? round((litri / km) * 100, 1) : null,
          },
          andamento_giornaliero: [...perGiorno.entries()].sort().map(([g, e]) => ({
            giorno: g, viaggi: e.viaggi, km: round(e.km, 1), consumo_l_100km: e.km >= 0.5 ? round((e.litri / e.km) * 100, 1) : null,
          })),
          nota_consumi: NOTA_CONSUMI,
        };
      },
    },
    {
      name: "storico_viaggi",
      description: "Elenco dei viaggi recenti (data, durata, km, consumo, velocità massima), dal più recente.",
      inputSchema: {
        type: "object",
        properties: { limite: { type: "integer", minimum: 1, maximum: 50, description: "Quanti viaggi (default 10)" } },
        additionalProperties: false,
      },
      async run(args) {
        const limite = intArg(args, "limite", 10, 1, 50);
        const trips = await repo.trips(0, limite);
        return { viaggi: trips.map(tripView), nota: trips.length === 0 ? "Nessun viaggio registrato." : NOTA_CONSUMI };
      },
    },
    {
      name: "controllo_salute",
      description:
        "Controllo di salute dell'auto: confronta gli ultimi 7 giorni di dati con gli intervalli normali (temperatura liquido, " +
        "tensione/alternatore, correzioni carburante), elenca codici errore e monitor di prontezza incompleti. " +
        "Usalo per «c'è qualche problema?». I risultati sono indizi, non una diagnosi da officina.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
      async run() {
        const giorni = 7;
        const [snapshots, status, codes] = await Promise.all([
          repo.snapshotsSince(now() - giorni * DAY, 3000),
          repo.status(),
          repo.activeCodes(),
        ]);
        const s = evaluateHealth({ snapshots, status, codes, periodoGiorni: giorni });
        const last = snapshots[0] as SnapshotRow | undefined;
        return {
          ...s,
          ultimo_dato: last ? quando(last.ts) : null,
          nota: "Indicativo: basato su campioni ogni ~15 s e soglie generiche. Per una diagnosi sicura serve l'officina.",
        };
      },
    },
    {
      name: "rapporto_test_guida",
      description:
        "Rapporto dell'ultimo «test di guida diagnostico» (15–20 min): sonde O2, catalizzatore, miscela, batteria, " +
        "monitor di prontezza e codici prima/dopo. Restituisce il testo completo del rapporto.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
      async run() {
        const r = await repo.lastReport();
        if (!r) return { disponibile: false, nota: "Nessun test di guida registrato. Si avvia dall'app (pagina Codici)." };
        const altri = (await repo.reportList(6)).slice(1);
        return {
          disponibile: true,
          data: quando(r.start_ms),
          durata_minuti: minutiDa(r.start_ms, r.end_ms),
          rapporto: r.text,
          rapporti_precedenti: altri.map((x) => quando(x.start_ms)),
        };
      },
    },
  ];
}
