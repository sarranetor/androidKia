package it.ehimacchina.obd

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

/** Viaggio in corso, con consumo già tarato */
data class TripInfo(val startMs: Long, val km: Double, val fuelL: Double) {
    val avgLitersPer100Km: Double? get() = if (km >= 0.3) fuelL / km * 100 else null
}

/** Test di guida in corso: stato per la schermata */
data class DriveUi(
    val startMs: Long,
    val elapsedMs: Long,
    val criteria: List<Criterion>,
    val readiness: Readiness?,
)

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
    /** Viaggio in corso (null se il motore è spento) */
    val trip: TripInfo? = null,
    /** Consumo istantaneo tarato, L/h */
    val litersPerHour: Double? = null,
    /** Fattore di taratura dei consumi (1.0 = nessuna taratura) */
    val calibration: Double = 1.0,
    /** Messaggio una tantum per l'utente (es. esito cancellazione codici) */
    val notice: String? = null,
    /** Test di guida diagnostico in corso (null se non attivo) */
    val drive: DriveUi? = null,
    /** Testo dell'ultimo rapporto di test di guida, da mostrare/condividere */
    val driveReport: String? = null,
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
        data object ClearCodes : Command
        data object StartDrive : Command
        data object StopDrive : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val tracker = TripTracker()
    private var tripId: Long? = null
    private var lastSampleMs = 0L
    private var lastTripSaveMs = 0L
    /** Fattore di taratura dei consumi, ricalcolato dai rifornimenti */
    private var calibration = 1.0

    // test di guida diagnostico
    private var drive: DriveTestAnalyzer? = null
    private var driveStartDiag: Diagnosis? = null
    private var lastReadinessMs = 0L
    private var driveSilentSinceMs = 0L

    val db: AppDatabase get() = AppDatabase.get(app)
    private val currentLog = MutableStateFlow<SessionLog?>(null)

    /** Ultime righe scambiate con l'adattatore, per la schermata di debug. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val debugLines: StateFlow<List<String>> =
        currentLog.flatMapLatest { it?.recentLines ?: flowOf(emptyList()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Va chiamato prima di ogni altro uso (Activity e Service); sicuro chiamarlo più volte. */
    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext as Application
        settings = SyncSettings(app)
        sync = SyncManager(RoomOutbox(db.dao()), UrlConnectionPoster(), { settings.config.value })
        sync.start(scope)
    }

    lateinit var settings: SyncSettings
        private set
    lateinit var sync: SyncManager
        private set

    /** Mette un evento in coda per il server (solo se l'invio è configurato e attivo). */
    private suspend fun enqueue(json: String) {
        val c = settings.config.value
        if (!c.ready) return
        if (currentMode != Mode.REAL && !c.sendSimulated) return // dati finti: restano sul telefono
        val dao = db.dao()
        dao.insertOutbox(OutboxEntity(createdMs = System.currentTimeMillis(), json = json))
        dao.trimOutbox(MAX_OUTBOX)
        sync.kick()
    }

    /** Attese fra un tentativo di riconnessione e il successivo (l'ultima si ripete) */
    private var currentMode = Mode.REAL
    private const val MAX_OUTBOX = 5000
    private const val SNAPSHOT_EVERY_MS = 15_000L
    private var lastSnapshotSentMs = 0L

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
        currentMode = mode
        val log = SessionLog.newSession(app.filesDir, tag)
        currentLog.value = log
        // running = true subito (non dentro la coroutine): il Service lo guarda appena parte
        _state.value = UiState(running = true, status = "Connessione…")

        job = scope.launch {
            try {
                refreshCalibration()
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
                withContext(NonCancellable) {
                    finishDrive(null) // se un test di guida era in corso, il rapporto si crea con i dati raccolti
                    endTrip(tracker.finish(System.currentTimeMillis()))
                }
                _state.update { it.copy(running = false, trip = null, litersPerHour = null, drive = null) }
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

    /** Cancella i codici errore (modo 04). Il controllo auto ferma/motore spento è rifatto al momento dell'invio. */
    fun requestClearCodes() {
        commands.trySend(Command.ClearCodes)
    }

    /** Avvia il test di guida diagnostico (15–20 min): registra a velocità più alta i PID utili e crea un rapporto. */
    fun startDriveTest() {
        if (drive == null) commands.trySend(Command.StartDrive)
    }

    fun stopDriveTest() {
        commands.trySend(Command.StopDrive)
    }

    fun dismissDriveReport() = _state.update { it.copy(driveReport = null) }

    /** Mette in coda tutto lo storico già presente nell'app (viaggi, codici, rapporti), per il primo collegamento al server. */
    suspend fun queueHistory(): Int {
        if (!settings.config.value.ready) return 0
        val dao = db.dao()
        var n = 0
        refreshCalibration()
        dao.allTrips().filter { it.km >= 0.1 }.forEach {
            enqueue(Events.trip(it.startMs, it.endMs, it.km, it.fuelL * calibration, it.maxSpeed)); n++
        }
        dao.allDtcs().forEach { enqueue(Events.dtc(it)); n++ }
        dao.allDriveReports().forEach { enqueue(Events.driveReport(it.startMs, it.endMs, it.text)); n++ }
        return n
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun addRefuel(liters: Double, km: Double) {
        scope.launch {
            db.dao().insertRefuel(RefuelEntity(dateMs = System.currentTimeMillis(), liters = liters, km = km))
            refreshCalibration()
        }
    }

    fun deleteRefuel(id: Long) {
        scope.launch {
            db.dao().deleteRefuel(id)
            refreshCalibration()
        }
    }

    private suspend fun refreshCalibration() {
        val dao = db.dao()
        calibration = Calibration.factor(
            dao.allRefuels().map { RefuelRec(it.dateMs, it.liters, it.km) },
            dao.allTrips().map { TripRec(it.startMs, it.km, it.fuelL) },
        )
        _state.update { it.copy(calibration = calibration) }
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

        val normalPoller = Poller(elm, supported.mapNotNull { Pids.get(it) })
        // durante il test di guida: sonde O2 e giri a ogni ciclo, 3 PID lenti a rotazione
        fun known(ids: List<Int>) = ids.filter { it in supported }.mapNotNull { Pids.get(it) }
        val drivePoller = Poller(
            elm, known(listOf(0x14, 0x15, 0x0C, 0x0D, 0x04, 0x0B)), known(listOf(0x03, 0x05, 0x06, 0x07, 0x3C, 0x44, 0x0F, 0x43)), 3,
        )
        var lastBattery = 0L
        // all'avvio una diagnosi automatica, così il cruscotto sa subito se ci sono codici
        commands.trySend(Command.Diagnose)
        _state.update { it.copy(diagnosing = true) }

        while (true) {
            handleCommands(elm)

            val poller = if (drive != null) drivePoller else normalPoller
            poller.cycle { pid, value ->
                _state.update {
                    it.copy(values = if (value == null) it.values - pid.id else it.values + (pid.id to value))
                }
            }
            trackTrip()
            trackDrive(elm, 0x3C in supported)
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
                    recordDiagnosis(d)
                    _state.update { it.copy(diagnosis = d, diagnosing = false) }
                }
                Command.ClearCodes -> clearCodes(elm)
                Command.StartDrive -> startDrive(elm)
                Command.StopDrive -> finishDrive(elm)
            }
        }
    }

    private suspend fun startDrive(elm: Elm327) {
        if (drive != null) return
        val now = System.currentTimeMillis()
        val diag = elm.readDiagnosis() // punto di partenza: codici e monitor prima della guida
        recordDiagnosis(diag)
        driveStartDiag = diag
        drive = DriveTestAnalyzer(now).also {
            it.readinessStart = diag.readiness
            it.readinessNow = diag.readiness
        }
        lastReadinessMs = now
        _state.update { it.copy(diagnosis = diag, driveReport = null, notice = null) }
    }

    /** Aggiunge il campione al test di guida e aggiorna l'avanzamento; ogni 10 s rilegge i monitor di prontezza. */
    private suspend fun trackDrive(elm: Elm327, hasCatalystTemp: Boolean) {
        val d = drive ?: return
        val now = System.currentTimeMillis()
        val s = _state.value
        if (s.values.isEmpty()) {
            // quadro spento: si chiude il test prima di raccogliere solo "NO DATA"
            if (driveSilentSinceMs == 0L) driveSilentSinceMs = now
            if (now - driveSilentSinceMs >= 30_000) {
                finishDrive(elm)
                driveSilentSinceMs = 0L
            }
            return
        }
        driveSilentSinceMs = 0L
        d.add(now, s.values, s.battery)
        if (now - lastReadinessMs >= 10_000) {
            lastReadinessMs = now
            elm.query(0x01)?.let { Readiness.parse(it) }?.let { d.readinessNow = it }
        }
        _state.update {
            it.copy(drive = DriveUi(d.startMs, now - d.startMs, d.criteria(now, hasCatalystTemp), d.readinessNow))
        }
    }

    /**
     * Chiude il test di guida e crea il rapporto (salvato nel database). Con [elm] null
     * (collegamento chiuso) si usano solo i dati già raccolti.
     */
    private suspend fun finishDrive(elm: Elm327?) {
        val d = drive ?: return
        drive = null
        val endDiag = try {
            elm?.readDiagnosis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        endDiag?.let {
            recordDiagnosis(it)
            it.readiness?.let { r -> d.readinessNow = r }
        }
        val now = System.currentTimeMillis()
        val report = d.report(now, driveStartDiag, endDiag)
        db.dao().insertDriveReport(DriveReportEntity(startMs = report.startMs, endMs = report.endMs, text = report.text))
        enqueue(Events.driveReport(report.startMs, report.endMs, report.text))
        _state.update { it.copy(drive = null, driveReport = report.text, diagnosis = endDiag ?: it.diagnosis) }
    }

    /** Cancellazione codici: ricontrolla le condizioni, salva codici e freeze frame, poi invia "04". */
    private suspend fun clearCodes(elm: Elm327) {
        ClearGuard.check(_state.value.values)?.let { reason ->
            _state.update { it.copy(notice = "Cancellazione annullata: $reason") }
            return
        }
        // prima di perdere tutto: fotografia di codici e freeze frame nel database
        val before = elm.readDiagnosis()
        recordDiagnosis(before)
        val ok = elm.clearCodes()
        val dao = db.dao()
        val now = System.currentTimeMillis()
        if (ok) dao.openDtcs().forEach {
            val cleared = it.copy(clearedMs = now)
            dao.updateDtc(cleared)
            enqueue(Events.dtc(cleared))
        }
        val after = elm.readDiagnosis()
        _state.update {
            it.copy(
                diagnosis = after,
                notice = if (ok) "Codici cancellati. I monitor di prontezza sono azzerati: servirà una guida per ricompletarli."
                else "La centralina non ha confermato la cancellazione.",
            )
        }
    }

    /** Salva nello storico i codici visti (con freeze frame, se c'è e non era già salvato) e avvisa il server. */
    private suspend fun recordDiagnosis(d: Diagnosis) {
        if (d.milOn == null && d.stored == null) return // centralina non raggiungibile: niente da registrare
        val dao = db.dao()
        val all = (d.stored.orEmpty().map { it to "memorizzato" } + d.pending.orEmpty().map { it to "pendente" })
            .distinctBy { it.first }
        for ((code, kind) in all) {
            val open = dao.openDtc(code)
            val freeze = d.freezeFrame?.textFor(code)
            if (open == null) {
                dao.insertDtc(DtcEntity(code = code, kind = kind, firstSeenMs = d.atMillis, lastSeenMs = d.atMillis, clearedMs = null, freeze = freeze))
            } else {
                // ripara anche i freeze frame salvati per errore sul codice sbagliato (versioni precedenti)
                val keep = open.freeze?.takeIf { it.startsWith(code) }
                dao.updateDtc(open.copy(kind = kind, lastSeenMs = d.atMillis, freeze = keep ?: freeze))
            }
        }
        // codici aperti che la centralina non riporta più: spariti da soli (o cancellati altrove)
        if (d.stored != null && d.pending != null) {
            dao.openDtcs().filter { o -> all.none { it.first == o.code } }.forEach {
                dao.updateDtc(it.copy(clearedMs = d.atMillis))
            }
        }
        // al server tutti i codici ancora aperti (poche righe; l'invio è idempotente, quindi anche i codici
        // già presenti prima di attivare l'invio arrivano), più quelli appena cancellati
        dao.allDtcs().filter { it.clearedMs == null || it.clearedMs == d.atMillis }.forEach { enqueue(Events.dtc(it)) }
        val s = _state.value
        enqueue(Events.status(d.atMillis, s.info, s.supported, calibration, d))
    }

    /** Aggiorna viaggio e consumi dopo ogni ciclo di lettura; salva su database. */
    private suspend fun trackTrip() {
        val now = System.currentTimeMillis()
        val v = _state.value.values
        when (val ev = tracker.tick(now, v)) {
            is TripTracker.Event.Started -> {
                tripId = db.dao().insertTrip(TripEntity(startMs = ev.startMs, endMs = now, km = 0.0, fuelL = 0.0, maxSpeed = 0.0))
                lastSampleMs = now; lastTripSaveMs = now; lastSnapshotSentMs = 0
            }
            is TripTracker.Event.Ended -> endTrip(ev)
            null -> {}
        }
        val lph = Consumption.fuelLitersPerHour(v)?.times(calibration)
        _state.update {
            it.copy(
                litersPerHour = lph,
                trip = if (tracker.active) TripInfo(tracker.currentStartMs, tracker.km, tracker.fuelL * calibration) else null,
            )
        }
        val id = tripId
        if (id != null && tracker.active) {
            if (now - lastSampleMs >= 2000) {
                lastSampleMs = now
                val s = _state.value
                db.dao().insertSample(
                    SampleEntity(
                        tripId = id, tMs = now, rpm = v[0x0C], speed = v[0x0D], coolant = v[0x05],
                        load = v[0x04], mapKpa = v[0x0B], battery = s.battery, lph = lph,
                    ),
                )
            }
            if (now - lastSnapshotSentMs >= SNAPSHOT_EVERY_MS) {
                lastSnapshotSentMs = now
                enqueue(Events.snapshot(now, v, _state.value.battery, lph, tracker.km))
            }
            if (now - lastTripSaveMs >= 30_000) {
                lastTripSaveMs = now
                saveTrip(id, now, tracker.maxSpeed)
            }
        }
    }

    private suspend fun saveTrip(id: Long, endMs: Long, maxSpeed: Double = 0.0) {
        db.dao().updateTrip(
            TripEntity(id = id, startMs = tracker.currentStartMs, endMs = endMs, km = tracker.km, fuelL = tracker.fuelL, maxSpeed = maxSpeed),
        )
    }

    /** Chiude il viaggio: salva il riepilogo (o scarta le accensioni trascurabili) e ricalcola la taratura. */
    private suspend fun endTrip(ev: TripTracker.Event.Ended?) {
        val id = tripId ?: return
        tripId = null
        val sm = ev?.summary ?: return
        val dao = db.dao()
        if (sm.worthSaving) {
            dao.updateTrip(TripEntity(id, sm.startMs, sm.endMs, sm.km, sm.fuelL, sm.maxSpeed))
            refreshCalibration()
            enqueue(Events.trip(sm.startMs, sm.endMs, sm.km, sm.fuelL * calibration, sm.maxSpeed))
        } else {
            dao.deleteSamples(id)
            dao.deleteTrip(id)
        }
    }

    private fun addConsole(line: String) = _state.update { it.copy(console = (it.console + line).takeLast(50)) }
    private fun setStatus(s: String) = _state.update { it.copy(status = s) }
}
