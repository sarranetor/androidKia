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
- **Fase 0: fatta (da provare in auto).** App minima: connessione RFCOMM, init ELM, rilevamento PID, lettura continua, log raw, condivisione log, console debug nascosta (pressione lunga sul titolo), simulatore e riproduzione log.
- Fasi 1–5: da fare.

## Struttura (`app/src/main/java/it/ehimacchina/obd/`)
- `Transport.kt` interfaccia canale; `BluetoothTransport` (SPP, fallback canale 1 per cloni), `SimulatedTransport`, `ReplayTransport`, `LoggingTransport` (in `SessionLog.kt`).
- `Elm327.kt` protocollo (senza dipendenze Android → testabile). `Pid.kt` tabella PID e decodifica.
- `ObdViewModel.kt` + `MainActivity.kt` (Compose).
- Formato log (TAB): `epochMs  comando  risposta('|' = a capo)  durataMs`; righe `#ERR`/`#NOTE` per errori/note. Stesso formato letto dalla riproduzione.

## Decisioni
- Accesso centralina **sola lettura**; `Elm327.isReadOnlyCommand` blocca il modo 04 e simili dalla console manuale. Cancellazione codici solo in fase 2, con conferma e auto ferma.
- Fase 0 senza foreground service (arriva in fase 1): l'app deve restare in primo piano (schermo sempre acceso).
- Il simulatore in-app sostituisce l'emulatore Python (niente codice doppio).

## Particolarità scoperte
(da riempire dopo i test in auto)
