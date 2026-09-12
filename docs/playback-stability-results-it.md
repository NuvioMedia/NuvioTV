# Verifica NuvioTV - 12 settembre 2026

Base: `dev`, `d2eee15`, versione `0.9.0-brusus.13`.
Branch di lavoro: `codex/playback-stability-audit`.

## Correzioni

- Il timeout di avvio viene cancellato quando il caricamento termina in ExoPlayer,
  tunneling o MPV. Un errore diverso non viene nascosto.
- I timer vengono annullati alla chiusura; il watchdog verifica la generazione
  della sessione e ignora la pausa manuale. Un vecchio primo fotogramma non puo
  completare la nuova inizializzazione.
- Un nuovo tentativo riparte senza il messaggio del precedente.
- Corretto il parametro numerico ebraico `collections_valid_summary`.
- Reso il test di rinnovo Trakt indipendente dalle credenziali di compilazione.

## Verifiche automatiche

- `:app:testFullDebugUnitTest`: 1260 test, 1257 riusciti, 3 esclusi, 0 fallimenti.
  Inclusi 7 nuovi test del timeout.
- Test Python degli script di rilascio: 19 riusciti.
- `:app:assembleFullDebug`: riuscito. Firma locale di prova, non di distribuzione.
- `git diff --check`: riuscito.
- `:app:lintFullDebug`: NON superato. Report completo: 3199 errori,
  1817 warning, 25 hint. Non equivalgono a 3199 crash riprodotti.
  Tra le categorie: 2303 traduzioni mancanti, 729 opt-in Media3,
  59 API ristrette, 33 segnalazioni RememberInComposition.

Ambiente: Windows, JDK 17, SDK Android 36. La prima esecuzione combinata di lint
e packaging ha esaurito l'heap Gradle; build e lint sono stati rieseguiti
separatamente con `--max-workers=2` e heap locale 12 GiB. Le impostazioni del
repository non sono state aumentate globalmente.

## Prove su emulatore Windows

AVD isolato `NuvioTV_CodexAudit`, Android TV API 36 x86_64, WHPX, grafica software.
Installazione del fullDebug x86_64, accesso senza account e addon HTTP locale.
Video sintetico H.264/AAC di 90 secondi.

- ExoPlayer: avvio veloce, pausa e ripresa verificati visivamente.
- Avvio rallentato di circa 28 secondi: timeout visibile intorno alla scadenza,
  poi rimosso quando arriva il video. Nella prova con ritardo solo iniziale,
  la posizione avanza e Android segnala PLAYING, errore nullo e buffer a 90000 ms.
- Sorgente senza dati: timeout conservato e Go Back riporta alle sorgenti.
- Cambio manuale ExoPlayer -> Libmpv: il campione riprende dalla posizione
  precedente e avanza. Nessuna FATAL EXCEPTION AndroidRuntime osservata.
- Una prima fixture rallentava anche le richieste successive: ha generato
  rebuffering prolungato. La prova e stata ripetuta con ritardo solo iniziale.

## Lavoro ancora aperto

L'audit non azzera tutti i bug del progetto. Il report lint completo richiede
triage e correzioni per categoria, senza soppressioni indiscriminate. Non sono
state provate tutte le integrazioni, gli account, i provider, i formati video,
il tunneling hardware, HDR/Dolby Vision, il passthrough audio o una TV fisica.

Prima di pubblicare un aggiornamento, ripetere il caso sul dispositivo e provider
della segnalazione e verificare ripresa, sottotitoli, ricerca temporale ed episodio
successivo. Nessuna release o merge automatico e stato effettuato.
