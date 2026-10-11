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

## Particolarità scoperte (log `app/src/test/resources/cee-d-ign-on-engine-off.log`, 11/10/2026, quadro acceso motore spento)
- Adattatore: `ELM327 v2.2`, protocollo `ISO 15765-4 (CAN 11/500)`. Init pulita, nessun timeout/errore in 43 s. Prima risposta a `0100` con `SEARCHING...` (~350 ms).
- Latenza: ~130 ms per richiesta (ATRV ~80 ms) → ciclo di 13 PID + ATRV ≈ 2 s. In fase 1: polling a priorità (veloci ogni ciclo, lenti ogni tanto); provare il suffisso "numero risposte attese" (`010C1`) per accorciare.
- PID supportati (27): 01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 14 15 1C 1F 21 2E 30 31 3C 41 43 44 45 46. **Niente MAF (10)** → consumi con speed-density (MAP 0B + IAT 0F + giri + cilindrata 1.6). **Niente 42** (tensione centralina), niente 2F (livello carburante), 0A, 5C.
- Non ancora decodificati: 13/14/15/1C (sonde O2 / standard OBD), 2E (purge), 30 (warm-up), 31 (km da cancellazione codici), 3C (temp. catalizzatore), 41, 43 (carico assoluto), 44 (lambda comandata), 45 (farfalla relativa). Da aggiungere se utili.
- **PID 21 = 267 km con spia MIL (check engine) accesa** → probabile codice errore memorizzato: leggere `0101` (stato MIL/numero DTC) e `03` in fase 2.
- Batteria `ATRV` = 10.7 V a motore spento: bassa (12.4–12.7 V attesi). Può essere batteria scarica o lettura imprecisa del clone: ripetere con motore acceso (alternatore ~13.8–14.4 V).
- Il primo log è a motore spento: manca ancora un log con motore acceso e in marcia.
