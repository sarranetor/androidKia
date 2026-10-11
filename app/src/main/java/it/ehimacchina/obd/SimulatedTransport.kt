package it.ehimacchina.obd

import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * ELM327 finto, per sviluppare senza auto. Simula una benzina con MAF e MAP.
 * Valori che oscillano nel tempo così il cruscotto si muove.
 */
class SimulatedTransport(private val clock: () -> Long = System::currentTimeMillis) : Transport {
    private val startMs = clock()
    private val supported = setOf(
        0x01, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1F, 0x21, 0x2F, 0x33,
    )

    override suspend fun open() {}
    override fun close() {}

    override suspend fun exchange(cmd: String, timeoutMs: Long): String {
        delay(30) // un po' di latenza come un adattatore vero
        val c = cmd.trim().uppercase()
        val t = (clock() - startMs) / 1000.0
        return when {
            c == "ATZ" -> "\r\rELM327 v1.5"
            c == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
            c == "ATRV" -> "%.1fV".format(13.8 + 0.3 * sin(t / 7))
            c.startsWith("AT") -> "OK"
            c == "03" -> "43 01 01 33" // un codice memorizzato: P0133 (sonda O2 lenta)
            c == "07" -> "47 00"
            // 5 caratteri = suffisso "risposte attese" (es. 010C1)
            (c.length == 4 || c.length == 5) && c.startsWith("01") -> mode01(c.substring(2, 4).toInt(16), t)
            else -> "?"
        }
    }

    private fun mode01(pid: Int, t: Double): String {
        if (pid % 0x20 == 0) return maskResponse(pid)
        if (pid !in supported) return "NO DATA"
        if (pid == 0x01) return "41 01 81 07 E5 00" // spia accesa, 1 codice
        val bytes: List<Int> = when (pid) {
            0x04 -> listOf((30 + 20 * sin(t / 5)).percent())
            0x05 -> listOf((85 + 5 * sin(t / 30) + 40).toInt())
            0x06, 0x07 -> listOf((128 + 3 * sin(t / 9)).toInt())
            0x0B -> listOf((40 + 20 * sin(t / 5)).toInt())
            0x0C -> word(((1500 + 700 * sin(t / 5)) * 4).toInt())
            0x0D -> listOf((60 + 30 * sin(t / 8)).toInt())
            0x0E -> listOf(((12 + 4 * sin(t / 5)) * 2 + 128).toInt())
            0x0F -> listOf(25 + 40)
            0x10 -> word(((8 + 6 * sin(t / 5)) * 100).toInt())
            0x11 -> listOf((20 + 10 * sin(t / 5)).percent())
            0x1F -> word(t.toInt())
            0x21 -> word(0)
            0x2F -> listOf(60.0.percent())
            0x33 -> listOf(100)
            else -> return "NO DATA"
        }
        return "41 %02X %s".format(pid, bytes.joinToString(" ") { "%02X".format(it) })
    }

    /** Risposta a 0100/0120/...: maschera dei PID supportati nel blocco. */
    private fun maskResponse(base: Int): String {
        var mask = 0L
        for (i in 0 until 32) {
            val pid = base + i + 1
            val hasNextBlock = i == 31 && supported.any { it > pid }
            if (pid in supported || hasNextBlock) mask = mask or (1L shl (31 - i))
        }
        val b = (0..3).map { ((mask shr (24 - 8 * it)) and 0xFF).toInt() }
        return "41 %02X %s".format(base, b.joinToString(" ") { "%02X".format(it) })
    }

    private fun Double.percent() = (this * 255 / 100).toInt().coerceIn(0, 255)
    private fun word(v: Int) = listOf((v shr 8) and 0xFF, v and 0xFF)
}
