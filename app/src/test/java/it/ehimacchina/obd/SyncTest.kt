package it.ehimacchina.obd

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private class FakeStore(rows: List<String>) : OutboxStore {
    val all = rows.mapIndexed { i, j -> OutboxRow(i + 1L, j) }.toMutableList()
    override suspend fun next(limit: Int) = all.take(limit)
    override suspend fun remove(ids: List<Long>) { all.removeAll { it.id in ids } }
}

private class FakePoster(private val reply: (String, String) -> HttpResult) : HttpPoster {
    val calls = mutableListOf<Triple<String, String, String?>>() // metodo, url, corpo
    var lastToken: String? = null
    override suspend fun request(method: String, url: String, token: String, body: String?): HttpResult {
        calls += Triple(method, url, body)
        lastToken = token
        return reply(method, url)
    }
}

class EventsTest {
    @Test fun snapshotJson() {
        val j = JSONObject(Events.snapshot(1000L, mapOf(0x0C to 2100.0, 0x05 to 88.0), 13.7, 4.2, 3.5))
        assertEquals("snapshot", j.getString("type"))
        assertEquals(1000L, j.getLong("ts"))
        assertEquals(2100.0, j.getJSONObject("values").getDouble("0C"), 0.0)
        assertEquals(88.0, j.getJSONObject("values").getDouble("05"), 0.0)
        assertEquals(3.5, j.getJSONObject("trip").getDouble("km"), 0.0)
        assertEquals(13.7, j.getDouble("battery"), 0.0)
    }

    @Test fun nanAndNullAreSafe() {
        val j = JSONObject(Events.snapshot(1L, mapOf(0x0C to Double.NaN, 0x0D to 50.0), null, Double.POSITIVE_INFINITY, null))
        assertFalse(j.getJSONObject("values").has("0C"))
        assertTrue(j.isNull("battery"))
        assertTrue(j.isNull("lph"))
        assertFalse(j.has("trip"))
    }

    @Test fun tripAndReportJson() {
        val t = JSONObject(Events.trip(1, 2, 10.5, 0.8, 95.0))
        assertEquals(10.5, t.getDouble("km"), 0.0)
        assertEquals(0.8, t.getDouble("fuelL"), 0.0)
        val r = JSONObject(Events.driveReport(1, 2, "riga 1\nriga \"2\""))
        assertEquals("riga 1\nriga \"2\"", r.getString("text")) // a capo e virgolette sopravvivono
    }

    @Test fun dtcJson() {
        val d = DtcEntity(code = "P0172", kind = "memorizzato", firstSeenMs = 10, lastSeenMs = 20, clearedMs = null, freeze = "P0172;0C=1.0")
        val j = JSONObject(Events.dtc(d))
        assertEquals("P0172", j.getString("code"))
        assertTrue(j.isNull("clearedMs"))
        assertEquals("P0172;0C=1.0", j.getString("freeze"))
        val gone = JSONObject(Events.dtc(d.copy(clearedMs = 30, freeze = null)))
        assertEquals(30L, gone.getLong("clearedMs"))
        assertTrue(gone.isNull("freeze"))
    }

    @Test fun statusJson() {
        val diag = Diagnosis(true, 2, listOf("P0650"), emptyList(), 0, readiness = Readiness.parse(intArrayOf(0x82, 0x07, 0x61, 0x01)))
        val j = JSONObject(Events.status(5, "ELM327 v2.2", listOf(1, 4, 12), 1.1, diag))
        assertEquals(3, j.getJSONArray("supported").length())
        assertTrue(j.getBoolean("milOn"))
        val cat = (0 until j.getJSONArray("readiness").length()).map { j.getJSONArray("readiness").getJSONObject(it) }
            .first { it.getString("name") == "Catalizzatore" }
        assertFalse(cat.getBoolean("complete"))
        assertTrue(JSONObject(Events.status(5, "x", emptyList(), 1.0, null)).isNull("readiness"))
    }
}

class SyncManagerTest {
    private val cfg = SyncConfig("https://ehi.example.workers.dev/", "segreto", true)
    private fun ok() = HttpResult.Response(200, "{\"accepted\":2,\"rejected\":[]}")

    @Test fun configNormalization() {
        assertEquals("https://ehi.example.workers.dev", cfg.baseUrl)
        assertEquals("https://x.dev", SyncConfig("x.dev").baseUrl)
        assertEquals("http://localhost:8787", SyncConfig("http://localhost:8787/").baseUrl)
        assertFalse(SyncConfig("a", "b", false).ready)
        assertFalse(SyncConfig("", "b", true).ready)
        assertFalse(SyncConfig("a", "", true).ready)
        assertTrue(cfg.ready)
    }

    @Test fun sendsBatchAndClearsQueue() = runBlocking {
        val store = FakeStore(listOf("{\"type\":\"a\"}", "{\"type\":\"b\"}"))
        val poster = FakePoster { _, _ -> ok() }
        val m = SyncManager(store, poster, { cfg }, { 777L })
        assertEquals(FlushOutcome.SENT, m.flushOnce())
        assertEquals(0, store.all.size)
        assertEquals("POST", poster.calls[0].first)
        assertEquals("https://ehi.example.workers.dev/api/ingest", poster.calls[0].second)
        assertEquals("{\"events\":[{\"type\":\"a\"},{\"type\":\"b\"}]}", poster.calls[0].third)
        assertEquals("segreto", poster.lastToken)
        assertEquals(777L, m.state.value.lastOkMs)
        assertNull(m.state.value.lastError)
        assertEquals(FlushOutcome.NOTHING, m.flushOnce())
    }

    @Test fun batchesOfFifty() = runBlocking {
        val store = FakeStore((1..120).map { "{\"n\":$it}" })
        val m = SyncManager(store, FakePoster { _, _ -> ok() }, { cfg })
        assertEquals(FlushOutcome.SENT, m.flushOnce())
        assertEquals(70, store.all.size)
        m.flushOnce(); m.flushOnce()
        assertEquals(0, store.all.size)
    }

    @Test fun networkErrorKeepsQueue() = runBlocking {
        val store = FakeStore(listOf("{}"))
        val m = SyncManager(store, FakePoster { _, _ -> HttpResult.NetworkError("timeout") }, { cfg })
        assertEquals(FlushOutcome.RETRY, m.flushOnce())
        assertEquals(1, store.all.size)
        assertTrue(m.state.value.lastError!!.contains("timeout"))
    }

    @Test fun serverErrorKeepsQueue() = runBlocking {
        for (status in listOf(500, 502, 503, 429)) {
            val store = FakeStore(listOf("{}"))
            val m = SyncManager(store, FakePoster { _, _ -> HttpResult.Response(status, "") }, { cfg })
            assertEquals(FlushOutcome.RETRY, m.flushOnce())
            assertEquals(1, store.all.size)
        }
    }

    @Test fun badTokenStopsAndKeepsQueue() = runBlocking {
        val store = FakeStore(listOf("{}"))
        val poster = FakePoster { _, _ -> HttpResult.Response(401, "") }
        val m = SyncManager(store, poster, { cfg })
        assertEquals(FlushOutcome.AUTH_REJECTED, m.flushOnce())
        assertTrue(m.state.value.authRejected)
        assertEquals(1, store.all.size)
        // finché non cambia la configurazione non insiste (non martella il server con un token sbagliato)
        assertEquals(FlushOutcome.AUTH_REJECTED, m.flushOnce())
        assertEquals(1, poster.calls.size)
        // dopo kick() (configurazione cambiata) riprova
        m.kick()
        m.flushOnce()
        assertEquals(2, poster.calls.size)
    }

    @Test fun malformedBatchIsDropped() = runBlocking {
        val store = FakeStore(listOf("{}", "{}"))
        val m = SyncManager(store, FakePoster { _, _ -> HttpResult.Response(400, "{\"error\":\"x\"}") }, { cfg })
        assertEquals(FlushOutcome.DROPPED, m.flushOnce())
        assertEquals(0, store.all.size) // non resta bloccata la coda per sempre
        assertTrue(m.state.value.lastError!!.contains("400"))
    }

    @Test fun disabledOrUnconfiguredDoesNothing() = runBlocking {
        val store = FakeStore(listOf("{}"))
        val poster = FakePoster { _, _ -> ok() }
        assertEquals(FlushOutcome.NOTHING, SyncManager(store, poster, { SyncConfig("u", "t", false) }).flushOnce())
        assertEquals(FlushOutcome.NOTHING, SyncManager(store, poster, { SyncConfig() }).flushOnce())
        assertEquals(0, poster.calls.size)
        assertEquals(1, store.all.size)
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(15_000L, SyncManager.backoffMs(1))
        assertEquals(30_000L, SyncManager.backoffMs(2))
        assertEquals(60_000L, SyncManager.backoffMs(3))
        assertEquals(300_000L, SyncManager.backoffMs(10))
        assertEquals(300_000L, SyncManager.backoffMs(50))
    }

    @Test fun pingMessages() = runBlocking {
        fun mgr(r: HttpResult) = SyncManager(FakeStore(emptyList()), FakePoster { _, _ -> r }, { cfg })
        assertEquals("Collegamento riuscito", mgr(HttpResult.Response(200, "{}")).ping(cfg))
        assertEquals("Il server ha rifiutato il token", mgr(HttpResult.Response(401, "")).ping(cfg))
        assertTrue(mgr(HttpResult.NetworkError("dns")).ping(cfg).contains("non raggiungibile"))
        assertTrue(mgr(HttpResult.Response(200, "")).ping(SyncConfig()).contains("Inserisci"))
        val poster = FakePoster { _, _ -> HttpResult.Response(200, "{}") }
        SyncManager(FakeStore(emptyList()), poster, { cfg }).ping(cfg)
        assertEquals("GET", poster.calls[0].first)
        assertEquals("https://ehi.example.workers.dev/api/ping", poster.calls[0].second)
    }
}
