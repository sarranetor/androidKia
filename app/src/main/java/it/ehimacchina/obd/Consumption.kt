package it.ehimacchina.obd

/**
 * Stima del consumo senza MAF (la Cee'd non ce l'ha): metodo speed-density.
 * Dall'aria che entra nei cilindri (MAP, temperatura aria, giri, cilindrata) si ricava
 * il carburante dividendo per il rapporto aria/benzina. È una STIMA: il fattore di taratura
 * (vedi [Calibration]) la avvicina ai rifornimenti reali.
 */
object Consumption {
    const val DISPLACEMENT_L = 1.6
    /** Efficienza volumetrica media assunta; l'errore residuo lo assorbe il fattore di taratura */
    const val VOLUMETRIC_EFFICIENCY = 0.85
    const val AFR_STOICH = 14.7
    const val GASOLINE_G_PER_L = 745.0
    private const val R_AIR = 287.05 // J/(kg·K)

    /** Aria aspirata in g/s */
    fun airGramsPerSecond(mapKpa: Double, iatC: Double, rpm: Double): Double {
        val volumePerSecond = DISPLACEMENT_L / 1000.0 * rpm / 120.0 // m³/s, motore a 4 tempi
        val kg = mapKpa * 1000.0 * volumePerSecond * VOLUMETRIC_EFFICIENCY / (R_AIR * (iatC + 273.15))
        return kg * 1000.0
    }

    /**
     * Litri/ora senza taratura, dai valori correnti (chiave = PID), oppure null se mancano dati.
     * Con il motore spento ritorna 0.
     */
    fun fuelLitersPerHour(v: Map<Int, Double>): Double? {
        val rpm = v[0x0C] ?: return null
        if (rpm < 400) return 0.0
        val map = v[0x0B] ?: return null
        val iat = v[0x0F] ?: return null
        // lambda comandato (PID 44): se plausibile corregge il rapporto aria/benzina
        val lambda = v[0x44]?.takeIf { it in 0.6..1.5 } ?: 1.0
        val fuelGramsPerSecond = airGramsPerSecond(map, iat, rpm) / (AFR_STOICH * lambda)
        return fuelGramsPerSecond * 3600.0 / GASOLINE_G_PER_L
    }

    /** L/100 km istantanei, null se l'auto è praticamente ferma. */
    fun litersPer100Km(lph: Double?, speedKmh: Double?): Double? {
        if (lph == null || speedKmh == null || speedKmh < 3) return null
        return lph / speedKmh * 100.0
    }
}

data class RefuelRec(val dateMs: Long, val liters: Double, val km: Double)
data class TripRec(val startMs: Long, val km: Double, val fuelL: Double)

/** Calcola il fattore che moltiplica la stima per avvicinarla ai litri veri. */
object Calibration {
    /**
     * Per ogni rifornimento confronta i litri/km veri con quelli stimati dai viaggi nello stesso intervallo
     * (dal rifornimento precedente). Si ignorano gli intervalli in cui l'app ha coperto meno della
     * metà dei km dichiarati. Media degli ultimi [lastN] intervalli validi, limitata a 0.5–2.0.
     */
    fun factor(refuels: List<RefuelRec>, trips: List<TripRec>, lastN: Int = 3): Double {
        val sorted = refuels.sortedBy { it.dateMs }
        val factors = sorted.mapIndexedNotNull { i, r ->
            val from = if (i == 0) Long.MIN_VALUE else sorted[i - 1].dateMs
            val inWindow = trips.filter { it.startMs > from && it.startMs <= r.dateMs }
            val tripKm = inWindow.sumOf { it.km }
            val tripFuel = inWindow.sumOf { it.fuelL }
            if (r.km <= 0 || r.liters <= 0 || tripKm < 0.5 * r.km || tripFuel <= 0) null
            else (r.liters / r.km) / (tripFuel / tripKm)
        }
        if (factors.isEmpty()) return 1.0
        return factors.takeLast(lastN).average().coerceIn(0.5, 2.0)
    }
}

/** Quando è lecito cancellare i codici: auto ferma e motore spento (quadro acceso). */
object ClearGuard {
    fun check(values: Map<Int, Double>): String? {
        val speed = values[0x0D] ?: return "Velocità non letta: non posso verificare che l'auto sia ferma."
        val rpm = values[0x0C] ?: return "Giri non letti: non posso verificare che il motore sia spento."
        if (speed > 0) return "Auto in movimento: fermati prima."
        if (rpm >= Alerts.ENGINE_RUNNING_RPM) return "Spegni il motore e lascia il quadro acceso."
        return null
    }
}
