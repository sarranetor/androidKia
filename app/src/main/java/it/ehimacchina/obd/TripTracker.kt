package it.ehimacchina.obd

/**
 * Riconosce i viaggi dai valori letti. Parte quando i giri superano [Alerts.ENGINE_RUNNING_RPM],
 * finisce dopo [END_AFTER_MS] a motore spento. Somma km e litri (stima non tarata).
 * Nessuna dipendenza da Android: si prova sul PC.
 */
class TripTracker {
    data class Summary(val startMs: Long, val endMs: Long, val km: Double, val fuelL: Double, val maxSpeed: Double) {
        /** Viaggi trascurabili (accensioni di prova) non si salvano */
        val worthSaving get() = km >= 0.1 || endMs - startMs >= 60_000
    }

    sealed interface Event {
        data class Started(val startMs: Long) : Event
        data class Ended(val summary: Summary) : Event
    }

    private var startMs = 0L
    private var lastTickMs = 0L
    private var lastRunningMs = 0L
    private var inTrip = false

    var km = 0.0
        private set
    var fuelL = 0.0
        private set
    var maxSpeed = 0.0
        private set

    val active get() = inTrip
    val currentStartMs get() = startMs

    /** Da chiamare a ogni ciclo di lettura con i valori correnti (chiave = PID). */
    fun tick(now: Long, v: Map<Int, Double>): Event? {
        val running = (v[0x0C] ?: 0.0) >= Alerts.ENGINE_RUNNING_RPM
        if (!inTrip) {
            if (!running) return null
            inTrip = true
            startMs = now; lastTickMs = now; lastRunningMs = now
            km = 0.0; fuelL = 0.0; maxSpeed = 0.0
            return Event.Started(now)
        }
        val dtH = minOf(now - lastTickMs, MAX_STEP_MS) / 3_600_000.0
        lastTickMs = now
        val speed = v[0x0D] ?: 0.0
        km += speed * dtH
        fuelL += (Consumption.fuelLitersPerHour(v) ?: 0.0) * dtH
        if (speed > maxSpeed) maxSpeed = speed
        if (running) {
            lastRunningMs = now
            return null
        }
        return if (now - lastRunningMs >= END_AFTER_MS) endAt(lastRunningMs) else null
    }

    /** Chiude il viaggio in corso (disconnessione). */
    fun finish(now: Long): Event.Ended? = if (inTrip) endAt(minOf(now, lastRunningMs + END_AFTER_MS)) else null

    private fun endAt(endMs: Long): Event.Ended {
        inTrip = false
        return Event.Ended(Summary(startMs, endMs, km, fuelL, maxSpeed))
    }

    companion object {
        const val END_AFTER_MS = 120_000L
        /** Un buco più lungo di così (es. collegamento perso) non si integra come se fosse marcia */
        const val MAX_STEP_MS = 5_000L
    }
}
