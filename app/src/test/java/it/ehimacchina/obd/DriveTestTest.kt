package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.min
import kotlin.math.sin

class ReadinessTest {
    @Test fun realCeedMonitors() {
        // risposta reale a 0101: 41 01 82 07 61 01 → dati 82 07 61 01
        val r = Readiness.parse(intArrayOf(0x82, 0x07, 0x61, 0x01))!!
        assertTrue(r.milOn)
        assertEquals(2, r.dtcCount)
        assertEquals(6, r.monitors.size)
        assertEquals(listOf("Catalizzatore"), r.incomplete.map { it.name })
        assertTrue(r.monitors.first { it.name == "Sonda O2" }.complete)
        assertTrue(r.monitors.first { it.name == "Accensione (misfire)" }.complete)
    }

    @Test fun afterClearEverythingIncomplete() {
        val r = Readiness.parse(intArrayOf(0x00, 0x77, 0x61, 0x61))!!
        assertFalse(r.milOn)
        assertEquals(6, r.incomplete.size)
    }

    @Test fun dieselNotHandled() = assertNull(Readiness.parse(intArrayOf(0x00, 0x08, 0, 0)))
    @Test fun shortDataNull() = assertNull(Readiness.parse(intArrayOf(1, 2)))

    @Test fun realLogDiagnosisHasReadiness() = runBlocking {
        val lines = javaClass.getResource("/cee-d-idle-engine-on-dtc.log")!!.readText().lines()
        val elm = Elm327(ReplayTransport(lines))
        elm.init()
        val d = elm.readDiagnosis()
        assertEquals(listOf("Catalizzatore"), d.readiness!!.incomplete.map { it.name })
    }
}

class DriveAnalyzerTest {
    private class Scenario(
        val postSensor: (Double, Double) -> Double,     // (t, pre) → tensione sonda a valle
        val stft: Double = 0.0,
        val ltft: Double = 3.0,
        val minutes: Int = 20,
        val catMax: Double = 450.0,
    )

    private fun run(sc: Scenario): DriveTestAnalyzer {
        val a = DriveTestAnalyzer(0)
        for (t in 0..sc.minutes * 60) {
            val tt = t.toDouble()
            // 0–120 s minimo, poi crociera a 70 km/h fino a 16 min, poi minimo finale
            val driving = t in 120..960
            val rpm = if (driving) 2200.0 else if (t < 3) 0.0 else 880.0
            val closedLoop = t > 60
            val pre = 0.45 + (if (closedLoop) 0.35 * sin(tt * 2.1) else 0.0)
            val v = mapOf(
                0x05 to 20 + 70 * min(1.0, tt / 360),
                0x0C to rpm,
                0x0D to if (driving) 70.0 else 0.0,
                0x14 to pre,
                0x15 to sc.postSensor(tt, pre),
                0x06 to sc.stft,
                0x07 to sc.ltft,
                0x3C to min(sc.catMax, 20 + tt),
            )
            a.add(t * 1000L, v, if (t > 3) 13.9 else 12.4)
        }
        return a
    }

    private fun DriveReport.has(level: FindingLevel, part: String) =
        findings.any { it.level == level && it.text.contains(part) }

    private val healthy = Scenario(postSensor = { t, _ -> 0.70 + 0.02 * sin(t) })

    @Test fun healthyCarHasNoWarnings() {
        val a = run(healthy)
        val r = a.report(20 * 60_000L, null, null)
        assertTrue(r.text, r.findings.none { it.level == FindingLevel.ATTENZIONE })
        assertTrue(r.text, r.has(FindingLevel.OK, "efficienza buona"))
        assertTrue(r.has(FindingLevel.OK, "temperatura di esercizio"))
        assertTrue(r.has(FindingLevel.OK, "Anello chiuso"))
        assertTrue(r.has(FindingLevel.OK, "alternatore"))
        assertTrue(r.text.contains("TEST DI GUIDA DIAGNOSTICO"))
    }

    @Test fun postSensorFollowingPreIndicatesWornCatalyst() {
        val a = run(Scenario(postSensor = { _, pre -> pre }))
        val r = a.report(20 * 60_000L, null, null)
        assertTrue(r.text, r.has(FindingLevel.ATTENZIONE, "P0420"))
    }

    @Test fun postSensorStuckMidpointIsInconclusive() {
        val a = run(Scenario(postSensor = { _, _ -> 0.455 }))
        val r = a.report(20 * 60_000L, null, null)
        assertTrue(r.text, r.has(FindingLevel.INFO, "non posso valutare il catalizzatore"))
        assertFalse(r.has(FindingLevel.ATTENZIONE, "P0420"))
    }

    @Test fun strongNegativeTrimsMeanRich() {
        val a = run(Scenario(postSensor = { t, _ -> 0.70 + 0.02 * sin(t) }, stft = -3.0, ltft = -14.0))
        val r = a.report(20 * 60_000L, null, null)
        assertTrue(r.text, r.has(FindingLevel.ATTENZIONE, "P0172"))
    }

    @Test fun strongPositiveTrimsMeanLean() {
        val a = run(Scenario(postSensor = { t, _ -> 0.70 + 0.02 * sin(t) }, stft = 4.0, ltft = 12.0))
        assertTrue(a.report(20 * 60_000L, null, null).has(FindingLevel.ATTENZIONE, "povera"))
    }

    @Test fun shortTestWarnsUnreliable() {
        val a = run(Scenario(postSensor = { t, _ -> 0.70 + 0.02 * sin(t) }, minutes = 3))
        val r = a.report(3 * 60_000L, null, null)
        assertTrue(r.has(FindingLevel.INFO, "Test breve"))
        assertTrue(r.has(FindingLevel.INFO, "Dati insufficienti"))
    }

    @Test fun coldCatalystNotValidTest() {
        val a = run(Scenario(postSensor = { t, _ -> 0.70 + 0.02 * sin(t) }, catMax = 250.0))
        assertTrue(a.report(20 * 60_000L, null, null).has(FindingLevel.INFO, "troppo freddo"))
    }

    @Test fun criteriaCompleteAfterFullDrive() {
        val a = run(healthy)
        val c = a.criteria(20 * 60_000L, hasCatalystTemp = true)
        assertEquals(6, c.size)
        assertTrue(c.joinToString { "${it.label}=${it.done}" }, c.all { it.done })
    }

    @Test fun criteriaStartIncomplete() {
        val a = DriveTestAnalyzer(0)
        a.add(0, mapOf(0x05 to 20.0, 0x0C to 900.0, 0x0D to 0.0), 12.4)
        val c = a.criteria(1000, hasCatalystTemp = false)
        assertEquals(5, c.size)
        assertTrue(c.none { it.done })
    }

    @Test fun readinessCompletedDuringTest() {
        val a = run(healthy)
        a.readinessStart = Readiness.parse(intArrayOf(0x82, 0x07, 0x61, 0x01))
        a.readinessNow = Readiness.parse(intArrayOf(0x82, 0x07, 0x61, 0x00))
        val r = a.report(20 * 60_000L, null, null)
        assertTrue(r.text, r.has(FindingLevel.OK, "«Catalizzatore» completato durante il test"))
    }

    @Test fun readinessStillIncomplete() {
        val a = run(healthy)
        val still = Readiness.parse(intArrayOf(0x82, 0x07, 0x61, 0x01))
        a.readinessStart = still; a.readinessNow = still
        assertTrue(a.report(20 * 60_000L, null, null).has(FindingLevel.INFO, "ancora incompleto"))
    }

    @Test fun newAndGoneCodes() {
        val a = run(healthy)
        val start = Diagnosis(true, 2, listOf("P0650", "P0172"), listOf("P0420"), 0)
        val end = Diagnosis(true, 2, listOf("P0650", "P0300"), listOf("P0420"), 0)
        val r = a.report(20 * 60_000L, start, end)
        assertTrue(r.has(FindingLevel.ATTENZIONE, "NUOVI comparsi durante il test: P0300"))
        assertTrue(r.has(FindingLevel.OK, "spariti durante il test: P0172"))
    }

    @Test fun unchangedCodes() {
        val a = run(healthy)
        val d = Diagnosis(true, 1, listOf("P0650"), emptyList(), 0)
        assertTrue(a.report(20 * 60_000L, d, d).has(FindingLevel.INFO, "Codici invariati: P0650"))
    }

    @Test fun lowAlternatorVoltage() {
        val a = DriveTestAnalyzer(0)
        for (t in 0..600) a.add(t * 1000L, mapOf(0x0C to 900.0, 0x0D to 0.0, 0x05 to 85.0), 12.6)
        assertTrue(a.report(600_000L, null, null).has(FindingLevel.ATTENZIONE, "alternatore"))
    }

    @Test fun attentionComesFirst() {
        val a = run(Scenario(postSensor = { _, pre -> pre }))
        val f = a.report(20 * 60_000L, null, null).findings
        assertEquals(FindingLevel.ATTENZIONE, f.first().level)
        assertEquals(FindingLevel.OK, f.last().level)
    }

    @Test fun emptyRunDoesNotCrash() {
        val r = DriveTestAnalyzer(0).report(1000, null, null)
        assertTrue(r.text.contains("TEST DI GUIDA"))
    }

    @Test fun simulatorPipeline() = runBlocking {
        // 400 cicli reali di lettura sul simulatore, orologio finto che avanza di 1 s a ciclo
        var now = 0L
        val elm = Elm327(SimulatedTransport(latencyMs = 0) { now })
        elm.init()
        val supported = elm.detectSupportedPids()
        fun known(ids: List<Int>) = ids.filter { it in supported }.mapNotNull { Pids.get(it) }
        val poller = Poller(elm, known(listOf(0x14, 0x15, 0x0C, 0x0D, 0x04, 0x0B)), known(listOf(0x05, 0x06, 0x07, 0x3C, 0x44, 0x0F)), 3)
        val a = DriveTestAnalyzer(0)
        a.readinessStart = Readiness.parse(elm.query(0x01)!!)
        val values = HashMap<Int, Double>()
        repeat(400) {
            now += 1000
            poller.cycle { pid, v -> if (v == null) values.remove(pid.id) else values[pid.id] = v }
            a.add(now, HashMap(values), 13.8)
            if (it % 10 == 0) a.readinessNow = Readiness.parse(elm.query(0x01)!!)
        }
        val r = a.report(now, null, null)
        assertTrue(r.text, r.has(FindingLevel.OK, "Anello chiuso"))
        assertTrue(r.text, r.has(FindingLevel.OK, "«Catalizzatore» completato durante il test"))
        assertTrue(r.text, r.has(FindingLevel.OK, "temperatura di esercizio"))
    }
}
