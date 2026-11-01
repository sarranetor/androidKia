package it.ehimacchina.obd

/**
 * Attese fra i tentativi di riconnessione: subito fitti (un'interruzione breve in marcia), poi
 * una ogni 2 minuti senza fermarsi, così la lettura riparte da sola quando si risale in auto.
 * Se il collegamento manca da [closeAfterMs], viaggio e test in corso vanno chiusi. Nessuna dipendenza da Android.
 */
class ReconnectPolicy(
    private val delaysSec: List<Long> = listOf(2L, 5L, 10L, 30L, 60L, 120L),
    private val closeAfterMs: Long = TripTracker.END_AFTER_MS,
) {
    var attempt = 0
        private set
    private var lastDataMs = 0L
    private var workOpen = false

    /** Da chiamare a ogni ciclo di lettura riuscito. */
    fun onData(now: Long) {
        attempt = 0
        lastDataMs = now
        workOpen = true
    }

    fun nextDelaySec(): Long {
        attempt++
        return delaysSec[minOf(attempt - 1, delaysSec.lastIndex)]
    }

    /** Vero una sola volta per ogni interruzione, passato [closeAfterMs] dall'ultimo dato. */
    fun shouldCloseOpenWork(now: Long): Boolean {
        if (!workOpen || now - lastDataMs < closeAfterMs) return false
        workOpen = false
        return true
    }
}
