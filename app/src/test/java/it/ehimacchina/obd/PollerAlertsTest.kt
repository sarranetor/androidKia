package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PollerAlertsTest {
    private val recording = object : Transport {
        val sent = mutableListOf<String>()
        override suspend fun open() {}
        override fun close() {}
        override suspend fun exchange(cmd: String, timeoutMs: Long): String {
            sent += cmd
            return "NO DATA"
        }
    }

    @Test fun fastEveryCycleSlowRotates() = runBlocking {
        val pids = listOf(0x0C, 0x0D, 0x05, 0x0F, 0x06, 0x07).map { Pids.get(it)!! }
        val poller = Poller(Elm327(recording), pids, slowPerCycle = 2)
        repeat(3) { poller.cycle { _, _ -> } }
        val cycles = recording.sent.chunked(4) // 2 veloci + 2 lenti per ciclo
        cycles.forEach { assertEquals(listOf("010C", "010D"), it.take(2)) }
        // lenti: 05,0F | 06,07 | 05,0F
        assertEquals(listOf("0105", "010F", "0106", "0107", "0105", "010F"), cycles.flatMap { it.drop(2) })
    }

    @Test fun consecutiveTimeoutsCounted() = runBlocking {
        val elm = Elm327(object : Transport {
            override suspend fun open() {}
            override fun close() {}
            override suspend fun exchange(cmd: String, timeoutMs: Long): String = throw ObdTimeout(cmd)
        })
        repeat(5) { elm.query(0x0C) }
        assertEquals(5, elm.consecutiveTimeouts)
    }

    @Test fun responseCountSuffixFallback() = runBlocking {
        // adattatore che non capisce il suffisso: risponde "?" → resta disattivato
        val elm = Elm327(object : Transport {
            override suspend fun open() {}
            override fun close() {}
            override suspend fun exchange(cmd: String, timeoutMs: Long) = when (cmd) {
                "0100" -> "41 00 08 18 00 00" // 0C, 0D? (solo per riempire)
                "010C" -> "41 0C 0F A0"
                else -> "?"
            }
        })
        elm.detectSupportedPids()
        assertFalse(elm.tryResponseCount())
        assertEquals(1000.0, elm.read(Pids.get(0x0C)!!)!!, 0.001)
    }

    @Test fun responseCountSuffixWorks() = runBlocking {
        val elm = Elm327(SimulatedTransport())
        elm.init(); elm.detectSupportedPids()
        assertTrue(elm.tryResponseCount())
        assertNotNull(elm.read(Pids.get(0x0C)!!))
    }

    @Test fun coolantAndBatteryLevels() {
        assertNull(Alerts.coolantLevel(90.0))
        assertEquals(Level.WARN, Alerts.coolantLevel(100.0))
        assertEquals(Level.CRIT, Alerts.coolantLevel(106.0))
        // batteria: ignorata a motore spento (10.7 V come nel log reale)
        assertNull(Alerts.batteryLevel(10.7, 0.0))
        assertEquals(Level.CRIT, Alerts.batteryLevel(10.7, 800.0))
        assertEquals(Level.WARN, Alerts.batteryLevel(11.8, 800.0))
        assertNull(Alerts.batteryLevel(14.0, 800.0))
        assertEquals(Level.CRIT, Alerts.batteryLevel(15.6, 2000.0))
    }

    @Test fun diagnosisAlert() {
        val d = Diagnosis(true, 1, listOf("P0133"), emptyList(), 0)
        val alerts = Alerts.evaluate(emptyMap(), null, d)
        assertEquals(1, alerts.size)
        assertTrue(alerts[0].text.contains("1 codice errore"))
        assertTrue(Alerts.evaluate(emptyMap(), null, Diagnosis(false, 0, emptyList(), emptyList(), 0)).isEmpty())
    }
}
