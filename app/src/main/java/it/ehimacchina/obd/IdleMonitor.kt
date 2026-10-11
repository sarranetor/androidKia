package it.ehimacchina.obd

/**
 * Vista "prova al minimo": tiene gli ultimi minuti di sonda a monte, correzioni e stato anello,
 * e riassume gli ultimi 30 secondi. Serve a vedere in diretta l'effetto di una prova
 * (es. chiudere il tubo della valvola del canister) confrontando due "fotografie".
 * Nessuna dipendenza da Android.
 */
class IdleMonitor(private val windowMs: Long = 30_000, private val keepMs: Long = 120_000) {

    data class Point(val t: Long, val o2: Double?, val stft: Double?, val ltft: Double?, val loop: Double?)

    data class Summary(
        val samples: Int,
        val stft: Double?,
        val ltft: Double?,
        /** STFT + LTFT: quanto carburante la centralina toglie (−) o aggiunge (+) in totale */
        val total: Double?,
        val o2Mean: Double?,
        /** Quota di tempo con la sonda a monte sopra 0,7 V (ricco) */
        val richShare: Double?,
        /** Passaggi ricco/magro al minuto: una sonda che regola ne fa diversi, una ferma zero */
        val switchesPerMin: Double?,
        /** Quota di tempo in anello chiuso (PID 03 = 2) */
        val closedLoopShare: Double?,
    )

    data class Snapshot(val atMs: Long, val number: Int, val summary: Summary)

    private val points = ArrayDeque<Point>()
    private val shots = mutableListOf<Snapshot>()

    @Synchronized
    fun add(t: Long, v: Map<Int, Double>) {
        points.addLast(Point(t, v[0x14], v[0x06], v[0x07], v[0x03]))
        while (points.isNotEmpty() && t - points.first().t > keepMs) points.removeFirst()
    }

    @Synchronized
    fun history(): List<Point> = points.toList()

    @Synchronized
    fun snapshots(): List<Snapshot> = shots.toList()

    @Synchronized
    fun summary(now: Long): Summary {
        val w = points.filter { now - it.t <= windowMs }
        val o2 = w.mapNotNull { it.o2 }
        val stft = w.mapNotNull { it.stft }
        val ltft = w.mapNotNull { it.ltft }
        val loop = w.mapNotNull { it.loop }
        val spanMin = if (w.size >= 2) (w.last().t - w.first().t) / 60_000.0 else 0.0
        val switches = o2.zipWithNext().count { (a, b) -> (a - 0.45) * (b - 0.45) < 0 }
        val s = stft.takeIf { it.isNotEmpty() }?.average()
        val l = ltft.takeIf { it.isNotEmpty() }?.average()
        return Summary(
            samples = w.size,
            stft = s,
            ltft = l,
            total = if (s != null && l != null) s + l else null,
            o2Mean = o2.takeIf { it.isNotEmpty() }?.average(),
            richShare = o2.takeIf { it.isNotEmpty() }?.let { v -> v.count { it > 0.7 }.toDouble() / v.size },
            switchesPerMin = if (o2.size >= 5 && spanMin > 0.1) switches / spanMin else null,
            closedLoopShare = loop.takeIf { it.isNotEmpty() }?.let { v -> v.count { it.toInt() == 2 }.toDouble() / v.size },
        )
    }

    /** Fissa il riassunto degli ultimi 30 s, per confrontarlo con quello di dopo la prova. */
    @Synchronized
    fun snapshot(now: Long): Snapshot = Snapshot(now, shots.size + 1, summary(now)).also { shots += it }

    companion object {
        /** Lettura in parole dello stato anello (PID 03). */
        fun loopLabel(v: Double?): String = when (v?.toInt()) {
            null -> "stato regolazione sconosciuto"
            1 -> "anello aperto (motore freddo)"
            2 -> "anello chiuso: regola con la sonda"
            4 -> "anello aperto (rilascio/pieno carico)"
            8 -> "anello aperto per guasto"
            16 -> "anello chiuso con sonda in errore"
            else -> "stato %d".format(v.toInt())
        }
    }
}
