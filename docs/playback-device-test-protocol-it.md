# Protocollo ripetibile di riproduzione su Android TV

Questo protocollo usa una fixture HTTP reale con un video sintetico locale. I test
Kotlin `PlayerRuntimeControllerStartupIntegrationTest` eseguono invece il confine
del controller con tempo virtuale e callback ritardate: non decodificano video e
non verificano una TV fisica. Le due prove sono complementari.

## Preparazione Windows

Servono Python 3.10+, Android SDK platform-tools (`adb`) e un MP4 H.264/AAC locale.
Per generare un campione riproducibile con FFmpeg:

```powershell
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 90 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart audit-video.mp4
python scripts/playback_fixture.py --video ./audit-video.mp4
```

La fixture ascolta solo su `127.0.0.1:8765`. Per emulatore o TV collegata via ADB:

```powershell
adb devices -l
adb -s SERIAL reverse tcp:8765 tcp:8765
```

Sostituire `SERIAL` con il dispositivo elencato. Installare l'APK debug compilato
dalla revisione in esame, quindi aggiungere nell'app l'addon locale
`http://127.0.0.1:8765/manifest.json`. Il catalogo si chiama "Audit riproduzione".
La TV deve essere gia collegata e autorizzata al debug dal proprietario; non
attivare il debug di rete o modificare il firewall alla cieca.

Se la TV non supporta `adb reverse`, usare lo stesso PC e una rete locale fidata:

```powershell
python scripts/playback_fixture.py --video ./audit-video.mp4 --bind 0.0.0.0 --public-base-url http://IP_DEL_PC:8765
```

In questo caso aggiungere `http://IP_DEL_PC:8765/manifest.json`. La raggiungibilita
dipende dalla rete/firewall del dispositivo. Terminare la fixture a fine prova.

## Condizioni e ripetizioni

Annotare revisione/variante APK, modello TV, versione Android, engine, impostazione
di cambio automatico engine, tunneling, audio passthrough e tipo di collegamento.
Iniziare con cambio automatico engine **disattivato**, cosi il timeout rimane
osservabile. Ripetere poi con cambio automatico abilitato e con entrambi gli engine.
Il primo fotogramma e il ripristino dell'audio vanno verificati sullo schermo.
Per confrontare i tempi di avvio scegliere "dall'inizio" e annotare qualsiasi
posizione salvata: una ripresa con seek cambia le richieste HTTP del player.

Ogni nuova richiesta delle sorgenti produce un URL con un nuovo numero di prova.
La sorgente slow ritarda solo la prima richiesta da byte zero per quella prova;
seek, retry e richieste Range successive non ricevono un secondo ritardo. Per
ripetere la prova lenta, ricaricare le sorgenti e controllare un nuovo `run` nel
log della fixture. Se il client mantiene le sorgenti in cache, riavviare l'app e
ricaricare il catalogo. Non riusare lo stesso URL pensando di ripetere il ritardo.

| Caso | Azione | Risultato atteso |
|---|---|---|
| Fast | Avviare `Audit fast`, poi pausa/ripresa, seek avanti/indietro | Video e audio avanzano; nessun timeout tardivo dopo 30 s |
| Slow | Avviare `Audit slow`, aspettare almeno 40 s | Con failover disattivato, possibile timeout intorno a 25 s; dopo arrivo del video intorno a 28 s l'errore di timeout scompare |
| Hung | Avviare `Audit hung` e aspettare 35 s | Errore di timeout e uscita/retry disponibili, nessuno spinner infinito |
| Uscita | Lasciare `slow` o `hung` prima di 25 s; avviare `fast` | Il vecchio timer non mostra errori sul nuovo video |
| Sostituzione | Passare da `slow` a un'altra sorgente/engine prima del timeout | Solo la sessione corrente puo completare il caricamento o mostrare errori |
| Pausa in avvio | Se il dispositivo consente pausa prima del primo frame, attendere >25 s e riprendere | La pausa non consuma il budget; alla ripresa parte un budget nuovo |
| Failover | Ripetere `slow` e `hung` con cambio automatico abilitato | Eventuale cambio engine coerente con impostazioni; nessun errore del player precedente |

Eseguire almeno 5 ripetizioni per caso e per engine, includendo un avvio a freddo.
Su TV fisica aggiungere contenuti legittimi del provider interessato e le prove
di sottotitoli, ripresa salvata, episodio successivo, HDR e passthrough pertinenti.
Il video sintetico non certifica codec hardware, Dolby Vision o provider esterni.
Il tempo esatto del primo frame dipende da buffering, demuxer e hardware: il
ritardo della fixture e controllato, il rendering del dispositivo non lo e.

## Evidenze da raccogliere

Usare la diagnostica gia presente nell'app: tempo totale di avvio, durata delle
fasi, engine, primo fotogramma, categoria dell'errore (STARTUP_TIMEOUT, PLAYER,
STREAM, TORRENT) e codice Media3/HTTP se disponibile. Per ogni ripetizione salvare:

```text
revisione;dispositivo;engine;caso;run;avvio_ms;fase;categoria;codice;esito;note
```

Conservare localmente eventuali screenshot/log Android grezzi. Prima di condividere
un report controllare che non contenga URL completi, credenziali, query con token,
header sensibili o percorsi personali. Preferire il report sanitizzato dell'app;
non pubblicare automaticamente `adb logcat` grezzo. Il log della fixture riporta
solo scenario, numero prova, byte iniziale e presenza del ritardo.

I risultati su TV fisica devono essere marcati **non eseguiti** finche non viene
provato un dispositivo effettivamente collegato. Non equiparare l'emulatore alla TV.

## Pulizia e test della fixture

```powershell
adb -s SERIAL reverse --remove tcp:8765
python -m unittest scripts.tests.test_playback_fixture
```

Terminare il server con Ctrl+C e rimuovere l'addon di prova se non serve piu.
I test Python verificano socket HTTP reali, richieste Range e HEAD, ritardo una
sola volta per prova, nuovi identificativi, e assenza di query segrete dai log.

## Regressioni Android: provider TV e tasto Indietro

I test strumentali mirati richiedono Android TV API 26 o superiore e un profilo
di prova gia configurato sulla Home. Installare l'APK dell'app e l'APK androidTest
della stessa variante `fullDebug`, firmati con la stessa chiave. Non cancellare
i dati dell'app per aggirare eventuali problemi di firma.

```powershell
./gradlew.bat :app:assembleFullDebug :app:assembleFullDebugAndroidTest
adb -s SERIAL install -r APP_APK
adb -s SERIAL install -r TEST_APK
adb -s SERIAL shell am instrument -w -r -e class com.nuvio.tv.core.recommendations.TvProviderCrudTest,com.nuvio.tv.MainActivityOverlayBackTest com.nuviodebug.com.test/androidx.test.runner.AndroidJUnitRunner
```

`TvProviderCrudTest` usa il vero ContentProvider TV con l'UID dell'app: crea,
legge, aggiorna e rimuove esclusivamente righe sintetiche identificate da UUID,
con pulizia in `finally`. Verifica Watch Next e un canale Preview non pubblicato.
Non modifica le righe dell'utente o la preferenza del canale salvato.

`MainActivityOverlayBackTest` usa l'Activity reale. Introduce in memoria un
overlay sintetico e verifica Indietro da telecomando e dispatcher; il successivo
Indietro deve tornare alla destinazione. Include navigazione Home -> Settings
mentre l'overlay e gia visibile, per verificare la priorita rispetto ai nuovi
handler della destinazione. Richiede il menu TV standard e la categoria Account
iniziale; non effettua login o modifica impostazioni. Lo stato sintetico viene
ripristinato in `finally`. La verifica del dispatcher avviene sulla destinazione
attivata, dopo la navigazione: non certifica il gesto predittivo durante ogni
fotogramma di transizione o il ritorno da un player esterno reale.

Questi test si affiancano ai test JVM di identita dei FocusRequester e del
controller. Per i test di focus del telecomando, provare inoltre scorrimento
orizzontale, apertura/chiusura sidebar, Settings e ritorno Home sul dispositivo.
