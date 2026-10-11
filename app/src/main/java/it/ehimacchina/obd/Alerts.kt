package it.ehimacchina.obd

enum class Level { WARN, CRIT }

data class Alert(val level: Level, val text: String)

/** Soglie e logica degli avvisi del cruscotto. Soglie provvisorie: da tarare con un log a motore acceso. */
object Alerts {
    const val COOLANT_WARN = 98.0
    const val COOLANT_CRIT = 105.0
    const val BATTERY_WARN = 12.0
    const val BATTERY_CRIT = 11.5
    const val BATTERY_HIGH = 15.0

    /** Sopra questi giri si considera il motore acceso (la tensione batteria ha senso solo così) */
    const val ENGINE_RUNNING_RPM = 400.0

    fun coolantLevel(c: Double?): Level? = when {
        c == null -> null
        c >= COOLANT_CRIT -> Level.CRIT
        c >= COOLANT_WARN -> Level.WARN
        else -> null
    }

    fun batteryLevel(volts: Double?, rpm: Double?): Level? = when {
        volts == null || rpm == null || rpm < ENGINE_RUNNING_RPM -> null
        volts < BATTERY_CRIT || volts > BATTERY_HIGH -> Level.CRIT
        volts < BATTERY_WARN -> Level.WARN
        else -> null
    }

    /** [values] = ultimi valori per PID (es. 0x0C → giri). */
    fun evaluate(values: Map<Int, Double>, battery: Double?, diagnosis: Diagnosis?): List<Alert> {
        val out = mutableListOf<Alert>()
        values[0x05]?.let { c ->
            coolantLevel(c)?.let { out += Alert(it, "Temperatura liquido alta: %.0f °C".format(c)) }
        }
        batteryLevel(battery, values[0x0C])?.let {
            out += Alert(it, "Tensione batteria anomala: %.1f V".format(battery))
        }
        if (diagnosis != null) {
            val n = diagnosis.stored?.size ?: 0
            if (diagnosis.milOn == true || n > 0) {
                val text = if (n > 0) "$n codici errore memorizzati — apri Diagnosi" else "Spia motore accesa — apri Diagnosi"
                out += Alert(Level.WARN, text)
            }
        }
        return out
    }
}
