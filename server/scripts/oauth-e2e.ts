// Prova end-to-end del percorso OAuth + MCP, come lo fa Claude:
//   BASE=http://localhost:8787 PASSWORD=... npx tsx scripts/oauth-e2e.ts
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const BASE = (process.env.BASE ?? "http://localhost:8787").replace(/\/$/, "");
const PASSWORD = process.env.PASSWORD ?? "dev-password-locale";
const REDIRECT = "https://claude.ai/api/mcp/auth_callback";
const b64url = (b: Buffer) => b.toString("base64url");

// 1. senza token: 401 con puntatore ai metadati
const unauth = await fetch(`${BASE}/mcp`, { method: "POST", body: "{}", headers: { "content-type": "application/json" } });
assert.equal(unauth.status, 401);
const challenge = unauth.headers.get("www-authenticate") ?? "";
assert.match(challenge, /resource_metadata="[^"]+oauth-protected-resource/);
console.log("401 + WWW-Authenticate ok");

// 2. metadati
const prm = (await (await fetch(/resource_metadata="([^"]+)"/.exec(challenge)![1])).json()) as any;
assert.equal(prm.resource, `${BASE}/mcp`);
assert.equal(prm.authorization_servers[0], BASE);
const asm = (await (await fetch(`${BASE}/.well-known/oauth-authorization-server`)).json()) as any;
assert.ok(asm.registration_endpoint && asm.token_endpoint && asm.authorization_endpoint);
assert.ok(asm.code_challenge_methods_supported.includes("S256"));
assert.ok(asm.scopes_supported.includes("offline_access"));
console.log("metadati ok:", asm.authorization_endpoint, asm.token_endpoint, asm.registration_endpoint);

// 3. registrazione dinamica del client (come fa Claude)
const reg = await fetch(asm.registration_endpoint, {
  method: "POST", headers: { "content-type": "application/json" },
  body: JSON.stringify({
    client_name: "Claude", redirect_uris: [REDIRECT], grant_types: ["authorization_code", "refresh_token"],
    response_types: ["code"], token_endpoint_auth_method: "none",
  }),
});
assert.equal(reg.status, 201, await reg.clone().text());
const client = (await reg.json()) as any;
console.log("client registrato:", client.client_id);

// 4. /authorize: pagina con password (PKCE S256)
const verifier = b64url(randomBytes(32));
const challengeCode = b64url(createHash("sha256").update(verifier).digest());
const authUrl = new URL(asm.authorization_endpoint);
Object.entries({
  response_type: "code", client_id: client.client_id, redirect_uri: REDIRECT, code_challenge: challengeCode,
  code_challenge_method: "S256", state: "stato123", scope: "mcp:read offline_access", resource: `${BASE}/mcp`,
}).forEach(([k, v]) => authUrl.searchParams.set(k, v));
const page = await fetch(authUrl, { redirect: "manual" });
assert.equal(page.status, 200);
const cookies = page.headers.getSetCookie().map((c) => c.split(";")[0]).join("; ");
assert.ok(cookies.length > 0, "cookie di consenso mancante");
const html = await page.text();
assert.match(html, /Collega «Claude»/);
const handle = /name="handle" value="([^"]+)"/.exec(html)![1];
console.log("pagina di autorizzazione ok");

const post = (password: string, decision = "approve") =>
  fetch(authUrl, {
    method: "POST", redirect: "manual",
    headers: { "content-type": "application/x-www-form-urlencoded", cookie: cookies },
    body: new URLSearchParams({ handle, decision, password, clientName: "Claude", redirectHost: "claude.ai" }),
  });

// 5. password sbagliata → 401 e pagina di nuovo; poi giusta → redirect con codice
const wrong = await post("sbagliata");
assert.equal(wrong.status, 401);
assert.match(await wrong.text(), /Password errata/);
const good = await post(PASSWORD);
assert.equal(good.status, 302, await good.clone().text());
const loc = new URL(good.headers.get("location")!);
assert.equal(`${loc.origin}${loc.pathname}`, REDIRECT);
assert.equal(loc.searchParams.get("state"), "stato123");
const code = loc.searchParams.get("code")!;
assert.ok(code);
console.log("autorizzazione con password ok");

// 6. scambio codice → token (PKCE)
const tokenReq = (params: Record<string, string>) =>
  fetch(asm.token_endpoint, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(params) });
const t1 = await tokenReq({
  grant_type: "authorization_code", code, redirect_uri: REDIRECT, client_id: client.client_id, code_verifier: verifier, resource: `${BASE}/mcp`,
});
assert.equal(t1.status, 200, await t1.clone().text());
const tokens = (await t1.json()) as any;
assert.ok(tokens.access_token && tokens.refresh_token, "manca access o refresh token");
assert.equal(tokens.token_type.toLowerCase(), "bearer");
console.log("token ok (scade fra", tokens.expires_in, "s)");

// 7. client MCP ufficiale: initialize, tools/list, tools/call
const client2 = new Client({ name: "e2e", version: "1.0.0" });
const transport = new StreamableHTTPClientTransport(new URL(`${BASE}/mcp`), {
  requestInit: { headers: { Authorization: `Bearer ${tokens.access_token}` } },
});
await client2.connect(transport);
const listed = await client2.listTools();
assert.deepEqual(listed.tools.map((t) => t.name).sort(),
  ["codici_errore", "consumi", "controllo_salute", "rapporto_test_guida", "stato_attuale", "storico_viaggi"]);
for (const name of listed.tools.map((t) => t.name)) {
  const r: any = await client2.callTool({ name, arguments: {} });
  assert.ok(!r.isError, `${name}: ${JSON.stringify(r)}`);
  JSON.parse(r.content[0].text);
  console.log("  strumento", name, "→", r.content[0].text.length, "caratteri");
  if (process.env.VERBOSE) console.log(r.content[0].text);
}
await client2.close();

// 8. token sbagliato / rinnovo
const bad = await fetch(`${BASE}/mcp`, { method: "POST", body: "{}", headers: { authorization: "Bearer sbagliato", "content-type": "application/json" } });
assert.equal(bad.status, 401);
const refreshed = await tokenReq({ grant_type: "refresh_token", refresh_token: tokens.refresh_token, client_id: client.client_id });
assert.equal(refreshed.status, 200, await refreshed.clone().text());
const t2 = (await refreshed.json()) as any;
assert.ok(t2.access_token && t2.access_token !== tokens.access_token);
assert.ok(t2.refresh_token, "il rinnovo deve restituire un nuovo refresh token (rotazione)");
const ping = await fetch(`${BASE}/mcp`, {
  method: "POST", headers: { authorization: `Bearer ${t2.access_token}`, "content-type": "application/json" },
  body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "ping" }),
});
assert.equal(ping.status, 200);
console.log("rinnovo token ok");

// 9. le API dell'app restano raggiungibili con il loro token e non con quello OAuth
const apiOauth = await fetch(`${BASE}/api/latest`, { headers: { authorization: `Bearer ${t2.access_token}` } });
assert.equal(apiOauth.status, 401);
// 10. un codice usato due volte è rifiutato e, per sicurezza, revoca i token emessi con quel codice
const reuse = await tokenReq({ grant_type: "authorization_code", code, redirect_uri: REDIRECT, client_id: client.client_id, code_verifier: verifier });
assert.equal(reuse.status, 400);
const afterReuse = await fetch(`${BASE}/mcp`, {
  method: "POST", headers: { authorization: `Bearer ${t2.access_token}`, "content-type": "application/json" },
  body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "ping" }),
});
assert.equal(afterReuse.status, 401, "dopo il riuso del codice i token devono essere revocati");
console.log("riuso del codice: rifiutato e token revocati");
console.log("OAUTH E2E OK");
