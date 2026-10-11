package it.ehimacchina.obd

/**
 * Canale di comunicazione con l'ELM327. Le implementazioni:
 * Bluetooth reale, simulatore, riproduzione di un log registrato.
 * Una sola richiesta alla volta: [exchange] invia un comando e aspetta il prompt '>'.
 */
interface Transport {
    suspend fun open()
    fun close()

    /** Invia [cmd] e ritorna la risposta grezza (senza il prompt '>'). Lancia [ObdTimeout] se scade. */
    suspend fun exchange(cmd: String, timeoutMs: Long): String
}

class ObdTimeout(cmd: String) : Exception("Timeout in attesa della risposta a '$cmd'")
