# Server "Ehi macchina" (Cloudflare Worker + D1)

Riceve i dati dall'app Android e li conserva, ed espone a Claude gli strumenti MCP (sola lettura).

## Cosa fa
- `POST /api/ingest` — l'app invia gruppi di eventi (`snapshot`, `trip`, `dtc`, `drive_report`, `status`). Idempotente: reinviare lo stesso gruppo non crea doppioni.
- `GET /api/ping` — verifica token e connessione.
- `GET /api/latest` — ultimo stato noto (per controllare che l'invio funzioni).
- Tutto richiede `Authorization: Bearer <token>`.
- Le istantanee più vecchie di 30 giorni si cancellano da sole; viaggi, codici e rapporti restano.

## Sviluppo in locale (senza account Cloudflare)
```bash
. ~/Android/env.sh
cd server
npm install
npm test                  # test unitari (validazione, autenticazione)
npm run typecheck
wrangler d1 migrations apply ehi-macchina --local
npm run dev               # http://localhost:8787, token di prova in .dev.vars
BASE=http://localhost:8787 TOKEN=dev-token-solo-locale npx tsx scripts/smoke.ts   # prova end-to-end
```
Per provare col telefono: `adb reverse tcp:8787 tcp:8787` e nell'app (pagina Server) indirizzo `http://localhost:8787`, token `dev-token-solo-locale`.
(L'app ammette HTTP in chiaro solo verso `localhost`.)

## Pubblicazione su Cloudflare (piano gratuito)
1. Crea l'account gratuito su https://dash.cloudflare.com/sign-up (solo email, niente carta).
2. `wrangler login` (si apre il browser, conferma).
3. `wrangler d1 create ehi-macchina` → copia il `database_id` stampato in `wrangler.jsonc` (al posto dello zero).
4. `wrangler d1 migrations apply ehi-macchina --remote`
5. Token segreto (lungo e casuale): `openssl rand -hex 24 | wrangler secret put INGEST_TOKEN` (ricordati il valore: va scritto nell'app; per rigenerarlo basta ripetere il comando).
6. `wrangler deploy` → stampa l'indirizzo `https://ehi-macchina.<tuo-sottodominio>.workers.dev`.
7. Prova: `BASE=https://ehi-macchina.<…>.workers.dev TOKEN=<token> npx tsx scripts/smoke.ts` (scrive dati di prova nel database vero: cancellarli con `wrangler d1 execute ehi-macchina --remote --command "DELETE FROM snapshots; DELETE FROM trips; DELETE FROM dtc; DELETE FROM drive_reports"` se dà fastidio).
8. Nell'app: Server → indirizzo + token → attiva → Salva → "Invia prova" → "Invia storico".

## Collegamento a Claude (MCP + OAuth)
- Endpoint MCP: `https://ehi-macchina.<…>.workers.dev/mcp` (trasporto Streamable HTTP, risposte JSON, senza stato).
- Accesso: OAuth 2.1 con PKCE, registrazione automatica dei client (DCR) o Client ID Metadata Document, rinnovo dei token (rotazione). Libreria `@cloudflare/workers-oauth-provider`, token in KV (`OAUTH_KV`).
- Pagina `/authorize`: chiede una **password di approvazione** (segreto `OWNER_PASSWORD`; file `~/.config/ehi-macchina/owner-password`). Dopo 5 errori dallo stesso indirizzo si blocca per 15 minuti.
- Il token dell'app (`INGEST_TOKEN`) vale solo per `/api/*`; i token OAuth solo per `/mcp`.
- Strumenti: `stato_attuale`, `codici_errore`, `consumi`, `storico_viaggi`, `controllo_salute`, `rapporto_test_guida` (tutti di sola lettura).
- Prova completa in locale: `npm run dev` + `npx tsx scripts/oauth-e2e.ts` (registra un client, passa dalla pagina con password, scambia il codice, usa un client MCP ufficiale, rinnova il token, verifica la revoca al riuso del codice). Contro la produzione: `BASE=https://… PASSWORD=$(cat ~/.config/ehi-macchina/owner-password) npx tsx scripts/oauth-e2e.ts`.
- Per cambiare la password: `python3 …` come in CLAUDE.md oppure `wrangler secret put OWNER_PASSWORD`.
- Per scollegare Claude: in claude.ai rimuovi il connettore; per revocare tutto lato server basta svuotare il KV (`wrangler kv key list --binding OAUTH_KV --remote`) o cambiare `OWNER_PASSWORD` e ricollegare.
