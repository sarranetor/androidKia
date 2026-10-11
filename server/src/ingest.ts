// Validazione degli eventi ricevuti dall'app e costruzione delle query SQL.
// Funzioni pure: non toccano il database, così si provano con `npm test`.

export type Stmt = { sql: string; params: (string | number | null)[] };

export type Rejected = { index: number; reason: string };

export const MAX_EVENTS = 200;
export const MAX_BODY_BYTES = 256 * 1024;
export const SNAPSHOT_RETENTION_MS = 30 * 24 * 3600 * 1000;

type Obj = Record<string, unknown>;

const isObj = (x: unknown): x is Obj => typeof x === "object" && x !== null && !Array.isArray(x);
const isNum = (x: unknown): x is number => typeof x === "number" && Number.isFinite(x);
const numOrNull = (x: unknown): number | null => (isNum(x) ? x : null);
const isStr = (x: unknown, max: number): x is string => typeof x === "string" && x.length <= max;

// PID (esadecimale a 2 cifre) → colonna della tabella snapshots
const SNAPSHOT_COLUMNS: Record<string, string> = {
  "0C": "rpm", "0D": "speed", "05": "coolant", "04": "load", "0B": "map", "0F": "iat", "11": "throttle",
};

/** Costruisce lo statement di un evento, oppure ritorna il motivo per cui è da scartare. */
export function buildStatement(ev: unknown): Stmt | string {
  if (!isObj(ev)) return "evento non è un oggetto";
  switch (ev.type) {
    case "snapshot": {
      if (!isNum(ev.ts) || ev.ts <= 0) return "snapshot: ts mancante";
      if (!isObj(ev.values)) return "snapshot: values mancante";
      const values: Record<string, number> = {};
      for (const [k, v] of Object.entries(ev.values)) {
        if (/^[0-9A-F]{2}$/.test(k) && isNum(v)) values[k] = v;
      }
      const col = (pid: string) => (pid in values ? values[pid] : null);
      const trip = isObj(ev.trip) ? ev.trip : null;
      return {
        sql: `INSERT OR IGNORE INTO snapshots
              (ts, rpm, speed, coolant, load, map, iat, throttle, battery, lph, trip_km, values_json)
              VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
        params: [
          ev.ts, col("0C"), col("0D"), col("05"), col("04"), col("0B"), col("0F"), col("11"),
          numOrNull(ev.battery), numOrNull(ev.lph), trip ? numOrNull(trip.km) : null,
          JSON.stringify(values),
        ],
      };
    }
    case "trip": {
      if (!isNum(ev.startMs) || !isNum(ev.endMs) || !isNum(ev.km) || !isNum(ev.fuelL)) return "trip: campi mancanti";
      return {
        sql: `INSERT INTO trips (start_ms, end_ms, km, fuel_l, max_speed) VALUES (?,?,?,?,?)
              ON CONFLICT(start_ms) DO UPDATE SET end_ms=excluded.end_ms, km=excluded.km,
                fuel_l=excluded.fuel_l, max_speed=excluded.max_speed`,
        params: [ev.startMs, ev.endMs, ev.km, ev.fuelL, numOrNull(ev.maxSpeed) ?? 0],
      };
    }
    case "dtc": {
      if (!isStr(ev.code, 8) || !/^[PCBU][0-9A-F]{4}$/.test(ev.code)) return "dtc: codice non valido";
      if (!isNum(ev.firstSeenMs) || !isNum(ev.lastSeenMs)) return "dtc: date mancanti";
      return {
        sql: `INSERT INTO dtc (code, first_seen_ms, kind, last_seen_ms, cleared_ms, freeze)
              VALUES (?,?,?,?,?,?)
              ON CONFLICT(code, first_seen_ms) DO UPDATE SET kind=excluded.kind,
                last_seen_ms=excluded.last_seen_ms, cleared_ms=excluded.cleared_ms,
                freeze=COALESCE(excluded.freeze, dtc.freeze)`,
        params: [
          ev.code, ev.firstSeenMs, isStr(ev.kind, 20) ? ev.kind : "memorizzato", ev.lastSeenMs,
          numOrNull(ev.clearedMs), isStr(ev.freeze, 2000) ? ev.freeze : null,
        ],
      };
    }
    case "drive_report": {
      if (!isNum(ev.startMs) || !isNum(ev.endMs) || !isStr(ev.text, 20000)) return "drive_report: campi mancanti";
      return {
        sql: `INSERT OR IGNORE INTO drive_reports (start_ms, end_ms, text) VALUES (?,?,?)`,
        params: [ev.startMs, ev.endMs, ev.text],
      };
    }
    case "status": {
      if (!isNum(ev.ts)) return "status: ts mancante";
      // si tiene solo ciò che serve, con limiti di dimensione
      const status = {
        info: isStr(ev.info, 200) ? ev.info : null,
        supported: Array.isArray(ev.supported) ? ev.supported.filter(isNum).slice(0, 100) : [],
        calibration: numOrNull(ev.calibration),
        milOn: typeof ev.milOn === "boolean" ? ev.milOn : null,
        readiness: Array.isArray(ev.readiness)
          ? ev.readiness
              .filter((m): m is Obj => isObj(m) && isStr(m.name, 60) && typeof m.complete === "boolean")
              .slice(0, 20)
              .map((m) => ({ name: m.name, complete: m.complete }))
          : null,
        ts: ev.ts,
      };
      return {
        sql: `INSERT INTO meta (key, value, updated_at) VALUES ('status', ?, ?)
              ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at`,
        params: [JSON.stringify(status), ev.ts],
      };
    }
    default:
      return "tipo di evento sconosciuto";
  }
}

export type BatchResult = { statements: Stmt[]; accepted: number; rejected: Rejected[] };

/** Valida l'intero corpo ({events:[...]}) e prepara le query. `now` serve a pulire i dati vecchi. */
export function buildBatch(body: unknown, now: number): BatchResult | { error: string } {
  if (!isObj(body) || !Array.isArray(body.events)) return { error: "atteso {events:[...]}" };
  if (body.events.length > MAX_EVENTS) return { error: `troppi eventi (max ${MAX_EVENTS})` };

  const statements: Stmt[] = [];
  const rejected: Rejected[] = [];
  let hasSnapshot = false;
  body.events.forEach((ev, index) => {
    const result = buildStatement(ev);
    if (typeof result === "string") rejected.push({ index, reason: result });
    else {
      statements.push(result);
      if (isObj(ev) && ev.type === "snapshot") hasSnapshot = true;
    }
  });
  const accepted = statements.length;
  if (accepted > 0) {
    statements.push({
      sql: `INSERT INTO meta (key, value, updated_at) VALUES ('last_ingest', ?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at`,
      params: [String(now), now],
    });
  }
  if (hasSnapshot) {
    statements.push({ sql: `DELETE FROM snapshots WHERE ts < ?`, params: [now - SNAPSHOT_RETENTION_MS] });
  }
  return { statements, accepted, rejected };
}
