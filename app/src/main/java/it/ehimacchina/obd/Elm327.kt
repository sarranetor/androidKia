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

    /** Timeout per la lettura dei codici errore (le risposte multi-frame sono più lente). */
    var diagTimeoutMs = 5000L

    /** Timeout consecutivi senza risposta: se sale, l'adattatore non risponde più (collegamento perso). */
    var consecutiveTimeouts = 0
        private set

    /**
     * Se vero le richieste del modo 01 finiscono con "1" (es. "010C1"): l'ELM sa che aspetta
     * una sola risposta e non perde tempo. Attivato da [tryResponseCount] solo se l'adattatore lo gestisce.
     */
    var useResponseCount = false
        private set

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
    suspend fun send(cmd: String, timeout: Long = timeoutMs): List<String> {
        try {
            val lines = cleanLines(transport.exchange(cmd, timeout))
            consecutiveTimeouts = 0
            return lines
        } catch (e: ObdTimeout) {
            consecutiveTimeouts++
            throw e
        }
    }

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
    suspend fun query(pid: Int, timeout: Long = timeoutMs, countSuffix: Boolean = false): IntArray? {
        val cmd = "01%02X".format(pid) + if (countSuffix) "1" else ""
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
        val line = try {
            send("ATRV").firstOrNull()
        } catch (e: ObdTimeout) {
            null
        } ?: return null
        return Regex("""(\d+(\.\d+)?)\s*V""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** Legge e decodifica un PID noto. */
    suspend fun read(pid: Pid): Double? {
        val data = query(pid.id, countSuffix = useResponseCount) ?: return null
        if (data.size < pid.bytes) return null
        return pid.decode(data)
    }

    /** Prova il suffisso "una risposta attesa" su un PID supportato; lo tiene attivo solo se l'adattatore risponde bene. */
    suspend fun tryResponseCount(): Boolean {
        val pid = supportedPids.firstOrNull { it == 0x0C } ?: supportedPids.firstOrNull { Pids.get(it) != null } ?: return false
        useResponseCount = query(pid, countSuffix = true)?.let { it.size >= (Pids.get(pid)?.bytes ?: 1) } == true
        log("Suffisso risposte attese: ${if (useResponseCount) "attivo" else "non supportato"}")
        return useResponseCount
    }

    /** Richiesta modo 02 (freeze frame), frame 0. Ritorna i byte di dati o null. */
    suspend fun queryFreeze(pid: Int): IntArray? {
        val lines = try {
            send("02%02X00".format(pid), diagTimeoutMs)
        } catch (e: ObdTimeout) {
            return null
        }
        return parseResponse(lines, 0x42, pid, 0)
    }

    /** Freeze frame del primo guasto, oppure null se la centralina non ne ha. */
    suspend fun readFreezeFrame(): FreezeFrame? {
        val dtc = queryFreeze(0x02) ?: return null
        if (dtc.size < 2 || (dtc[0] == 0 && dtc[1] == 0)) return null
        val code = Dtc.decode(dtc[0], dtc[1])
        // quali PID sono salvati nel freeze frame (stessa logica delle maschere del modo 01)
        val pids = mutableListOf<Int>()
        var base = 0x00
        while (base <= 0x60) {
            val mask = queryFreeze(base) ?: break
            if (mask.size < 4) break
            val found = Pids.supportedFromMask(base, mask)
            pids += found
            if ((base + 0x20) !in found) break
            base += 0x20
        }
        val values = LinkedHashMap<Int, Double>()
        for (id in pids) {
            if (id % 0x20 == 0 || id == 0x02) continue
            val pid = Pids.get(id) ?: continue
            val data = queryFreeze(id) ?: continue
            if (data.size >= pid.bytes) values[id] = pid.decode(data)
        }
        return FreezeFrame(code, values)
    }

    /**
     * CANCELLA i codici errore (modo 04) e azzera i monitor di prontezza.
     * Va chiamata SOLO da [ObdEngine] dopo conferma utente e [ClearGuard]. Ritorna true se la centralina conferma (44).
     */
    suspend fun clearCodes(): Boolean =
        try {
            send("04", diagTimeoutMs).any { it.replace(" ", "").startsWith("44") }
        } catch (e: ObdTimeout) {
            false
        }

    /** Diagnosi in sola lettura: stato spia (0101), codici memorizzati (03) e pendenti (07), freeze frame (02). */
    suspend fun readDiagnosis(): Diagnosis {
        val status = query(0x01, diagTimeoutMs)
        val milOn = status?.let { it[0] and 0x80 != 0 }
        val stored = readDtcs("03", 0x43)
        val pending = readDtcs("07", 0x47)
        // il freeze frame ha senso solo se c'è almeno un codice
        val hasCodes = milOn == true || !stored.isNullOrEmpty() || !pending.isNullOrEmpty()
        return Diagnosis(
            milOn = milOn,
            reportedCount = status?.let { it[0] and 0x7F },
            stored = stored,
            pending = pending,
            atMillis = System.currentTimeMillis(),
            freezeFrame = if (hasCodes) readFreezeFrame() else null,
        )
    }

    private suspend fun readDtcs(cmd: String, responseByte: Int): List<String>? =
        try {
            Dtc.parse(send(cmd, diagTimeoutMs), responseByte)
        } catch (e: ObdTimeout) {
            null
        }

    companion object {
        /**
         * Cerca la riga "41 <pid> dati..." fra le righe di risposta.
         * Gestisce righe con o senza spazi ("410C1AF8") e risposte di più centraline
         * (prende la prima valida).
         */
        fun parseMode01(lines: List<String>, pid: Int): IntArray? = parseResponse(lines, 0x41, pid, null)

        /**
         * Come [parseMode01] ma per qualunque modo: [responseByte] 0x41 (modo 01) o 0x42 (modo 02,
         * dove dopo il PID c'è il numero del frame, [frame]).
         */
        fun parseResponse(lines: List<String>, responseByte: Int, pid: Int, frame: Int?): IntArray? {
            val prefix = "%02X%02X".format(responseByte, pid) + (frame?.let { "%02X".format(it) } ?: "")
            for (line in lines) {
                val hex = line.replace(" ", "").uppercase()
                if (hex.startsWith(prefix) && (hex.length - prefix.length) % 2 == 0 &&
                    hex.all { it in "0123456789ABCDEF" }
                ) {
                    val body = hex.substring(prefix.length)
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
