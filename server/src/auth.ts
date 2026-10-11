// Controllo del token segreto. Il confronto avviene sugli hash SHA-256, byte per byte
// senza uscite anticipate, così il tempo di risposta non rivela quanti caratteri sono giusti.

async function sha256(text: string): Promise<Uint8Array> {
  const data = new TextEncoder().encode(text);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data));
}

export async function tokensMatch(given: string, expected: string): Promise<boolean> {
  if (!expected) return false; // server non configurato: niente accesso
  const [a, b] = await Promise.all([sha256(given), sha256(expected)]);
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** Estrae il token da "Authorization: Bearer xxx". */
export function bearerToken(request: Request): string {
  const header = request.headers.get("Authorization") ?? "";
  const m = /^Bearer\s+(.+)$/i.exec(header.trim());
  return m ? m[1] : "";
}

export async function isAuthorized(request: Request, expected: string): Promise<boolean> {
  return tokensMatch(bearerToken(request), expected);
}
