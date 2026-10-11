package it.ehimacchina.obd

import org.junit.Assert.*
import org.junit.Test

class PidTest {
    private fun decode(pid: Int, vararg bytes: Int) = Pids.get(pid)!!.decode(bytes)

    @Test fun rpm() = assertEquals(1726.0, decode(0x0C, 0x1A, 0xF8), 0.001) // 0x1AF8 = 6904 / 4
    @Test fun speed() = assertEquals(90.0, decode(0x0D, 90), 0.001)
    @Test fun coolant() = assertEquals(87.0, decode(0x05, 127), 0.001)
    @Test fun load() = assertEquals(100.0, decode(0x04, 255), 0.001)
    @Test fun maf() = assertEquals(12.34, decode(0x10, 0x04, 0xD2), 0.001) // 1234 / 100
    @Test fun fuelTrim() {
        assertEquals(0.0, decode(0x06, 128), 0.001)
        assertEquals(-100.0, decode(0x06, 0), 0.001)
    }
    @Test fun moduleVoltage() = assertEquals(14.124, decode(0x42, 0x37, 0x2C), 0.001) // 14124 mV

    @Test fun supportedMask() {
        // BE 3F A8 13 = PID 01,03,04,05,06,07 | 09..0E | 0F,11,13,15 | 1C?,.. (controllo di alcuni)
        val pids = Pids.supportedFromMask(0x00, intArrayOf(0xBE, 0x3F, 0xA8, 0x13))
        assertTrue(0x01 in pids && 0x04 in pids && 0x05 in pids && 0x0C in pids && 0x0D in pids)
        assertFalse(0x02 in pids)
        assertTrue(0x20 in pids) // bit finale: esiste il blocco successivo
    }

    @Test fun supportedMaskWithOffset() {
        val pids = Pids.supportedFromMask(0x20, intArrayOf(0x80, 0x00, 0x00, 0x00))
        assertEquals(listOf(0x21), pids)
    }

    @Test fun o2Voltage() = assertEquals(0.45, decode(0x14, 90, 0x80), 0.001)
    @Test fun lambdaStoich() = assertEquals(1.0, decode(0x44, 0x80, 0x00), 0.001)
    @Test fun catalystTemp() = assertEquals(469.6, decode(0x3C, 0x13, 0xE8), 0.001) // 5096/10 - 40
    @Test fun absoluteLoad() = assertEquals(100.0, decode(0x43, 0x00, 0xFF), 0.001)
    @Test fun distanceSinceClear() = assertEquals(267.0, decode(0x31, 0x01, 0x0B), 0.001)
    @Test fun evapPurge() = assertEquals(50.0, decode(0x2E, 127.5.toInt()), 0.5)
}
