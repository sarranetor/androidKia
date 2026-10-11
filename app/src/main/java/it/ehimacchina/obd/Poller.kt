package it.ehimacchina.obd

/**
 * Decide cosa leggere a ogni ciclo: i PID veloci sempre, quelli lenti a rotazione
 * ([slowPerCycle] per ciclo), così giri e velocità restano reattivi.
 */
class Poller(
    private val elm: Elm327,
    pids: List<Pid>,
    private val slowPerCycle: Int = 2,
) {
    private val fast = pids.filter { it.fast }
    private val slow = pids.filter { !it.fast }
    private var next = 0

    val isEmpty get() = fast.isEmpty() && slow.isEmpty()

    /** Un ciclo di letture. [onValue] riceve null se la lettura non ha dato un valore. */
    suspend fun cycle(onValue: (Pid, Double?) -> Unit) {
        for (p in fast) onValue(p, elm.read(p))
        if (slow.isNotEmpty()) {
            repeat(minOf(slowPerCycle, slow.size)) {
                val p = slow[next % slow.size]
                next++
                onValue(p, elm.read(p))
            }
        }
    }
}
