// Accesso ai dati. Gli strumenti dipendono dall'interfaccia `Repo`, non da D1:
// nei test si usa una versione in memoria.

export type SnapshotRow = {
  ts: number; rpm: number | null; speed: number | null; coolant: number | null; load: number | null;
  map: number | null; iat: number | null; throttle: number | null; battery: number | null;
  lph: number | null; trip_km: number | null; values_json: string;
};
export type TripRow = { start_ms: number; end_ms: number; km: number; fuel_l: number; max_speed: number };
export type DtcRow = {
  code: string; first_seen_ms: number; kind: string; last_seen_ms: number; cleared_ms: number | null; freeze: string | null;
};
export type ReportRow = { start_ms: number; end_ms: number; text: string };
export type StatusDoc = {
  info: string | null; supported: number[]; calibration: number | null; milOn: boolean | null;
  readiness: { name: string; complete: boolean }[] | null; ts: number;
};

export interface Repo {
  latestSnapshot(): Promise<SnapshotRow | null>;
  snapshotsSince(sinceMs: number, limit: number): Promise<SnapshotRow[]>;
  status(): Promise<StatusDoc | null>;
  activeCodes(): Promise<DtcRow[]>;
  recentClearedCodes(limit: number): Promise<DtcRow[]>;
  /** Dal più recente; solo viaggi con inizio >= sinceMs */
  trips(sinceMs: number, limit: number): Promise<TripRow[]>;
  lastReport(): Promise<ReportRow | null>;
  /** Elenco (senza testo) dei rapporti, dal più recente */
  reportList(limit: number): Promise<Pick<ReportRow, "start_ms" | "end_ms">[]>;
}

export class D1Repo implements Repo {
  private db: D1Database;
  constructor(db: D1Database) { this.db = db; }

  latestSnapshot() {
    return this.db.prepare(`SELECT * FROM snapshots ORDER BY ts DESC LIMIT 1`).first<SnapshotRow>();
  }
  async snapshotsSince(sinceMs: number, limit: number) {
    const r = await this.db.prepare(`SELECT * FROM snapshots WHERE ts >= ? ORDER BY ts DESC LIMIT ?`).bind(sinceMs, limit).all<SnapshotRow>();
    return r.results;
  }
  async status() {
    const row = await this.db.prepare(`SELECT value FROM meta WHERE key='status'`).first<{ value: string }>();
    return row ? (JSON.parse(row.value) as StatusDoc) : null;
  }
  async activeCodes() {
    const r = await this.db.prepare(`SELECT * FROM dtc WHERE cleared_ms IS NULL ORDER BY first_seen_ms`).all<DtcRow>();
    return r.results;
  }
  async recentClearedCodes(limit: number) {
    const r = await this.db.prepare(`SELECT * FROM dtc WHERE cleared_ms IS NOT NULL ORDER BY cleared_ms DESC LIMIT ?`).bind(limit).all<DtcRow>();
    return r.results;
  }
  async trips(sinceMs: number, limit: number) {
    const r = await this.db.prepare(`SELECT * FROM trips WHERE start_ms >= ? ORDER BY start_ms DESC LIMIT ?`).bind(sinceMs, limit).all<TripRow>();
    return r.results;
  }
  lastReport() {
    return this.db.prepare(`SELECT * FROM drive_reports ORDER BY start_ms DESC LIMIT 1`).first<ReportRow>();
  }
  async reportList(limit: number) {
    const r = await this.db.prepare(`SELECT start_ms, end_ms FROM drive_reports ORDER BY start_ms DESC LIMIT ?`).bind(limit).all<{ start_ms: number; end_ms: number }>();
    return r.results;
  }
}
