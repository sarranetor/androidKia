package it.ehimacchina.obd

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class Mode(val label: String) {
    REAL("Auto (Bluetooth)"),
    SIMULATOR("Simulatore"),
    REPLAY("Riproduci log"),
}

data class ValueRow(val name: String, val text: String)

data class UiState(
    val running: Boolean = false,
    val status: String = "Non connesso",
    val info: String = "",
    val supported: String = "",
    val battery: String = "",
    val values: List<ValueRow> = emptyList(),
    val console: List<String> = emptyList(),
)

class ObdViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val filesDir = app.filesDir
    private var job: Job? = null
    private val manualCommands = Channel<String>(Channel.UNLIMITED)
    private val currentLog = MutableStateFlow<SessionLog?>(null)

    /** Ultime righe scambiate con l'adattatore, per la schermata di debug. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val debugLines: StateFlow<List<String>> = MutableStateFlow(emptyList<String>()).also { out ->
        viewModelScope.launch {
            currentLog.flatMapLatest { it?.recentLines ?: flowOf(emptyList()) }.collect { out.value = it }
        }
    }

    /** File di log più recente (sessione in corso o ultima), da condividere. */
    fun latestLogFile(): File? =
        SessionLog.logsDir(filesDir).listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

    fun replayFiles(): List<File> =
        SessionLog.logsDir(filesDir).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun start(mode: Mode, device: BluetoothDevice?, replayFile: File?) {
        if (job?.isActive == true) return
        val tag = when (mode) {
            Mode.REAL -> "auto"
            Mode.SIMULATOR -> "sim"
            Mode.REPLAY -> "replay"
        }
        val log = SessionLog.newSession(filesDir, tag)
        currentLog.value = log

        val base: Transport? = when (mode) {
            Mode.REAL -> device?.let { BluetoothTransport(it) }
            Mode.SIMULATOR -> SimulatedTransport()
            Mode.REPLAY -> replayFile?.let { ReplayTransport(it) }
        }
        if (base == null) {
            setStatus("Scegli prima un dispositivo / un file")
            return
        }
        val transport = LoggingTransport(base, log)
        val elm = Elm327(transport) { log.note(it) }

        job = viewModelScope.launch {
            _state.value = UiState(running = true, status = "Connessione…")
            try {
                transport.open()
                setStatus("Inizializzazione ELM327…")
                elm.init()
                _state.update { it.copy(info = "${elm.version} · ${elm.protocol}", status = "Ricerca PID supportati…") }
                val supported = elm.detectSupportedPids()
                _state.update { it.copy(supported = describeSupported(supported)) }
                pollLoop(elm, supported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("fine sessione: ${e.message}")
                setStatus("Errore: ${e.message}")
            } finally {
                transport.close()
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        setStatus("Disconnesso")
    }

    /** Comando manuale dalla schermata debug (solo lettura, vedi [Elm327.isReadOnlyCommand]). */
    fun sendManual(cmd: String) {
        if (!Elm327.isReadOnlyCommand(cmd)) {
            addConsole("RIFIUTATO (solo lettura): $cmd")
            return
        }
        manualCommands.trySend(cmd.trim())
    }

    private suspend fun pollLoop(elm: Elm327, supported: List<Int>) {
        val readable = supported.mapNotNull { Pids.get(it) }
        if (readable.isEmpty()) {
            setStatus("Collegato, ma nessun PID noto supportato (vedi log)")
        } else {
            setStatus("Lettura in corso")
        }
        val values = LinkedHashMap<String, String>()
        while (true) {
            // prima i comandi manuali in attesa
            while (true) {
                val cmd = manualCommands.tryReceive().getOrNull() ?: break
                val reply = try {
                    elm.send(cmd, elm.longTimeoutMs).joinToString(" | ")
                } catch (e: ObdTimeout) {
                    "timeout"
                }
                addConsole("$cmd → $reply")
            }
            for (pid in readable) {
                val v = elm.read(pid)
                values[pid.name] = if (v == null) "—" else "%.1f %s".format(v, pid.unit)
            }
            val battery = elm.batteryVoltage()
            _state.update {
                it.copy(
                    values = values.map { (k, v) -> ValueRow(k, v) },
                    battery = battery?.let { b -> "%.1f V".format(b) } ?: "—",
                )
            }
            // anche senza PID leggibili non si va a vuoto
            if (readable.isEmpty()) kotlinx.coroutines.delay(1000)
        }
    }

    private fun describeSupported(pids: List<Int>): String =
        pids.joinToString(" ") { "%02X".format(it) + if (Pids.get(it) == null) "*" else "" }

    private fun addConsole(line: String) = _state.update { it.copy(console = (it.console + line).takeLast(50)) }
    private fun setStatus(s: String) = _state.update { it.copy(status = s) }
}
