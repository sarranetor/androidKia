package it.ehimacchina.obd

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/** Impostazioni del server, nelle preferenze private dell'app. */
data class SyncConfig(
    val url: String = "",
    val token: String = "",
    val enabled: Boolean = false,
    /** Solo per prove: invia anche i dati di simulatore e riproduzione (di norma restano sul telefono, per non sporcare il server vero) */
    val sendSimulated: Boolean = false,
) {
    val ready get() = enabled && url.isNotBlank() && token.isNotBlank()

    /** Base senza barra finale e con https:// se manca lo schema (http:// resta ammesso solo per localhost, vedi network_security_config) */
    val baseUrl: String
        get() {
            val u = url.trim().trimEnd('/')
            return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
        }
}

class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(
        SyncConfig(prefs.getString("url", "") ?: "", prefs.getString("token", "") ?: "", prefs.getBoolean("enabled", false), prefs.getBoolean("sendSimulated", false)),
    )
    val config: StateFlow<SyncConfig> = _config

    fun save(c: SyncConfig) {
        prefs.edit().putString("url", c.url.trim()).putString("token", c.token.trim()).putBoolean("enabled", c.enabled)
            .putBoolean("sendSimulated", c.sendSimulated).apply()
        _config.value = c.copy(url = c.url.trim(), token = c.token.trim())
    }
}

// ------------------------------------------------------------------ rete

sealed interface HttpResult {
    data class Response(val status: Int, val body: String) : HttpResult
    data class NetworkError(val message: String) : HttpResult
}

interface HttpPoster {
    suspend fun request(method: String, url: String, token: String, body: String?): HttpResult
}

/** Invio vero via HttpURLConnection. Non segue i redirect: il token non deve finire su un altro indirizzo. */
class UrlConnectionPoster : HttpPoster {
    override suspend fun request(method: String, url: String, token: String, body: String?): HttpResult =
        withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = method
                    c.connectTimeout = 10_000
                    c.readTimeout = 15_000
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("Authorization", "Bearer $token")
                    if (body != null) {
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = c.responseCode
                    val stream = if (status >= 400) c.errorStream else c.inputStream
                    HttpResult.Response(status, stream?.bufferedReader()?.use { it.readText() } ?: "")
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                HttpResult.NetworkError(e.message ?: e.javaClass.simpleName)
            }
        }
}

// ------------------------------------------------------------------ coda

class OutboxRow(val id: Long, val json: String)

interface OutboxStore {
    suspend fun next(limit: Int): List<OutboxRow>
    suspend fun remove(ids: List<Long>)
}

class RoomOutbox(private val dao: ObdDao) : OutboxStore {
    override suspend fun next(limit: Int) = dao.outboxBatch(limit).map { OutboxRow(it.id, it.json) }
    override suspend fun remove(ids: List<Long>) = dao.deleteOutbox(ids)
}

data class SyncState(
    val lastOkMs: Long? = null,
    val lastError: String? = null,
    /** Il server ha risposto 401/403: token sbagliato, si ferma finché non cambia la configurazione */
    val authRejected: Boolean = false,
)

enum class FlushOutcome { NOTHING, SENT, RETRY, AUTH_REJECTED, DROPPED }

/** Svuota la coda verso il server a gruppi, con attese crescenti se qualcosa va storto. */
class SyncManager(
    private val store: OutboxStore,
    private val poster: HttpPoster,
    private val config: () -> SyncConfig,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Sveglia il ciclo: c'è qualcosa di nuovo da inviare, o la configurazione è cambiata. */
    fun kick() {
        _state.update { it.copy(authRejected = false) }
        kicks.trySend(Unit)
    }

    /** Un tentativo: invia un gruppo (fino a [BATCH]) se c'è qualcosa in coda. */
    suspend fun flushOnce(): FlushOutcome {
        val c = config()
        if (!c.ready) return FlushOutcome.NOTHING
        if (_state.value.authRejected) return FlushOutcome.AUTH_REJECTED
        val rows = store.next(BATCH)
        if (rows.isEmpty()) return FlushOutcome.NOTHING

        val body = "{\"events\":[" + rows.joinToString(",") { it.json } + "]}"
        val ids = rows.map { it.id }
        return when (val r = poster.request("POST", c.baseUrl + "/api/ingest", c.token, body)) {
            is HttpResult.NetworkError -> {
                _state.update { it.copy(lastError = "rete: ${r.message}") }
                FlushOutcome.RETRY
            }
            is HttpResult.Response -> when (r.status) {
                in 200..299 -> {
                    store.remove(ids)
                    _state.update { it.copy(lastOkMs = now(), lastError = null) }
                    FlushOutcome.SENT
                }
                401, 403 -> {
                    _state.update { it.copy(lastError = "token rifiutato dal server (${r.status})", authRejected = true) }
                    FlushOutcome.AUTH_REJECTED
                }
                // 400/413/422: il gruppo è malformato, riprovare non servirebbe: si scarta per non bloccare la coda
                400, 413, 422 -> {
                    store.remove(ids)
                    _state.update { it.copy(lastError = "gruppo scartato dal server (${r.status}): ${r.body.take(120)}") }
                    FlushOutcome.DROPPED
                }
                else -> {
                    _state.update { it.copy(lastError = "server: ${r.status}") }
                    FlushOutcome.RETRY
                }
            }
        }
    }

    /** Prova di connessione (GET /api/ping): ritorna un messaggio leggibile. */
    suspend fun ping(c: SyncConfig): String {
        if (c.url.isBlank() || c.token.isBlank()) return "Inserisci indirizzo e token"
        return when (val r = poster.request("GET", c.baseUrl + "/api/ping", c.token, null)) {
            is HttpResult.NetworkError -> "Server non raggiungibile: ${r.message}"
            is HttpResult.Response -> when (r.status) {
                200 -> "Collegamento riuscito"
                401, 403 -> "Il server ha rifiutato il token"
                else -> "Risposta inattesa dal server: ${r.status}"
            }
        }
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            var failures = 0
            while (true) {
                when (flushOnce()) {
                    FlushOutcome.SENT, FlushOutcome.DROPPED -> failures = 0 // ce n'è forse altra: si riprova subito
                    FlushOutcome.NOTHING, FlushOutcome.AUTH_REJECTED -> {
                        failures = 0
                        withTimeoutOrNull(IDLE_WAIT_MS) { kicks.receive() }
                    }
                    FlushOutcome.RETRY -> {
                        failures++
                        withTimeoutOrNull(backoffMs(failures)) { kicks.receive() }
                    }
                }
            }
        }
    }

    companion object {
        const val BATCH = 50
        const val IDLE_WAIT_MS = 15_000L

        /** 15 s, 30 s, 60 s, ... fino a 5 minuti */
        fun backoffMs(failures: Int): Long = minOf(300_000L, 15_000L shl (failures - 1).coerceIn(0, 10))
    }
}
