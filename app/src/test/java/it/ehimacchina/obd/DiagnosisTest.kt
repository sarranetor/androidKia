package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiagnosisTest {
    @Test fun decodeCodes() {
        assertEquals("P0133", Dtc.decode(0x01, 0x33))
        assertEquals("P0301", Dtc.decode(0x03, 0x01))
        assertEquals("P2002", Dtc.decode(0x20, 0x02))
        assertEquals("C0123", Dtc.decode(0x41, 0x23))
        assertEquals("B1234", Dtc.decode(0x92, 0x34))
        assertEquals("U0100", Dtc.decode(0xC1, 0x00))
    }

    @Test fun canSingleFrameNoCodes() = assertEquals(emptyList<String>(), Dtc.parse(listOf("4300"), 0x43))

    @Test fun canSingleFrameTwoCodes() =
        assertEquals(listOf("P0133", "P0301"), Dtc.parse(listOf("43020133 0301".replace(" ", "")), 0x43))

    @Test fun canMultiFrame() {
        // 10 byte: 43, count=4, 4 codici; l'ultimo frame ha riempimento
        val lines = listOf("00A", "0:430401330201", "1:02020303000000")
        assertEquals(listOf("P0133", "P0201", "P0202", "P0303"), Dtc.parse(lines, 0x43))
    }

    @Test fun classicProtocolPaddedPairs() {
        assertEquals(listOf("P0133"), Dtc.parse(listOf("43 01 33 00 00 00 00"), 0x43))
        assertEquals(emptyList<String>(), Dtc.parse(listOf("43 00 00 00 00 00 00"), 0x43))
    }

    @Test fun twoEcusSameCodeDeduplicated() =
        assertEquals(listOf("P0133"), Dtc.parse(listOf("43010133", "43010133"), 0x43))

    @Test fun pendingMode() = assertEquals(listOf("P0420"), Dtc.parse(listOf("47010420"), 0x47))

    @Test fun noDataMeansNoCodes() = assertEquals(emptyList<String>(), Dtc.parse(listOf("NO DATA"), 0x47))

    @Test fun garbageIsNull() {
        assertNull(Dtc.parse(listOf("UNABLE TO CONNECT"), 0x43))
        assertNull(Dtc.parse(listOf("?"), 0x43))
    }

    @Test fun diagnosisFromSimulator() = runBlocking {
        val elm = Elm327(SimulatedTransport())
        elm.init()
        val d = elm.readDiagnosis()
        assertEquals(true, d.milOn)
        assertEquals(1, d.reportedCount)
        assertEquals(listOf("P0133"), d.stored)
        assertEquals(emptyList<String>(), d.pending)
    }
}
