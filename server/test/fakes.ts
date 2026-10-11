import type { DtcRow, ReportRow, Repo, SnapshotRow, StatusDoc, TripRow } from "../src/repo.ts";

export const NOW = Date.UTC(2026, 9, 11, 12, 0, 0); // 11/10/2026 14:00 ora italiana

export function snap(over: Partial<SnapshotRow> & { ts: number }, values: Record<string, number> = {}): SnapshotRow {
  return {
    rpm: 2000, speed: 70, coolant: 88, load: 30, map: 50, iat: 20, throttle: 15, battery: 13.7, lph: 4.5, trip_km: 1.2,
    values_json: JSON.stringify(values), ...over,
  };
}

export class FakeRepo implements Repo {
  snapshots: SnapshotRow[] = [];
  statusDoc: StatusDoc | null = null;
  codes: DtcRow[] = [];
  tripRows: TripRow[] = [];
  reports: ReportRow[] = [];

  async latestSnapshot() { return [...this.snapshots].sort((a, b) => b.ts - a.ts)[0] ?? null; }
  async snapshotsSince(since: number, limit: number) {
    return this.snapshots.filter((s) => s.ts >= since).sort((a, b) => b.ts - a.ts).slice(0, limit);
  }
  async status() { return this.statusDoc; }
  async activeCodes() { return this.codes.filter((c) => c.cleared_ms === null); }
  async recentClearedCodes(limit: number) { return this.codes.filter((c) => c.cleared_ms !== null).slice(0, limit); }
  async trips(since: number, limit: number) {
    return this.tripRows.filter((t) => t.start_ms >= since).sort((a, b) => b.start_ms - a.start_ms).slice(0, limit);
  }
  async lastReport() { return [...this.reports].sort((a, b) => b.start_ms - a.start_ms)[0] ?? null; }
  async reportList(limit: number) {
    return [...this.reports].sort((a, b) => b.start_ms - a.start_ms).slice(0, limit).map((r) => ({ start_ms: r.start_ms, end_ms: r.end_ms }));
  }
}

export const dtc = (code: string, over: Partial<DtcRow> = {}): DtcRow => ({
  code, first_seen_ms: NOW - 3 * 86400000, kind: "memorizzato", last_seen_ms: NOW - 3600000, cleared_ms: null, freeze: null, ...over,
});
