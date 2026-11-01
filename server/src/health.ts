// Controllo di salute: confronta i dati recenti con gli intervalli normali.
// Funzione pura (nessun accesso a database) così si prova con i test.

import { round, quando } from "./format.ts";
import type { DtcRow, SnapshotRow, StatusDoc } from "./repo.ts";
import { describeCode } from "./dtc.ts";

export type Livello = "critico" | "attenzione" | "info";
export type Problema = { livello: Livello; area: string; messaggio: string };
export type Salute = {
  esito: "ok" | "attenzione" | "critico" | "nessun_dato";
  problemi: Problema[];
  valori_nella_norma: string[];
  basato_su: { campioni_a_motore_acceso: number; periodo_giorni: number };
};

/** Soglie (stesse dell'app dove esistono). */
export const SOGLIE = {
  motoreAcceso: 400,
  liquidoAttenzione: 98,
  liquidoCritico: 105,
  caldo: 75,
  batteriaCritica: 11.5,
  batteriaBassa: 13.0, // a motore acceso sotto questo valore l'alternatore carica poco
  batteriaAlta: 14.8,
  batteriaAltaCritica: 15.2,
  correzioneAttenzione: 10,
  correzioneCritica: 20,
};

const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;

function parseValues(json: string): Record<string, number> {
  try {
    return JSON.parse(json) as Record<string, number>;
  } catch {
    return {};
  }
}

export function evaluateHealth(input: {
  snapshots: SnapshotRow[];
  status: StatusDoc | null;
  codes: DtcRow[];
  periodoGiorni: number;
}): Salute {
  const { snapshots, status, codes } = input;
  const problemi: Problema[] = [];
  const ok: string[] = [];
  const running = snapshots.filter((s) => (s.rpm ?? 0) >= SOGLIE.motoreAcceso);

  // --- temperatura del liquido
  const coolants = running.filter((s) => s.coolant !== null);
  if (coolants.length > 0) {
    const peak = coolants.reduce((a, b) => ((b.coolant ?? 0) > (a.coolant ?? 0) ? b : a));
    const max = peak.coolant as number;
    if (max >= SOGLIE.liquidoCritico) {
      problemi.push({ livello: "critico", area: "temperatura liquido", messaggio: `Il liquido ha toccato ${round(max, 0)} °C (${quando(peak.ts)}): rischio surriscaldamento.` });
    } else if (max >= SOGLIE.liquidoAttenzione) {
      problemi.push({ livello: "attenzione", area: "temperatura liquido", messaggio: `Il liquido ha raggiunto ${round(max, 0)} °C (${quando(peak.ts)}): alta, da tenere d'occhio.` });
    } else {
      ok.push(`Temperatura liquido: massimo ${round(max, 0)} °C.`);
    }
  }

  // --- batteria / alternatore (solo a motore acceso)
  const volts = running.map((s) => s.battery).filter((v): v is number => v !== null);
  if (volts.length >= 3) {
    // percentili invece di minimo/massimo assoluti: un campione preso durante l'avviamento non è un guasto
    const sorted = [...volts].sort((a, b) => a - b);
    const pct = (p: number) => sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * p))];
    const avg = mean(volts);
    const min = pct(0.05);
    const maxV = pct(0.95);
    const dettaglio = `media ${round(avg, 1)} V, minima tipica ${round(min, 1)} V, massima tipica ${round(maxV, 1)} V`;
    if (min < SOGLIE.batteriaCritica || maxV > SOGLIE.batteriaAltaCritica) {
      problemi.push({ livello: "critico", area: "batteria / alternatore", messaggio: `Tensione a motore acceso fuori limite (${dettaglio}).` });
    } else if (avg < SOGLIE.batteriaBassa) {
      problemi.push({ livello: "attenzione", area: "batteria / alternatore", messaggio: `A motore acceso la tensione è bassa (${dettaglio}): l'alternatore carica poco o la batteria è debole. Conviene un controllo con tester.` });
    } else if (avg > SOGLIE.batteriaAlta) {
      problemi.push({ livello: "attenzione", area: "batteria / alternatore", messaggio: `Tensione alta a motore acceso (${dettaglio}): regolatore dell'alternatore da controllare.` });
    } else {
      ok.push(`Carica dell'alternatore regolare (${dettaglio}).`);
    }
  }

  // --- correzioni carburante (motore caldo), al minimo e in marcia separate: un difetto può vedersi solo al minimo
  const warm = running.filter((s) => (s.coolant ?? 0) >= SOGLIE.caldo);
  const trimsOf = (group: SnapshotRow[]) =>
    group.map((s) => parseValues(s.values_json)).filter((v) => typeof v["06"] === "number" && typeof v["07"] === "number");
  const idleWarm = warm.filter((s) => (s.speed ?? 1) === 0);
  const moving = warm.filter((s) => (s.speed ?? 0) > 0);
  // --- al minimo la sonda a monte dovrebbe oscillare; se resta sempre sul ricco la miscela è ricca
  let richIdle = false;
  const o2Idle = idleWarm.map((s) => parseValues(s.values_json)["14"]).filter((v): v is number => typeof v === "number");
  if (o2Idle.length >= 5) {
    const richShare = o2Idle.filter((v) => v > 0.7).length / o2Idle.length;
    if (richShare >= 0.9) {
      richIdle = true;
      problemi.push({
        livello: "attenzione",
        area: "sonda lambda / miscela",
        messaggio: `Al minimo la sonda a monte resta sul ricco (media ${round(mean(o2Idle), 2)} V nel ${Math.round(richShare * 100)} % dei campioni) invece di oscillare: miscela ricca al minimo, coerente con P0172.`,
      });
    }
  }

  let trimProblem = false;
  let idleTrimNote = "";
  const dettagli: string[] = [];
  for (const [dove, group] of [["al minimo", idleWarm], ["in marcia", moving]] as const) {
    const t = trimsOf(group);
    if (t.length < 3) continue;
    const stft = mean(t.map((v) => v["06"]));
    const ltft = mean(t.map((v) => v["07"]));
    const tot = stft + ltft;
    const dett = `breve ${round(stft, 1)} %, lunga ${round(ltft, 1)} %, totale ${round(tot, 1)} %`;
    if (!(dove === "al minimo" && richIdle)) dettagli.push(`${dove}: ${dett}`);
    else idleTrimNote = ` Correzione al minimo: ${dett}.`;
    const abs = Math.abs(tot);
    if (abs >= SOGLIE.correzioneAttenzione) {
      trimProblem = true;
      problemi.push({
        livello: abs >= SOGLIE.correzioneCritica ? "critico" : "attenzione",
        area: "miscela / correzioni carburante",
        messaggio: tot < 0
          ? `${dove[0].toUpperCase()}${dove.slice(1)} la centralina toglie carburante (${dett}): miscela ricca ${dove}, coerente con P0172.`
          : `${dove[0].toUpperCase()}${dove.slice(1)} la centralina aggiunge carburante (${dett}): miscela povera o aria falsa (tipo P0171).`,
      });
    }
  }
  if (!trimProblem && dettagli.length > 0) ok.push(`Correzioni carburante nella norma (${dettagli.join("; ")}).`);
  if (idleTrimNote) {
    const p = problemi.find((x) => x.area === "sonda lambda / miscela");
    if (p) p.messaggio += idleTrimNote;
  }

  // --- spia e codici
  for (const c of codes) {
    const d = describeCode(c.code);
    problemi.push({
      livello: "attenzione",
      area: "codici errore",
      messaggio: `${c.code} (${c.kind}) — ${d.descrizione ?? d.categoria}. Presente dal ${quando(c.first_seen_ms)}.`,
    });
  }
  if (status?.milOn === true && codes.length === 0) {
    problemi.push({ livello: "attenzione", area: "codici errore", messaggio: "La spia motore risulta accesa ma non ho codici registrati: rileggere i codici dall'app." });
  }
  if (codes.length === 0 && status?.milOn === false) ok.push("Nessun codice errore attivo, spia spenta.");

  // --- test della centralina (modo 06)
  for (const t of status?.monitorTests ?? []) {
    if (t.passed === false) {
      problemi.push({ livello: "attenzione", area: "test centralina", messaggio: `${t.name}: test non superato (valore ${t.value}, limiti ${t.min}…${t.max}).` });
    } else if (t.passed === true) {
      ok.push(`${t.name}: test della centralina superato.`);
    }
  }

  // --- monitor di prontezza
  const incompleti = (status?.readiness ?? []).filter((m) => !m.complete).map((m) => m.name);
  if (incompleti.length > 0) {
    problemi.push({
      livello: "info",
      area: "monitor di prontezza",
      messaggio: `Monitor non ancora completati: ${incompleti.join(", ")}. La centralina non ha finito di valutarli (serve una guida a caldo, vedi il test di guida).`,
    });
  } else if (status?.readiness && status.readiness.length > 0) {
    ok.push("Tutti i monitor di prontezza sono completati.");
  }

  const esito: Salute["esito"] =
    snapshots.length === 0 && !status
      ? "nessun_dato"
      : problemi.some((p) => p.livello === "critico") ? "critico"
      : problemi.some((p) => p.livello === "attenzione") ? "attenzione"
      : "ok";

  const peso = { critico: 0, attenzione: 1, info: 2 } as const;
  problemi.sort((a, b) => peso[a.livello] - peso[b.livello]);
  return {
    esito,
    problemi,
    valori_nella_norma: ok,
    basato_su: { campioni_a_motore_acceso: running.length, periodo_giorni: input.periodoGiorni },
  };
}
