import { test } from "node:test";
import assert from "node:assert/strict";
import { bearerToken, isAuthorized, tokensMatch } from "../src/auth.ts";

test("token uguale passa", async () => assert.equal(await tokensMatch("abc123", "abc123"), true));
test("token diverso no", async () => assert.equal(await tokensMatch("abc124", "abc123"), false));
test("token vuoto no", async () => assert.equal(await tokensMatch("", "abc123"), false));
test("server senza segreto rifiuta tutto, anche il vuoto", async () => {
  assert.equal(await tokensMatch("", ""), false);
  assert.equal(await tokensMatch("x", ""), false);
});
test("prefisso del token non basta", async () => assert.equal(await tokensMatch("abc", "abc123"), false));

test("bearer: estrazione", () => {
  const r = (h?: string) => new Request("https://x/", { headers: h ? { Authorization: h } : {} });
  assert.equal(bearerToken(r("Bearer segreto")), "segreto");
  assert.equal(bearerToken(r("bearer segreto")), "segreto");
  assert.equal(bearerToken(r("Basic abc")), "");
  assert.equal(bearerToken(r()), "");
});

test("isAuthorized end to end", async () => {
  const ok = new Request("https://x/", { headers: { Authorization: "Bearer s3" } });
  const ko = new Request("https://x/", { headers: { Authorization: "Bearer s4" } });
  assert.equal(await isAuthorized(ok, "s3"), true);
  assert.equal(await isAuthorized(ko, "s3"), false);
  assert.equal(await isAuthorized(new Request("https://x/"), "s3"), false);
});
