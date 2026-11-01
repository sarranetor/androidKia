package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/** Trasporto a copione: per ogni comando una lista di risposte, l'ultima si ripete. */
private class SequenceTransport(script: Map<String, List<String>>) : Transport {
    private val left = script.mapValues { it.value.toMutableList() }
    val sent = mutableListOf<String>()
    override suspend fun open() {}
    override fun close() {}
    override suspend fun exchange(cmd: String, timeoutMs: Long): String {
        sent += cmd
        val answers = left[cmd] ?: throw ObdTimeout(cmd)
        return if (answers.size > 1) answers.removeAt(0) else answers[0]
    }
}

class Mode06Test {
    @Test fun singleRecord() {
        val t = Mode06.parse(listOf("46 21 A0 0A 00 96 00 00 01 2C"), 0x21)!!.single()
        assertEquals(0xA0, t.tid)
        assertEquals(150, t.value)
        assertEquals(0, t.min)
        assertEquals(300, t.max)
        assertEquals(true, t.passed)
        assertEquals("Catalizzatore", t.name)
    }

    @Test fun outOfLimitsFails() {
        val t = Mode06.parse(listOf("46210A0A0190000000C8"), 0x21)!!.single()
        assertEquals(false, t.passed)
        assertEquals("NON superato", t.outcome)
    }

    @Test fun allZeroMeansNotRun() {
        val t = Mode06.parse(listOf("46 21 A0 0A 00 00 00 00 00 00"), 0x21)!!.single()
        assertNull(t.passed)
        assertEquals("non eseguito", t.outcome)
    }

    @Test fun multiFrameManyRecords() {
        val lines = listOf("013", "0:46 21 A0 0A 00 96", "1:00 00 01 2C 21 A1 0A", "2:00 00 00 00 00 00 00")
        val tests = Mode06.parse(lines, 0x21)!!
        assertEquals(listOf(0xA0, 0xA1), tests.map { it.tid })
    }

    @Test fun signedUnit() {
        // unità ≥ 0x80 con segno: valore -10, limiti -100…100
        val t = Mode06.parse(listOf("46 01 01 FE FF F6 FF 9C 00 64"), 0x01)!!.single()
        assertEquals(-10, t.value)
        assertEquals(-100, t.min)
        assertEquals(true, t.passed)
    }

    @Test fun otherMonitorAndNoDataAreIgnored() {
        assertNull(Mode06.parse(listOf("46 02 01 0A 00 96 00 00 01 2C"), 0x21))
        assertNull(Mode06.parse(listOf("NO DATA"), 0x21))
        assertNull(Mode06.parse(listOf("?"), 0x21))
    }

    @Test fun readsOnlyWantedSupportedMonitors() = runBlocking {
        val t = SequenceTransport(
            mapOf(
                "0600" to listOf("46 00 C0 00 00 01"), // 01, 02 e blocco successivo
                "0620" to listOf("46 20 80 00 00 00"), // 21
                "0601" to listOf("46 01 01 0A 02 58 00 C8 03 20"),
                "0602" to listOf("NO DATA"),
                "0621" to listOf("46 21 A0 0A 00 96 00 00 01 2C"),
            ),
        )
        val tests = Elm327(t).readMonitorTests()!!
        assertEquals(listOf(Mode06.CATALYST, Mode06.O2_UPSTREAM), tests.map { it.mid })
    }

    @Test fun carWithoutMode06() = runBlocking {
        assertNull(Elm327(SequenceTransport(mapOf("0600" to listOf("NO DATA")))).readMonitorTests())
        assertNull(Elm327(SequenceTransport(emptyMap())).readMonitorTests())
    }

    @Test fun simulatorAnswers() = runBlocking {
        val tests = Elm327(SimulatedTransport(latencyMs = 0)).readMonitorTests()!!
        assertEquals(listOf(0xA0, 0xA1, 0x01, 0x01), tests.map { it.tid })
        assertEquals(listOf<Boolean?>(true, null, true, true), tests.map { it.passed })
    }

    @Test fun consoleAllowsMode06ButNotClear() {
        assertTrue(Elm327.isReadOnlyCommand("06 21"))
        assertFalse(Elm327.isReadOnlyCommand("04"))
    }
}

class CheckCodesTest {
    private val base = Diagnosis(true, 2, listOf("P0650", "P0172"), listOf("P0650"), 1L, freezeFrame = FreezeFrame("P0650", emptyMap()))

    @Test fun sameCountSkipsStoredAndKeepsFreeze() = runBlocking {
        val t = SequenceTransport(mapOf("0101" to listOf("41 01 82 07 61 00"), "07" to listOf("47 02 06 50 01 72")))
        val d = Elm327(t).checkCodes(base)!!
        assertFalse("03" in t.sent)
        assertEquals(base.stored, d.stored)
        assertEquals(listOf("P0650", "P0172"), d.pending)
        assertEquals("P0650", d.freezeFrame?.code)
    }

    @Test fun countChangedReadsStored() = runBlocking {
        val t = SequenceTransport(
            mapOf("0101" to listOf("41 01 83 07 61 00"), "07" to listOf("47 00"), "03" to listOf("43 03 06 50 01 72 03 00")),
        )
        val d = Elm327(t).checkCodes(base)!!
        assertEquals(listOf("P0650", "P0172", "P0300"), d.stored)
        assertEquals(3, d.reportedCount)
    }

    /** Quadro spento fra una richiesta e l'altra: NO DATA su 03/07 non deve diventare "nessun codice". */
    @Test fun ignitionOffDuringCheckIsUnknown() = runBlocking {
        val t = SequenceTransport(mapOf("0101" to listOf("41 01 82 07 61 00", "NO DATA"), "07" to listOf("NO DATA")))
        assertNull(Elm327(t).checkCodes(base))
    }

    @Test fun ignitionOffDuringFullDiagnosisIsUnknown() = runBlocking {
        val t = SequenceTransport(
            mapOf("0101" to listOf("41 01 82 07 61 00", "NO DATA"), "03" to listOf("NO DATA"), "07" to listOf("NO DATA")),
        )
        val d = Elm327(t).readDiagnosis()
        assertNull(d.stored)
        assertNull(d.pending)
        assertNull(d.milOn)
    }

    @Test fun simulatorNewPendingCodeAppears() = runBlocking {
        var now = 0L
        val elm = Elm327(SimulatedTransport(latencyMs = 0, clock = { now }, newCodeAfterSec = 120))
        val first = elm.readDiagnosis()
        assertEquals(emptyList<String>(), first.pending)
        now = 121_000
        assertEquals(listOf("P0300"), elm.checkCodes(first)!!.pending)
    }
}

class CodeNewsTest {
    private val known = mapOf("P0650" to Dtc.KIND_STORED, "P0172" to Dtc.KIND_BOTH)

    @Test fun knownCodesAreSilent() {
        assertNull(CodeNews.find(known, listOf("P0650", "P0172"), listOf("P0650", "P0172"), true, true))
        // P0172 che esce dai pendenti non è una novità
        assertNull(CodeNews.find(known, listOf("P0650", "P0172"), emptyList(), true, true))
    }

    @Test fun newPendingCode() {
        val n = CodeNews.find(known, listOf("P0650", "P0172"), listOf("P0300"), true, true)!!
        assertEquals(listOf("P0300"), n.newCodes)
        assertTrue(n.title, "P0300" in n.title)
    }

    @Test fun codeThatCameBackIsNews() {
        // P0420 era sparito, quindi non è fra gli aperti
        assertEquals(listOf("P0420"), CodeNews.find(known, listOf("P0650"), listOf("P0420"), true, true)!!.newCodes)
    }

    @Test fun pendingBecomesStored() {
        val n = CodeNews.find(mapOf("P0420" to Dtc.KIND_PENDING), listOf("P0420"), emptyList(), true, true)!!
        assertEquals(listOf("P0420"), n.confirmed)
        assertTrue(n.newCodes.isEmpty())
    }

    @Test fun milTurnsOn() {
        assertTrue(CodeNews.find(emptyMap(), emptyList(), emptyList(), false, true)!!.milTurnedOn)
        // stato precedente sconosciuto (appena collegati): nessun avviso
        assertNull(CodeNews.find(emptyMap(), emptyList(), emptyList(), null, true))
        assertNull(CodeNews.find(emptyMap(), emptyList(), emptyList(), true, false))
    }

    @Test fun unreadableListsGiveNoNews() = assertNull(CodeNews.find(known, null, null, true, null))

    @Test fun kinds() {
        assertEquals(Dtc.KIND_BOTH, Dtc.kindOf("P0172", listOf("P0172"), listOf("P0172")))
        assertEquals(Dtc.KIND_STORED, Dtc.kindOf("P0172", listOf("P0172"), emptyList()))
        assertEquals(Dtc.KIND_PENDING, Dtc.kindOf("P0172", emptyList(), listOf("P0172")))
        assertTrue(Dtc.KIND_BOTH.length <= 20) // limite del server
    }
}

class ReconnectPolicyTest {
    @Test fun delaysGrowThenStayAtTwoMinutes() {
        val p = ReconnectPolicy()
        assertEquals(listOf(2L, 5L, 10L, 30L, 60L, 120L, 120L, 120L), List(8) { p.nextDelaySec() })
        assertEquals(8, p.attempt)
    }

    @Test fun dataResetsAttempts() {
        val p = ReconnectPolicy()
        repeat(6) { p.nextDelaySec() }
        p.onData(1000)
        assertEquals(0, p.attempt)
        assertEquals(2L, p.nextDelaySec())
    }

    @Test fun closesOnceAfterTwoMinutesWithoutData() {
        val p = ReconnectPolicy()
        assertFalse(p.shouldCloseOpenWork(10_000_000)) // niente letto finora: niente da chiudere
        p.onData(1000)
        assertFalse(p.shouldCloseOpenWork(1000 + TripTracker.END_AFTER_MS - 1))
        assertTrue(p.shouldCloseOpenWork(1000 + TripTracker.END_AFTER_MS))
        assertFalse(p.shouldCloseOpenWork(1000 + 2 * TripTracker.END_AFTER_MS))
        p.onData(5_000_000)
        assertTrue(p.shouldCloseOpenWork(5_000_000 + TripTracker.END_AFTER_MS))
    }

    @Test fun lostLinkClosesTripAtLastEngineTime() {
        val t = TripTracker()
        t.tick(0, mapOf(0x0C to 2000.0, 0x0D to 60.0))
        t.tick(60_000, mapOf(0x0C to 2000.0, 0x0D to 60.0))
        val ended = t.finish(600_000)!!
        assertEquals(60_000 + TripTracker.END_AFTER_MS, ended.summary.endMs)
        assertFalse(t.active)
        assertNull(t.finish(700_000))
    }
}

class DriveReportCodesTest {
    /** 20 min: minimo caldo all'inizio e alla fine, in mezzo crociera in cui la sonda a valle copia quella a monte. */
    private fun analyzer(purge: Double? = null, idleO2: (Int) -> Double = { 0.45 + 0.35 * sin(it * 2.1) }): DriveTestAnalyzer {
        val a = DriveTestAnalyzer(0)
        for (t in 0..1200) {
            val driving = t in 120..1000
            val pre = if (driving) 0.45 + 0.35 * sin(t * 2.1) else idleO2(t)
            val v = hashMapOf(
                0x05 to 90.0, 0x0C to if (driving) 2200.0 else 700.0, 0x0D to if (driving) 70.0 else 0.0,
                0x14 to pre, 0x15 to pre, 0x06 to 0.0, 0x07 to 1.0, 0x44 to 1.0,
            )
            purge?.let { v[0x2E] = if (t % 2 == 0) it else 0.0 }
            a.add(t * 1000L, v, 13.8)
        }
        return a
    }

    private fun diag(stored: List<String>, pending: List<String>, at: Long = 1_200_000, tests: List<MonitorTest>? = null) =
        Diagnosis(true, stored.size, stored, pending, at, monitorTests = tests)

    private fun DriveReport.finding(part: String) = findings.firstOrNull { part in it.text }

    @Test fun catalystWordingFollowsTheCode() {
        val withCode = analyzer().report(1_200_000, null, diag(emptyList(), listOf("P0420")))
        assertTrue(withCode.text, withCode.finding("copia quella a monte")!!.text.contains("Coerente con P0420"))
        val without = analyzer().report(1_200_000, null, diag(listOf("P0650"), emptyList()))
        assertTrue(without.text, without.finding("copia quella a monte")!!.text.contains("non segnala P0420"))
    }

    @Test fun catalystTestPassedIsMentioned() {
        val tests = listOf(MonitorTest(Mode06.CATALYST, 0xA0, 0x0A, 150, 0, 300))
        val r = analyzer().report(1_200_000, null, diag(listOf("P0650"), emptyList(), tests = tests))
        assertTrue(r.text, r.finding("copia quella a monte")!!.text.contains("test risulta superato"))
        assertTrue(r.text, "TEST DELLA CENTRALINA" in r.text)
        assertTrue(r.text, "Catalizzatore (test A0): superato" in r.text)
    }

    @Test fun failedEcuTestIsAWarning() {
        val tests = listOf(MonitorTest(Mode06.CATALYST, 0xA0, 0x0A, 400, 0, 300))
        val r = analyzer().report(1_200_000, null, diag(listOf("P0650"), emptyList(), tests = tests))
        assertEquals(FindingLevel.ATTENZIONE, r.finding("NON superato")!!.level)
    }

    @Test fun pendingRichCodeIsNotHiddenByGoodTrims() {
        val r = analyzer().report(1_200_000, null, diag(listOf("P0650", "P0172"), listOf("P0172")))
        assertEquals(r.text, FindingLevel.ATTENZIONE, r.finding("P0172 come pendente")!!.level)
        assertNull(analyzer().report(1_200_000, null, diag(listOf("P0172"), emptyList())).finding("come pendente"))
    }

    @Test fun lateReadingIsDeclared() {
        val start = diag(listOf("P0650"), emptyList(), at = 0)
        val late = analyzer().report(1_200_000, start, diag(listOf("P0650"), emptyList(), at = 1_100_000))
        assertTrue(late.text, "ultima lettura possibile" in late.text)
        assertFalse("non confrontabili" in late.text)
        assertFalse("ultima lettura possibile" in analyzer().report(1_200_000, start, diag(listOf("P0650"), emptyList())).text)
    }

    @Test fun canisterComparison() {
        // sul ricco solo quando il canister è attivo (nei secondi pari)
        val onlyActive = analyzer(purge = 20.0) { if (it % 2 == 0) 0.85 else 0.2 }.report(1_200_000, null, null)
        assertTrue(onlyActive.text, "sul ricco 100 % col canister attivo, 0 % col canister chiuso" in onlyActive.text)
        assertEquals(FindingLevel.INFO, onlyActive.finding("più ricca quando il canister è attivo")!!.level)

        // sempre sul ricco col canister comandato chiuso
        val closed = analyzer(purge = 0.0) { 0.85 }.report(1_200_000, null, null)
        assertTrue(closed.text, closed.finding("anche col canister comandato chiuso") != null)

        // PID non letto: nessuna riga
        assertFalse("canister attivo per" in analyzer().report(1_200_000, null, null).text)
    }
}
