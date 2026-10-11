package it.ehimacchina.obd

/** Un PID del modo 01 che sappiamo decodificare. */
class Pid(
    val id: Int,
    val name: String,
    val unit: String,
    /** Quanti byte di dati servono */
    val bytes: Int,
    /** Veloce = letto a ogni ciclo, altrimenti ogni tanto (fase 1) */
    val fast: Boolean,
    val decode: (IntArray) -> Double,
) {
    val hex: String get() = "%02X".format(id)
}

object Pids {
    val all: List<Pid> = listOf(
        Pid(0x04, "Carico motore", "%", 1, true) { it[0] * 100.0 / 255 },
        Pid(0x05, "Temp. liquido", "°C", 1, false) { it[0] - 40.0 },
        Pid(0x06, "Correz. carb. breve B1", "%", 1, false) { (it[0] - 128) * 100.0 / 128 },
        Pid(0x07, "Correz. carb. lunga B1", "%", 1, false) { (it[0] - 128) * 100.0 / 128 },
        Pid(0x0A, "Pressione carburante", "kPa", 1, false) { it[0] * 3.0 },
        Pid(0x0B, "Pressione MAP", "kPa", 1, true) { it[0].toDouble() },
        Pid(0x0C, "Giri motore", "rpm", 2, true) { (it[0] * 256 + it[1]) / 4.0 },
        Pid(0x0D, "Velocità", "km/h", 1, true) { it[0].toDouble() },
        Pid(0x0E, "Anticipo accensione", "°", 1, false) { it[0] / 2.0 - 64 },
        Pid(0x0F, "Temp. aria aspirata", "°C", 1, false) { it[0] - 40.0 },
        Pid(0x10, "Flusso aria MAF", "g/s", 2, true) { (it[0] * 256 + it[1]) / 100.0 },
        Pid(0x11, "Farfalla", "%", 1, true) { it[0] * 100.0 / 255 },
        Pid(0x1F, "Tempo dall'avvio", "s", 2, false) { (it[0] * 256 + it[1]).toDouble() },
        Pid(0x21, "Distanza con MIL", "km", 2, false) { (it[0] * 256 + it[1]).toDouble() },
        Pid(0x2F, "Livello carburante", "%", 1, false) { it[0] * 100.0 / 255 },
        Pid(0x33, "Pressione barometrica", "kPa", 1, false) { it[0].toDouble() },
        Pid(0x42, "Tensione centralina", "V", 2, false) { (it[0] * 256 + it[1]) / 1000.0 },
        Pid(0x46, "Temp. ambiente", "°C", 1, false) { it[0] - 40.0 },
        Pid(0x5C, "Temp. olio", "°C", 1, false) { it[0] - 40.0 },
        Pid(0x5E, "Consumo carburante", "L/h", 2, false) { (it[0] * 256 + it[1]) / 20.0 },
    )

    private val byId = all.associateBy { it.id }
    fun get(id: Int): Pid? = byId[id]

    /**
     * Decodifica la maschera dei PID supportati (4 byte) risposta a 0100/0120/0140...
     * [base] = 0x00, 0x20, ... Il bit più alto è il PID base+1, il più basso è base+0x20
     * (che indica se esiste il blocco successivo).
     */
    fun supportedFromMask(base: Int, mask: IntArray): List<Int> {
        val result = mutableListOf<Int>()
        for (i in 0 until 32) {
            val byte = mask[i / 8]
            if (byte and (0x80 shr (i % 8)) != 0) result += base + i + 1
        }
        return result
    }
}
