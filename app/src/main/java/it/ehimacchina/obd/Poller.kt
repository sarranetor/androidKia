package it.ehimacchina.obd

/**
 * Decide cosa leggere a ogni ciclo: i PID veloci sempre, quelli lenti a rotazione
 * ([slowPerCycle] per ciclo), così giri e velocità restano reattivi.
 */
class Poller(
    private val elm: Elm327,
    private val fast: List<Pid>,
    private val slow: List<Pid>,
    private val slowPerCycle: Int = 2,
) {
    /** Divide [pids] in veloci e lenti secondo [Pid.fast] */
    constructor(elm: Elm327, pids: List<Pid>, slowPerCycle: Int = 2) :
        this(elm, pids.filter { it.fast }, pids.filter { !it.fast }, slowPerCycle)

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
