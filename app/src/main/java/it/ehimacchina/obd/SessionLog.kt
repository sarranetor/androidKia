package it.ehimacchina.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log di sessione su file. Una riga per scambio, separata da TAB:
 *   <epoch ms> <comando> <risposta, a-capo sostituiti da '|'> <durata ms>
 * Gli errori hanno il comando "#ERR". Lo stesso formato serve alla riproduzione
 * ([ReplayTransport]), quindi un log registrato in auto si rilegge a casa.
 */
class SessionLog(val file: File) {
    private val recent = ArrayDeque<String>()
    private val _recentLines = MutableStateFlow<List<String>>(emptyList())

    /** Ultime righe, per la schermata di debug. */
    val recentLines: StateFlow<List<String>> = _recentLines

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun exchange(cmd: String, response: String, elapsedMs: Long) {
        write(cmd, response.replace("\r\n", "|").replace('\r', '|').replace('\n', '|'), elapsedMs)
    }

    @Synchronized
    fun error(message: String) = write("#ERR", message.replace('\t', ' ').replace('\n', ' '), 0)

    @Synchronized
    fun note(message: String) = write("#NOTE", message.replace('\t', ' ').replace('\n', ' '), 0)

    private fun write(cmd: String, response: String, elapsedMs: Long) {
        val now = System.currentTimeMillis()
        file.appendText("$now\t$cmd\t$response\t$elapsedMs\n")
        val shown = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY).format(Date(now))}  $cmd  →  $response  (${elapsedMs}ms)"
        recent.addLast(shown)
        if (recent.size > 200) recent.removeFirst()
        _recentLines.value = recent.toList()
    }

    companion object {
        fun logsDir(filesDir: File) = File(filesDir, "logs")

        fun newSession(filesDir: File, tag: String): SessionLog {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(Date())
            return SessionLog(File(logsDir(filesDir), "sessione-$stamp-$tag.log"))
        }
    }
}

/** Avvolge un [Transport] registrando ogni scambio nel [SessionLog]. */
class LoggingTransport(private val inner: Transport, private val log: SessionLog) : Transport {
    override suspend fun open() {
        try {
            inner.open()
            log.note("connesso")
        } catch (e: Exception) {
            log.error("open: ${e.message}")
            throw e
        }
    }

    override fun close() {
        inner.close()
        log.note("chiuso")
    }

    override suspend fun exchange(cmd: String, timeoutMs: Long): String {
        val start = System.currentTimeMillis()
        return try {
            inner.exchange(cmd, timeoutMs).also {
                log.exchange(cmd, it, System.currentTimeMillis() - start)
            }
        } catch (e: ObdTimeout) {
            log.error("timeout dopo ${System.currentTimeMillis() - start}ms su '$cmd'")
            throw e
        } catch (e: java.io.IOException) {
            log.error("IO su '$cmd': ${e.message}")
            throw e
        }
    }
}
