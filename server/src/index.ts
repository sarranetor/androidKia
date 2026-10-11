import OAuthProvider, { type OAuthHelpers } from "@cloudflare/workers-oauth-provider";
import { handleApi, type ApiEnv } from "./api.ts";
import { handleAuthorize, type AuthEnv } from "./authorize.ts";
import { handleMcpHttp } from "./mcp.ts";
import { D1Repo } from "./repo.ts";
import { makeTools } from "./tools.ts";

export interface Env extends ApiEnv, Omit<AuthEnv, "OAUTH_PROVIDER" | "OAUTH_KV"> {
  OAUTH_KV: KVNamespace;
  OAUTH_PROVIDER: OAuthHelpers;
}

/** Tutto ciò che non è /mcp né un endpoint OAuth: pagina di autorizzazione e API dell'app. */
const appHandler = {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/authorize") return handleAuthorize(request, env);
    return handleApi(request, env);
  },
};

/** /mcp: raggiunto solo con un token OAuth valido (lo controlla la libreria prima di arrivare qui). */
const mcpHandler = {
  async fetch(request: Request, env: Env): Promise<Response> {
    return handleMcpHttp(request, makeTools(new D1Repo(env.DB)));
  },
};

// L'indirizzo pubblico serve nei metadati OAuth: si ricava dalla richiesta (workers.dev, dominio proprio
// o localhost in sviluppo) e si tiene un provider per indirizzo.
const providers = new Map<string, OAuthProvider<Env>>();

function providerFor(origin: string): OAuthProvider<Env> {
  let p = providers.get(origin);
  if (!p) {
    p = new OAuthProvider<Env>({
      apiRoute: "/mcp",
      apiHandler: mcpHandler,
      defaultHandler: appHandler,
      authorizeEndpoint: "/authorize",
      tokenEndpoint: "/oauth/token",
      clientRegistrationEndpoint: "/oauth/register",
      // offline_access = token di rinnovo, così Claude non chiede la password a ogni scadenza
      scopesSupported: ["mcp:read", "offline_access"],
      requiredScopes: ["mcp:read"],
      clientIdMetadataDocumentEnabled: true,
      resourceMetadata: { resource: `${origin}/mcp`, authorization_servers: [origin], resource_name: "Ehi macchina" },
    });
    providers.set(origin, p);
  }
  return p;
}

export default {
  fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    return providerFor(new URL(request.url).origin).fetch(request, env, ctx);
  },
} satisfies ExportedHandler<Env>;
