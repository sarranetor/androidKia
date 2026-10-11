package it.ehimacchina.obd

/** Esito di una diagnosi (sola lettura). Un campo null = non è stato possibile leggerlo. */
data class Diagnosis(
    /** Spia motore (MIL) accesa, dal PID 0101 */
    val milOn: Boolean?,
    /** Numero di codici dichiarato dalla centralina (PID 0101) */
    val reportedCount: Int?,
    /** Codici memorizzati (modo 03) */
    val stored: List<String>?,
    /** Codici pendenti (modo 07) */
    val pending: List<String>?,
    val atMillis: Long,
    /** Fotografia dei valori nel momento del guasto (modo 02), se la centralina la fornisce */
    val freezeFrame: FreezeFrame? = null,
)

/** Freeze frame: il codice che l'ha causato e i valori (chiave = PID) letti in quel momento. */
data class FreezeFrame(val code: String, val values: Map<Int, Double>) {
    /** Testo per il database: "P0172;05=88.0;0C=2100.0" */
    fun toText(): String = (listOf(code) + values.map { (k, v) -> "%02X=%s".format(k, v) }).joinToString(";")

    companion object {
        fun fromText(text: String): FreezeFrame? {
            val parts = text.split(';')
            val code = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val values = parts.drop(1).mapNotNull {
                val kv = it.split('=')
                val pid = kv.getOrNull(0)?.toIntOrNull(16)
                val v = kv.getOrNull(1)?.toDoubleOrNull()
                if (pid != null && v != null) pid to v else null
            }.toMap()
            return FreezeFrame(code, values)
        }
    }
}

object Dtc {
    /** Trasforma due byte nel codice, es. 0x01 0x33 → "P0133". */
    fun decode(b1: Int, b2: Int): String {
        val letter = "PCBU"[(b1 shr 6) and 0x03]
        return "%c%d%X%02X".format(letter, (b1 shr 4) and 0x03, b1 and 0x0F, b2)
    }

    /**
     * Legge la risposta ai modi 03/07. [responseByte] = 0x43 (03) o 0x47 (07).
     * Ritorna la lista dei codici (vuota = nessun codice), oppure null se la risposta non si capisce.
     *
     * Formati gestiti (ELM con ATH0 e ATS0):
     *  - CAN, un solo frame: "430201330134" = 43, numero codici, coppie di byte
     *    (l'ELM mostra solo i byte reali, quindi dopo il 43 il numero di byte è dispari)
     *  - CAN, più frame: riga lunghezza "00A", poi "0:43..." "1:..." da concatenare
     *  - protocolli vecchi: "43 0133 0000 0000" (senza numero codici, coppie 0000 = riempimento)
     */
    fun parse(lines: List<String>, responseByte: Int): List<String>? {
        val prefix = "%02X".format(responseByte)
        val hexOnly = Regex("^[0-9A-F]+$")
        val messages = mutableListOf<List<Int>>()
        var multi = StringBuilder()
        var expected: Int? = null

        fun toBytes(hex: String) = hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }

        for (line in lines) {
            val l = line.replace(" ", "").uppercase()
            when {
                Regex("^[0-9A-F]:").containsMatchIn(l) -> multi.append(l.substring(2))
                l.length == 3 && hexOnly.matches(l) -> expected = l.toInt(16)
                l.startsWith(prefix) && hexOnly.matches(l) -> messages += toBytes(l)
            }
        }
        if (multi.isNotEmpty()) {
            val bytes = toBytes(multi.toString())
            messages += expected?.let { bytes.take(it) } ?: bytes
        }

        if (messages.isEmpty()) {
            // "NO DATA" = la centralina non ha niente da dire = nessun codice
            return if (lines.any { it.contains("NO DATA") }) emptyList() else null
        }

        val codes = linkedSetOf<String>()
        for (m in messages) {
            if (m.firstOrNull() != responseByte) continue
            var rest = m.drop(1)
            if (rest.size % 2 == 1) rest = rest.drop(1) // primo byte = numero di codici
            for (i in 0 until rest.size / 2) {
                val b1 = rest[2 * i]
                val b2 = rest[2 * i + 1]
                if (b1 == 0 && b2 == 0) continue
                codes += decode(b1, b2)
            }
        }
        return codes.toList()
    }
}
