package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Trasporto a copione: risponde col testo registrato per ogni comando. */
private class ScriptTransport(val script: Map<String, String>) : Transport {
    val sent = mutableListOf<String>()
    override suspend fun open() {}
    override fun close() {}
    override suspend fun exchange(cmd: String, timeoutMs: Long): String {
        sent += cmd
        return script[cmd] ?: throw ObdTimeout(cmd)
    }
}

class Elm327Test {
    @Test fun parseMode01Spaced() =
        assertArrayEquals(intArrayOf(0x1A, 0xF8), Elm327.parseMode01(listOf("41 0C 1A F8"), 0x0C))

    @Test fun parseMode01Compact() =
        assertArrayEquals(intArrayOf(0x1A, 0xF8), Elm327.parseMode01(listOf("410C1AF8"), 0x0C))

    @Test fun parseMode01SkipsOtherEcuAndNoise() {
        // due centraline: la prima risposta è per un altro PID
        val lines = listOf("41 0D 32", "41 0C 0F A0")
        assertArrayEquals(intArrayOf(0x0F, 0xA0), Elm327.parseMode01(lines, 0x0C))
    }

    @Test fun parseMode01NoData() {
        assertNull(Elm327.parseMode01(listOf("NO DATA"), 0x0C))
        assertNull(Elm327.parseMode01(emptyList(), 0x0C))
    }

    @Test fun cleanLinesRemovesSearchingAndBlank() {
        val elm = Elm327(ScriptTransport(emptyMap()))
        assertEquals(listOf("41 00 BE 3F A8 13"), elm.cleanLines("SEARCHING...\r41 00 BE 3F A8 13\r\r"))
    }

    @Test fun initAndDetectPids() = runBlocking {
        val t = ScriptTransport(
            mapOf(
                "ATZ" to "\r\rELM327 v1.5\r\r", "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK",
                "ATH0" to "OK", "ATSP0" to "OK",
                "0100" to "SEARCHING...\r41 00 98 18 00 01\r",
                "0120" to "41 20 80 00 00 01", "0140" to "41 40 44 00 00 00",
                "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
            ),
        )
        val elm = Elm327(t)
        elm.init()
        assertEquals("ELM327 v1.5", elm.version)
        assertTrue(elm.protocol.contains("CAN"))
        val pids = elm.detectSupportedPids()
        assertTrue(0x04 in pids && 0x05 in pids && 0x0C in pids)
        assertTrue(0x21 in pids && 0x42 in pids)
        assertTrue(0x20 !in pids && 0x40 !in pids)
    }

    @Test fun queryTimeoutReturnsNull() = runBlocking {
        val elm = Elm327(ScriptTransport(emptyMap()))
        assertNull(elm.query(0x0C))
    }

    @Test fun batteryVoltage() = runBlocking {
        val elm = Elm327(ScriptTransport(mapOf("ATRV" to "12.6V")))
        assertEquals(12.6, elm.batteryVoltage()!!, 0.001)
    }

    @Test fun readOnlyGuard() {
        assertTrue(Elm327.isReadOnlyCommand("010C"))
        assertTrue(Elm327.isReadOnlyCommand("03"))
        assertTrue(Elm327.isReadOnlyCommand("ATRV"))
        assertTrue(Elm327.isReadOnlyCommand("09 02"))
        assertFalse(Elm327.isReadOnlyCommand("04"))   // cancella codici
        assertFalse(Elm327.isReadOnlyCommand("14"))
        assertFalse(Elm327.isReadOnlyCommand("2E F1"))
        assertFalse(Elm327.isReadOnlyCommand("zz"))
    }

    @Test fun simulatorEndToEnd() = runBlocking {
        var now = 0L
        val elm = Elm327(SimulatedTransport { now })
        now = 30_000 // 30 s dopo l'avvio: già in marcia
        elm.init()
        val pids = elm.detectSupportedPids()
        assertTrue(0x0C in pids && 0x0D in pids && 0x21 in pids && 0x44 in pids)
        assertFalse("il simulatore non ha il MAF, come la Cee'd", 0x10 in pids)
        val rpm = elm.read(Pids.get(0x0C)!!)!!
        assertTrue(rpm in 700.0..2300.0)
    }

    @Test fun replayRoundTrip() = runBlocking {
        val log = listOf(
            "1\tATZ\t|ELM327 v1.5|\t900",
            "2\t0100\t41 00 80 00 00 00\t60",
            "3\t010C\t41 0C 0F A0\t40",
            "4\t010C\t41 0C 1F A0\t40",
            "5\t#ERR\ttimeout\t0",
        )
        val t = ReplayTransport(log)
        assertEquals(1000.0, Elm327.parseMode01(listOf(t.exchange("010C", 1)), 0x0C)!!.let { Pids.get(0x0C)!!.decode(it) }, 0.001)
        assertEquals(2024.0, Elm327.parseMode01(listOf(t.exchange("010C", 1)), 0x0C)!!.let { Pids.get(0x0C)!!.decode(it) }, 0.001)
        // riparte da capo
        assertEquals(1000.0, Elm327.parseMode01(listOf(t.exchange("010C", 1)), 0x0C)!!.let { Pids.get(0x0C)!!.decode(it) }, 0.001)
        assertEquals("NO DATA", t.exchange("0105", 1))
    }
}
