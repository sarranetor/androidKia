package it.ehimacchina.obd

/**
 * Le novità di una lettura dei codici rispetto a quelli già aperti nello storico: la spia motore
 * di questa auto non si accende (P0650), quindi l'avviso dell'app è l'unico. I codici già noti non
 * si ripetono; un codice sparito che ricompare è una novità.
 */
data class CodeNews(val newCodes: List<String>, val confirmed: List<String>, val milTurnedOn: Boolean) {
    val title get() = when {
        newCodes.isNotEmpty() -> if (newCodes.size == 1) "Nuovo codice errore: ${newCodes[0]}" else "Nuovi codici errore: ${newCodes.joinToString()}"
        confirmed.isNotEmpty() -> "Codice confermato: ${confirmed.joinToString()}"
        else -> "Spia motore accesa"
    }

    val body get() = buildList {
        if (newCodes.isNotEmpty()) add("La centralina ha registrato un problema nuovo.")
        if (confirmed.isNotEmpty()) add("Un codice pendente è diventato memorizzato: ${confirmed.joinToString()}.")
        if (milTurnedOn) add("La centralina ha comandato la spia motore.")
        add("Dettagli nella pagina Diagnosi.")
    }.joinToString(" ")

    companion object {
        /** [openBefore] = codici aperti prima di questa lettura (codice → tipo). Null se non c'è niente di nuovo. */
        fun find(openBefore: Map<String, String>, stored: List<String>?, pending: List<String>?, milBefore: Boolean?, milNow: Boolean?): CodeNews? {
            val now = stored.orEmpty() + pending.orEmpty()
            val newCodes = now.distinct().filter { it !in openBefore }
            val confirmed = stored.orEmpty().filter { code -> openBefore[code]?.let { Dtc.KIND_STORED !in it } == true }
            val milTurnedOn = milBefore == false && milNow == true
            if (newCodes.isEmpty() && confirmed.isEmpty() && !milTurnedOn) return null
            return CodeNews(newCodes, confirmed, milTurnedOn)
        }
    }
}
