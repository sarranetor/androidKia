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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Page { HOME, DIAGNOSIS, DEBUG }

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
            page == Page.DEBUG -> DebugPage(ui) { page = Page.HOME }
            ui.running -> Dashboard(ui, onDiagnosis = { page = Page.DIAGNOSIS }, onDebug = { page = Page.DEBUG })
            else -> ConnectPage(ui, devices, onDebug = { page = Page.DEBUG })
        }
    }

    // ---- connessione

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ConnectPage(ui: UiState, devices: List<BluetoothDevice>, onDebug: () -> Unit) {
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
                Button(onClick = {
                    connect(mode, address, replayName?.let { n -> ObdEngine.logFiles().firstOrNull { it.name == n } })
                }) { Text("Connetti") }
                OutlinedButton(onClick = { ObdEngine.latestLogFile()?.let { shareLog(it) } }) { Text("Condividi log") }
            }
            Text(ui.status, fontSize = 16.sp)
        }
    }

    // ---- cruscotto

    private class Tile(val label: String, val value: String, val unit: String, val color: Color, val big: Boolean)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Dashboard(ui: UiState, onDiagnosis: () -> Unit, onDebug: () -> Unit) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val v = ui.values
        val alerts = Alerts.evaluate(v, ui.battery, ui.diagnosis)

        fun levelColor(l: Level?) = when (l) {
            Level.CRIT -> Red
            Level.WARN -> Yellow
            null -> Color.White
        }
        fun num(pid: Int, decimals: Int = 0) = v[pid]?.let { "%.${decimals}f".format(it) } ?: "—"

        val tiles = listOf(
            Tile("GIRI", num(0x0C), "rpm", Color.White, true),
            Tile("VELOCITÀ", num(0x0D), "km/h", Color.White, true),
            Tile("LIQUIDO", num(0x05), "°C", levelColor(Alerts.coolantLevel(v[0x05])), true),
            Tile("BATTERIA", ui.battery?.let { "%.1f".format(it) } ?: "—", "V",
                levelColor(Alerts.batteryLevel(ui.battery, v[0x0C])), true),
            Tile("CARICO", num(0x04), "%", Color.White, false),
            Tile("FARFALLA", num(0x11), "%", Color.White, false),
            Tile("ARIA ASPIRATA", num(0x0F), "°C", Color.White, false),
            Tile("MAP", num(0x0B), "kPa", Color.White, false),
        )

        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.status, fontSize = 14.sp, color = Color.Gray,
                    modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = onDebug),
                )
                TextButton(onClick = onDiagnosis) { Text("Diagnosi") }
                TextButton(onClick = { ObdEngine.stop() }) { Text("Stop") }
            }
            alerts.forEach {
                Text(
                    it.text, fontSize = 18.sp, color = Color.Black, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(if (it.level == Level.CRIT) Red else Yellow, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
            }
            // 4 colonne in orizzontale, 2 in verticale
            tiles.chunked(if (landscape) 4 else 2).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { TileView(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }

    @Composable
    private fun TileView(t: Tile, modifier: Modifier) {
        Column(
            modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp)).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(t.label, fontSize = 12.sp, color = Color.Gray)
            Text(t.value, fontSize = if (t.big) 56.sp else 40.sp, color = t.color, fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(t.unit, fontSize = 14.sp, color = Color.Gray)
        }
    }

    // ---- diagnosi codici errore

    @Composable
    private fun DiagnosisPage(ui: UiState, onBack: () -> Unit) {
        val d = ui.diagnosis
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
                Text(
                    "Solo lettura: l'app non cancella nessun codice. Il prefisso indica la zona: " +
                        "P = motore/cambio, C = telaio, B = carrozzeria, U = rete di comunicazione.",
                    fontSize = 12.sp, color = Color.Gray,
                )
            }
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
