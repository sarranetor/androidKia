// Server MCP minimale (trasporto "Streamable HTTP", senza stato): ogni richiesta è un POST JSON-RPC
// e la risposta è JSON. Basta per strumenti di sola lettura; niente SSE.

import type { Tool } from "./tools.ts";

export const SUPPORTED_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];
export const SERVER_INFO = { name: "ehi-macchina", version: "1.0.0" };
export const INSTRUCTIONS =
  "Dati in tempo quasi reale della Kia Cee'd 1.6 benzina (2009) di Filippo, letti dalla centralina via OBD-II. " +
  "Rispondi in italiano. I dati sono indizi, non diagnosi da officina. Se «aggiornato_da» è grande, l'auto è spenta o il telefono non invia.";

type Id = string | number | null;
type JsonRpcRequest = { jsonrpc: "2.0"; id?: Id; method: string; params?: Record<string, unknown> };

const ok = (id: Id, result: unknown) => ({ jsonrpc: "2.0" as const, id, result });
const err = (id: Id, code: number, message: string) => ({ jsonrpc: "2.0" as const, id, error: { code, message } });

const isObj = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null && !Array.isArray(x);

/** Gestisce un messaggio. Ritorna null per le notifiche (nessuna risposta). */
export async function handleMessage(msg: unknown, tools: Tool[]): Promise<object | null> {
  if (!isObj(msg) || msg.jsonrpc !== "2.0" || typeof msg.method !== "string") {
    return err(isObj(msg) && (typeof msg.id === "string" || typeof msg.id === "number") ? msg.id : null, -32600, "Richiesta non valida");
  }
  const req = msg as unknown as JsonRpcRequest;
  const isNotification = req.id === undefined;
  if (isNotification) return null; // notifications/initialized, notifications/cancelled, ...
  const id = req.id as Id;
  const params = isObj(req.params) ? req.params : {};

  switch (req.method) {
    case "initialize": {
      const asked = typeof params.protocolVersion === "string" ? params.protocolVersion : "";
      return ok(id, {
        protocolVersion: SUPPORTED_VERSIONS.includes(asked) ? asked : SUPPORTED_VERSIONS[0],
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions: INSTRUCTIONS,
      });
    }
    case "ping":
      return ok(id, {});
    case "tools/list":
      return ok(id, {
        tools: tools.map((t) => ({
          name: t.name,
          description: t.description,
          inputSchema: t.inputSchema,
          annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
        })),
      });
    case "tools/call": {
      const tool = tools.find((t) => t.name === params.name);
      if (!tool) return err(id, -32602, `Strumento sconosciuto: ${String(params.name)}`);
      const args = isObj(params.arguments) ? params.arguments : {};
      try {
        const result = await tool.run(args);
        return ok(id, { content: [{ type: "text", text: JSON.stringify(result, null, 2) }], isError: false });
      } catch (e) {
        // errore dello strumento: lo vede Claude, non è un errore di protocollo
        return ok(id, { content: [{ type: "text", text: `Errore nello strumento: ${e instanceof Error ? e.message : String(e)}` }], isError: true });
      }
    }
    default:
      return err(id, -32601, `Metodo non supportato: ${req.method}`);
  }
}

const json = (body: unknown, status = 200, extra: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...extra } });

export const MAX_MCP_BODY = 64 * 1024;

export async function handleMcpHttp(request: Request, tools: Tool[]): Promise<Response> {
  if (request.method !== "POST") {
    // niente flusso SSE lato server: GET/DELETE non sono supportati (consentito dalla specifica)
    return new Response(null, { status: 405, headers: { Allow: "POST" } });
  }
  const text = await request.text();
  if (text.length > MAX_MCP_BODY) return json(err(null, -32600, "Richiesta troppo grande"), 413);
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return json(err(null, -32700, "JSON non valido"), 400);
  }

  if (Array.isArray(body)) {
    if (body.length === 0 || body.length > 20) return json(err(null, -32600, "Batch non valido"), 400);
    const replies = (await Promise.all(body.map((m) => handleMessage(m, tools)))).filter((r) => r !== null);
    return replies.length === 0 ? new Response(null, { status: 202 }) : json(replies);
  }
  const reply = await handleMessage(body, tools);
  return reply === null ? new Response(null, { status: 202 }) : json(reply);
}
