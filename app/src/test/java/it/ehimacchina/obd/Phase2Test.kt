package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FreezeFrameTest {
    @Test fun parseMode02Response() {
        assertArrayEquals(intArrayOf(0x01, 0x72), Elm327.parseResponse(listOf("42 02 00 01 72"), 0x42, 0x02, 0))
        assertArrayEquals(intArrayOf(0x0F, 0xA0), Elm327.parseResponse(listOf("420C000FA0"), 0x42, 0x0C, 0))
        // risposta del modo 01 non va scambiata per freeze frame
        assertNull(Elm327.parseResponse(listOf("41 0C 0F A0"), 0x42, 0x0C, 0))
    }

    @Test fun freezeBelongsOnlyToItsCode() {
        val ff = FreezeFrame("P0650", mapOf(0x0C to 0.0))
        assertNotNull(ff.textFor("P0650"))
        assertNull(ff.textFor("P0172"))
    }

    @Test fun textRoundTrip() {
        val ff = FreezeFrame("P0172", mapOf(0x0C to 2100.0, 0x05 to 88.0))
        assertEquals(ff, FreezeFrame.fromText(ff.toText()))
        assertNull(FreezeFrame.fromText(""))
    }

    @Test fun simulatorFreezeFrame() = runBlocking {
        val elm = Elm327(SimulatedTransport())
        elm.init()
        val ff = elm.readFreezeFrame()!!
        assertEquals("P0133", ff.code)
        assertTrue(0x0C in ff.values && 0x05 in ff.values && 0x0D in ff.values)
        assertTrue(ff.values[0x05]!! in 60.0..110.0)
    }

    @Test fun diagnosisIncludesFreezeFrameWhenCodes() = runBlocking {
        val elm = Elm327(SimulatedTransport())
        elm.init()
        assertNotNull(elm.readDiagnosis().freezeFrame)
    }

    @Test fun noFreezeFrameFromRealEcuWithoutData() = runBlocking {
        // il log reale non contiene richieste modo 02: il replay risponde NO DATA e non deve esplodere
        val lines = javaClass.getResource("/cee-d-idle-engine-on-dtc.log")!!.readText().lines()
        val elm = Elm327(ReplayTransport(lines))
        elm.init()
        assertNull(elm.readFreezeFrame())
    }
}

class ClearCodesTest {
    @Test fun clearWorksAndCodesDisappear() = runBlocking {
        val elm = Elm327(SimulatedTransport())
        elm.init()
        assertEquals(listOf("P0133"), elm.readDiagnosis().stored)
        assertTrue(elm.clearCodes())
        val after = elm.readDiagnosis()
        assertEquals(emptyList<String>(), after.stored)
        assertEquals(false, after.milOn)
        assertNull(after.freezeFrame)
    }

    @Test fun clearOnlySendsMode04() = runBlocking {
        val sent = mutableListOf<String>()
        val elm = Elm327(object : Transport {
            override suspend fun open() {}
            override fun close() {}
            override suspend fun exchange(cmd: String, timeoutMs: Long): String { sent += cmd; return "44" }
        })
        assertTrue(elm.clearCodes())
        assertEquals(listOf("04"), sent)
    }

    @Test fun clearNotConfirmed() = runBlocking {
        val elm = Elm327(object : Transport {
            override suspend fun open() {}
            override fun close() {}
            override suspend fun exchange(cmd: String, timeoutMs: Long) = "NO DATA"
        })
        assertFalse(elm.clearCodes())
    }

    @Test fun guard() {
        assertNull(ClearGuard.check(mapOf(0x0D to 0.0, 0x0C to 0.0)))
        assertNotNull("in marcia", ClearGuard.check(mapOf(0x0D to 30.0, 0x0C to 0.0)))
        assertNotNull("motore acceso", ClearGuard.check(mapOf(0x0D to 0.0, 0x0C to 850.0)))
        assertNotNull("velocità sconosciuta", ClearGuard.check(mapOf(0x0C to 0.0)))
        assertNotNull("giri sconosciuti", ClearGuard.check(mapOf(0x0D to 0.0)))
        assertNotNull(ClearGuard.check(emptyMap()))
    }
}

class ConsumptionTest {
    private fun v(rpm: Double, map: Double, iat: Double = 20.0, lambda: Double? = null) =
        buildMap {
            put(0x0C, rpm); put(0x0B, map); put(0x0F, iat)
            lambda?.let { put(0x44, it) }
        }

    @Test fun engineOffIsZero() = assertEquals(0.0, Consumption.fuelLitersPerHour(v(0.0, 100.0))!!, 0.0001)

    @Test fun missingDataIsNull() {
        assertNull(Consumption.fuelLitersPerHour(mapOf(0x0C to 1500.0)))
        assertNull(Consumption.fuelLitersPerHour(emptyMap()))
    }

    @Test fun idleIsPlausible() {
        // minimo 850 giri, MAP 35 kPa: una 1.6 beve circa 0.5–1.5 L/h
        val lph = Consumption.fuelLitersPerHour(v(850.0, 35.0))!!
        assertTrue("minimo $lph L/h", lph in 0.4..1.6)
    }

    @Test fun cruisePlausible() {
        // crociera ~90 km/h: 2500 giri, MAP 55 kPa → circa 5–9 L/100km
        val lph = Consumption.fuelLitersPerHour(v(2500.0, 55.0))!!
        val l100 = Consumption.litersPer100Km(lph, 90.0)!!
        assertTrue("crociera $l100 L/100km", l100 in 4.0..10.0)
    }

    @Test fun richerLambdaBurnsLess() {
        // lambda > 1 = miscela povera = meno carburante
        assertTrue(
            Consumption.fuelLitersPerHour(v(2000.0, 50.0, lambda = 1.1))!! <
                Consumption.fuelLitersPerHour(v(2000.0, 50.0, lambda = 1.0))!!,
        )
        // lambda 2.0 (valore "non disponibile" visto prima dell'avvio) si ignora
        assertEquals(
            Consumption.fuelLitersPerHour(v(2000.0, 50.0))!!,
            Consumption.fuelLitersPerHour(v(2000.0, 50.0, lambda = 2.0))!!, 1e-9,
        )
    }

    @Test fun l100OnlyWhenMoving() {
        assertNull(Consumption.litersPer100Km(1.0, 0.0))
        assertNull(Consumption.litersPer100Km(null, 50.0))
        assertEquals(6.0, Consumption.litersPer100Km(3.0, 50.0)!!, 1e-9)
    }
}

class CalibrationTest {
    @Test fun noRefuelsMeansOne() = assertEquals(1.0, Calibration.factor(emptyList(), emptyList()), 0.0)

    @Test fun estimateTooLowIsScaledUp() {
        // l'app stimava 40 L su 800 km (5.0), il pieno dice 48 L su 800 km (6.0) → fattore 1.2
        val trips = listOf(TripRec(1_000, 400.0, 20.0), TripRec(2_000, 400.0, 20.0))
        val refuels = listOf(RefuelRec(3_000, 48.0, 800.0))
        assertEquals(1.2, Calibration.factor(refuels, trips), 1e-9)
    }

    @Test fun partialCoverageUsesRatios() {
        // l'app ha visto solo 600 dei 800 km (75%, ok): confronto per km, non in assoluto
        val trips = listOf(TripRec(1_000, 600.0, 30.0)) // 5.0 L/100
        val refuels = listOf(RefuelRec(3_000, 48.0, 800.0)) // 6.0 L/100
        assertEquals(1.2, Calibration.factor(refuels, trips), 1e-9)
    }

    @Test fun poorCoverageIgnored() {
        val trips = listOf(TripRec(1_000, 100.0, 5.0))
        val refuels = listOf(RefuelRec(3_000, 48.0, 800.0))
        assertEquals(1.0, Calibration.factor(refuels, trips), 0.0)
    }

    @Test fun windowsStartAfterPreviousRefuel() {
        val trips = listOf(TripRec(1_000, 500.0, 25.0), TripRec(5_000, 500.0, 40.0))
        val refuels = listOf(RefuelRec(3_000, 25.0, 500.0), RefuelRec(7_000, 40.0, 500.0))
        // primo intervallo fattore 1.0, secondo 1.0
        assertEquals(1.0, Calibration.factor(refuels, trips), 1e-9)
    }

    @Test fun clamped() {
        val trips = listOf(TripRec(1_000, 500.0, 1.0))
        assertEquals(2.0, Calibration.factor(listOf(RefuelRec(2_000, 50.0, 500.0)), trips), 0.0)
    }
}

class TripTrackerTest {
    private fun drive(rpm: Double, speed: Double) =
        mapOf(0x0C to rpm, 0x0D to speed, 0x0B to 50.0, 0x0F to 20.0)

    @Test fun startsWhenEngineRunsAndIgnoresKeyOn() {
        val t = TripTracker()
        assertNull(t.tick(0, drive(0.0, 0.0)))
        assertFalse(t.active)
        assertTrue(t.tick(1_000, drive(850.0, 0.0)) is TripTracker.Event.Started)
        assertTrue(t.active)
    }

    @Test fun integratesDistanceAndFuel() {
        val t = TripTracker()
        t.tick(0, drive(2000.0, 90.0))
        // 4 passi da 5 s = 20 s a 90 km/h → 0.5 km
        for (i in 1..4) t.tick(i * 5_000L, drive(2000.0, 90.0))
        assertEquals(0.5, t.km, 1e-9)
        assertTrue(t.fuelL > 0)
        assertEquals(90.0, t.maxSpeed, 0.0)
    }

    @Test fun gapIsCappedNotIntegrated() {
        val t = TripTracker()
        t.tick(0, drive(2000.0, 90.0))
        t.tick(60_000, drive(2000.0, 90.0)) // buco di 60 s: conta solo 5 s
        assertEquals(90.0 * 5 / 3600, t.km, 1e-9)
    }

    @Test fun endsAfterTwoMinutesEngineOff() {
        val t = TripTracker()
        t.tick(0, drive(2000.0, 90.0))
        t.tick(5_000, drive(2000.0, 90.0))
        assertNull(t.tick(10_000, drive(0.0, 0.0)))
        assertNull(t.tick(100_000, drive(0.0, 0.0)))
        val ev = t.tick(130_000, drive(0.0, 0.0)) as TripTracker.Event.Ended
        assertEquals(5_000, ev.summary.endMs) // fine = ultimo istante col motore acceso
        assertFalse(t.active)
    }

    @Test fun shortStopDoesNotEndTrip() {
        val t = TripTracker()
        t.tick(0, drive(2000.0, 50.0))
        t.tick(5_000, drive(0.0, 0.0)) // stallo
        assertNull(t.tick(30_000, drive(850.0, 0.0))) // riparte: stesso viaggio
        assertTrue(t.active)
    }

    @Test fun finishOnDisconnect() {
        val t = TripTracker()
        t.tick(0, drive(2000.0, 90.0))
        t.tick(5_000, drive(2000.0, 90.0))
        val ev = t.finish(8_000)!!
        assertEquals(0, ev.summary.startMs)
        assertTrue(ev.summary.worthSaving.not() || ev.summary.km > 0)
        assertNull(t.finish(9_000))
    }

    @Test fun negligibleTripsNotWorthSaving() {
        val t = TripTracker()
        t.tick(0, drive(850.0, 0.0))
        t.tick(5_000, drive(850.0, 0.0))
        val ev = t.finish(6_000)!!
        assertFalse(ev.summary.worthSaving)
    }
}
