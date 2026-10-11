package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Test con le risposte vere della Cee'd (quadro acceso, motore spento, 11/10/2026). */
class RealLogTest {
    private fun elm(): Elm327 {
        val lines = javaClass.getResource("/cee-d-ign-on-engine-off.log")!!.readText().lines()
        return Elm327(ReplayTransport(lines))
    }

    @Test fun initAndSupportedPids() = runBlocking {
        val e = elm()
        e.init()
        assertEquals("ELM327 v2.2", e.version)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", e.protocol)
        val expected = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x14, 0x15,
            0x1C, 0x1F, 0x21, 0x2E, 0x30, 0x31, 0x3C, 0x41, 0x43, 0x44, 0x45, 0x46,
        )
        assertEquals(expected, e.detectSupportedPids())
        assertFalse("la Cee'd non ha il MAF", 0x10 in e.supportedPids)
    }

    @Test fun values() = runBlocking {
        val e = elm()
        suspend fun v(pid: Int) = e.read(Pids.get(pid)!!)!!
        assertEquals(18.0, v(0x05), 0.001)   // liquido
        assertEquals(0.0, v(0x0C), 0.001)    // giri: motore spento
        assertEquals(100.0, v(0x0B), 0.001)  // MAP ~ pressione ambiente
        assertEquals(18.0, v(0x0F), 0.001)   // aria aspirata
        assertEquals(267.0, v(0x21), 0.001)  // km con spia MIL accesa
        assertEquals(10.7, e.batteryVoltage()!!, 0.001)
    }
}

/** Log reale del 11/10/2026: motore acceso al minimo da freddo, auto ferma, con codici in memoria. */
class RealLogIdleTest {
    private fun elm(): Elm327 {
        val lines = javaClass.getResource("/cee-d-idle-engine-on-dtc.log")!!.readText().lines()
        return Elm327(ReplayTransport(lines))
    }

    @Test fun diagnosisFromRealEcu() = runBlocking {
        val e = elm()
        e.init()
        val d = e.readDiagnosis()
        assertEquals(true, d.milOn)
        assertEquals(2, d.reportedCount)
        assertEquals(listOf("P0650", "P0172"), d.stored)
        assertEquals(listOf("P0650", "P0420"), d.pending)
    }

    @Test fun responseCountSuffixWorksOnThisAdapter() = runBlocking {
        val e = elm()
        e.init()
        e.detectSupportedPids()
        assertTrue(e.tryResponseCount())
    }

    @Test fun idleValues() = runBlocking {
        val e = elm()
        e.init(); e.detectSupportedPids(); e.tryResponseCount()
        val rpm = e.read(Pids.get(0x0C)!!)!!
        assertEquals(0.0, rpm, 0.001) // primo campione: motore ancora spento
        assertEquals(449.0, e.read(Pids.get(0x31)!!)!!, 0.001)
        assertEquals(41.0, e.read(Pids.get(0x30)!!)!!, 0.001)
    }
}
