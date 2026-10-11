package it.ehimacchina.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: ObdViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    @SuppressLint("MissingPermission")
    @Composable
    private fun Screen() {
        val ui by vm.state.collectAsState()
        val debugLines by vm.debugLines.collectAsState()
        var mode by remember { mutableStateOf(Mode.SIMULATOR) }
        var debug by remember { mutableStateOf(false) }
        var devices by remember { mutableStateOf(bondedDevices()) }
        var device by remember { mutableStateOf<BluetoothDevice?>(null) }
        var replayFile by remember { mutableStateOf<File?>(null) }
        var command by remember { mutableStateOf("") }

        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            devices = bondedDevices()
        }
        LaunchedEffect(Unit) {
            if (!hasBtPermission()) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Pressione lunga sul titolo = schermata debug nascosta
            Text(
                "Ehi macchina · Fase 0",
                fontSize = 22.sp,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { debug = !debug }),
            )

            // --- scelta sorgente ---
            Mode.entries.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == m, onClick = { mode = m }, enabled = !ui.running)
                    Text(m.label)
                }
            }
            if (mode == Mode.REAL) {
                Picker(
                    label = "Adattatore (già associato in Impostazioni Bluetooth)",
                    options = devices.map { "${it.name} (${it.address})" },
                    selected = device?.let { "${it.name} (${it.address})" },
                    onSelect = { device = devices[it] },
                    enabled = !ui.running,
                )
            }
            if (mode == Mode.REPLAY) {
                val files = remember(ui.running) { vm.replayFiles() }
                Picker(
                    label = "Log da riprodurre",
                    options = files.map { it.name },
                    selected = replayFile?.name,
                    onSelect = { replayFile = files[it] },
                    enabled = !ui.running,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.running) {
                    Button(onClick = { vm.stop() }) { Text("Disconnetti") }
                } else {
                    Button(onClick = { vm.start(mode, device, replayFile) }) { Text("Connetti") }
                }
                OutlinedButton(onClick = { vm.latestLogFile()?.let { shareLog(it) } }) { Text("Condividi log") }
            }

            Text(ui.status, fontSize = 16.sp)
            if (ui.info.isNotEmpty()) Text(ui.info, fontSize = 13.sp)
            if (ui.supported.isNotEmpty()) {
                Text("PID supportati (* = non decodificato):", fontSize = 13.sp)
                Text(ui.supported, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            if (ui.battery.isNotEmpty()) ValueLine("Batteria (ATRV)", ui.battery)
            ui.values.forEach { ValueLine(it.name, it.text) }

            // --- schermata debug nascosta ---
            if (debug) {
                HorizontalDivider()
                Text("DEBUG — ultimi scambi con l'ELM327", fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = command, onValueChange = { command = it },
                        label = { Text("Comando (es. 0902, ATDP)") }, singleLine = true,
                        enabled = ui.running, modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { vm.sendManual(command); command = "" }, enabled = ui.running) { Text("Invia") }
                }
                ui.console.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                debugLines.takeLast(40).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }

    @Composable
    private fun ValueLine(name: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, fontSize = 18.sp)
            Text(value, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Picker(label: String, options: List<String>, selected: String?, onSelect: (Int) -> Unit, enabled: Boolean) {
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
            OutlinedTextField(
                value = selected ?: "", onValueChange = {}, readOnly = true, label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
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
