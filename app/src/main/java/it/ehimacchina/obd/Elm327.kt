package it.ehimacchina.obd

import kotlinx.coroutines.CancellationException

/**
 * Parla il protocollo ELM327 sopra un [Transport]. Nessuna dipendenza da Android,
 * quindi testabile sul PC.
 */
class Elm327(
    private val transport: Transport,
    private val log: (String) -> Unit = {},
) {
    var version: String = "?"
        private set
    var protocol: String = "?"
        private set

    /** PID del modo 01 supportati dall'auto, riempito da [detectSupportedPids]. */
    var supportedPids: List<Int> = emptyList()
        private set

    /** Timeout normale; i cloni economici a volte sono lenti. Modificabile. */
    var timeoutMs = 3000L

    /** Timeout lungo per ATZ e per la prima richiesta (ricerca protocollo). */
    var longTimeoutMs = 12000L

    /** Parole con cui l'ELM dice "niente dati / errore" (non sono dati veri). */
    private val errorWords = listOf(
        "NO DATA", "UNABLE TO CONNECT", "STOPPED", "CAN ERROR", "BUS ERROR",
        "BUS INIT", "ERROR", "FB ERROR", "DATA ERROR", "?",
    )

    /** Ripulisce la risposta grezza in righe: toglie eco, "SEARCHING...", righe vuote. */
    fun cleanLines(raw: String): List<String> =
        raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }

    /** Invia un comando e ritorna le righe pulite. */
    suspend fun send(cmd: String, timeout: Long = timeoutMs): List<String> =
        cleanLines(transport.exchange(cmd, timeout))

    /** Sequenza di inizializzazione. Ritorna un testo con l'esito (per il log/UI). */
    suspend fun init() {
        // ATZ: reset. Dopo il reset l'ELM ci mette un po' a rispondere.
        val z = send("ATZ", longTimeoutMs)
        version = z.lastOrNull { it.contains("ELM", ignoreCase = true) } ?: z.joinToString(" ")
        log("Versione ELM: $version")
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
            val r = send(c)
            if (r.none { it.contains("OK") }) log("Attenzione: '$c' ha risposto '${r.joinToString(" ")}'")
        }
        // Prima richiesta vera: l'ELM cerca il protocollo, può volerci parecchio.
        send("0100", longTimeoutMs)
        protocol = send("ATDP").firstOrNull() ?: "?"
        log("Protocollo: $protocol")
    }

    /** Legge 0100, 0120, 0140... finché il blocco successivo è dichiarato supportato. */
    suspend fun detectSupportedPids(): List<Int> {
        val found = mutableListOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val data = query(base) ?: break
            if (data.size < 4) break
            val pids = Pids.supportedFromMask(base, data)
            found += pids
            // l'ultimo bit del blocco = "esiste il blocco base+0x20"
            if ((base + 0x20) !in pids) break
            base += 0x20
        }
        supportedPids = found.filter { it % 0x20 != 0 } // togli i PID "maschera"
        return supportedPids
    }

    /**
     * Richiesta modo 01. Ritorna i byte di dati (senza "41 xx") oppure null se
     * l'auto non risponde / dice NO DATA.
     */
    suspend fun query(pid: Int, timeout: Long = timeoutMs): IntArray? {
        val cmd = "01%02X".format(pid)
        val lines = try {
            send(cmd, timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdTimeout) {
            log("Timeout su $cmd")
            return null
        }
        return parseMode01(lines, pid)
    }

    /** Tensione della batteria misurata dall'adattatore stesso (ATRV), es. "12.6V". */
    suspend fun batteryVoltage(): Double? {
        val line = send("ATRV").firstOrNull() ?: return null
        return Regex("""(\d+(\.\d+)?)\s*V""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** Legge e decodifica un PID noto. */
    suspend fun read(pid: Pid): Double? {
        val data = query(pid.id) ?: return null
        if (data.size < pid.bytes) return null
        return pid.decode(data)
    }

    companion object {
        /**
         * Cerca la riga "41 <pid> dati..." fra le righe di risposta.
         * Gestisce righe con o senza spazi ("410C1AF8") e risposte di più centraline
         * (prende la prima valida).
         */
        fun parseMode01(lines: List<String>, pid: Int): IntArray? {
            val prefix = "41%02X".format(pid)
            for (line in lines) {
                val hex = line.replace(" ", "").uppercase()
                if (hex.startsWith(prefix) && (hex.length - 4) % 2 == 0 &&
                    hex.all { it in "0123456789ABCDEF" }
                ) {
                    val body = hex.substring(4)
                    return IntArray(body.length / 2) { body.substring(it * 2, it * 2 + 2).toInt(16) }
                }
            }
            return null
        }

        /** Comandi consentiti dalla console manuale: solo lettura. Il modo 04 (cancella codici) è escluso. */
        fun isReadOnlyCommand(cmd: String): Boolean {
            val c = cmd.trim().uppercase().replace(" ", "")
            if (c.startsWith("AT")) return true
            if (c.length < 2 || !c.all { it in "0123456789ABCDEF" }) return false
            return c.substring(0, 2) in setOf("01", "02", "03", "07", "09", "0A")
        }
    }
}
