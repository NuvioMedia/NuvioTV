# Prompt operativo: stabilita NuvioTV

Analizza brusus/NuvioTV sul branch dev e correggi i difetti dimostrabili, iniziando
dal messaggio di timeout che puo restare visibile mentre il video e gia partito.
Lavora su un branch dedicato. Leggi le regole del repository e i percorsi di avvio,
chiusura, cambio motore, pausa, ripresa ed errore di ExoPlayer e MPV.

Identifica le cause prima di modificare il codice. Mantieni le preferenze, i dati
utente e le interfacce esistenti. Non mascherare errori reali e non limitarti ad
aumentare i timeout. Aggiungi test di regressione per i difetti corretti, esegui
la suite disponibile, la compilazione e i controlli statici pertinenti. Analizza
anche gli altri problemi emersi dai controlli e correggi quelli riproducibili.

Documenta separatamente correzioni, verifiche riuscite, controlli bloccati e
scenari che richiedono un dispositivo reale. Prepara le modifiche per la revisione
su GitHub senza pubblicare automaticamente una release. Crea un PDF in italiano
con risultati, rischi residui, istruzioni di verifica e suggerimenti. Non dichiarare
che ogni bug e risolto o che non esistono regressioni senza evidenza.
