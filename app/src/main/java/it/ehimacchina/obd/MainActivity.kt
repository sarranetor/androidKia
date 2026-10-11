package it.ehimacchina.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Page { HOME, DIAGNOSIS, IDLE, CONSUMI, SERVER, DEBUG }


class MainActivity : ComponentActivity() {

    private val uiPrefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    /** Tema chiaro (giorno) o scuro (notte, predefinito); la scelta resta salvata. */
    private var lightTheme by mutableStateOf(false)

    private fun chooseTheme(light: Boolean) {
        lightTheme = light
        uiPrefs.edit().putBoolean("light", light).apply()
        applySystemBars(light)
    }

    /** Barre di sistema trasparenti con icone leggibili sul tema scelto. */
    private fun applySystemBars(light: Boolean) {
        val t = android.graphics.Color.TRANSPARENT
        if (light) enableEdgeToEdge(SystemBarStyle.light(t, t), SystemBarStyle.light(t, t))
        else enableEdgeToEdge(SystemBarStyle.dark(t), SystemBarStyle.dark(t))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        lightTheme = uiPrefs.getBoolean("light", false)
        applySystemBars(lightTheme) // gli spazi delle barre li gestisce safeDrawingPadding()
        super.onCreate(savedInstanceState)
        ObdEngine.attach(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // schermo sempre acceso
        setContent {
            EhiTheme(light = lightTheme) {
                Surface(Modifier.fillMaxSize(), color = Palette.Background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { Screen() }
                }
            }
        }
    }

    private fun hasBtPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<BluetoothDevice> {
        if (!hasBtPermission()) return emptyList()
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    private fun shareText(text: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, title))
    }

    private fun shareLog(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Condividi log"))
    }

    /** Avvia la lettura e il servizio in primo piano che tiene vivo il processo. */
    private fun connect(mode: Mode, address: String?, replayFile: File?) {
        ObdEngine.start(mode, address, replayFile)
        if (ObdEngine.state.value.running) {
            ContextCompat.startForegroundService(this, Intent(this, ObdService::class.java))
        }
    }

    // ------------------------------------------------------------------ schermate

    @SuppressLint("MissingPermission")
    @Composable
    private fun Screen() {
        val ui by ObdEngine.state.collectAsState()
        var page by rememberSaveable { mutableStateOf(Page.HOME) }

        // permessi: Bluetooth (Android 12+) e notifiche (Android 13+)
        var devices by remember { mutableStateOf(bondedDevices()) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            devices = bondedDevices()
        }
        LaunchedEffect(Unit) {
            val wanted = buildList {
                if (!hasBtPermission()) add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
            if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        }

        BackHandler(enabled = page != Page.HOME) {
            if (page == Page.IDLE) { ObdEngine.stopIdleView(); page = Page.DIAGNOSIS } else page = Page.HOME
        }

        when {
            page == Page.DIAGNOSIS -> DiagnosisPage(ui, onIdle = { ObdEngine.startIdleView(); page = Page.IDLE }) { page = Page.HOME }
            page == Page.IDLE -> IdlePage(ui) { ObdEngine.stopIdleView(); page = Page.DIAGNOSIS }
            page == Page.CONSUMI -> ConsumiPage(ui) { page = Page.HOME }
            page == Page.SERVER -> ServerPage { page = Page.HOME }
            page == Page.DEBUG -> DebugPage(ui) { page = Page.HOME }
            ui.running -> Dashboard(
                ui,
                onDiagnosis = { page = Page.DIAGNOSIS },
                onConsumi = { page = Page.CONSUMI },
                onDebug = { page = Page.DEBUG },
            )
            else -> ConnectPage(
                ui, devices,
                onDiagnosis = { page = Page.DIAGNOSIS },
                onConsumi = { page = Page.CONSUMI },
                onServer = { page = Page.SERVER },
                onDebug = { page = Page.DEBUG },
            )
        }
    }

    // ---- connessione (schermata iniziale)

    @OptIn(ExperimentalFoundationApi::class)
    @SuppressLint("MissingPermission")
    @Composable
    private fun ConnectPage(
        ui: UiState, devices: List<BluetoothDevice>,
        onDiagnosis: () -> Unit, onConsumi: () -> Unit, onServer: () -> Unit, onDebug: () -> Unit,
    ) {
        var mode by rememberSaveable { mutableStateOf(Mode.SIMULATOR) }
        var address by rememberSaveable { mutableStateOf<String?>(null) }
        var replayName by rememberSaveable { mutableStateOf<String?>(null) }
        val canConnect = when (mode) {
            Mode.REAL -> address != null
            Mode.REPLAY -> replayName != null
            Mode.SIMULATOR -> true
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // intestazione; pressione lunga sul titolo = schermata debug nascosta
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).combinedClickable(onClick = {}, onLongClick = onDebug),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_stat_gauge), contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ehi macchina", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Kia Cee'd 1.6 · diagnostica OBD", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(ui.status, if (ui.status.startsWith("Errore")) Palette.Crit else Palette.Muted)
                Spacer(Modifier.weight(1f))
                Text(if (lightTheme) "Tema chiaro" else "Tema scuro", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                Spacer(Modifier.width(8.dp))
                Switch(checked = lightTheme, onCheckedChange = { chooseTheme(it) })
            }

            SectionCard("Sorgente dati") {
                ChoiceCard(mode == Mode.REAL, "Auto", "Adattatore ELM327 via Bluetooth", painterResource(R.drawable.ic_stat_gauge), { mode = Mode.REAL })
                ChoiceCard(mode == Mode.SIMULATOR, "Simulatore", "Dati finti per prove (salvati come «simulati»)", rememberVectorPainter(Icons.Filled.PlayArrow), { mode = Mode.SIMULATOR })
                ChoiceCard(mode == Mode.REPLAY, "Riproduci log", "Rivedi una sessione registrata (dati «simulati»)", rememberVectorPainter(Icons.Filled.Refresh), { mode = Mode.REPLAY })
                if (mode == Mode.REAL) {
                    val names = devices.map { "${it.name} (${it.address})" }
                    Picker(
                        label = "Adattatore associato",
                        options = names,
                        selected = devices.indexOfFirst { it.address == address }.takeIf { it >= 0 }?.let { names[it] },
                        onSelect = { address = devices[it].address },
                    )
                    if (devices.isEmpty()) Hint("Nessun dispositivo associato: associa l'ELM327 in Impostazioni → Bluetooth (PIN 1234 o 0000).")
                }
                if (mode == Mode.REPLAY) {
                    val files = remember { ObdEngine.logFiles() }
                    Picker(
                        label = "Log da riprodurre",
                        options = files.map { it.name },
                        selected = replayName,
                        onSelect = { replayName = files[it].name },
                    )
                }
            }

            Button(
                onClick = { connect(mode, address, replayName?.let { n -> ObdEngine.logFiles().firstOrNull { it.name == n } }) },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Connetti", style = MaterialTheme.typography.titleMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavTile("Codici", Icons.Filled.Warning, onDiagnosis, Modifier.weight(1f))
                NavTile("Consumi", Icons.Filled.DateRange, onConsumi, Modifier.weight(1f))
                NavTile("Server", Icons.Filled.Settings, onServer, Modifier.weight(1f))
            }
            val simCount by remember { ObdEngine.db.dao().simulatedCount() }.collectAsState(0)
            var confirmDelete by remember { mutableStateOf(false) }
            if (simCount > 0) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Elimina dati di prova ($simCount)")
                }
            }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Eliminare i dati di prova?") },
                    text = { Text("Si cancellano viaggi, codici e rapporti nati col simulatore o con la riproduzione di un log. I dati veri dell'auto restano.") },
                    confirmButton = { TextButton(onClick = { confirmDelete = false; ObdEngine.deleteSimulatedData() }) { Text("Elimina") } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
                )
            }
            TextButton(onClick = { ObdEngine.latestLogFile()?.let { shareLog(it) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Condividi l'ultimo log")
            }
        }
    }

    // ---- cruscotto

    private class Tile(
        val label: String, val value: String, val unit: String,
        val color: Color = Color.Unspecified, val level: Float? = null, val levelColor: Color = Color.Unspecified,
    )

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Dashboard(ui: UiState, onDiagnosis: () -> Unit, onConsumi: () -> Unit, onDebug: () -> Unit) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val v = ui.values
        val alerts = Alerts.evaluate(v, ui.battery, ui.diagnosis)
        fun num(pid: Int, decimals: Int = 0) = v[pid]?.let { "%.${decimals}f".format(it) } ?: "—"

        val coolantLevel = Alerts.coolantLevel(v[0x05])
        val batteryLevel = Alerts.batteryLevel(ui.battery, v[0x0C])
        // consumo istantaneo: L/100 km in marcia, L/h da fermi
        val l100 = Consumption.litersPer100Km(ui.litersPerHour, v[0x0D])
        val instant = when {
            l100 != null -> Tile("Consumo", "%.1f".format(l100), "L/100km")
            ui.litersPerHour != null -> Tile("Consumo", "%.1f".format(ui.litersPerHour), "L/h")
            else -> Tile("Consumo", "—", "L/100km")
        }
        val trip = ui.trip
        val tiles = listOf(
            Tile("Giri", num(0x0C), "rpm", level = v[0x0C]?.let { (it / 6500).toFloat() }),
            Tile("Velocità", num(0x0D), "km/h", level = v[0x0D]?.let { (it / 180).toFloat() }),
            Tile(
                "Liquido", num(0x05), "°C", levelColor(coolantLevel),
                level = v[0x05]?.let { ((it - 40) / 80).toFloat() }, levelColor = levelColor(coolantLevel, Palette.Ok),
            ),
            Tile("Batteria", ui.battery?.let { "%.1f".format(it) } ?: "—", "V", levelColor(batteryLevel)),
            instant,
            Tile("Media viaggio", trip?.avgLitersPer100Km?.let { "%.1f".format(it) } ?: "—",
                if (trip != null) "L/100 · %.1f km".format(trip.km) else "L/100km"),
            Tile("Carico", num(0x04), "%", level = v[0x04]?.let { (it / 100).toFloat() }),
            Tile("Aria aspirata", num(0x0F), "°C"),
            Tile("MAP", num(0x0B), "kPa"),
            Tile("Farfalla", num(0x11), "%", level = v[0x11]?.let { (it / 100).toFloat() }),
        )
        val valueSp = if (landscape) 36.sp else 46.sp
        val statusColor = when {
            ui.status.startsWith("Lettura") -> Palette.Ok
            ui.status.startsWith("Errore") -> Palette.Crit
            else -> Palette.Warn
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // pressione lunga sullo stato = debug
                Row(
                    Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = onDebug).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(statusColor)
                    Spacer(Modifier.width(8.dp))
                    Text(ui.status, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1)
                }
                FilledTonalButton(onClick = onConsumi, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Consumi") }
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(onClick = onDiagnosis, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Codici") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { ObdEngine.stop() }, contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Crit),
                ) { Text("Stop") }
            }
            ui.drive?.let { dr ->
                InfoBanner(
                    "Test di guida in corso · ${DriveTestAnalyzer.mmss(dr.elapsedMs)} · ${dr.criteria.count { it.done }}/${dr.criteria.size} condizioni",
                    onClick = onDiagnosis,
                )
            }
            alerts.forEach {
                Banner(it.text, if (it.level == Level.CRIT) Palette.Crit else Palette.Warn, onClick = onDiagnosis)
            }
            // 5 colonne in orizzontale, 2 in verticale
            tiles.chunked(if (landscape) 5 else 2).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach {
                        MetricTile(
                            it.label, it.value, it.unit, valueSp, Modifier.weight(1f).fillMaxHeight(),
                            color = it.color.takeOrElse { Palette.Text }, level = it.level,
                            levelColor = it.levelColor.takeOrElse { Palette.Accent },
                        )
                    }
                }
            }
        }
    }

    // ---- diagnosi codici errore

    @Composable
    private fun DiagnosisPage(ui: UiState, onIdle: () -> Unit, onBack: () -> Unit) {
        val d = ui.diagnosis
        val history by remember { ObdEngine.db.dao().dtcFlow() }.collectAsState(emptyList())
        var confirmClear by remember { mutableStateOf(false) }
        val clearBlock = ClearGuard.check(ui.values)
        val hasCodes = d != null && (d.milOn == true || !d.stored.isNullOrEmpty() || !d.pending.isNullOrEmpty())
        val fmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.ITALY)

        PageScaffold(
            title = "Diagnosi",
            subtitle = d?.let { "Ultima lettura " + SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date(it.atMillis)) }
                ?: if (ui.running) "Lettura in corso…" else "Non connesso",
            onBack = onBack,
            actions = {
                FilledTonalButton(onClick = { ObdEngine.requestDiagnosis() }, enabled = ui.running && !ui.diagnosing) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (ui.diagnosing) "Lettura…" else "Leggi")
                }
            },
        ) {
            ui.notice?.let {
                Banner(it, Palette.Warn, Icons.Filled.Info)
                TextButton(onClick = { ObdEngine.dismissNotice() }) { Text("OK, ho letto") }
            }
            if (!ui.running) InfoBanner("Connettiti all'auto per leggere i codici e avviare il test di guida.")

            // test di guida in alto: è la cosa che si cerca di più
            DriveTestSection(ui)

            SectionCard("Prova al minimo") {
                Text(
                    "Correzione carburante e sonda lambda in diretta, a motore caldo e auto ferma. " +
                        "Serve a vedere subito l'effetto di una prova (es. tubo del canister chiuso).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(onClick = onIdle, enabled = ui.running, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Apri vista minimo")
                }
            }

            if (d != null) {
                SectionCard("Spia motore e codici") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val milColor = when (d.milOn) { true -> Palette.Crit; false -> Palette.Ok; null -> Palette.Muted }
                        StatusDot(milColor, 14.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when (d.milOn) { true -> "Spia motore ACCESA"; false -> "Spia motore spenta"; null -> "Spia motore non letta" },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = milColor,
                        )
                    }
                    d.reportedCount?.let { KeyValueRow("Codici dichiarati dalla centralina", "$it") }
                    CodeList("Memorizzati", d.stored, Palette.Crit)
                    CodeList("Pendenti (non ancora confermati)", d.pending, Palette.Warn)
                    Hint("P = motore/cambio · C = telaio · B = carrozzeria · U = rete fra centraline. Le spiegazioni dettagliate te le dà Claude.")
                }

                d.freezeFrame?.let { ff ->
                    SectionCard("Freeze frame · ${ff.code}") {
                        Hint("Valori registrati dalla centralina nell'istante in cui è scattato il codice.")
                        FreezeValues(ff)
                    }
                }

                if (hasCodes) {
                    SectionCard("Cancellazione codici") {
                        Hint(
                            "Prima di cancellare, codici e freeze frame vengono salvati nello storico. La cancellazione azzera anche " +
                                "i monitor di prontezza (servirà una guida per ricompletarli). Se il guasto c'è ancora, i codici tornano.",
                        )
                        OutlinedButton(
                            onClick = { confirmClear = true },
                            enabled = ui.running && !ui.diagnosing && clearBlock == null,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Crit),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Cancella codici…")
                        }
                        if (clearBlock != null && ui.running) Hint(clearBlock, Palette.Warn)
                    }
                }

                d.readiness?.let { r -> SectionCard("Monitor di prontezza") { ReadinessList(r) } }
            }

            SectionCard("Storico codici") {
                if (history.isEmpty()) Hint("Nessun codice registrato finora.")
                history.forEach { h ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CodeBadge(h.code, if (h.clearedMs == null && !h.simulated) Palette.Crit else Palette.Muted)
                            Spacer(Modifier.width(10.dp))
                            if (h.simulated) { SimBadge(); Spacer(Modifier.width(8.dp)) }
                            Text(
                                if (h.clearedMs == null) h.kind else "cancellato " + fmt.format(Date(h.clearedMs)),
                                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                            )
                        }
                        Hint("Prima volta ${fmt.format(Date(h.firstSeenMs))} · ultima ${fmt.format(Date(h.lastSeenMs))}")
                        h.freeze?.let { text -> FreezeFrame.fromText(text)?.let { FreezeValues(it) } }
                    }
                }
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.Crit) },
                title = { Text("Cancellare i codici errore?") },
                text = {
                    Text(
                        "Verranno cancellati i codici e il freeze frame dalla centralina (copia salvata nello storico) " +
                            "e si azzerano i monitor di prontezza. Se il guasto c'è ancora, i codici torneranno. " +
                            "Auto ferma, motore spento, quadro acceso.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; ObdEngine.requestClearCodes() }) {
                        Text("Cancella", color = Palette.Crit)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annulla") } },
            )
        }
    }

    @Composable
    private fun ReadinessList(r: Readiness) {
        r.monitors.forEach {
            CheckRow(it.complete, it.name, if (it.complete) null else "incompleto", pendingColor = Palette.Warn)
        }
        Hint("Incompleto = la centralina non ha ancora finito di valutare quel sistema (succede dopo una cancellazione dei codici o con poca guida a caldo).")
    }

    @Composable
    private fun DriveTestSection(ui: UiState) {
        val reports by remember { ObdEngine.db.dao().driveReportsFlow() }.collectAsState(emptyList())
        var openId by rememberSaveable { mutableStateOf<Long?>(null) }
        val fmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.ITALY)
        val drive = ui.drive

        SectionCard("Test di guida diagnostico") {
            if (drive == null) {
                Text(
                    "Guida 15–20 minuti: motore in temperatura, un tratto costante a 50–100 km/h e un minimo finale. " +
                        "L'app registra in background e crea un rapporto su sonde, catalizzatore, miscela, batteria e monitor.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Hint("Non usare il telefono mentre guidi.", Palette.Warn)
                Button(
                    onClick = { ObdEngine.startDriveTest() }, enabled = ui.running,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Avvia test di guida")
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(DriveTestAnalyzer.mmss(drive.elapsedMs), style = Numbers, fontSize = 40.sp, color = Palette.Accent)
                    Spacer(Modifier.width(8.dp))
                    Text("/ 15:00 · ${drive.criteria.count { it.done }} di ${drive.criteria.size} condizioni",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(bottom = 8.dp))
                }
                LevelBar((drive.elapsedMs / 900_000f), Palette.Accent)
                drive.criteria.forEach { CheckRow(it.done, it.label, it.progress) }
                drive.readiness?.let {
                    HorizontalDivider(color = Palette.Outline)
                    Text("Monitor di prontezza", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                    ReadinessList(it)
                }
                Hint("Condizioni indicative, non il ciclo ufficiale Kia. Puoi terminare quando vuoi.")
                Button(
                    onClick = { ObdEngine.stopDriveTest() },
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Done, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Termina e crea rapporto")
                }
            }

            ui.driveReport?.let { text ->
                HorizontalDivider(color = Palette.Outline)
                Text("Rapporto", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ReportText(text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shareText(text, "Condividi rapporto") }) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Condividi")
                    }
                    OutlinedButton(onClick = { ObdEngine.dismissDriveReport() }) { Text("Chiudi") }
                }
            }

            if (reports.isNotEmpty()) {
                HorizontalDivider(color = Palette.Outline)
                Text("Rapporti precedenti", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                reports.forEach { r ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${fmt.format(Date(r.startMs))} · ${DriveTestAnalyzer.mmss(r.endMs - r.startMs)}",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            if (r.simulated) SimBadge()
                            TextButton(onClick = { openId = if (openId == r.id) null else r.id }) {
                                Text(if (openId == r.id) "Nascondi" else "Apri")
                            }
                        }
                        if (openId == r.id) {
                            ReportText(r.text)
                            TextButton(onClick = { shareText(r.text, "Condividi rapporto") }) { Text("Condividi") }
                        }
                    }
                }
            }
        }
    }

    /** Testo del rapporto: le righe [ATTENZIONE]/[info]/[ok] colorate. */
    @Composable
    private fun ReportText(text: String) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            text.lines().forEach { line ->
                val t = line.trim()
                val color = when {
                    t.startsWith("[ATTENZIONE]") -> Palette.Warn
                    t.startsWith("[ok") -> Palette.Ok
                    t.startsWith("[info") -> Palette.Accent
                    t.isNotEmpty() && t == t.uppercase() && t.any { it.isLetter() } -> Palette.Muted
                    else -> Palette.Text
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }

    @Composable
    private fun FreezeValues(ff: FreezeFrame) {
        ff.values.forEach { (id, value) ->
            val pid = Pids.get(id)
            // decimali solo dove servono (tensioni sonde, lambda)
            val decimals = when (pid?.unit) { "V", "λ" -> 2; "%", "°" -> 1; else -> 0 }
            KeyValueRow(pid?.name ?: "PID %02X".format(id), "%.${decimals}f %s".format(value, pid?.unit ?: ""))
        }
    }

    @Composable
    private fun CodeList(title: String, codes: List<String>?, color: Color) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
        when {
            codes == null -> Hint("Non letti (nessuna risposta valida).", Palette.Warn)
            codes.isEmpty() -> CheckRow(true, "Nessuno")
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { codes.forEach { CodeBadge(it, color) } }
        }
    }

    // ---- prova al minimo (diretta)

    @Composable
    private fun IdlePage(ui: UiState, onBack: () -> Unit) {
        val idle = ui.idle
        val sm = idle?.summary
        val last = idle?.history?.lastOrNull()
        val v = ui.values
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

        PageScaffold("Prova al minimo", subtitle = "Correzione e sonda in diretta", onBack = onBack) {
            if (!ui.running) InfoBanner("Connettiti all'auto: la vista si aggiorna solo con l'app collegata.")
            Hint("Motore caldo, auto ferma in folle o P, freno a mano tirato. Attenzione a ventola e parti calde se apri il cofano.", Palette.Warn)

            // stato della regolazione (PID 03)
            val loop = v[0x03]
            StatusPill(
                IdleMonitor.loopLabel(loop),
                when (loop?.toInt()) { 2 -> Palette.Ok; null -> Palette.Muted; else -> Palette.Warn },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val total = (v[0x06] ?: 0.0) + (v[0x07] ?: 0.0)
                val totalColor = when {
                    v[0x06] == null -> Palette.Text
                    kotlin.math.abs(total) >= 10 -> Palette.Warn
                    else -> Palette.Text
                }
                MetricTile(
                    "Correzione totale", if (v[0x06] != null) "%+.1f".format(total) else "—", "%", 40.sp,
                    Modifier.weight(1f).height(130.dp), color = totalColor,
                )
                val o2 = v[0x14]
                val o2Color = when { o2 == null -> Palette.Text; o2 > 0.7 -> Palette.Warn; o2 < 0.2 -> Palette.Accent; else -> Palette.Text }
                MetricTile(
                    "Sonda a monte", o2?.let { "%.2f".format(it) } ?: "—", "V", 40.sp,
                    Modifier.weight(1f).height(130.dp), color = o2Color,
                )
            }

            SectionCard("Sonda a monte (ultimi 2 min)") {
                Sparkline(idle?.history?.map { it.o2 } ?: emptyList(), 0.0, 1.0, 0.45, Palette.Accent)
                Hint("Sana e in regolazione: oscilla sopra e sotto la linea (0,45 V) più volte al minuto. Sempre in alto = miscela ricca.")
            }
            SectionCard("Correzione breve (ultimi 2 min)") {
                Sparkline(idle?.history?.map { it.stft } ?: emptyList(), -25.0, 25.0, 0.0, Palette.Warn)
                Hint("Sotto lo zero la centralina toglie benzina, sopra ne aggiunge. Ferma = non sta correggendo.")
            }

            SectionCard("Ultimi 30 secondi") {
                if (sm == null || sm.samples < 3) Hint("Raccolta dati in corso…")
                else IdleSummaryRows(sm)
                KeyValueRow("Correzione lunga (LTFT)", v[0x07]?.let { "%+.1f %%".format(it) } ?: "—")
                KeyValueRow("Giri", v[0x0C]?.let { "%.0f".format(it) } ?: "—")
                KeyValueRow("Temperatura liquido", v[0x05]?.let { "%.0f °C".format(it) } ?: "—")
                KeyValueRow("Spurgo canister comandato", v[0x2E]?.let { "%.0f %%".format(it) } ?: "—")
            }

            SectionCard("Confronto prima / dopo") {
                Text(
                    "1. Aspetta 1 minuto al minimo e premi «Fotografa».  2. Fai la prova (es. stringi o tappa il tubo della " +
                        "valvola del canister).  3. Aspetta 1 minuto e fotografa di nuovo.  Se la correzione va verso lo zero e la sonda " +
                        "torna a oscillare, la prova ha trovato il colpevole.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { ObdEngine.idleSnapshot() }, enabled = ui.running && (sm?.samples ?: 0) >= 3, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fotografa ultimi 30 s")
                }
                val shots = idle?.snapshots.orEmpty()
                val first = shots.firstOrNull()?.summary
                shots.forEach { shot ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Fotografia ${shot.number} · ${fmt.format(Date(shot.atMs))}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        IdleSummaryRows(shot.summary)
                        if (shot.number > 1 && first?.total != null && shot.summary.total != null) {
                            val delta = shot.summary.total - first.total
                            Hint("Rispetto alla prima: correzione %+.1f punti".format(delta), if (delta > 3) Palette.Ok else Palette.Muted)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun IdleSummaryRows(s: IdleMonitor.Summary) {
        KeyValueRow("Correzione totale media", s.total?.let { "%+.1f %%".format(it) } ?: "—",
            if ((s.total ?: 0.0) <= -10 || (s.total ?: 0.0) >= 10) Palette.Warn else Palette.Text)
        KeyValueRow("Sonda a monte media", s.o2Mean?.let { "%.2f V".format(it) } ?: "—")
        KeyValueRow("Tempo sul ricco (> 0,7 V)", s.richShare?.let { "%.0f %%".format(it * 100) } ?: "—",
            if ((s.richShare ?: 0.0) >= 0.9) Palette.Warn else Palette.Text)
        KeyValueRow("Passaggi ricco/magro al minuto", s.switchesPerMin?.let { "%.0f".format(it) } ?: "—")
        s.closedLoopShare?.let { KeyValueRow("In anello chiuso", "%.0f %%".format(it * 100)) }
    }

    // ---- server

    @Composable
    private fun ServerPage(onBack: () -> Unit) {
        val saved by ObdEngine.settings.config.collectAsState()
        val sync by ObdEngine.sync.state.collectAsState()
        val pending by remember { ObdEngine.db.dao().outboxCount() }.collectAsState(0)
        var url by rememberSaveable { mutableStateOf(saved.url) }
        var token by rememberSaveable { mutableStateOf(saved.token) }
        var enabled by rememberSaveable { mutableStateOf(saved.enabled) }
        var sendSim by rememberSaveable { mutableStateOf(saved.sendSimulated) }
        var message by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val fmt = SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.ITALY)
        val draft = SyncConfig(url, token, enabled, sendSim)

        val (stateText, stateColor) = when {
            !saved.ready -> "Invio non attivo" to Palette.Muted
            sync.authRejected -> "Token rifiutato" to Palette.Crit
            sync.lastError != null -> "Problemi di invio" to Palette.Warn
            sync.lastOkMs != null -> "Collegato" to Palette.Ok
            else -> "In attesa del primo invio" to Palette.Accent
        }

        PageScaffold("Server", subtitle = "Invio dati a Claude", onBack = onBack) {
            SectionCard("Stato") {
                StatusPill(stateText, stateColor)
                KeyValueRow("Eventi in coda", "$pending")
                KeyValueRow("Ultimo invio riuscito", sync.lastOkMs?.let { fmt.format(Date(it)) } ?: "mai")
                sync.lastError?.let { Hint("Ultimo errore: $it", Palette.Crit) }
                if (sync.authRejected) Hint("Il token è stato rifiutato: correggilo e premi Salva.", Palette.Crit)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { ObdEngine.sync.kick() }, enabled = saved.ready && pending > 0) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Invia ora")
                    }
                    OutlinedButton(
                        onClick = { scope.launch { message = "Storico messo in coda: ${ObdEngine.queueHistory()} eventi" } },
                        enabled = saved.ready,
                    ) { Text("Invia storico") }
                }
                Hint(
                    "«Invia storico» manda viaggi, codici e rapporti già presenti nell'app, anche quelli nati col simulatore: " +
                        "usalo solo quando nell'app ci sono dati veri dell'auto. Si può ripetere senza creare doppioni.",
                )
            }

            SectionCard("Configurazione") {
                Hint(
                    "Valori ogni 15 s mentre guidi, viaggi, codici errore e rapporti vanno al tuo server Cloudflare, " +
                        "da cui Claude li legge. Senza internet restano in coda e partono dopo.",
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Indirizzo (https://…workers.dev)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, label = { Text("Token segreto") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                SwitchRow("Invio attivo", null, enabled) { enabled = it }
                SwitchRow("Invia anche i dati del simulatore", "Solo per prove: di norma i dati finti restano sul telefono.", sendSim) { sendSim = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        ObdEngine.settings.save(draft)
                        ObdEngine.sync.kick()
                        message = "Salvato"
                    }) { Text("Salva") }
                    OutlinedButton(onClick = {
                        message = "Prova in corso…"
                        scope.launch { message = ObdEngine.sync.ping(draft) }
                    }) { Text("Invia prova") }
                }
                message?.let { Hint(it, if (it.startsWith("Collegamento riuscito") || it == "Salvato") Palette.Ok else Palette.Warn) }
            }
        }
    }

    @Composable
    private fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) Hint(description)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    // ---- consumi, viaggi, rifornimenti

    @Composable
    private fun ConsumiPage(ui: UiState, onBack: () -> Unit) {
        val trips by remember { ObdEngine.db.dao().tripsFlow() }.collectAsState(emptyList())
        val refuels by remember { ObdEngine.db.dao().refuelsFlow() }.collectAsState(emptyList())
        var liters by rememberSaveable { mutableStateOf("") }
        var km by rememberSaveable { mutableStateOf("") }
        val fmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.ITALY)

        // riepilogo degli ultimi 30 giorni (solo viaggi non trascurabili)
        val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val recent = trips.filter { it.startMs >= since && it.km >= 0.5 && !it.simulated }
        val totalKm = recent.sumOf { it.km }
        val totalL = recent.sumOf { it.fuelL } * ui.calibration

        PageScaffold("Consumi", subtitle = "Viaggi e rifornimenti", onBack = onBack) {
            SectionCard("Ultimi 30 giorni (solo dati veri)") {
                Row {
                    SummaryNumber(if (totalKm >= 1) "%.1f".format(totalL / totalKm * 100) else "—", "L/100km medi", Modifier.weight(1f))
                    SummaryNumber("%.0f".format(totalKm), "km", Modifier.weight(1f))
                    SummaryNumber("${recent.size}", "viaggi", Modifier.weight(1f))
                }
                KeyValueRow("Fattore di taratura", "%.2f".format(ui.calibration))
                Hint("1,00 = stima non corretta. Si aggiorna da solo a ogni rifornimento inserito.")
            }

            SectionCard("Nuovo rifornimento") {
                Hint("Litri messi al pieno e km fatti dal pieno precedente.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = liters, onValueChange = { liters = it }, label = { Text("Litri") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = km, onValueChange = { km = it }, label = { Text("Km") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
                val l = liters.replace(',', '.').toDoubleOrNull()
                val k = km.replace(',', '.').toDoubleOrNull()
                Button(
                    onClick = { ObdEngine.addRefuel(l!!, k!!); liters = ""; km = "" },
                    enabled = l != null && k != null && l > 0 && k > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aggiungi rifornimento")
                }
                refuels.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmt.format(Date(r.dateMs)), style = MaterialTheme.typography.bodyMedium)
                            Hint("%.1f L · %.0f km · %.1f L/100km".format(r.liters, r.km, r.liters / r.km * 100))
                        }
                        IconButton(onClick = { ObdEngine.deleteRefuel(r.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Elimina", tint = Palette.Muted)
                        }
                    }
                }
            }

            SectionCard("Viaggi") {
                if (trips.isEmpty()) Hint("Nessun viaggio registrato.")
                trips.forEach { t ->
                    val minutes = (t.endMs - t.startMs) / 60_000
                    val avg = if (t.km >= 0.3) "%.1f".format(t.fuelL * ui.calibration / t.km * 100) else "—"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fmt.format(Date(t.startMs)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (t.simulated) { Spacer(Modifier.width(8.dp)); SimBadge() }
                            }
                            Hint("%.1f km · %d min · max %.0f km/h".format(t.km, minutes, t.maxSpeed))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(avg, style = Numbers, fontSize = 22.sp, color = Palette.Accent)
                            Hint("L/100km")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SummaryNumber(value: String, label: String, modifier: Modifier = Modifier) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = Numbers, fontSize = 28.sp, color = Palette.Text)
            Hint(label)
        }
    }

    // ---- debug (nascosto: pressione lunga sul titolo / sulla riga di stato)

    @Composable
    private fun DebugPage(ui: UiState, onBack: () -> Unit) {
        val debugLines by ObdEngine.debugLines.collectAsState()
        var command by remember { mutableStateOf("") }
        PageScaffold(
            "Debug", subtitle = ui.status, onBack = onBack,
            actions = {
                IconButton(onClick = { ObdEngine.latestLogFile()?.let { shareLog(it) } }) {
                    Icon(Icons.Filled.Share, contentDescription = "Condividi log")
                }
            },
        ) {
            SectionCard("Adattatore") {
                Text(ui.info.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
                if (ui.supported.isNotEmpty()) {
                    Hint("PID supportati (* = non decodificato):")
                    Text(
                        ui.supported.joinToString(" ") { "%02X".format(it) + if (Pids.get(it) == null) "*" else "" },
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    )
                }
            }
            SectionCard("Console (sola lettura)") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = command, onValueChange = { command = it },
                        label = { Text("Comando (es. 0902, ATDP)") }, singleLine = true,
                        enabled = ui.running, modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { ObdEngine.sendManual(command); command = "" }, enabled = ui.running) { Text("Invia") }
                }
                ui.console.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
            SectionCard("Ultimi scambi") {
                debugLines.takeLast(40).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.Muted) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Picker(label: String, options: List<String>, selected: String?, onSelect: (Int) -> Unit) {
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = selected ?: "", onValueChange = {}, readOnly = true, label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (options.isEmpty()) DropdownMenuItem(text = { Text("(nessuno)") }, onClick = { open = false })
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}
