// Pagina /authorize: unico utente, protetta da password. Claude (o un altro client MCP) manda qui il
// browser; si mostra chi chiede l'accesso e si chiede la password. Poi si completa l'autorizzazione OAuth.

import { AuthorizationError, CimdFetchError, type ConsentDescription, type OAuthHelpers } from "@cloudflare/workers-oauth-provider";
import { tokensMatch } from "./auth.ts";
import { esc, renderPage } from "./authpage.ts";
import { clearFails, isLocked, recordFail, type KvLike } from "./ratelimit.ts";

export interface AuthEnv {
  OAUTH_PROVIDER: OAuthHelpers;
  OAUTH_KV: KvLike;
  OWNER_PASSWORD: string;
}

const html = (body: string, status = 200, headers?: Headers) => {
  const h = new Headers(headers);
  h.set("content-type", "text/html; charset=utf-8");
  h.set("cache-control", "no-store");
  return new Response(body, { status, headers: h });
};

const text = (message: string, status: number) =>
  new Response(esc(message), { status, headers: { "content-type": "text/plain; charset=utf-8" } });

function clientIp(request: Request): string {
  return request.headers.get("CF-Connecting-IP") ?? "sconosciuto";
}

export async function handleAuthorize(request: Request, env: AuthEnv): Promise<Response> {
  const oauth = env.OAUTH_PROVIDER;
  try {
    if (request.method === "GET") {
      const authReq = await oauth.parseAuthRequest(request);
      const details: ConsentDescription = await oauth.describeConsent(authReq);
      const consent = await oauth.beginConsent(authReq);
      return html(
        renderPage({
          clientName: details.clientName, domain: details.clientDomain, redirectHost: details.redirectHost,
          loopback: details.redirectIsLoopback, handle: consent.handle,
        }),
        200,
        consent.headers,
      );
    }
    if (request.method !== "POST") return text("Metodo non consentito", 405);

    const form = await request.formData();
    const handle = String(form.get("handle") ?? "");

    if (form.get("decision") !== "approve") {
      const denied = await oauth.denyConsent(request, handle);
      return new Response(null, { status: 302, headers: denied.headers });
    }

    const ip = clientIp(request);
    const again = (error: string, status: number) =>
      html(
        renderPage({
          clientName: String(form.get("clientName") ?? "Claude"), domain: String(form.get("domain") ?? "") || undefined,
          redirectHost: String(form.get("redirectHost") ?? ""), loopback: form.get("loopback") === "1", handle, error,
        }),
        status,
      );

    if (await isLocked(env.OAUTH_KV, ip)) {
      return again("Troppi tentativi sbagliati. Riprova fra 15 minuti.", 429);
    }
    if (!(await tokensMatch(String(form.get("password") ?? ""), env.OWNER_PASSWORD))) {
      await recordFail(env.OAUTH_KV, ip);
      await new Promise((r) => setTimeout(r, 1000)); // rallenta chi prova a indovinare
      return again("Password errata.", 401);
    }
    await clearFails(env.OAUTH_KV, ip);

    const approved = await oauth.approveConsent(request, handle);
    const { redirectTo } = await oauth.completeAuthorization({
      request: approved.request,
      userId: "owner",
      metadata: {},
      scope: approved.request.scope,
      props: { userId: "owner" },
    });
    approved.headers.set("Location", redirectTo);
    return new Response(null, { status: 302, headers: approved.headers });
  } catch (error) {
    if (error instanceof AuthorizationError && error.redirectTo) return Response.redirect(error.redirectTo, 302);
    if (error instanceof AuthorizationError) return text(`Richiesta non valida: ${error.description}. Riparti dal collegamento in Claude.`, 400);
    if (error instanceof CimdFetchError) return text("Non riesco a verificare l'app che chiede l'accesso.", 400);
    throw error;
  }
}
