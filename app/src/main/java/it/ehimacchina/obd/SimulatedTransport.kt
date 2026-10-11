package it.ehimacchina.obd

import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * ELM327 finto, per sviluppare senza auto. Simula una benzina senza MAF (come la Cee'd).
 * Scenario: 0–8 s quadro acceso e motore spento, 8–16 s minimo da fermo, poi marcia con valori
 * che oscillano. C'è un codice errore (P0133) che sparisce dopo "04".
 */
class SimulatedTransport(
    private val latencyMs: Long = 30,
    private val clock: () -> Long = System::currentTimeMillis,
) : Transport {
    private val startMs = clock()

    /** Dopo "04" i codici spariscono, come su un'auto vera */
    private var cleared = false

    private val supported = setOf(
        0x01, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x14, 0x15, 0x1F, 0x21, 0x33, 0x3C, 0x44,
    )
    private val freezeSupported = setOf(0x02, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F)

    override suspend fun open() {}
    override fun close() {}

    override suspend fun exchange(cmd: String, timeoutMs: Long): String {
        delay(latencyMs) // un po' di latenza come un adattatore vero
        val c = cmd.trim().uppercase()
        val t = (clock() - startMs) / 1000.0
        return when {
            c == "ATZ" -> "\r\rELM327 v1.5"
            c == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
            c == "ATRV" -> "%.1fV".format(java.util.Locale.US, if (t < 8) 12.2 else 13.8 + 0.3 * sin(t / 7))
            c.startsWith("AT") -> "OK"
            c == "03" -> if (cleared) "43 00" else "43 01 01 33"
            c == "07" -> "47 00"
            c == "04" -> { cleared = true; "44" }
            // 6 caratteri = modo 02 (freeze frame): 02 + PID + frame
            c.length == 6 && c.startsWith("02") -> mode02(c.substring(2, 4).toInt(16))
            // 5 caratteri = suffisso "risposte attese" (es. 010C1)
            (c.length == 4 || c.length == 5) && c.startsWith("01") -> mode01(c.substring(2, 4).toInt(16), t)
            else -> "?"
        }
    }

    private fun mode01(pid: Int, t: Double): String {
        if (pid % 0x20 == 0) return maskResponse(pid, supported)
        if (pid !in supported) return "NO DATA"
        if (pid == 0x01) {
            // spia accesa + 1 codice; monitor del catalizzatore incompleto fino a 3 minuti, poi completo.
            // Dopo la cancellazione tutti i monitor tornano incompleti, come su un'auto vera.
            return when {
                cleared -> "41 01 00 07 61 61"
                t < 180 -> "41 01 81 07 61 01"
                else -> "41 01 81 07 61 00"
            }
        }

        val engineOn = t >= 8
        val driving = t >= 16
        val wave = sin(t / 5)
        val bytes: List<Int> = when (pid) {
            0x04 -> listOf((if (!engineOn) 0.0 else if (!driving) 22.0 else 30 + 20 * wave).percent())
            0x05 -> listOf((minOf(90.0, 20 + t) + 40).toInt())
            0x06 -> listOf((128 + 3 * sin(t / 9)).toInt())
            0x07 -> listOf(131)
            0x0B -> listOf((if (!engineOn) 100.0 else if (!driving) 35.0 else 40 + 20 * wave).toInt())
            0x0C -> word(((if (!engineOn) 0.0 else if (!driving) 850.0 else 1500 + 700 * wave) * 4).toInt())
            0x0D -> listOf((if (!driving) 0.0 else 60 + 30 * sin(t / 8)).toInt())
            0x0E -> listOf(((12 + 4 * wave) * 2 + 128).toInt())
            0x0F -> listOf(25 + 40)
            0x11 -> listOf((if (!driving) 6.0 else 20 + 10 * wave).percent())
            // sonda a monte: oscilla attorno a 0.45 V; a valle: stabile (catalizzatore "sano")
            0x14 -> listOf((200 * (0.45 + (if (engineOn) 0.35 else 0.0) * sin(t * 2.1))).toInt(), 128)
            0x15 -> listOf((200 * (if (engineOn) 0.70 + 0.03 * sin(t * 0.7) else 0.45)).toInt(), 255)
            0x1F -> word(maxOf(0.0, t - 8).toInt())
            0x21 -> word(0)
            0x33 -> listOf(100)
            0x3C -> word(((minOf(450.0, 20 + 5 * maxOf(0.0, t - 8)) + 40) * 10).toInt())
            0x44 -> word(32768) // lambda 1.0
            else -> return "NO DATA"
        }
        return "41 %02X %s".format(pid, bytes.joinToString(" ") { "%02X".format(it) })
    }

    /** Freeze frame: valori fissi, come fotografati in marcia (t = 45 s). */
    private fun mode02(pid: Int): String {
        if (cleared) return "NO DATA"
        if (pid % 0x20 == 0) return maskResponse(pid, freezeSupported).replaceFirst("41 %02X".format(pid), "42 %02X 00".format(pid))
        if (pid == 0x02) return "42 02 00 01 33"
        if (pid !in freezeSupported) return "NO DATA"
        val tokens = mode01(pid, 45.0).split(" ")
        return "42 %02X 00 %s".format(pid, tokens.drop(2).joinToString(" "))
    }

    /** Risposta a 0100/0120/...: maschera dei PID presenti in [pids] nel blocco. */
    private fun maskResponse(base: Int, pids: Set<Int>): String {
        var mask = 0L
        for (i in 0 until 32) {
            val pid = base + i + 1
            val hasNextBlock = i == 31 && pids.any { it > pid }
            if (pid in pids || hasNextBlock) mask = mask or (1L shl (31 - i))
        }
        val b = (0..3).map { ((mask shr (24 - 8 * it)) and 0xFF).toInt() }
        return "41 %02X %s".format(base, b.joinToString(" ") { "%02X".format(it) })
    }

    private fun Double.percent() = (this * 255 / 100).toInt().coerceIn(0, 255)
    private fun word(v: Int) = listOf((v shr 8) and 0xFF, v and 0xFF)
}
