import { test } from "node:test";
import assert from "node:assert/strict";
import { handleMcpHttp, handleMessage, SUPPORTED_VERSIONS } from "../src/mcp.ts";
import { makeTools } from "../src/tools.ts";
import { FakeRepo, NOW } from "./fakes.ts";

const tools = makeTools(new FakeRepo(), () => NOW);
const post = (body: unknown, raw?: string) =>
  handleMcpHttp(new Request("https://x/mcp", { method: "POST", body: raw ?? JSON.stringify(body), headers: { "content-type": "application/json" } }), tools);

test("initialize: versione richiesta se supportata, altrimenti la più recente", async () => {
  const a = (await handleMessage({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18" } }, tools)) as any;
  assert.equal(a.result.protocolVersion, "2025-06-18");
  assert.deepEqual(Object.keys(a.result.capabilities), ["tools"]);
  assert.equal(a.result.serverInfo.name, "ehi-macchina");
  const b = (await handleMessage({ jsonrpc: "2.0", id: 2, method: "initialize", params: { protocolVersion: "1999-01-01" } }, tools)) as any;
  assert.equal(b.result.protocolVersion, SUPPORTED_VERSIONS[0]);
});

test("notifiche: nessuna risposta, HTTP 202", async () => {
  assert.equal(await handleMessage({ jsonrpc: "2.0", method: "notifications/initialized" }, tools), null);
  const res = await post({ jsonrpc: "2.0", method: "notifications/initialized" });
  assert.equal(res.status, 202);
  assert.equal(await res.text(), "");
});

test("tools/list: sei strumenti di sola lettura", async () => {
  const r = (await handleMessage({ jsonrpc: "2.0", id: 3, method: "tools/list" }, tools)) as any;
  assert.equal(r.result.tools.length, 6);
  for (const t of r.result.tools) {
    assert.equal(t.annotations.readOnlyHint, true);
    assert.equal(t.annotations.destructiveHint, false);
  }
});

test("tools/call: risultato in testo JSON", async () => {
  const res = await post({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "stato_attuale", arguments: {} } });
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type")!, /application\/json/);
  const body = (await res.json()) as any;
  assert.equal(body.id, 4);
  assert.equal(body.result.isError, false);
  assert.equal(body.result.content[0].type, "text");
  assert.equal(JSON.parse(body.result.content[0].text).dati_disponibili, false);
});

test("tools/call: strumento sconosciuto = errore JSON-RPC; strumento che fallisce = isError", async () => {
  const unknown = (await handleMessage({ jsonrpc: "2.0", id: 5, method: "tools/call", params: { name: "boh" } }, tools)) as any;
  assert.equal(unknown.error.code, -32602);
  const bad = [{ name: "rotto", description: "", inputSchema: {}, run: async () => { throw new Error("db giù"); } }];
  const r = (await handleMessage({ jsonrpc: "2.0", id: 6, method: "tools/call", params: { name: "rotto" } }, bad)) as any;
  assert.equal(r.result.isError, true);
  assert.match(r.result.content[0].text, /db giù/);
});

test("ping, metodo sconosciuto, richiesta non valida", async () => {
  assert.deepEqual(((await handleMessage({ jsonrpc: "2.0", id: 7, method: "ping" }, tools)) as any).result, {});
  assert.equal(((await handleMessage({ jsonrpc: "2.0", id: 8, method: "resources/list" }, tools)) as any).error.code, -32601);
  assert.equal(((await handleMessage({ id: 9 }, tools)) as any).error.code, -32600);
  assert.equal(((await handleMessage("ciao", tools)) as any).error.code, -32600);
});

test("HTTP: GET/DELETE non supportati, JSON rotto, batch", async () => {
  assert.equal((await handleMcpHttp(new Request("https://x/mcp"), tools)).status, 405);
  assert.equal((await handleMcpHttp(new Request("https://x/mcp", { method: "DELETE" }), tools)).status, 405);
  assert.equal((await post(null, "{rotto")).status, 400);
  assert.equal((await post([])).status, 400);
  const batch = (await (await post([
    { jsonrpc: "2.0", id: 1, method: "ping" },
    { jsonrpc: "2.0", method: "notifications/initialized" },
    { jsonrpc: "2.0", id: 2, method: "tools/list" },
  ])).json()) as any[];
  assert.equal(batch.length, 2);
  assert.deepEqual(batch.map((b) => b.id).sort(), [1, 2]);
  assert.equal((await post(null, "x".repeat(70_000))).status, 413);
});
