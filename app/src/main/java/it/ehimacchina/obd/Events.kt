package it.ehimacchina.obd

import org.json.JSONArray
import org.json.JSONObject

/** Costruisce gli eventi JSON inviati al server (formato definito in server/src/ingest.ts). */
object Events {
    /** JSON non ammette NaN/infinito: valori non finiti diventano null */
    private fun finite(x: Double?): Any = if (x != null && x.isFinite()) x else JSONObject.NULL

    fun snapshot(ts: Long, values: Map<Int, Double>, battery: Double?, lph: Double?, tripKm: Double?): String =
        JSONObject().apply {
            put("type", "snapshot")
            put("ts", ts)
            put("values", JSONObject().also { o -> values.forEach { (pid, v) -> if (v.isFinite()) o.put("%02X".format(pid), v) } })
            put("battery", finite(battery))
            put("lph", finite(lph))
            if (tripKm != null) put("trip", JSONObject().put("km", finite(tripKm)))
        }.toString()

    fun trip(startMs: Long, endMs: Long, km: Double, fuelL: Double, maxSpeed: Double): String =
        JSONObject().apply {
            put("type", "trip")
            put("startMs", startMs); put("endMs", endMs)
            put("km", finite(km)); put("fuelL", finite(fuelL)); put("maxSpeed", finite(maxSpeed))
        }.toString()

    fun dtc(d: DtcEntity): String =
        JSONObject().apply {
            put("type", "dtc")
            put("code", d.code); put("kind", d.kind)
            put("firstSeenMs", d.firstSeenMs); put("lastSeenMs", d.lastSeenMs)
            put("clearedMs", d.clearedMs ?: JSONObject.NULL)
            put("freeze", d.freeze ?: JSONObject.NULL)
        }.toString()

    fun driveReport(startMs: Long, endMs: Long, text: String): String =
        JSONObject().apply {
            put("type", "drive_report")
            put("startMs", startMs); put("endMs", endMs); put("text", text)
        }.toString()

    fun status(ts: Long, info: String, supported: List<Int>, calibration: Double, diagnosis: Diagnosis?): String =
        JSONObject().apply {
            put("type", "status")
            put("ts", ts)
            put("info", info)
            put("supported", JSONArray(supported))
            put("calibration", finite(calibration))
            put("milOn", diagnosis?.milOn ?: JSONObject.NULL)
            put(
                "readiness",
                diagnosis?.readiness?.let { r ->
                    JSONArray().also { a -> r.monitors.forEach { a.put(JSONObject().put("name", it.name).put("complete", it.complete)) } }
                } ?: JSONObject.NULL,
            )
        }.toString()
}
