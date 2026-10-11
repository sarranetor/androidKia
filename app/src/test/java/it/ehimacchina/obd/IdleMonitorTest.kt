package it.ehimacchina.obd

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class IdleMonitorTest {
    private fun feed(m: IdleMonitor, seconds: Int, startMs: Long = 0, step: Long = 400, f: (Double) -> Map<Int, Double>) {
        var t = startMs
        while (t <= startMs + seconds * 1000L) {
            m.add(t, f(t / 1000.0)); t += step
        }
    }

    @Test fun stuckRichLikeTheCeed() {
        // caso reale: sonda ferma a 0,86 V, STFT ferma a -18,75 %, LTFT -0,78 %
        val m = IdleMonitor()
        feed(m, 40) { mapOf(0x14 to 0.86, 0x06 to -18.75, 0x07 to -0.78, 0x03 to 2.0) }
        val s = m.summary(40_000)
        assertEquals(-19.53, s.total!!, 0.01)
        assertEquals(1.0, s.richShare!!, 0.0)
        assertEquals(0.0, s.switchesPerMin!!, 0.0)
        assertEquals(1.0, s.closedLoopShare!!, 0.0)
    }

    @Test fun healthyOscillation() {
        val m = IdleMonitor()
        // ~1 Hz di oscillazione campionata a 2,5 Hz
        feed(m, 40) { t -> mapOf(0x14 to 0.45 + 0.4 * sin(t * 6.28), 0x06 to 2 * sin(t), 0x07 to 1.0) }
        val s = m.summary(40_000)
        assertTrue("passaggi/min ${s.switchesPerMin}", s.switchesPerMin!! > 30)
        assertTrue(s.richShare!! < 0.5)
        assertEquals(1.0, s.total!!, 1.5)
        assertNull(s.closedLoopShare)
    }

    @Test fun windowIsLast30sAndHistoryKeeps2Min() {
        val m = IdleMonitor()
        feed(m, 100) { t -> mapOf(0x06 to if (t < 70) -20.0 else 0.0, 0x07 to 0.0) }
        assertEquals(0.0, m.summary(100_000).stft!!, 0.01) // solo gli ultimi 30 s
        feed(m, 60, startMs = 100_400) { mapOf(0x06 to 0.0) }
        assertTrue(m.history().first().t >= 160_400 - 120_000)
    }

    @Test fun snapshotsForBeforeAfter() {
        val m = IdleMonitor()
        feed(m, 40) { mapOf(0x14 to 0.86, 0x06 to -18.0, 0x07 to -1.0) }
        val before = m.snapshot(40_000)
        // dopo la prova (tubo del canister chiuso): torna a regolare
        feed(m, 40, startMs = 40_400) { t -> mapOf(0x14 to 0.45 + 0.4 * sin(t * 6.28), 0x06 to 0.5, 0x07 to -1.0) }
        val after = m.snapshot(80_400)
        assertEquals(listOf(1, 2), m.snapshots().map { it.number })
        assertTrue(after.summary.total!! - before.summary.total!! > 15)
        assertTrue(after.summary.richShare!! < before.summary.richShare!!)
    }

    @Test fun emptyAndUnknown() {
        val s = IdleMonitor().summary(0)
        assertEquals(0, s.samples)
        assertNull(s.total); assertNull(s.o2Mean); assertNull(s.switchesPerMin)
        assertEquals("anello chiuso: regola con la sonda", IdleMonitor.loopLabel(2.0))
        assertEquals("stato regolazione sconosciuto", IdleMonitor.loopLabel(null))
    }
}
