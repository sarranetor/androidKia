const dateTime = new Intl.DateTimeFormat("it-IT", { timeZone: "Europe/Rome", dateStyle: "short", timeStyle: "short" });
const dayKey = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome", year: "numeric", month: "2-digit", day: "2-digit" });

/** "11/10/26, 14:30" in ora italiana */
export const quando = (ms: number): string => dateTime.format(new Date(ms));

/** "2026-10-11" in ora italiana (per raggruppare per giorno) */
export const giorno = (ms: number): string => dayKey.format(new Date(ms));

export const round = (x: number | null | undefined, digits = 1): number | null =>
  x === null || x === undefined || !Number.isFinite(x) ? null : Math.round(x * 10 ** digits) / 10 ** digits;

export const minutiDa = (startMs: number, endMs: number): number => Math.max(0, Math.round((endMs - startMs) / 60000));

/** "3 minuti", "2 ore e 5 minuti", "4 giorni" */
export function eta(secondi: number): string {
  const s = Math.max(0, Math.round(secondi));
  if (s < 90) return `${s} secondi`;
  const m = Math.round(s / 60);
  if (m < 90) return `${m} minuti`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h} ore e ${m % 60} minuti`;
  return `${Math.round(h / 24)} giorni`;
}
