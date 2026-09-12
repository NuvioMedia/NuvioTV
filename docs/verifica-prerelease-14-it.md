# NuvioTV - verifica prerelease 14

12 settembre 2026. Versione preparata `0.9.0-brusus.14`, codice `1073`. Non pubblicata.

## Modifiche

- Download APK associato a canale, tag e asset esatti; prevenzione dei doppi avvii, cancellazione del vero Call OkHttp e blocco di progressi/completamenti obsoleti.
- File temporanei unici, pulizia in caso di errore o annullamento, controllo delle dimensioni e promozione finale solo a trasferimento completato. Client dedicato senza il precedente timeout totale di 90 secondi, con timeout finiti di connessione/lettura e limite massimo di 1 GiB.
- Progressione e Annulla restano accessibili quando si disattivano le notifiche durante un download. Errori recuperabili dell’installer e dei permessi, senza mostrare URL dalle eccezioni.
- Le due nuove stringhe sono presenti in tutte le 36 varianti base/locali. Controllo esaustivo dei file XML, incluso `values-hu/string.xml`.
- Rafforzati confronto versioni, classificazione prerelease, selezione APK per ABI e controlli del processo di release.
- Conscrypt e WireGuard aggiornati; IAMF ricostruito con classi Java ed esportazioni native preservate. Controlli ELF/ZIP a 16 KiB sulle architetture a 64 bit superati.

## Risultati

| Prova | Risultato e perimetro |
|---|---|
| JVM fullDebug | 1318 superati, 3 saltati |
| JVM fullRelease | 1318 superati, 3 saltati |
| Updater | 19 regressioni incluse nelle suite JVM; HTTP reale locale, annullamento, troncamento, race e intent falliti |
| Python | 52/52 superati; 4 contratti risorse inclusi |
| Native su debug | 3/3: Conscrypt TLS verificato su loopback, WireGuard JNI senza tunnel, IAMF primo buffer stereo PCM ufficiale con hash golden esatti |
| Provider Android TV | 2/2 CRUD reali sul provider emulato, solo righe UUID sintetiche eliminate in finally |
| Overlay/Back | Passato nella ripetizione isolata da Home; primo tentativo combinato fallito nella precondizione sidebar Settings. Non è una suite 6/6 senza ripetizioni |
| Release R8 autonoma finale | Cold launch 953 ms (una misura), H264/AAC locale avviato, pausa a 14964 ms e ripresa da 14971 ms, error=null. Screenshot controllato e buffer crash finale vuoto |
| Lint | 2303 errori MissingTranslation preesistenti, 1754 warning, 25 hint. Zero errori tecnici e zero nuove segnalazioni per le due stringhe. Il lint complessivo resta non superato |

APK release x86_64 finale SHA256:

`7D5CAA73EC27C240467E648E9DD6A084183BBFC10A82D04BA432CD54E6BA1290`

Gli APK locali usano una firma di audit/test: non sono adatti ad aggiornare un’installazione di produzione. La CI confronta il certificato con quello pubblico della versione 13.

## Limiti

L’emulatore Android TV API 36 x86_64 su Windows usa pagine di memoria da 4096 byte: la verifica statica a 16 KiB non equivale a una prova runtime su dispositivo 16 KiB. Non è collegata una TV fisica. Il test IAMF riguarda il primo buffer di una fixture PCM; non certifica tutti i codec. WireGuard non apre tunnel e i test installer non sostituiscono una vera installazione tramite interfaccia Android.

La strumentazione sulla release ottimizzata si arresta prima dei test: AndroidJUnitRunner non trova `androidx.tracing.Trace`. Nessun keep di produzione è stato aggiunto per aggirare il limite. Le prove native debug e lo smoke della release R8 sono risultati distinti. I log conservano anche il tentativo fallito e il retry overlay.

Non è stato certificato che tutti i bug del progetto siano risolti. Le traduzioni preesistenti richiedono ancora triage.

## Checklist TV fisica

1. Quando pubblicato, usare l’APK della propria ABI con firma di produzione; annotare modello TV, Android, versione e rete.
2. Ripetere almeno cinque volte avvio rapido/lento, timeout con successo tardivo e uscita durante il caricamento.
3. Verificare pausa/ripresa, cambio sorgente, ritorno Home e assenza del timeout sopra un video già partito.
4. Provare download lento, Annulla, cambio canale, retry e ritorno dai permessi di installazione.
5. Registrare tempi, fase player e tipo errore; escludere URL completi, credenziali e token dai report condivisi.

Evidenze locali: `output/prerelease14-device/results.md`, log nella stessa directory e screenshot `release14-hu-final-playing.png`; verifiche Gradle in `output/verification`. Protocollo ripetibile: `docs/playback-device-test-protocol-it.md` e `scripts/playback_fixture.py`.

Fixture ed emulatore dell’audit sono stati arrestati; test APK rimossi. Nessuna pubblicazione o installazione su TV dell’utente eseguita.
