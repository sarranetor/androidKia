package it.ehimacchina.obd

/** Un monitor di prontezza: la centralina ha finito di valutare quel sistema dall'ultima cancellazione? */
data class Monitor(val name: String, val complete: Boolean)

/**
 * Stato dei monitor di prontezza, dal PID 0101 (4 byte A B C D):
 *  A = spia + numero codici; B = monitor continui (bit 0-2 supportati, 4-6 incompleti, bit 3 = diesel);
 *  C = monitor non continui supportati; D = monitor non continui INCOMPLETI (1 = non finito).
 */
data class Readiness(val milOn: Boolean, val dtcCount: Int, val monitors: List<Monitor>) {
    val incomplete get() = monitors.filter { !it.complete }

    companion object {
        private val continuous = listOf("Accensione (misfire)", "Sistema carburante", "Componenti")
        private val nonContinuous = listOf(
            "Catalizzatore", "Catalizzatore riscaldato", "EVAP", "Aria secondaria",
            "Climatizzatore", "Sonda O2", "Riscaldatore sonda O2", "EGR",
        )

        /** [data] = i 4 byte dopo "41 01". Ritorna null per motori diesel (monitor diversi, non gestiti). */
        fun parse(data: IntArray): Readiness? {
            if (data.size < 4) return null
            val (a, b, c, d) = data.toList()
            if (b and 0x08 != 0) return null
            val monitors = mutableListOf<Monitor>()
            continuous.forEachIndexed { i, name ->
                if (b and (1 shl i) != 0) monitors += Monitor(name, complete = b and (1 shl (i + 4)) == 0)
            }
            nonContinuous.forEachIndexed { i, name ->
                if (c and (1 shl i) != 0) monitors += Monitor(name, complete = d and (1 shl i) == 0)
            }
            return Readiness(milOn = a and 0x80 != 0, dtcCount = a and 0x7F, monitors = monitors)
        }
    }
}
