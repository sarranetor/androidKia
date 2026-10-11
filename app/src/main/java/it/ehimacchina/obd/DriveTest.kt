package it.ehimacchina.obd

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class FindingLevel { OK, INFO, ATTENZIONE }

data class Finding(val level: FindingLevel, val text: String)

/** Una condizione utile al test (es. "liquido ≥ 80 °C") con lo stato di avanzamento. */
data class Criterion(val label: String, val done: Boolean, val progress: String)

data class DriveReport(val startMs: Long, val endMs: Long, val findings: List<Finding>, val text: String)

/**
 * Analizza una guida di 15-20 minuti registrata a ~1 Hz. Dà INDIZI, non verdetti: i limiti
 * sono quelli classici da officina, applicati a campioni lenti (~1 Hz), quindi si usano
 * ampiezze e deviazioni standard e non la frequenza di oscillazione delle sonde.
 * Nessuna dipendenza da Android.
 */
class DriveTestAnalyzer(val startMs: Long) {
    private class Sample(val t: Long, val v: Map<Int, Double>, val battery: Double?)

    private val samples = ArrayList<Sample>()

    /** Monitor di prontezza all'inizio e ultimo letto */
    var readinessStart: Readiness? = null
    var readinessNow: Readiness? = null

    fun add(tMs: Long, values: Map<Int, Double>, battery: Double?) {
        samples += Sample(tMs, values, battery)
    }

    val sampleCount get() = samples.size

    // ---------------------------------------------------------------- strumenti

    /** Durata (s) che ogni campione "rappresenta", con tetto per i buchi. */
    private fun dtSec(i: Int): Double =
        if (i + 1 < samples.size) min(samples[i + 1].t - samples[i].t, 5000L) / 1000.0 else 0.0

    private fun seconds(pred: (Sample) -> Boolean): Double =
        samples.indices.sumOf { if (pred(samples[it])) dtSec(it) else 0.0 }

    private class Stats(val n: Int, val mean: Double, val std: Double, val lo: Double, val hi: Double) {
        val swing get() = hi - lo
    }

    /** Statistiche con 5° e 95° percentile come minimo/massimo, per ignorare i picchi isolati. */
    private fun stats(values: List<Double>): Stats? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mean = values.average()
        val std = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        val lo = sorted[((sorted.size - 1) * 0.05).toInt()]
        val hi = sorted[((sorted.size - 1) * 0.95).toInt()]
        return Stats(values.size, mean, std, lo, hi)
    }

    private fun Sample.get(pid: Int) = v[pid]
    private fun isWarm(s: Sample) = (s.get(0x05) ?: 0.0) >= WARM_C
    private fun isRunning(s: Sample) = (s.get(0x0C) ?: 0.0) >= Alerts.ENGINE_RUNNING_RPM
    private fun isCruise(s: Sample) = isRunning(s) && isWarm(s) && (s.get(0x0D) ?: 0.0) in 50.0..110.0
    private fun isWarmIdle(s: Sample) =
        isRunning(s) && (s.get(0x0D) ?: 0.0) == 0.0 && (s.get(0x05) ?: 0.0) >= 70.0 && (s.get(0x0C) ?: 0.0) <= 1300.0

    private fun maxOf(pid: Int): Double? = samples.mapNotNull { it.get(pid) }.maxOrNull()

    /** Primo istante in cui la sonda a monte oscilla con ampiezza ≥ 0.3 V su 15 campioni: anello chiuso. */
    private fun closedLoopAtMs(): Long? {
        val window = ArrayDeque<Double>()
        for (s in samples) {
            val v = s.get(0x14) ?: continue
            window.addLast(v)
            if (window.size > 15) window.removeFirst()
            if (window.size == 15 && window.max() - window.min() >= 0.3) return s.t
        }
        return null
    }

    private fun timeTo(pid: Int, atLeast: Double): Long? =
        samples.firstOrNull { (it.get(pid) ?: Double.NEGATIVE_INFINITY) >= atLeast }?.t

    private fun km(): Double =
        samples.indices.sumOf { (samples[it].get(0x0D) ?: 0.0) * dtSec(it) / 3600.0 }

    // ---------------------------------------------------------------- avanzamento

    fun criteria(nowMs: Long, hasCatalystTemp: Boolean): List<Criterion> {
        val elapsed = nowMs - startMs
        val cruiseSec = cruiseStableSeconds()
        val idleSec = seconds { isWarmIdle(it) }
        val list = mutableListOf(
            Criterion("Durata almeno 15 minuti", elapsed >= 15 * 60_000L, "${mmss(elapsed)} / 15:00"),
            Criterion("Liquido a 80 °C o più", (maxOf(0x05) ?: 0.0) >= 80.0, "max %.0f °C".format(maxOf(0x05) ?: 0.0)),
            Criterion("Anello chiuso (sonda a monte attiva)", closedLoopAtMs() != null, if (closedLoopAtMs() != null) "raggiunto" else "non ancora"),
            Criterion("Crociera 50–100 km/h costante per 5 min", cruiseSec >= 300, "${mmss((cruiseSec * 1000).toLong())} / 5:00"),
            Criterion("Minimo a caldo per 2 min (fermo)", idleSec >= 120, "${mmss((idleSec * 1000).toLong())} / 2:00"),
        )
        if (hasCatalystTemp) {
            list += Criterion("Catalizzatore a 400 °C o più", (maxOf(0x3C) ?: 0.0) >= 400.0, "max %.0f °C".format(maxOf(0x3C) ?: 0.0))
        }
        return list
    }

    /** Secondi di crociera "costante": velocità 50–100 km/h con variazioni piccole fra campioni. */
    private fun cruiseStableSeconds(): Double {
        var total = 0.0
        for (i in 1 until samples.size) {
            val s = samples[i]
            val speed = s.get(0x0D) ?: continue
            val prev = samples[i - 1].get(0x0D) ?: continue
            if (isRunning(s) && speed in 50.0..100.0 && abs(speed - prev) <= 8.0) total += dtSec(i - 1)
        }
        return total
    }

    // ---------------------------------------------------------------- rapporto

    fun report(endMs: Long, startDiag: Diagnosis?, endDiag: Diagnosis?): DriveReport {
        val durationMs = endMs - startMs
        val minutes = durationMs / 60_000.0
        val findings = mutableListOf<Finding>()
        fun add(level: FindingLevel, text: String) { findings += Finding(level, text) }
        val data = mutableListOf<String>()

        val cruise = samples.filter { isCruise(it) }
        val warm = samples.filter { isWarm(it) && isRunning(it) }

        // --- durata
        if (minutes < 10) add(FindingLevel.INFO, "Test breve (%.0f min): le conclusioni sono poco affidabili. Ripeti con 15–20 min di guida.".format(minutes))
        val kmDone = km()
        data += "Durata %s · %.1f km · velocità max %.0f km/h".format(mmss(durationMs), kmDone, maxOf(0x0D) ?: 0.0)

        // --- temperatura liquido
        val coolantStart = samples.firstNotNullOfOrNull { it.get(0x05) }
        val coolantMax = maxOf(0x05)
        val t80 = timeTo(0x05, 80.0)
        if (coolantMax != null) {
            data += "Liquido: partenza %.0f °C, massimo %.0f °C, %s".format(
                coolantStart ?: 0.0, coolantMax, t80?.let { "80 °C dopo ${mmss(it - startMs)}" } ?: "80 °C non raggiunti",
            )
            when {
                t80 != null -> add(FindingLevel.OK, "Motore a temperatura di esercizio dopo ${mmss(t80 - startMs)}.")
                minutes >= 15 -> add(FindingLevel.ATTENZIONE, "Dopo %.0f min il liquido è arrivato solo a %.0f °C: possibile termostato bloccato aperto (o giornata molto fredda).".format(minutes, coolantMax))
                else -> add(FindingLevel.INFO, "Il liquido non ha raggiunto 80 °C: test troppo breve per le prove sul catalizzatore.")
            }
        }

        // --- anello chiuso e sonda a monte
        val loopAt = closedLoopAtMs()
        val o2pre = stats(warm.mapNotNull { it.get(0x14) })
        if (loopAt != null) {
            add(FindingLevel.OK, "Anello chiuso raggiunto dopo ${mmss(loopAt - startMs)} (la sonda a monte oscilla).")
            data += "Anello chiuso dopo ${mmss(loopAt - startMs)}"
        } else if (samples.any { it.get(0x14) != null } && minutes >= 5 && warm.size > 120) {
            add(FindingLevel.ATTENZIONE, "La sonda a monte non oscilla (ampiezza < 0.3 V) anche a motore caldo: sonda lenta o ferma, oppure centralina in anello aperto.")
        }
        if (o2pre != null && o2pre.n >= 60) {
            data += "Sonda O2 a monte (caldo): media %.2f V, deviazione %.2f V, ampiezza %.2f–%.2f V".format(o2pre.mean, o2pre.std, o2pre.lo, o2pre.hi)
            if (o2pre.swing < 0.4 && loopAt != null) {
                add(FindingLevel.ATTENZIONE, "Sonda a monte poco reattiva (ampiezza %.2f V): compatibile con sonda invecchiata (codici tipo P0133).".format(o2pre.swing))
            }
        }

        // --- efficienza catalizzatore: confronto sonda a valle / a monte in crociera calda
        val pre = stats(cruise.mapNotNull { it.get(0x14) })
        val post = stats(cruise.mapNotNull { it.get(0x15) })
        if (pre != null && post != null && min(pre.n, post.n) >= 60) {
            data += "Crociera calda: sonda a valle media %.2f V, deviazione %.2f V (a monte %.2f V)".format(post.mean, post.std, pre.std)
            when {
                post.std < 0.02 && post.mean in 0.43..0.48 ->
                    add(FindingLevel.INFO, "La sonda a valle resta ferma a ~0.45 V: sonda non attiva o guasta. Con questo dato non posso valutare il catalizzatore (P0420 resta da verificare in officina).")
                pre.std < 0.05 ->
                    add(FindingLevel.INFO, "La sonda a monte è troppo ferma in crociera per confrontare le due sonde.")
                else -> {
                    val ratio = post.std / pre.std
                    data += "Rapporto oscillazione valle/monte: %.2f".format(ratio)
                    when {
                        ratio < 0.4 -> add(FindingLevel.OK, "Catalizzatore: la sonda a valle è molto più stabile di quella a monte (rapporto %.2f): efficienza buona.".format(ratio))
                        ratio < 0.7 -> add(FindingLevel.INFO, "Catalizzatore: rapporto %.2f, zona intermedia; da tenere d'occhio, ripeti il test.".format(ratio))
                        else -> add(FindingLevel.ATTENZIONE, "Catalizzatore: la sonda a valle segue quella a monte (rapporto %.2f): efficienza bassa, compatibile con P0420. Da far verificare in officina.".format(ratio))
                    }
                }
            }
        } else {
            add(FindingLevel.INFO, "Dati insufficienti per valutare il catalizzatore: servono almeno ~1 min a 50–110 km/h con motore caldo (e le sonde O2 supportate).")
        }

        // --- temperatura catalizzatore
        val catMax = maxOf(0x3C)
        if (catMax != null) {
            data += "Catalizzatore: massimo %.0f °C".format(catMax)
            if (catMax < 350) add(FindingLevel.INFO, "Il catalizzatore è arrivato a soli %.0f °C: troppo freddo per una prova valida (serve oltre 400 °C).".format(catMax))
        }

        // --- correzioni carburante
        fun trimTotal(group: List<Sample>) = stats(group.mapNotNull { s ->
            val st = s.get(0x06); val lt = s.get(0x07)
            if (st != null && lt != null) st + lt else null
        })
        val trimCruise = trimTotal(cruise)
        val trimIdle = trimTotal(samples.filter { isWarmIdle(it) })
        val ltft = stats(warm.mapNotNull { it.get(0x07) })
        trimIdle?.let { data += "Correzione carburante totale al minimo: %+.1f %%".format(it.mean) }
        trimCruise?.let { data += "Correzione carburante totale in crociera: %+.1f %%".format(it.mean) }
        ltft?.let { data += "Correzione di lungo periodo (LTFT): %+.1f %%".format(it.mean) }
        val trimMain = trimCruise?.takeIf { it.n >= 30 } ?: trimIdle?.takeIf { it.n >= 30 }
        if (trimMain != null) {
            when {
                trimMain.mean <= -10 -> add(FindingLevel.ATTENZIONE, "La centralina toglie in media %.0f %% di carburante: coerente con miscela ricca (P0172): iniettori, sonda, pressione benzina o EVAP.".format(-trimMain.mean))
                trimMain.mean >= 10 -> add(FindingLevel.ATTENZIONE, "La centralina aggiunge in media %.0f %% di carburante: miscela povera o aria falsa.".format(trimMain.mean))
                else -> add(FindingLevel.OK, "Correzioni carburante nella norma (%+.1f %% in media): in questo test non vedo miscela ricca/povera persistente.".format(trimMain.mean))
            }
        }

        // --- batteria / alternatore (dopo il primo minuto a motore acceso)
        val firstRun = samples.firstOrNull { isRunning(it) }?.t
        val batt = stats(samples.filter { s -> firstRun != null && s.t - firstRun > 60_000 && isRunning(s) }.mapNotNull { it.battery })
        if (batt != null && batt.n >= 5) {
            data += "Tensione a motore acceso: media %.1f V, minima %.1f V".format(batt.mean, batt.lo)
            when {
                batt.mean < 13.0 -> add(FindingLevel.ATTENZIONE, "Tensione media %.1f V a motore acceso: l'alternatore carica poco (o lettura imprecisa dell'adattatore). Fai misurare con un tester.".format(batt.mean))
                batt.mean > 14.8 -> add(FindingLevel.ATTENZIONE, "Tensione media %.1f V: troppo alta, regolatore dell'alternatore da controllare.".format(batt.mean))
                else -> add(FindingLevel.OK, "Carica dell'alternatore regolare (%.1f V in media).".format(batt.mean))
            }
        }

        // --- monitor di prontezza
        val before = readinessStart
        val after = readinessNow
        val monitorLines = mutableListOf<String>()
        if (after != null) {
            for (m in after.monitors) {
                val was = before?.monitors?.firstOrNull { it.name == m.name }
                val line = when {
                    m.complete && was != null && !was.complete -> {
                        add(FindingLevel.OK, "Monitor «${m.name}» completato durante il test.")
                        "${m.name}: completato (era incompleto)"
                    }
                    m.complete -> "${m.name}: completato"
                    else -> {
                        add(FindingLevel.INFO, "Monitor «${m.name}» ancora incompleto: la centralina non ha finito la valutazione (serve guida a caldo con le condizioni sopra).")
                        "${m.name}: INCOMPLETO"
                    }
                }
                monitorLines += line
            }
        }

        // --- codici errore prima/dopo
        val codeLines = mutableListOf<String>()
        if (startDiag != null && endDiag != null && startDiag.stored != null && endDiag.stored != null) {
            val s0 = (startDiag.stored.orEmpty() + startDiag.pending.orEmpty()).toSet()
            val s1 = (endDiag.stored.orEmpty() + endDiag.pending.orEmpty()).toSet()
            val new = s1 - s0
            val gone = s0 - s1
            if (new.isNotEmpty()) add(FindingLevel.ATTENZIONE, "Codici NUOVI comparsi durante il test: ${new.joinToString()}.")
            if (gone.isNotEmpty()) add(FindingLevel.OK, "Codici spariti durante il test: ${gone.joinToString()}.")
            if (new.isEmpty() && gone.isEmpty()) {
                if (s1.isEmpty()) add(FindingLevel.OK, "Nessun codice errore prima e dopo il test.")
                else add(FindingLevel.INFO, "Codici invariati: ${s1.joinToString()}.")
            }
            codeLines += "Prima: ${s0.joinToString().ifEmpty { "nessuno" }} — Dopo: ${s1.joinToString().ifEmpty { "nessuno" }}"
        } else {
            codeLines += "Codici non confrontabili (lettura mancante)."
        }

        val order = compareBy<Finding> { when (it.level) { FindingLevel.ATTENZIONE -> 0; FindingLevel.INFO -> 1; FindingLevel.OK -> 2 } }
        val sorted = findings.sortedWith(order)
        return DriveReport(startMs, endMs, sorted, buildText(startMs, durationMs, sorted, data, monitorLines, codeLines))
    }

    private fun buildText(
        start: Long, durationMs: Long, findings: List<Finding>,
        data: List<String>, monitors: List<String>, codes: List<String>,
    ): String = buildString {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(start))
        appendLine("TEST DI GUIDA DIAGNOSTICO — $date — durata ${mmss(durationMs)}")
        appendLine()
        appendLine("INDIZI (non sono verdetti)")
        if (findings.isEmpty()) appendLine("  nessuno")
        findings.forEach {
            val tag = when (it.level) { FindingLevel.ATTENZIONE -> "ATTENZIONE"; FindingLevel.INFO -> "info      "; FindingLevel.OK -> "ok        " }
            appendLine("  [$tag] ${it.text}")
        }
        appendLine()
        appendLine("DATI")
        data.forEach { appendLine("  $it") }
        if (monitors.isNotEmpty()) {
            appendLine()
            appendLine("MONITOR DI PRONTEZZA")
            monitors.forEach { appendLine("  $it") }
        }
        appendLine()
        appendLine("CODICI ERRORE")
        codes.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Nota: campioni a ~1 Hz e soglie indicative da officina. Il rapporto dice dove guardare, non cosa sostituire.")
    }

    companion object {
        const val WARM_C = 75.0

        fun mmss(ms: Long): String {
            val s = max(0L, ms) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
