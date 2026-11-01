# Ehi macchina — diagnostica OBD Kia Cee'd

Specifica completa: `~/Downloads/PROMPT_auto_kia.md`. Comunicare in italiano. Prima di scrivere codice di una fase: piano breve + conferma. Una fase alla volta, fermarsi a fine fase.

## Veicolo / adattatore
- Kia Cee'd 1ª serie (ED) Sporty Wagon 1.6 125CV EX, ~2009 → **benzina** 1.6 CVVT (G4FC). Protocollo probabile: ISO 15765 CAN 11/500 (da verificare col log).
- App OBD usata finora: Car Scanner (telefono).
- Adattatore ELM327 Bluetooth classico: marca/nome/versione **ancora ignoti** — compilare dopo il primo log (campo `Versione ELM` e `ATDP` nel log).
- MAF (PID 10) presente o no? Da scoprire col log di fase 0 → decide il metodo di calcolo consumi.

## Ambiente di sviluppo (PC, Ubuntu, senza sudo)
- Tutto in `~/Android`: `jdk` (Temurin 17), `Sdk` (platform 35, build-tools 35.0.0), `gradle-8.9` (solo per generare il wrapper), `env.sh` (caricato da `~/.bashrc`).
- `wrangler` in `~/.npm-global` (npm prefix utente).
- Build: `. ~/Android/env.sh && ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Test: `./gradlew :app:testDebugUnitTest`
- Niente Android Studio (poco spazio disco); build da riga di comando.

## Stato
- **Fase 0: fatta e provata in auto (a motore spento).** App minima: connessione RFCOMM, init ELM, rilevamento PID, lettura continua, log raw, condivisione log, console debug nascosta (pressione lunga sul titolo), simulatore e riproduzione log.
- **Fase 1: fatta e provata in auto (log 2, fermo al minimo).** Foreground service + `ObdEngine` (singleton, sopravvive a rotazione/chiusura Activity), riconnessione automatica (2/5/10 s), polling a priorità (`Poller`: veloci ogni ciclo, 2 lenti a rotazione, `ATRV` ogni 5 s), cruscotto orizzontale+verticale con avvisi (`Alerts`), diagnosi codici in sola lettura (0101 + modi 03/07, `Dtc.kt`) con lettura automatica alla connessione e pagina "Diagnosi". Suffisso "risposte attese" (`010C1`) provato all'avvio e tenuto solo se l'adattatore lo gestisce (vedi nota nel log).
- **Fase 2: codice fatto, test PC ok, da provare in auto.** Freeze frame (modo 02) letto con la diagnosi, cancellazione codici (modo 04) con doppio controllo (`ClearGuard` + dialogo di conferma; salva prima codici e freeze frame nello storico), Room (`Db.kt`: viaggi, campioni ogni 2 s, storico codici, rifornimenti), consumi speed-density (`Consumption.kt`, VE 0.85, fattore di taratura da rifornimenti = litri + km dal pieno precedente, `Calibration`), `TripTracker` (viaggio = giri>400, fine dopo 2 min a motore spento), pagine Consumi e Codici/storico, cruscotto a 10 riquadri (+consumo istantaneo e media viaggio).
- **Fase 2b: codice fatto, test PC ok, da provare in auto.** "Test di guida diagnostico": `DriveTest.kt` (`DriveTestAnalyzer`: checklist condizioni + rapporto con indizi OK/info/ATTENZIONE), `Readiness.kt` (monitor di prontezza da 0101, mostrati anche nella pagina Codici), poller dedicato (O2 monte/valle, giri, velocità, carico, MAP a ogni ciclo + 3 lenti a rotazione), confronto codici prima/dopo, rapporto salvato nel DB (`drive_reports`, migrazione Room 1→2) e condivisibile come testo.
- **Fase 3: codice fatto e provato in locale (telefono → adb reverse → wrangler dev), PUBBLICATO su Cloudflare il 11/10/2026** (vedi sotto). Server in `server/` (Worker TS + D1, `/api/ingest|ping|latest`, auth Bearer, 19 test + `scripts/smoke.ts`). App: coda in uscita Room (`outbox`, DB v3), `Events.kt`, `Sync.kt` (`SyncManager`: gruppi da 50, backoff 15 s→5 min, 401 ferma l'invio, 400/413/422 scarta il gruppo), pagina Server (URL, token, interruttore, prova, invia ora, invia storico), snapshot ogni 15 s in viaggio, viaggio a fine corsa, codici a ogni diagnosi, rapporti, stato.
- **Fase 4: server MCP + OAuth pubblicati e provati end-to-end (locale e produzione) con client MCP ufficiale; manca solo la prova dal vivo con claude.ai (richiede l'account dell'utente).** `server/src/`: `mcp.ts` (JSON-RPC a mano, senza SDK), `tools.ts` (6 strumenti), `health.ts` (controllo salute), `dtc.ts` (descrizioni IT), `authorize.ts` + `authpage.ts` (pagina password), `ratelimit.ts`, `api.ts` (ingest), `index.ts` (OAuthProvider). 50 test + `scripts/oauth-e2e.ts`.
- Avvisi per codici nuovi: fatti il 01/11/2026 (vedi «Correzioni del 01/11/2026»).
- Fase 5: da fare (avvio automatico, risparmio batteria).

## Struttura (`app/src/main/java/it/ehimacchina/obd/`)
- `Transport.kt` interfaccia canale; `BluetoothTransport` (SPP, fallback canale 1 per cloni), `SimulatedTransport`, `ReplayTransport`, `LoggingTransport` (in `SessionLog.kt`).
- `Elm327.kt` protocollo (senza dipendenze Android → testabile). `Pid.kt` tabella PID e decodifica.
- `Ui.kt`: tema scuro (predefinito) o chiaro, scelto con l'interruttore nella home e salvato in preferenze `ui`/`light` (`AppColors` Dark/Light + `LocalAppColors`; `Palette.X` sono getter @Composable, quindi usabili solo in composizione: nei `Canvas` leggere il colore prima). Palette ( accento ciano, stati Ok/Warn/Crit) e componenti condivisi (`PageScaffold`, `SectionCard`, `MetricTile`, `Banner`, `CheckRow`, `ChoiceCard`, `NavTile`, `CodeBadge`…). Icona app adattiva `ic_launcher_foreground` (contagiri) e `ic_stat_gauge` per la notifica. Edge-to-edge con `safeDrawingPadding()` (targetSdk 35).
- `Mode06.kt` (test centralina), `CodeNews.kt` (novità nei codici), `Reconnect.kt` (attese di riconnessione).
- `DriveTest.kt`, `Readiness.kt`, `Events.kt` (JSON verso il server), `Sync.kt` (impostazioni, rete, coda, `SyncManager`).
- `server/`: `src/index.ts` (router), `ingest.ts` (validazione e SQL, funzioni pure), `auth.ts`; `migrations/0001_init.sql`; `test/`; `scripts/smoke.ts`.
- `Db.kt` (Room, versione 2), `Consumption.kt` (stima consumi, `Calibration`, `ClearGuard`), `TripTracker.kt`.
- `ObdEngine.kt` (connessione, sessione, riconnessione, comandi in coda), `ObdService.kt` (notifica/foreground), `Poller.kt`, `Alerts.kt` (soglie: liquido 98/105 °C, batteria 12.0/11.5 V solo a motore acceso, >15 V), `Dtc.kt` (decodifica codici, `Diagnosis`), `MainActivity.kt` (Compose: connessione, cruscotto, diagnosi, debug).
- Formato log (TAB): `epochMs  comando  risposta('|' = a capo)  durataMs`; righe `#ERR`/`#NOTE` per errori/note. Stesso formato letto dalla riproduzione.

## Decisioni
- Accesso centralina **sola lettura**, eccetto la cancellazione codici: `Elm327.clearCodes()` (modo 04) è chiamata solo da `ObdEngine.clearCodes` dopo dialogo di conferma, con auto ferma (velocità 0) e motore spento (giri<400), controllo rifatto al momento dell'invio. `Elm327.isReadOnlyCommand` continua a bloccare il modo 04 dalla console manuale.
- Consumi: stima non tarata salvata nel DB (`fuelL`), la taratura si applica al volo (si può ricalcolare). Fuel-cut in decelerazione non gestito (sovrastima lieve).
- Soglie degli avvisi provvisorie: tarare con un log a motore acceso.
- Risposta modo 03/07 su CAN: l'ELM mostra solo i byte reali; dopo `43`/`47` un numero dispari di byte = primo byte è il conteggio codici (da verificare col primo log reale con codici).
- **Test di guida diagnostico (richiesta utente)**: per una diagnosi serve guidare 15–20 min (monitor di prontezza + catalizzatore caldo). L'app registra la guida, segue i monitor `0101` (byte C=supportati, D=incompleti), confronta O2 B1S1/B1S2, correzioni carburante, tempo anello chiuso, temp. catalizzatore e liquido, e produce un rapporto con indizi (non verdetti). Salvato in DB ed esportabile (poi tool MCP `controllo_salute`).
- Il simulatore in-app sostituisce l'emulatore Python (niente codice doppio).

## Particolarità scoperte (log `app/src/test/resources/cee-d-ign-on-engine-off.log`, 11/10/2026, quadro acceso motore spento)
- Adattatore: `ELM327 v2.2`, protocollo `ISO 15765-4 (CAN 11/500)`. Init pulita, nessun timeout/errore in 43 s. Prima risposta a `0100` con `SEARCHING...` (~350 ms).
- Latenza: ~130 ms per richiesta (ATRV ~80 ms) → ciclo di 13 PID + ATRV ≈ 2 s. In fase 1: polling a priorità (veloci ogni ciclo, lenti ogni tanto); provare il suffisso "numero risposte attese" (`010C1`) per accorciare.
- PID supportati (27): 01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 14 15 1C 1F 21 2E 30 31 3C 41 43 44 45 46. **Niente MAF (10)** → consumi con speed-density (MAP 0B + IAT 0F + giri + cilindrata 1.6). **Niente 42** (tensione centralina), niente 2F (livello carburante), 0A, 5C.
- Non ancora decodificati: 13/14/15/1C (sonde O2 / standard OBD), 2E (purge), 30 (warm-up), 31 (km da cancellazione codici), 3C (temp. catalizzatore), 41, 43 (carico assoluto), 44 (lambda comandata), 45 (farfalla relativa). Da aggiungere se utili.
- **PID 21 = 267 km con spia MIL (check engine) accesa** → probabile codice errore memorizzato: leggere `0101` (stato MIL/numero DTC) e `03` in fase 2.
- Batteria `ATRV` = 10.7 V a motore spento: bassa (12.4–12.7 V attesi). Può essere batteria scarica o lettura imprecisa del clone: ripetere con motore acceso (alternatore ~13.8–14.4 V).
- Il primo log è a motore spento: manca ancora un log con motore acceso e in marcia.

### Log 2 — `app/src/test/resources/cee-d-idle-engine-on-dtc.log` (11/10/2026, motore acceso al minimo da freddo, auto ferma, 68 s)
- **Fase 1 verificata in auto**: connessione stabile, nessun errore. Il **suffisso risposte attese (`010C1`) funziona** su questo adattatore: richieste ~92 ms (prima ~130), periodo giri ~0.7 s (prima ~2 s). Resta il limite di ~1.4 Hz sui giri: se serve più fluido, giri e velocità in una corsia ancora più veloce (fase 5).
- **Codici**: `0101` = `41 01 82 07 61 01` → MIL accesa, 2 codici. `03` = `43 02 06 50 01 72` → memorizzati **P0650** (circuito spia MIL) e **P0172** (miscela ricca banco 1). `07` = `47 02 06 50 04 20` → pendenti P0650 e **P0420** (efficienza catalizzatore). Formato CAN con byte conteggio confermato: il parser funziona.
- Cronologia: PID 31 = 449 km da cancellazione codici, PID 21 = 267 km con MIL accesa, PID 30 = 41 riscaldamenti → codici cancellati 449 km fa, guasto comparso dopo ~182 km. **Non cancellare i codici prima di aver salvato il freeze frame (modo 02)**: in fase 2 leggerlo per primo.
- Batteria `ATRV`: 10.7 V a quadro acceso/motore spento, **13.6–13.7 V a motore acceso** (alternatore ok, un po' basso). 10.7 V a riposo è molto bassa: far controllare la batteria con un tester.
- Sonde: O2 B1S1 (14) oscilla 0.07–0.71 V (anello chiuso, normale); O2 B1S2 (15) fissa a 0.455 V con byte trim 0xFF ("non usata") per tutto il test; catalizzatore (3C) sale da 18 a 118 °C in 60 s (test troppo breve per valutare P0420). Correzione carburante: STFT 0 → −9% dopo l'avvio, LTFT +3.1% costante.
- Al minimo: MAP ~29–30 kPa, carico motore ~21–24%, farfalla ~6%, liquido 18→27 °C, giri ~1160–1560 (minimo a freddo). Velocità sempre 0 (auto ferma). PID 0x44 vale 2.0 prima dell'avvio, 1.0 dopo.
- Non ancora verificati in auto: velocità in marcia, riconnessione dopo perdita Bluetooth, schermo bloccato col servizio, 0902 (VIN). Nessun gap/errore nel log.
- A fine log, con quadro spento: `NO DATA` su tutti i PID (~250–340 ms l'uno). Il polling continua a 3 richieste/s: ok ma da pensare per il risparmio batteria (fase 5).
- **Monitor di prontezza (da `0101` = `41 01 82 07 61 01`)**: motore a scintilla; supportati misfire/carburante/componenti (B=07, tutti completi), catalizzatore, sonda O2, riscaldatore O2 (C=61; EVAP non supportato). **D=01 → monitor del catalizzatore NON completato** (gli altri completi). Coerente con P0420 *pendente*: la centralina non ha ancora finito di valutare il catalizzatore; serve una guida a caldo per completarlo.

### Da verificare in auto con la Fase 2
- Risposta reale al freeze frame (`0202 00`, `0200 00`...): la Cee'd potrebbe non supportarlo (`NO DATA`) → in quel caso la UI non mostra la sezione.
- Plausibilità consumi: al minimo ~0.5–1.5 L/h, crociera 90 km/h ~5–9 L/100 km. Se lontani, tarare con i rifornimenti.
- Rilevamento viaggio (parte a motore acceso, finisce 2 min dopo lo spegnimento) e salvataggio nel DB.
- Cancellazione codici: solo se si vuole davvero; il guasto P0172/P0650 può tornare.

### Da verificare in auto con la Fase 2b
- Il test di guida con una guida vera di 15–20 min: il confronto sonda valle/monte (rapporto deviazioni standard) è tarato su soglie da officina e campioni a ~1 Hz, **non validato su dati reali**. Se il rapporto dà risultati strani, mandare il log + il testo del rapporto per ritarare.
- Se la sonda a valle (PID 15) resta ferma a ~0.45 V come nel log 2, il rapporto lo segnala come non valutabile (non conclude su P0420).
- I monitor di prontezza dovrebbero passare da "Catalizzatore incompleto" a completo dopo una guida a caldo.
- Il rapporto è testo piano: sarà letto anche dal futuro tool MCP `controllo_salute`.

### Provare Fase 3 in locale con il telefono (debug)
- Server: `cd server && wrangler d1 migrations apply ehi-macchina --local && npm run dev`; `adb reverse tcp:8787 tcp:8787`.
- Configurazione app senza digitare: `adb shell "run-as it.ehimacchina.obd sh -c 'mkdir -p shared_prefs && cat > shared_prefs/sync.xml'"` con `<map><string name="url">http://localhost:8787</string><string name="token">dev-token-solo-locale</string><boolean name="enabled" value="true"/></map>` (funziona solo con l'APK debug).
- Trovati e corretti con questa prova: codici/viaggi già presenti non partivano mai (ora ogni diagnosi rimette in coda i codici aperti + pulsante «Invia storico»); simulatore con virgola decimale (locale italiano) falsava ATRV.
- Dopo che il server torna online, la coda riparte da sola entro il backoff (max 5 min) o subito con «Invia ora».
- uiautomator dump + `adb shell input tap` funzionano per pilotare l'app da PC (le coordinate cambiano se compaiono righe di messaggio).

### Server pubblicato (Cloudflare, piano gratuito)
- Indirizzo: `https://ehi-macchina.ehi-macchina-server.workers.dev` (Worker `ehi-macchina`, D1 `ehi-macchina` id `b5174c0f-b217-42f9-8ae9-22b02556771c`, nel `wrangler.jsonc`). Account Cloudflare: quello di livio.sarra@gmail.com, login con `. ~/Android/env.sh && wrangler login`.
- Token segreto di invio: file `~/.config/ehi-macchina/token` (permessi 600, fuori dal repo); sul server è il secret `INGEST_TOKEN`. Rigenerare: `openssl rand -hex 24 > ~/.config/ehi-macchina/token && wrangler secret put INGEST_TOKEN < ~/.config/ehi-macchina/token`, poi aggiornare l'app (pagina Server o `run-as ... shared_prefs/sync.xml`).
- Pubblicare modifiche: `cd server && wrangler deploy`; migrazioni: `wrangler d1 migrations apply ehi-macchina --remote`.
- Prova: `BASE=<url> TOKEN=$(cat ~/.config/ehi-macchina/token) npx tsx scripts/smoke.ts` (scrive dati di prova nel DB vero: poi `DELETE` come in `server/README.md`).
- L'indirizzo workers.dev nuovo ha impiegato ~1 minuto a risolvere i DNS.
- **Dati del simulatore/riproduzione NON vengono inviati** (di default) per non sporcare il DB vero; l'interruttore «Invia anche i dati del simulatore» nella pagina Server serve alle prove (Fase 4), poi ripulire il DB remoto.
- DB del telefono ripulito dai dati del simulatore l'11/10/2026.

### Fase 4: dati di prova e segreti
- Connettore: `https://ehi-macchina.ehi-macchina-server.workers.dev/mcp`. Password di approvazione: `~/.config/ehi-macchina/owner-password` (segreto `OWNER_PASSWORD`; rigenerare con python `secrets` come in sessione 11/10/2026 e `wrangler secret put OWNER_PASSWORD < file`). KV `OAUTH_KV` id `b77da07a62d14635984cd21657de25a2`.
- Il server contiene DATI DEL SIMULATORE (caricati l'11/10/2026 per provare il connettore: 22 istantanee, 1 viaggio, codice P0133, 1 rapporto, stato). **Da cancellare prima dell'uso vero** e da spegnere l'interruttore «Invia anche i dati del simulatore» nell'app: `wrangler d1 execute ehi-macchina --remote --command "DELETE FROM snapshots; DELETE FROM trips; DELETE FROM dtc; DELETE FROM drive_reports; DELETE FROM meta"`.
- Su `wrangler dev` locale il D1 va migrato (`wrangler d1 migrations apply ehi-macchina --local`) se manca la tabella; `.dev.vars` contiene `INGEST_TOKEN` e `OWNER_PASSWORD` di prova (non in git).
- Un nuovo `wrangler deploy` impiega alcuni secondi a propagarsi: possibili 404 transitori.
- Libreria OAuth: riusare un codice di autorizzazione revoca i token emessi con quel codice (comportamento voluto).
- `@modelcontextprotocol/sdk` è solo devDependency (client usato nel test e2e); `npm audit` segnala una vulnerabilità del suo *client* OAuth, che non usiamo in produzione.

### Design (11/10/2026)
- Restyling completo della UI senza cambiare logica: verificato con screenshot sul telefono (verticale e orizzontale) di home, cruscotto, diagnosi, consumi, server. Screenshot: `adb exec-out screencap -p` (serve il telefono sbloccato; `adb shell svc power stayon usb` lo tiene acceso).
- Server ripulito dai dati del simulatore e interruttore «Invia anche i dati del simulatore» spento (11/10/2026). Anche il DB locale dell'app è stato cancellato (11/10/2026, rimossi i file `databases/ehi-macchina.db*` via `run-as`; restano log e impostazioni): da qui in poi solo dati veri.

### Log 3 — guida reale 11/10/2026 16:39–18:00 (`app/src/test/resources/cee-d-guida-29min.log.gz` + `cee-d-quadro-spento.log`)
- Test di guida 29:39, 28 km, fino a 130 km/h; poi sosta al minimo 8 min, altro viaggio 10,8 km, test al minimo 5 min. Consumi stimati 6,4 e 7,6 L/100 km (plausibili, non tarati). Alternatore 13,4–13,6 V. Liquido max 98 °C al minimo (ventola).
- **Catalizzatore**: anche a regime stabile (λ comandato ≈1, senza tagli d'iniezione) la sonda a valle copia quella a monte: rapporto deviazioni 0,95–0,98, passaggi ricco/magro 918 vs 943. Efficienza bassa, coerente con P0420 (monitor catalizzatore ancora incompleto).
- **Miscela ricca al minimo** (P0172): a minimo caldo (~658 giri) sonda a monte > 0,7 V nel 96–98 % del tempo (0,86–0,90 V), STFT ferma a −6…−19 % (deviazione 0 a blocchi: la centralina non corregge/oscilla), LTFT ~−1 %, purge EVAP comandato 0 %. In marcia invece tutto regolare (STFT −2 %, sonda oscilla). PID 03 (anello aperto/chiuso) non era letto: aggiunto.
- **Freeze frame** = P0650 (spia), scattato a quadro acceso e motore spento (giri 0, liquido 30 °C): tipico di lampadina/circuito della spia.
- **BUG trovati e corretti**: (1) a quadro spento la centralina risponde NO DATA e l'app lo prendeva per "nessun codice" → codici segnati cancellati (app e server) senza nessun `04` inviato; ora `readDiagnosis` senza risposta a 0101 = tutto sconosciuto e `recordDiagnosis` non registra nulla. (2) Il freeze frame veniva salvato su tutti i codici; ora solo sul codice che l'ha causato (`FreezeFrame.textFor`), e quelli sbagliati si ripuliscono alla diagnosi successiva. (3) Analisi test di guida: catalizzatore valutato solo a regime stabile + conteggio passaggi; correzioni carburante valutate separatamente al minimo e in marcia; nuovo indizio "sonda sempre sul ricco al minimo"; il test si chiude da solo dopo 30 s di centralina muta. (4) Server `controllo_salute`: stesse separazioni minimo/marcia.
- Dati riparati l'11/10/2026 con il consenso dell'utente: server D1 (codici riattivati, freeze solo su P0650, milOn=true) e DB del telefono (stesso intervento, copia di sicurezza nella scratchpad della sessione). Server ripubblicato: `controllo_salute` separa minimo/marcia, segnala sonda sempre ricca al minimo, batteria su percentili (un campione all'avviamento non è più "critico").

### Dati simulati marcati (11/10/2026, richiesta utente)
- App DB v4 (migrazione 3→4): colonna `simulated` su `trips`, `dtc`, `drive_reports` (`@ColumnInfo(defaultValue="0")`, obbligatorio perché Room validi la migrazione). Simulatore e riproduzione salvano con `simulated=true`; codici aperti cercati per (codice, simulated) → cancellare nel simulatore non tocca mai i codici veri; taratura e «Invia storico» usano solo dati veri (`realTrips`); etichetta «SIMULATO» nelle liste; pulsante «Elimina dati di prova (N)» nella home (`deleteSimulated*`).
- Eventi al server con `"simulated": true` (solo se l'interruttore è acceso). Server migrazione `0002_simulated.sql`: colonna `simulated` su snapshots/trips/dtc/drive_reports; lo stato simulato va in `meta.status_sim`; `D1Repo` e `/api/latest` filtrano sempre `simulated = 0`.
- Nuova vista «Prova al minimo» (`IdleMonitor.kt`, pagina IDLE da Codici): sonda a monte, STFT/LTFT, PID 03 in diretta con grafici 2 min (`Sparkline`), riassunto 30 s (correzione totale, tempo sul ricco, passaggi/min, anello chiuso) e «Fotografa» per confronto prima/dopo (prova tubo canister). Poller dedicato: 14, 06, 0C a ogni ciclo.
- PID 03 (stato sistema carburante) ora decodificato e letto.

### Stato diagnosi (11/10/2026 sera)
- Confermato dall'utente: la spia motore NON si accende girando la chiave → P0650 = LED/lampadina/circuito spia (elettrauto). Per questo la spia non era mai stata vista.
- Il meccanico abituale non usa volentieri la diagnosi: preparata scheda riassuntiva `~/Downloads/kia-ceed-diagnosi.html` (gravità + passi in ordine + frase per l'officina). Valvola canister difficile da raggiungere (sotto il collettore, vedi video i30 G4FA).
- Idea in sospeso (offerta, non richiesta): pulsante «Scheda per l'officina» nell'app che genera il riepilogo aggiornato.

### Log 4 — tre viaggi lunghi (24/10 14:24 e 17:15, 01/11 00:54; log e DB letti dal telefono il 01/11/2026)
- 48 / 50 / 59 km, consumi stimati 8,0 / 5,9 / 7,3 L/100 km (non tarati, nessun rifornimento inserito; i due del 24/10 sembrano salita e discesa). Liquido max 92–93 °C.
- **Tensione ATRV**: 11,3 V prima dell'avvio, 11,7 V dopo un'ora di guida, 13,6–13,7 V a motore acceso. Batteria debole oppure adattatore che legge ~0,6 V in meno: **da misurare con un tester** (non ancora fatto).
- **P0172**: al minimo caldo tempo sul ricco 96 % (11/10) → 66 → 22 → 20 %, correzione breve −17,5 % → +0,5 %. Però dal 17/10 è anche **pendente** (`07`), mentre l'11/10 no: non è risolto. Il 24/10 14:24 il ricco cresce per sosta (13, 64, 95, 100, 100 %). Ipotesi vapori canister non verificata: nei test di guida il PID 2E non veniva letto (ora sì).
- **Catalizzatore**: P0420 sparito da solo fra 17/10 e 24/10, monitor completato (`0101` D=00), nessuno ha cancellato (PID 31 sale senza azzerarsi: 453 → 1029 km). Ma la sonda a valle copia ancora quella a monte (rapporto 0,95–0,99), e al minimo la correlazione ha il massimo con ~0,5 s di ritardo: segnale vero. Contraddizione da risolvere col modo 06.
- Dal 24/10 al 01/11 la centralina conta 345 km, l'app ne ha 99: ~246 km non registrati (app non collegata).

### Correzioni del 01/11/2026 (provate al PC e col simulatore sul telefono; NON ancora in auto)
- **Modo 06** (`Mode06.kt`, `Elm327.readMonitorTests`): test centralina su catalizzatore (MID 21) e sonde (01, 02), numeri grezzi + limiti; in Diagnosi, rapporto, `status` al server (`monitorTests`) e strumenti MCP. Formato reale non ancora visto: **al primo viaggio guardare `06xx` nel log**; se la Cee'd non risponde la sezione non compare.
- **Controllo codici in guida** (`ObdEngine.watchCodes`, `Elm327.checkCodes`): ogni 60 s a motore acceso `0101` + `07`, `03` se cambia il numero. Il rapporto usa l'ultima lettura se a fine test il collegamento è già caduto.
- **Avviso** (`CodeNews.kt`, `ObdService.showCodeAlert`): codice nuovo, pendente che diventa memorizzato o spia che si accende → banner rosso, notifica canale `codici` (priorità alta, schermo bloccato), invio al server. I codici aperti non si ripetono; **un codice sparito che ricompare (es. P0420) avvisa**. Il simulatore fa comparire P0300 dopo 2 min.
- Bug corretto: quadro spento fra `0101` e `03`/`07` → NO DATA scambiato per "nessun codice" (ora `0101` riletto dopo).
- `Dtc.KIND_BOTH` = `memorizzato+pendente`; «cancellato» → «non più presente» (app e server, `non_piu_presenti`). Nessuna migrazione DB.
- Test di guida: letti anche 2E, farfalla, anticipo, km da cancellazione, temperatura esterna; rapporto con ricco a canister attivo/chiuso; segnala P017x pendente con correzioni ok; cita P0420 solo se c'è.
- `ReconnectPolicy` (`Reconnect.kt`): attese 2, 5, 10, 30, 60, 120 s, poi ogni 2 min senza fermarsi (scelta utente); dopo 2 min senza dati chiude viaggio e test (causa probabile del viaggio del 17/10 mai inviato, recuperato con «Invia storico»).
- Da verificare in auto: risposta al modo 06, avviso vero, riconnessione a fine viaggio. Sul telefono era attivo Non disturbare: può silenziare il suono degli avvisi.
