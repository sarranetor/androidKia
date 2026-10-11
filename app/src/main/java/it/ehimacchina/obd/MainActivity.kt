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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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

private enum class Page { HOME, DIAGNOSIS, CONSUMI, DEBUG }

private val Red = Color(0xFFE53935)
private val Yellow = Color(0xFFFFB300)
private val Green = Color(0xFF66BB6A)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObdEngine.attach(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // schermo sempre acceso
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
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

        BackHandler(enabled = page != Page.HOME) { page = Page.HOME }

        when {
            page == Page.DIAGNOSIS -> DiagnosisPage(ui) { page = Page.HOME }
            page == Page.CONSUMI -> ConsumiPage(ui) { page = Page.HOME }
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
                onDebug = { page = Page.DEBUG },
            )
        }
    }

    // ---- connessione

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ConnectPage(
        ui: UiState, devices: List<BluetoothDevice>,
        onDiagnosis: () -> Unit, onConsumi: () -> Unit, onDebug: () -> Unit,
    ) {
        var mode by rememberSaveable { mutableStateOf(Mode.SIMULATOR) }
        var address by rememberSaveable { mutableStateOf<String?>(null) }
        var replayName by rememberSaveable { mutableStateOf<String?>(null) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Pressione lunga sul titolo = schermata debug nascosta
            Text(
                "Ehi macchina",
                fontSize = 24.sp,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDebug),
            )
            Mode.entries.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == m, onClick = { mode = m })
                    Text(m.label)
                }
            }
            if (mode == Mode.REAL) {
                @SuppressLint("MissingPermission")
                val names = devices.map { "${it.name} (${it.address})" }
                Picker(
                    label = "Adattatore (già associato in Impostazioni Bluetooth)",
                    options = names,
                    selected = devices.indexOfFirst { it.address == address }.takeIf { it >= 0 }?.let { names[it] },
                    onSelect = { address = devices[it].address },
                )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDiagnosis) { Text("Codici") }
                OutlinedButton(onClick = onConsumi) { Text("Consumi") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    connect(mode, address, replayName?.let { n -> ObdEngine.logFiles().firstOrNull { it.name == n } })
                }) { Text("Connetti") }
                OutlinedButton(onClick = { ObdEngine.latestLogFile()?.let { shareLog(it) } }) { Text("Condividi log") }
            }
            Text(ui.status, fontSize = 16.sp)
        }
    }

    // ---- cruscotto

    private class Tile(val label: String, val value: String, val unit: String, val color: Color)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Dashboard(ui: UiState, onDiagnosis: () -> Unit, onConsumi: () -> Unit, onDebug: () -> Unit) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val v = ui.values
        val alerts = Alerts.evaluate(v, ui.battery, ui.diagnosis)

        fun levelColor(l: Level?) = when (l) {
            Level.CRIT -> Red
            Level.WARN -> Yellow
            null -> Color.White
        }
        fun num(pid: Int, decimals: Int = 0) = v[pid]?.let { "%.${decimals}f".format(it) } ?: "—"

        // consumo istantaneo: L/100 km in marcia, L/h da fermi
        val l100 = Consumption.litersPer100Km(ui.litersPerHour, v[0x0D])
        val instant = when {
            l100 != null -> Tile("CONSUMO", "%.1f".format(l100), "L/100km", Color.White)
            ui.litersPerHour != null -> Tile("CONSUMO", "%.1f".format(ui.litersPerHour), "L/h", Color.White)
            else -> Tile("CONSUMO", "—", "L/100km", Color.White)
        }
        val trip = ui.trip
        val tripAvg = Tile(
            "MEDIA VIAGGIO",
            trip?.avgLitersPer100Km?.let { "%.1f".format(it) } ?: "—",
            if (trip != null) "L/100km · %.1f km".format(trip.km) else "L/100km",
            Color.White,
        )

        val tiles = listOf(
            Tile("GIRI", num(0x0C), "rpm", Color.White),
            Tile("VELOCITÀ", num(0x0D), "km/h", Color.White),
            Tile("LIQUIDO", num(0x05), "°C", levelColor(Alerts.coolantLevel(v[0x05]))),
            Tile("BATTERIA", ui.battery?.let { "%.1f".format(it) } ?: "—", "V",
                levelColor(Alerts.batteryLevel(ui.battery, v[0x0C]))),
            instant,
            tripAvg,
            Tile("CARICO", num(0x04), "%", Color.White),
            Tile("ARIA ASPIRATA", num(0x0F), "°C", Color.White),
            Tile("MAP", num(0x0B), "kPa", Color.White),
            Tile("FARFALLA", num(0x11), "%", Color.White),
        )
        val valueSp = if (landscape) 40.sp else 52.sp

        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.status, fontSize = 14.sp, color = Color.Gray, maxLines = 1,
                    modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = onDebug),
                )
                TextButton(onClick = onConsumi) { Text("Consumi") }
                TextButton(onClick = onDiagnosis) { Text("Codici") }
                TextButton(onClick = { ObdEngine.stop() }) { Text("Stop") }
            }
            ui.drive?.let { dr ->
                Text(
                    "Test di guida in corso · ${DriveTestAnalyzer.mmss(dr.elapsedMs)} · ${dr.criteria.count { it.done }}/${dr.criteria.size} condizioni",
                    fontSize = 14.sp, color = Green, maxLines = 1,
                    modifier = Modifier.combinedClickable(onClick = onDiagnosis),
                )
            }
            alerts.forEach {
                Text(
                    it.text, fontSize = 18.sp, color = Color.Black, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(if (it.level == Level.CRIT) Red else Yellow, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
            }
            // 5 colonne in orizzontale, 2 in verticale
            tiles.chunked(if (landscape) 5 else 2).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { TileView(it, valueSp, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }

    @Composable
    private fun TileView(t: Tile, valueSp: androidx.compose.ui.unit.TextUnit, modifier: Modifier) {
        Column(
            modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(t.label, fontSize = 12.sp, color = Color.Gray)
            Text(t.value, fontSize = valueSp, color = t.color, fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(t.unit, fontSize = 13.sp, color = Color.Gray, maxLines = 1)
        }
    }

    // ---- diagnosi codici errore

    @Composable
    private fun DiagnosisPage(ui: UiState, onBack: () -> Unit) {
        val d = ui.diagnosis
        val history by remember { ObdEngine.db.dao().dtcFlow() }.collectAsState(emptyList())
        var confirmClear by remember { mutableStateOf(false) }
        val clearBlock = ClearGuard.check(ui.values)
        val hasCodes = d != null && (d.milOn == true || !d.stored.isNullOrEmpty() || !d.pending.isNullOrEmpty())

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Diagnosi centralina", fontSize = 24.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ObdEngine.requestDiagnosis() }, enabled = ui.running && !ui.diagnosing) {
                    Text(if (ui.diagnosing) "Lettura…" else "Leggi codici")
                }
                OutlinedButton(onClick = onBack) { Text("Indietro") }
            }
            ui.notice?.let {
                Text(it, color = Yellow, modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).padding(8.dp))
                TextButton(onClick = { ObdEngine.dismissNotice() }) { Text("OK") }
            }
            // test di guida in alto: è la cosa che si cerca di più e i codici/freeze frame sono lunghi
            HorizontalDivider()
            DriveTestSection(ui)
            HorizontalDivider()
            if (!ui.running) Text("Connettiti all'auto per leggere i codici.", color = Color.Gray)
            if (d == null) {
                if (ui.running) Text("Lettura in corso…", color = Color.Gray)
            } else {
                val time = SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date(d.atMillis))
                Text("Ultima lettura: $time", fontSize = 13.sp, color = Color.Gray)
                Text(
                    when (d.milOn) {
                        true -> "Spia motore (MIL): ACCESA"
                        false -> "Spia motore (MIL): spenta"
                        null -> "Spia motore (MIL): non letta"
                    },
                    fontSize = 18.sp, color = if (d.milOn == true) Red else Green,
                )
                d.reportedCount?.let { Text("Codici dichiarati dalla centralina: $it") }
                CodeList("Codici memorizzati (modo 03)", d.stored)
                CodeList("Codici pendenti (modo 07)", d.pending)

                d.freezeFrame?.let { ff ->
                    HorizontalDivider()
                    Text("Freeze frame — condizioni quando è scattato ${ff.code}", fontSize = 16.sp)
                    FreezeValues(ff)
                }

                if (hasCodes) {
                    HorizontalDivider()
                    Text(
                        "Prima di cancellare: codici e freeze frame vengono salvati nello storico qui sotto. " +
                            "La cancellazione azzera anche i monitor di prontezza (serve una guida per ricompletarli).",
                        fontSize = 12.sp, color = Color.Gray,
                    )
                    Button(
                        onClick = { confirmClear = true },
                        enabled = ui.running && !ui.diagnosing && clearBlock == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                    ) { Text("Cancella codici…") }
                    if (clearBlock != null && ui.running) Text(clearBlock, fontSize = 13.sp, color = Yellow)
                }
                Text(
                    "Il prefisso indica la zona: P = motore/cambio, C = telaio, B = carrozzeria, U = rete di comunicazione.",
                    fontSize = 12.sp, color = Color.Gray,
                )
            }

            // ---- monitor di prontezza
            d?.readiness?.let { r ->
                HorizontalDivider()
                Text("Monitor di prontezza", fontSize = 18.sp)
                ReadinessList(r)
            }

            HorizontalDivider()
            Text("Storico codici", fontSize = 18.sp)
            if (history.isEmpty()) Text("nessun codice registrato finora", color = Color.Gray)
            history.forEach { h ->
                val fmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.ITALY)
                Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Text(h.code, fontSize = 20.sp, fontFamily = FontFamily.Monospace, color = if (h.clearedMs == null) Red else Color.Gray)
                    Text(
                        "${h.kind} · visto la prima volta ${fmt.format(Date(h.firstSeenMs))}, ultima ${fmt.format(Date(h.lastSeenMs))}" +
                            (h.clearedMs?.let { " · cancellato ${fmt.format(Date(it))}" } ?: ""),
                        fontSize = 12.sp, color = Color.Gray,
                    )
                    h.freeze?.let { text -> FreezeFrame.fromText(text)?.let { FreezeValues(it) } }
                }
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
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
                        Text("Cancella", color = Red)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annulla") } },
            )
        }
    }

    @Composable
    private fun ReadinessList(r: Readiness) {
        r.monitors.forEach {
            Text(
                (if (it.complete) "✓ " else "○ ") + it.name + if (it.complete) "" else " — incompleto",
                fontSize = 14.sp, color = if (it.complete) Green else Yellow,
            )
        }
        Text(
            "Un monitor incompleto = la centralina non ha ancora finito di valutare quel sistema (succede dopo una cancellazione dei codici o con poca guida a caldo).",
            fontSize = 12.sp, color = Color.Gray,
        )
    }

    @Composable
    private fun DriveTestSection(ui: UiState) {
        val reports by remember { ObdEngine.db.dao().driveReportsFlow() }.collectAsState(emptyList())
        var openId by rememberSaveable { mutableStateOf<Long?>(null) }
        val fmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.ITALY)
        val drive = ui.drive

        Text("Test di guida diagnostico", fontSize = 18.sp)
        if (drive == null) {
            Text(
                "Per una diagnosi attendibile serve guidare 15–20 minuti: motore a temperatura, un tratto costante a 50–100 km/h " +
                    "e un minimo finale. L'app registra tutto in background e crea un rapporto con indizi su sonde, " +
                    "catalizzatore, miscela, batteria e monitor di prontezza. Non usare il telefono mentre guidi.",
                fontSize = 13.sp, color = Color.Gray,
            )
            Button(onClick = { ObdEngine.startDriveTest() }, enabled = ui.running) { Text("Avvia test di guida") }
            if (!ui.running) Text("Connettiti all'auto per avviare il test.", fontSize = 13.sp, color = Color.Gray)
        } else {
            Text("In corso: ${DriveTestAnalyzer.mmss(drive.elapsedMs)}", fontSize = 22.sp, color = Green)
            drive.criteria.forEach {
                Text(
                    (if (it.done) "✓ " else "○ ") + it.label + " — " + it.progress,
                    fontSize = 14.sp, color = if (it.done) Green else Color.White,
                )
            }
            drive.readiness?.let { ReadinessList(it) }
            Text("Condizioni indicative, non il ciclo ufficiale Kia. Puoi terminare quando vuoi.", fontSize = 12.sp, color = Color.Gray)
            Button(onClick = { ObdEngine.stopDriveTest() }) { Text("Termina e crea rapporto") }
        }

        ui.driveReport?.let { text ->
            Text("Rapporto", fontSize = 16.sp)
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareText(text, "Condividi rapporto") }) { Text("Condividi rapporto") }
                OutlinedButton(onClick = { ObdEngine.dismissDriveReport() }) { Text("Chiudi") }
            }
        }

        if (reports.isNotEmpty()) {
            Text("Rapporti precedenti", fontSize = 16.sp)
            reports.forEach { r ->
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).padding(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${fmt.format(Date(r.startMs))} · ${DriveTestAnalyzer.mmss(r.endMs - r.startMs)}",
                            fontSize = 14.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { openId = if (openId == r.id) null else r.id }) {
                            Text(if (openId == r.id) "Nascondi" else "Apri")
                        }
                    }
                    if (openId == r.id) {
                        Text(r.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        TextButton(onClick = { shareText(r.text, "Condividi rapporto") }) { Text("Condividi") }
                    }
                }
            }
        }
    }

    @Composable
    private fun FreezeValues(ff: FreezeFrame) {
        ff.values.forEach { (id, value) ->
            val pid = Pids.get(id)
            Text(
                "${pid?.name ?: "PID %02X".format(id)}: %.1f %s".format(value, pid?.unit ?: ""),
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
        }
    }

    @Composable
    private fun CodeList(title: String, codes: List<String>?) {
        Text(title, fontSize = 16.sp)
        when {
            codes == null -> Text("non letti (nessuna risposta valida)", color = Yellow)
            codes.isEmpty() -> Text("nessuno", color = Green)
            else -> codes.forEach { Text(it, fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = Red) }
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

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Consumi e viaggi", fontSize = 24.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onBack) { Text("Indietro") }
            }

            Text("Rifornimento", fontSize = 18.sp)
            Text(
                "Inserisci i litri messi al pieno e i km fatti dal pieno precedente. " +
                    "L'app confronta con la sua stima e corregge i consumi.",
                fontSize = 12.sp, color = Color.Gray,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = liters, onValueChange = { liters = it }, label = { Text("Litri") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = km, onValueChange = { km = it }, label = { Text("Km dal pieno prec.") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
            }
            val l = liters.replace(',', '.').toDoubleOrNull()
            val k = km.replace(',', '.').toDoubleOrNull()
            Button(
                onClick = { ObdEngine.addRefuel(l!!, k!!); liters = ""; km = "" },
                enabled = l != null && k != null && l > 0 && k > 0,
            ) { Text("Aggiungi rifornimento") }
            Text("Fattore di taratura: %.2f (1.00 = stima non corretta)".format(ui.calibration), fontSize = 13.sp)
            refuels.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${fmt.format(Date(r.dateMs))} · %.1f L · %.0f km · %.1f L/100km".format(r.liters, r.km, r.liters / r.km * 100),
                        fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ObdEngine.deleteRefuel(r.id) }) { Text("Elimina") }
                }
            }

            HorizontalDivider()
            Text("Viaggi", fontSize = 18.sp)
            if (trips.isEmpty()) Text("nessun viaggio registrato", color = Color.Gray)
            trips.forEach { t ->
                val minutes = (t.endMs - t.startMs) / 60_000
                val avg = if (t.km >= 0.3) "%.1f L/100km".format(t.fuelL * ui.calibration / t.km * 100) else "—"
                Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Text(fmt.format(Date(t.startMs)), fontSize = 14.sp)
                    Text(
                        "%.1f km · %d min · %s · max %.0f km/h".format(t.km, minutes, avg, t.maxSpeed),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    )
                }
            }
        }
    }

    // ---- debug (nascosto: pressione lunga sul titolo / sulla riga di stato)

    @Composable
    private fun DebugPage(ui: UiState, onBack: () -> Unit) {
        val debugLines by ObdEngine.debugLines.collectAsState()
        var command by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DEBUG", fontSize = 20.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { ObdEngine.latestLogFile()?.let { shareLog(it) } }) { Text("Condividi log") }
                OutlinedButton(onClick = onBack) { Text("Indietro") }
            }
            Text(ui.status, fontSize = 14.sp)
            if (ui.info.isNotEmpty()) Text(ui.info, fontSize = 13.sp)
            if (ui.supported.isNotEmpty()) {
                Text("PID supportati (* = non decodificato):", fontSize = 13.sp)
                Text(
                    ui.supported.joinToString(" ") { "%02X".format(it) + if (Pids.get(it) == null) "*" else "" },
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    label = { Text("Comando (es. 0902, ATDP)") }, singleLine = true,
                    enabled = ui.running, modifier = Modifier.weight(1f),
                )
                Button(onClick = { ObdEngine.sendManual(command); command = "" }, enabled = ui.running) { Text("Invia") }
            }
            ui.console.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            HorizontalDivider()
            debugLines.takeLast(40).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
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
