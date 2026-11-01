package it.ehimacchina.obd

/**
 * Risultato di un test che la centralina fa da sola (modo 06): valore misurato e limiti.
 * Non si converte in unità fisiche: per dire "superato" bastano i numeri grezzi, e la scala
 * dipende dalla centralina. Tutto a zero = test non ancora eseguito.
 */
data class MonitorTest(val mid: Int, val tid: Int, val unit: Int, val value: Int, val min: Int, val max: Int) {
    val name get() = Mode06.monitorName(mid)
    val ran get() = !(value == 0 && min == 0 && max == 0)
    val passed: Boolean? get() = if (ran) value in min..max else null
    val outcome get() = when (passed) { true -> "superato"; false -> "NON superato"; null -> "non eseguito" }
    val detail get() = "valore $value, limiti $min…$max"
}

object Mode06 {
    const val O2_UPSTREAM = 0x01
    const val O2_DOWNSTREAM = 0x02
    const val CATALYST = 0x21

    /** Monitor che interessano: sonde del banco 1 e catalizzatore del banco 1. */
    val wanted = listOf(CATALYST, O2_UPSTREAM, O2_DOWNSTREAM)

    fun monitorName(mid: Int) = when (mid) {
        O2_UPSTREAM -> "Sonda O2 a monte"
        O2_DOWNSTREAM -> "Sonda O2 a valle"
        CATALYST -> "Catalizzatore"
        else -> "Monitor %02X".format(mid)
    }

    /**
     * Risposta CAN a "06 <mid>": "46" seguito da record di 9 byte
     * (mid, tid, unità, valore, minimo, massimo; gli ultimi tre su 2 byte). Le unità da 0x80 in su sono con segno.
     * Null se la centralina non dà risultati per [mid].
     */
    fun parse(lines: List<String>, mid: Int): List<MonitorTest>? {
        val tests = Dtc.messages(lines, 0x46).flatMap { message ->
            message.drop(1).chunked(9).filter { it.size == 9 && it[0] == mid }.map { r ->
                fun word(i: Int): Int {
                    val w = r[i] * 256 + r[i + 1]
                    return if (r[2] >= 0x80) w.toShort().toInt() else w
                }
                MonitorTest(mid, r[1], r[2], word(3), word(5), word(7))
            }
        }
        return tests.takeIf { it.isNotEmpty() }
    }
}
