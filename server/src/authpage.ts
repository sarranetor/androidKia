// HTML della pagina di autorizzazione (puro: si prova senza il runtime Cloudflare).

export const esc = (value: string) => value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

const CSS = `body{font-family:system-ui,sans-serif;max-width:30rem;margin:2rem auto;padding:0 1rem;background:#111;color:#eee}
input,button{font-size:1rem;padding:.6rem;margin:.3rem 0;border-radius:.4rem;border:1px solid #555}
input{width:100%;box-sizing:border-box;background:#222;color:#eee}
button{background:#2d6cdf;color:#fff;border:0;margin-right:.5rem}button.no{background:#444}
.err{color:#ff8a80}.small{color:#aaa;font-size:.85rem}`;

export function renderPage(opts: { clientName: string; domain?: string; redirectHost: string; loopback: boolean; handle: string; error?: string }): string {
  const origin = opts.domain
    ? `Pubblicato da <strong>${esc(opts.domain)}</strong>.`
    : "Questa app si è registrata da sola: il nome non è verificato.";
  return `<!doctype html><html lang="it"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Collega Claude all'auto</title><style>${CSS}</style>
<h1>Collega «${esc(opts.clientName)}» alla tua auto</h1>
<p>${origin} Dopo l'accesso, i dati verranno inviati a <strong>${esc(opts.redirectHost)}</strong>.</p>
${opts.loopback ? "<p class=err><strong>Questo invia l'accesso a un'app sul tuo computer.</strong> Continua solo se l'hai appena avviata tu.</p>" : ""}
<p class=small>Permesso richiesto: <strong>sola lettura</strong> dei dati dell'auto (valori, codici errore, viaggi, consumi). Nessuna scrittura.</p>
${opts.error ? `<p class=err>${esc(opts.error)}</p>` : ""}
<form method="post">
  <input type="hidden" name="handle" value="${esc(opts.handle)}">
  <input type="hidden" name="clientName" value="${esc(opts.clientName)}">
  <input type="hidden" name="domain" value="${esc(opts.domain ?? "")}">
  <input type="hidden" name="redirectHost" value="${esc(opts.redirectHost)}">
  <input type="hidden" name="loopback" value="${opts.loopback ? "1" : ""}">
  <label>Password di approvazione<input type="password" name="password" autocomplete="current-password" autofocus></label>
  <p><button name="decision" value="approve">Consenti</button><button class="no" name="decision" value="deny" formnovalidate>Nega</button></p>
</form></html>`;
}

