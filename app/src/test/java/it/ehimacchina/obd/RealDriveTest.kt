package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Guida reale dell'11/10/2026 (29 min, 28 km, fino a 130 km/h) e fine sessione a quadro spento.
 * Errori trovati con questi dati: "NO DATA" a quadro spento scambiato per "nessun codice";
 * analisi del catalizzatore falsata dai tagli d'iniezione; miscela ricca al minimo non segnalata.
 */
class RealDriveTest {
    private fun lines(name: String): List<String> {
        val stream = javaClass.getResourceAsStream(name)!!
        val text = if (name.endsWith(".gz")) GZIPInputStream(stream).bufferedReader().readText() else stream.bufferedReader().readText()
        return text.lines().filter { it.isNotBlank() }
    }

    @Test fun ignitionOffMeansUnknownNotNoCodes() = runBlocking {
        val elm = Elm327(ReplayTransport(lines("/cee-d-quadro-spento.log")))
        val d = elm.readDiagnosis()
        assertNull("spia sconosciuta", d.milOn)
        assertNull("codici memorizzati sconosciuti, non 'nessuno'", d.stored)
        assertNull(d.pending)
        assertNull(d.freezeFrame)
    }

    @Test fun realDiagnosisAndFreezeFrameBelongToP0650() = runBlocking {
        val elm = Elm327(ReplayTransport(lines("/cee-d-guida-29min.log.gz")))
        elm.init()
        val d = elm.readDiagnosis()
        assertEquals(listOf("P0650", "P0172"), d.stored)
        assertEquals(listOf("P0650", "P0420"), d.pending)
        val ff = d.freezeFrame!!
        assertEquals("P0650", ff.code)
        assertEquals(0.0, ff.values[0x0C]!!, 0.001) // scattato a quadro acceso, motore spento
        assertNotNull(ff.textFor("P0650"))
        assertNull(ff.textFor("P0172"))
        assertNull(ff.textFor("P0420"))
    }

    /** Ricostruisce i campioni come fa l'app: stato dei valori aggiornato a ogni risposta, un campione per lettura dei giri. */
    private fun analyzerFromLog(): DriveTestAnalyzer {
        val rows = lines("/cee-d-guida-29min.log.gz").map { it.split('\t') }.filter { it.size >= 3 }
        val start = 1791729593000L
        val a = DriveTestAnalyzer(start)
        val values = HashMap<Int, Double>()
        var battery: Double? = null
        for (r in rows) {
            val ts = r[0].toLong()
            if (ts < start) continue
            val cmd = r[1]
            if (cmd == "ATRV") battery = Regex("""(\d+[.,]\d+)""").find(r[2])?.groupValues?.get(1)?.replace(',', '.')?.toDouble()
            if (!(cmd.length == 5 && cmd.startsWith("01"))) continue
            val pid = cmd.substring(2, 4).toInt(16)
            val p = Pids.get(pid) ?: continue
            val data = Elm327.parseMode01(r[2].split('|'), pid)
            if (data == null || data.size < p.bytes) values.remove(pid) else values[pid] = p.decode(data)
            if (pid == 0x0C) a.add(ts, HashMap(values), battery)
        }
        return a
    }

    @Test fun realDriveReport() {
        val a = analyzerFromLog()
        val r = a.report(1791731372000L, null, null)
        java.io.File("build/rapporto-guida-reale.txt").writeText(r.text) // per rileggerlo a mano
        val att = r.findings.filter { it.level == FindingLevel.ATTENZIONE }.joinToString("\n") { it.text }
        assertTrue(r.text, att.contains("P0420"))                 // la sonda a valle copia quella a monte anche a regime stabile
        assertTrue(r.text, att.contains("Al minimo"))             // miscela ricca al minimo (prima non segnalata)
        assertTrue(r.text, att.contains("P0172"))
        assertTrue(r.text, r.text.contains("regime stabile") || r.text.contains("stabile"))
        assertTrue(r.findings.any { it.level == FindingLevel.OK && it.text.contains("alternatore") })
    }
}
