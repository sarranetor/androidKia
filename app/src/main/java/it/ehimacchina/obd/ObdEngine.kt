package it.ehimacchina.obd

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

enum class Mode(val label: String) {
    REAL("Auto (Bluetooth)"),
    SIMULATOR("Simulatore"),
    REPLAY("Riproduci log"),
}

data class UiState(
    val running: Boolean = false,
    val status: String = "Non connesso",
    val info: String = "",
    val supported: List<Int> = emptyList(),
    /** Ultimo valore letto per ogni PID (la chiave è il numero del PID) */
    val values: Map<Int, Double> = emptyMap(),
    /** Tensione misurata dall'adattatore (ATRV) */
    val battery: Double? = null,
    val diagnosis: Diagnosis? = null,
    val diagnosing: Boolean = false,
    val console: List<String> = emptyList(),
)

/**
 * Tiene la connessione con l'auto, indipendente dalle schermate: l'Activity può essere
 * chiusa/ruotata e la lettura continua (il processo è tenuto vivo da [ObdService]).
 */
object ObdEngine {
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private sealed interface Command {
        data class Manual(val cmd: String) : Command
        data object Diagnose : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val currentLog = MutableStateFlow<SessionLog?>(null)

    /** Ultime righe scambiate con l'adattatore, per la schermata di debug. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val debugLines: StateFlow<List<String>> =
        currentLog.flatMapLatest { it?.recentLines ?: flowOf(emptyList()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Va chiamato prima di ogni altro uso (Activity e Service); sicuro chiamarlo più volte. */
    fun attach(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext as Application
    }

    /** Attese fra un tentativo di riconnessione e il successivo (l'ultima si ripete) */
    private val retryDelaysSec = listOf(2L, 5L, 10L)

    fun logFiles(): List<File> =
        SessionLog.logsDir(app.filesDir).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun latestLogFile(): File? = logFiles().firstOrNull()

    fun start(mode: Mode, deviceAddress: String?, replayFile: File?) {
        if (job?.isActive == true) return
        val tag = when (mode) {
            Mode.REAL -> "auto"
            Mode.SIMULATOR -> "sim"
            Mode.REPLAY -> "replay"
        }
        if ((mode == Mode.REAL && deviceAddress == null) || (mode == Mode.REPLAY && replayFile == null)) {
            _state.update { it.copy(status = "Scegli prima un dispositivo / un file") }
            return
        }
        val log = SessionLog.newSession(app.filesDir, tag)
        currentLog.value = log
        // running = true subito (non dentro la coroutine): il Service lo guarda appena parte
        _state.value = UiState(running = true, status = "Connessione…")

        job = scope.launch {
            try {
                var attempt = 0
                while (true) {
                    val transport = LoggingTransport(makeTransport(mode, deviceAddress, replayFile), log)
                    try {
                        runSession(transport, log, attempt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("sessione interrotta: ${e.message}")
                        if (mode != Mode.REAL) { // simulatore/riproduzione non si riconnettono
                            setStatus("Errore: ${e.message}")
                            break
                        }
                    } finally {
                        transport.close()
                    }
                    attempt++
                    val wait = retryDelaysSec[minOf(attempt - 1, retryDelaysSec.lastIndex)]
                    setStatus("Collegamento perso — riprovo tra ${wait}s (tentativo $attempt)")
                    delay(wait * 1000)
                }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(status = "Disconnesso", running = false) }
    }

    /** Comando manuale dalla schermata debug (solo lettura, vedi [Elm327.isReadOnlyCommand]). */
    fun sendManual(cmd: String) {
        if (!Elm327.isReadOnlyCommand(cmd)) {
            addConsole("RIFIUTATO (solo lettura): $cmd")
            return
        }
        commands.trySend(Command.Manual(cmd.trim()))
    }

    /** Legge i codici errore. Il risultato arriva in [UiState.diagnosis]. */
    fun requestDiagnosis() {
        if (_state.value.diagnosing) return
        _state.update { it.copy(diagnosing = true) }
        commands.trySend(Command.Diagnose)
    }

    @SuppressLint("MissingPermission") // permesso chiesto da MainActivity prima di arrivare qui
    private fun makeTransport(mode: Mode, address: String?, replayFile: File?): Transport = when (mode) {
        Mode.SIMULATOR -> SimulatedTransport()
        Mode.REPLAY -> ReplayTransport(replayFile!!)
        Mode.REAL -> {
            val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: throw IOException("Bluetooth non disponibile")
            BluetoothTransport(adapter.getRemoteDevice(address))
        }
    }

    /** Una sessione completa: connessione, init, rilevamento PID, lettura continua. Esce solo con un'eccezione. */
    private suspend fun runSession(transport: Transport, log: SessionLog, attempt: Int) {
        val elm = Elm327(transport) { log.note(it) }
        setStatus(if (attempt == 0) "Connessione…" else "Riconnessione (tentativo $attempt)…")
        transport.open()
        setStatus("Inizializzazione ELM327…")
        elm.init()
        _state.update { it.copy(info = "${elm.version} · ${elm.protocol}", status = "Ricerca PID supportati…") }
        val supported = elm.detectSupportedPids()
        elm.tryResponseCount()
        _state.update { it.copy(supported = supported, status = "Lettura in corso") }

        val poller = Poller(elm, supported.mapNotNull { Pids.get(it) })
        var lastBattery = 0L
        // all'avvio una diagnosi automatica, così il cruscotto sa subito se ci sono codici
        commands.trySend(Command.Diagnose)
        _state.update { it.copy(diagnosing = true) }

        while (true) {
            handleCommands(elm)

            poller.cycle { pid, value ->
                _state.update {
                    it.copy(values = if (value == null) it.values - pid.id else it.values + (pid.id to value))
                }
            }
            if (System.currentTimeMillis() - lastBattery > 5000) {
                lastBattery = System.currentTimeMillis()
                val v = elm.batteryVoltage()
                _state.update { it.copy(battery = v) }
            }
            if (elm.consecutiveTimeouts >= 5) throw IOException("l'adattatore non risponde")
            // auto spenta / niente da leggere: non martellare l'adattatore
            if (poller.isEmpty || _state.value.values.isEmpty()) delay(500)
        }
    }

    /** Esegue i comandi in coda (manuali e diagnosi), uno alla volta, fra un ciclo e l'altro. */
    private suspend fun handleCommands(elm: Elm327) {
        while (true) {
            when (val c = commands.tryReceive().getOrNull() ?: return) {
                is Command.Manual -> {
                    val reply = try {
                        elm.send(c.cmd, elm.longTimeoutMs).joinToString(" | ")
                    } catch (e: ObdTimeout) {
                        "timeout"
                    }
                    addConsole("${c.cmd} → $reply")
                }
                Command.Diagnose -> {
                    val d = elm.readDiagnosis()
                    _state.update { it.copy(diagnosis = d, diagnosing = false) }
                }
            }
        }
    }

    private fun addConsole(line: String) = _state.update { it.copy(console = (it.console + line).takeLast(50)) }
    private fun setStatus(s: String) = _state.update { it.copy(status = s) }
}
