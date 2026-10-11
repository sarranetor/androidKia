import { test } from "node:test";
import assert from "node:assert/strict";
import { esc, renderPage } from "../src/authpage.ts";
import { clearFails, isLocked, MAX_FAILS, recordFail, type KvLike } from "../src/ratelimit.ts";

class MemKv implements KvLike {
  m = new Map<string, string>();
  async get(k: string) { return this.m.get(k) ?? null; }
  async put(k: string, v: string) { this.m.set(k, v); }
  async delete(k: string) { this.m.delete(k); }
}

test("la pagina scherma i dati scelti dal client", () => {
  const page = renderPage({ clientName: '<script>alert(1)</script>"', domain: "evil.<b>x", redirectHost: "a\"b", loopback: false, handle: "h'1" });
  assert.ok(!page.includes("<script>"));
  assert.ok(!page.includes("<b>x"));
  assert.match(page, /&#60;script&#62;/);
  assert.match(page, /name="handle" value="h&#39;1"/);
  assert.equal(esc(`&<>"'`), "&#38;&#60;&#62;&#34;&#39;");
});

test("la pagina mostra errore, avviso loopback e dominio verificato", () => {
  const p = renderPage({ clientName: "Claude", domain: "claude.ai", redirectHost: "localhost", loopback: true, handle: "h", error: "Password errata." });
  assert.match(p, /Password errata\./);
  assert.match(p, /sul tuo computer/);
  assert.match(p, /Pubblicato da <strong>claude\.ai/);
  assert.match(p, /sola lettura/);
  assert.match(renderPage({ clientName: "X", redirectHost: "claude.ai", loopback: false, handle: "h" }), /nome non è verificato/);
});

test("freno ai tentativi: blocca dopo MAX_FAILS, per indirizzo, e si azzera", async () => {
  const kv = new MemKv();
  for (let i = 0; i < MAX_FAILS - 1; i++) await recordFail(kv, "1.2.3.4");
  assert.equal(await isLocked(kv, "1.2.3.4"), false);
  await recordFail(kv, "1.2.3.4");
  assert.equal(await isLocked(kv, "1.2.3.4"), true);
  assert.equal(await isLocked(kv, "5.6.7.8"), false);
  await clearFails(kv, "1.2.3.4");
  assert.equal(await isLocked(kv, "1.2.3.4"), false);
});
