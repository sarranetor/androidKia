package it.ehimacchina.obd

import kotlinx.coroutines.delay
import java.io.File

/**
 * Riproduce un log registrato in auto come se l'adattatore fosse collegato.
 * Per ogni comando restituisce le risposte registrate in ordine, e quando finiscono
 * riparte dalla prima. Comandi mai visti: "OK" per gli AT, "NO DATA" per gli altri.
 */
class ReplayTransport(lines: List<String>) : Transport {
    constructor(file: File) : this(file.readLines())

    private val responses = HashMap<String, MutableList<String>>()
    private val position = HashMap<String, Int>()

    init {
        for (line in lines) {
            val parts = line.split('\t')
            if (parts.size < 3 || parts[1].startsWith("#")) continue
            responses.getOrPut(parts[1]) { mutableListOf() }.add(parts[2].replace('|', '\r'))
        }
    }

    override suspend fun open() {}
    override fun close() {}

    override suspend fun exchange(cmd: String, timeoutMs: Long): String {
        delay(20)
        val list = responses[cmd.trim()] ?: return if (cmd.uppercase().startsWith("AT")) "OK" else "NO DATA"
        val i = position.getOrDefault(cmd.trim(), 0)
        position[cmd.trim()] = (i + 1) % list.size
        return list[i]
    }
}
