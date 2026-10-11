package it.ehimacchina.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** ELM327 via Bluetooth classico (RFCOMM / profilo SPP). L'adattatore deve essere già associato nelle impostazioni Android. */
@SuppressLint("MissingPermission") // il permesso BLUETOOTH_CONNECT è chiesto in MainActivity prima di arrivare qui
class BluetoothTransport(private val device: BluetoothDevice) : Transport {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        close()
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: java.io.IOException) {
            // Molti cloni economici non annunciano il servizio SPP: si ripiega sul canale 1 diretto.
            @Suppress("DiscouragedPrivateApi")
            val fallback = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
            fallback.connect()
            fallback
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    override suspend fun exchange(cmd: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val inp = input ?: throw java.io.IOException("non connesso")
        val out = output ?: throw java.io.IOException("non connesso")

        // scarta eventuali byte rimasti da una risposta precedente
        while (inp.available() > 0) inp.read()

        out.write((cmd.trim() + "\r").toByteArray())
        out.flush()

        // InputStream.read() blocca: si legge solo quando c'è qualcosa, fino al prompt '>' o al timeout
        val buf = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                buf.append(inp.read().toChar())
                if (buf.endsWith(">")) return@withContext buf.dropLast(1).toString()
            } else {
                delay(5)
            }
        }
        throw ObdTimeout(cmd)
    }

    companion object {
        /** UUID standard del profilo seriale Bluetooth (SPP) */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
