# MauzTube

<img src="docs/logo.svg" width="96" alt="Logo MauzTube">

Lightweight, ad-free YouTube client for Android with SponsorBlock, no Shorts, background &amp; pop-up playback, downloads (MP4/MP3) and optional Google account sync. Kotlin + Jetpack Compose + Media3, powered by NewPipeExtractor.

> **MauzTube is not affiliated with, endorsed or sponsored by YouTube or Google.**
> Licensed under the [GNU GPL v3.0](LICENSE). See the [Legal](#legal) section below.

Client YouTube alternativo per Android: niente pubblicità, salto automatico dei segmenti
sponsorizzati (SponsorBlock), ripresa esatta della riproduzione e Shorts nascosti ovunque.

Kotlin · Jetpack Compose · Media3/ExoPlayer · NewPipeExtractor · Room · DataStore · Hilt · MVVM

## Architettura

```
UI (Compose)  ──►  ViewModel (StateFlow)  ──►  Repository  ──►  NewPipeExtractor / SponsorBlock REST / Room / DataStore
                         │
                         └──► ExoPlayer (posseduto da PlayerViewModel, sopravvive alle rotazioni)
```

| Package | Contenuto |
|---|---|
| `data/extractor` | `NewPipeDownloader`: tutte le richieste HTTP di NewPipeExtractor passano da OkHttp |
| `data/model` | Modelli di dominio, `ContentMapper` (NewPipe → dominio), `ShortsFilter` |
| `data/local` | Room: posizioni/cronologia, cache SponsorBlock, iscrizioni, cronologia ricerche, commenti pubblicati |
| `data/remote` | `SponsorBlockApi` (endpoint hash-prefix) |
| `data/prefs` | `SettingsRepository` (DataStore): preferenze e sessione attiva |
| `data/repository` | `YouTubeRepository`, `SponsorBlockRepository`, `PlaybackRepository`, `SubscriptionRepository`, `CommentsRepository`, `SearchHistoryRepository` |
| `player` | `MediaSourceBuilder` (scelta stream + header per googlevideo), `SponsorSkipper` |
| `data/account` | `AccountManager`: sessione Google (cookie del WebView di login, header `SAPISIDHASH`) |
| `data/innertube` | `InnerTubeClient` + `InnerTubeParser`: home consigliata e correlati personalizzati |
| `ui/*` | Schermate: Home (consigliati), Login, Ricerca, Canale, Iscrizioni, Cronologia, Impostazioni, Player |

## I 4 requisiti

### 1. Blocco annunci
Gli annunci YouTube non sono nel file video: è il player ufficiale (o l'IMA SDK) a riceverli
dalla risposta `/player` (`adPlacements`, `playerAds`) e a riprodurli prima/durante il video.
NewPipeExtractor legge da quella risposta solo gli URL degli stream `googlevideo.com`, e
ExoPlayer riproduce direttamente quelli. Gli annunci non vengono mai richiesti, quindi non
c'è niente da filtrare.

È l'unico approccio che funziona davvero. Un `WebView` con `youtube.com` più un filtro di
rete (stile uBlock) si rompe a ogni cambio lato server. YouTube ormai fa anche server-side ad
insertion, cioè annunci cuciti nello stream. Un DNS/hosts-block non separa i domini degli annunci
da quelli dei video. Il player ufficiale (IFrame / Android Player API) vieta per contratto di
toccare gli annunci.

### 2. SponsorBlock
- `SponsorBlockApi`: `GET /api/skipSegments/{sha256(videoId)[0..4]}?categories=[…]&actionTypes=["skip"]`.
  L'endpoint hash-prefix non rivela al server quale video stai guardando. 404 vuol dire nessun segmento.
- `SponsorBlockRepository`: scarica **tutte** le categorie e le salva in Room. Filtra quelle
  scelte dall'utente solo al momento dell'uso. La cache dura 6 h; se la rete fallisce usa la cache scaduta.
- `SponsorSkipper`: ogni 200 ms durante la riproduzione, se la posizione è in un segmento
  → `seekTo(fine)`. Se l'utente fa seek dentro un segmento, quel segmento non viene saltato.
  La snackbar "Saltato: Sponsor" ha il pulsante **Annulla**. I segmenti compaiono come marker
  sulla timeline.

### 3. Ripresa della riproduzione
Salvataggio in `playback_state` (Room, chiave `videoId`) da `PlayerViewModel.savePosition()`:
- ogni N secondi durante la riproduzione (default 5, configurabile): protegge da kill e crash;
- quando il player va in pausa (manuale, buffering, cuffie scollegate, perdita audio focus);
- `ON_PAUSE` e `ON_STOP` dell'Activity (dopo onStop Android può uccidere il processo senza
  altri callback). In `ON_STOP` il video viene anche messo in pausa;
- `onCleared()` del ViewModel (equivalente di onDestroy per la schermata).

Le scritture vanno su un `CoroutineScope` applicativo con dispatcher seriale, così non vengono
cancellate insieme al ViewModel e restano nell'ordine giusto.

Il `videoId` attivo è salvato in DataStore. All'avvio `MainActivity` lo riapre e il player
fa `setMediaSource(source, posizioneSalvata)` **prima** di `prepare()`, quindi riparte da
quel millisecondo. Uscire dal player con "indietro" è un'uscita volontaria: la posizione resta
salvata, ma il video non viene riaperto in automatico all'avvio successivo.

### 4. Shorts nascosti a livello di modello dati
Ogni `InfoItem` di NewPipe passa da `ContentMapper.map()`, che restituisce `null` per gli
Shorts (`isShortFormContent`, URL `/shorts/`, opzionalmente durata ≤ 60 s). Nessuno Short esce
dai repository, quindi home, ricerca, canali, iscrizioni e video correlati non devono filtrare
nulla. In più, dei canali si carica solo la tab **Video** (mai la tab Shorts), e un link
`/shorts/…` aperto dall'esterno viene rifiutato da `YouTubeRepository.videoDetails()`.

## Account Google, velocità, sottotitoli

### Consigliati dal profilo Google
- **Login** (`LoginScreen`): pagina di accesso Google in un WebView con User-Agent di Chrome
  mobile. A login completato i cookie di youtube.com restano nel `CookieManager` di sistema.
  Se Google rifiuta il WebView ("browser non sicuro"), c'è **Impostazioni → Importa cookie**:
  si incolla l'header `Cookie` copiato da un browser desktop già loggato.
- **Home**: solo i consigliati dell'account (`browse` con `FEwhat_to_watch` sull'API InnerTube
  del sito, autenticata con `SAPISIDHASH`), con scroll infinito. Le categorie non ci sono più.
- **Correlati** nel player: quelli personalizzati dell'account (endpoint `next`). Se non sei
  loggato si usano quelli di NewPipe.
- **Cronologia**: dopo 10 s di visione il video viene segnato come visto sull'account, con gli
  stessi ping `playbackTracking` del sito. Così i consigliati imparano anche da quello che guardi
  qui. Si può disattivare dalle impostazioni.
- `InnerTubeParser` non dipende dai percorsi JSON esatti: cerca i renderer video
  (`lockupViewModel`, `videoRenderer`, …). Salta interi sotto-alberi di **annunci in-feed**
  (`adSlotRenderer`, …) e di **Shorts** (`reelShelfRenderer`, `shortsLockupViewModel`, …).

### Consigli senza account (`LocalRecommender`)
Senza login la home propone video calcolati **sul telefono** da due fonti già salvate in Room:
- i **correlati dei video visti**, salvati ogni volta che apri un video senza richieste extra.
  Un video che compare nei correlati di più video visti sale in classifica;
- gli **ultimi video dei canali** seguiti e di quelli che guardi più spesso, pesati per
  affinità (iscrizione, numero di visioni) e freschezza.

I video già visti sono esclusi, ci sono al massimo 2 video per canale nelle prime posizioni e a
ogni aggiornamento l'ordine varia un po'. Il calcolo non usa la rete, quindi la home compare
subito. Le fonti si aggiornano in background: canali più vecchi di 3 h, correlati mancanti
solo su Wi-Fi.
Con l'account collegato la home mostra invece il feed consigliato dell'account, e la scheda
Iscrizioni il feed iscrizioni dell'account (una sola richiesta). Se l'account non risponde,
la home ripiega sui consigli locali.

### Guarda più tardi e condivisione
- Menu **⋮** su ogni video e pulsanti nel player: *Guarda più tardi*, *Condividi* e
  *Condividi dal minuto attuale* (link `youtu.be/…?t=`).
- La lista è locale (scheda **Raccolta**, insieme alla cronologia). Con l'account le modifiche
  vengono inviate anche alla playlist "Guarda più tardi" di YouTube, e quella playlist viene
  importata.

### Qualità e aspetto
- **Qualità minima e massima**: tutte le risoluzioni comprese nell'intervallo finiscono in un
  unico manifest DASH adattivo. ExoPlayer parte da quella che la rete regge subito e sale.
  Dall'ingranaggio del player si può fissarla a mano.
- **Tema** Sistema / Chiaro / Scuro e interruttore **Colori di sistema (Material You)**. Se
  disattivato si usa la palette di MauzTube.

### Velocità di apertura
Misure reali sull'estrazione (NewPipeExtractor v0.26.5, JVM):

| | tempo di estrazione |
|---|---|
| primo video dopo l'avvio | ~5 s |
| video successivi | ~2 s (8 richieste HTTP in sequenza, di cui `next` ≈ 1 s) |

Interventi:
1. **Warm-up all'avvio**: connessioni TLS aperte e versione del client WEB ricavata subito.
   Elimina buona parte del costo del primo video.
2. **Prefetch** (come fa l'app ufficiale): vengono estratti in anticipo i primi 3 consigliati
   della home e i primi 2 correlati dopo 5 s di riproduzione, con cache di 30 min e unione delle
   richieste in corso. Aprire uno di questi video è quasi istantaneo. Il prefetch avviene solo
   su rete non a consumo (Wi-Fi).
3. **Anteprima immediata**: titolo e miniatura del video toccato compaiono subito nel player.
4. **DASH a blocchi**: ogni stream viene riprodotto tramite un manifest DASH generato dai
   metadati itag (`YoutubeProgressiveDashManifestCreator`, come NewPipe), invece di un'unica
   richiesta progressiva aperta. ExoPlayer scarica blocchi con range limitato, quindi avvio e
   seek sono più rapidi e non c'è throttling.
5. **Buffer d'avvio** di 1,5 s (2,5 s dopo uno stallo) e buffer di riserva tra 30 e 50 s, così
   un calo momentaneo della rete non blocca il video. La qualità automatica usa il 65% della
   banda stimata, per non salire a una risoluzione che la rete non regge.

### Salto sponsor senza blocchi
Con la richiesta progressiva aperta, un seek fuori dal buffer (come lo skip di uno sponsor)
apriva una nuova connessione a `googlevideo.com` con `Range: bytes=X-` illimitato. Questo tipo
di richiesta può essere rifiutato (403) o rallentato. Il 403 faceva scattare la ri-estrazione
automatica, con il video fermo in caricamento per alcuni secondi. Con il DASH a blocchi il seek
scarica solo il blocco necessario, e dopo uno stallo bastano 1,5 s di buffer per ripartire.
In caso di errore la ri-estrazione ora ignora la cache, perché gli URL possono essere scaduti.

### Sottotitoli
Tracce di YouTube (manuali e generate automaticamente) in TTML, aggiunte come sorgenti
esterne e scelte dal pulsante **CC** del player. Per non rallentare l'avvio se ne caricano al
massimo 4, in ordine: lingua del dispositivo, inglese, automatici. In Impostazioni si possono
attivare automaticamente nella lingua del dispositivo.

### Player
- **Unico player per tutta l'app** (`PlaybackManager`), indipendente dalle schermate:
  - **mini player** in basso a destra mentre navighi. Toccalo per riaprire il video;
    trascinalo verso il basso o verso destra per chiuderlo;
  - **background e schermo spento**: `PlaybackService` (Media3 `MediaSessionService`) fornisce
    notifica con controlli, lockscreen e cuffie. Fuori dall'app la traccia video viene
    disattivata (solo audio: meno dati e batteria) e si riattiva al ritorno. Disattivabile.
- **Controlli personalizzati** in Compose, con i colori del tema: barra con buffer e segmenti
  SponsorBlock, doppio tocco ±10 s, menu **Qualità** (automatica o fissa), **Velocità** e
  **Sottotitoli**, pulsante **Solo audio**.
- In alto nel player: **Riproduzione automatica** (a fine video parte il primo consigliato non
  ancora visto; disattivata di default, la scelta viene ricordata) e **Ripeti** il video
  (disattivato di default).
- **Gesti**: in verticale trascinando il video verso il basso diventa mini player; a schermo
  intero trascinando verso l'alto compaiono i video consigliati.
- **Decoder di riserva**: se il decoder hardware del telefono fallisce, il video riparte dallo
  stesso punto con un altro decoder invece di fermarsi con un errore.
- **Data di pubblicazione** in formato europeo (gg/mm/aaaa).

### Commenti, ricerche, modalità anonimo
- **Commenti** sotto il video (caricati solo quando arrivi a quel punto, per non rallentare
  l'apertura), con risposte e caricamento progressivo. Con l'account collegato si può
  **commentare**. In **Cronologia → I tuoi commenti** ci sono i commenti pubblicati da MauzTube,
  con il relativo video, e la pagina dell'attività Google con tutti i commenti dell'account.
- **Cronologia ricerche** (attiva di default, disattivabile e cancellabile dalle Impostazioni):
  le ultime ricerche compaiono toccando la barra di ricerca e si possono rimuovere una per una.
- **Modalità anonimo** (icona in alto): finché è attiva non vengono salvati cronologia, punti di
  ripresa e ricerche, e l'account non viene usato. Vale fino alla chiusura dell'app, oppure si
  disattiva dal banner in alto.
- **Cronologia**: con l'account collegato è quella dell'account Google, con il punto di ripresa
  locale sovrapposto; altrimenti è quella locale.

Predefiniti: tema **scuro**, colori di sistema **disattivati**, ripresa e riapertura
automatica **disattivate** (si attivano dalle Impostazioni).

### Coda, playlist, primo avvio
- **Coda** (`PlaybackManager.queue`): "Aggiungi alla coda" dal menu ⋮ di ogni video e con
  l'icona dedicata su ogni video di "Prossimi video" nel player. La barra della coda compare in
  tutta l'app solo quando la coda contiene video, e si apre con un tocco o trascinandola verso
  l'alto. Toccando un video della coda puoi riprodurlo subito, spostarlo (in cima, su, giù) o
  toglierlo. Il video successivo viene estratto in anticipo, così il passaggio è immediato. A coda
  esaurita la barra scompare. La coda è solo in memoria: chiusa l'app, sparisce.
- **Playlist**: "Guarda più tardi", playlist locali (create, rinominate, eliminate, "Riproduci
  tutto" e "Aggiungi tutto in coda") e, con l'account collegato, le playlist dell'account
  Google. "Salva in playlist" è disponibile dal menu ⋮ e dal player.
- **Iscrizioni sincronizzate** con l'account: iscriversi e disiscriversi vale anche su
  YouTube. Al primo collegamento le iscrizioni locali vengono aggiunte all'account; dopo,
  l'account è la fonte di verità.
- **Primo avvio**: scelta tra "Accedi con Google" e "Continua senza account".

### Fluidità
- Transizioni di navigazione da 140 ms (il default di Navigation Compose è 700 ms).
- Tocco sul player immediato: il doppio tocco ±10 s non ritarda più il tocco singolo.
- **Animazioni leggere** (`ui/components/Motion.kt`): casella di ricerca a pillola con sfondo
  animato, ricerche recenti e risultati in dissolvenza, miniature in dissolvenza solo quando
  arrivano dalla rete, entrata del mini player, banner anonimo, "rimbalzo" di Mi piace/Non mi
  piace. Durano 150–220 ms e cambiano solo trasparenza, scala e posizione in `graphicsLayer`,
  quindi non ricompongono né rimisurano la schermata. Misurato sull'emulatore: il lavoro
  dell'app per fotogramma durante lo scorrimento resta sotto 1 ms, come senza animazioni.
- `profileinstaller`: i Baseline Profile di Compose vengono compilati anche su APK
  sideloaded. **Per un'app fluida va installato l'APK release**: la build debug è
  "debuggable" e Compose al suo interno è molto più lento. Senza keystore personale la release
  viene firmata con la chiave di debug, sia in locale che in CI (artifact
  `MauzTube-release-debugkey-…`).
- Miniature delle liste a 480p invece di 720p, connessioni HTTP riutilizzate più a lungo e
  riaperte al ritorno nell'app, prefetch dei video fermi sullo schermo per 1,2 s (solo Wi-Fi,
  massimo 2).

### Lingua, popup, valutazioni
- **Lingua**: inglese predefinito, oppure italiano. Si sceglie al primo avvio e si cambia dalle
  Impostazioni; il cambio è immediato. Si adeguano anche date e contatori di YouTube
  (`util/I18n.kt`, funzione `tr(en, it)`).
- **Popup (Picture-in-Picture)**: pulsante nel player e attivazione automatica uscendo
  dall'app con un video in riproduzione (disattivabile). Chiudendo il popup il video si ferma.
- **Mi piace / Non mi piace**: salvati in locale (lista "Video che ti piacciono" tra le
  playlist) e, con l'account collegato, sincronizzati con YouTube. La valutazione dell'account
  prevale. I conteggi dei "non mi piace" arrivano da Return YouTube Dislike (disattivabile).
- **Anteprima sulla barra**: trascinando la barra compare il fotogramma del punto scelto
  (storyboard YouTube, sprite di circa 25 KB caricati solo durante il trascinamento).
- **Schermo intero e foro della fotocamera**: il video occupa tutto il display, anche la zona
  del foro/notch, mentre i comandi ne restano fuori, come nel client ufficiale.

### Download
- **Video MP4**: tutte le qualità H.264 fino a 1080p (più il file unico a 360p, più rapido).
  Video e audio si scaricano separatamente e vengono uniti **senza ricodifica** con
  `MediaMuxer` di Android: rapido e quasi senza CPU.
- **Audio MP3** a 320/192/128 kbps: l'audio originale (AAC) viene decodificato con il
  decoder di sistema e codificato in MP3 con LAME (jump3r, Java puro, nessuna libreria
  nativa), con tag ID3 di titolo e artista. In più c'è l'**M4A originale**, senza ricodifica.
- Scaricamento a blocchi da 8 MB con `Range`, così YouTube non rallenta. Riprova automatica con
  link nuovi se quelli vecchi sono scaduti. Servizio in primo piano con notifica di avanzamento.
  I file finiscono in **Download/MauzTube**. La pagina "Download" (dalla sezione Playlist)
  permette di aprire, condividere, riprovare ed eliminare.
- jump3r usa `javax.sound.sampled.AudioFormat`, che su Android non esiste: l'app ne include
  una versione minima (`app/src/main/java/javax/sound/sampled/`), un semplice contenitore di dati.

### Fluidità misurata
Misure fatte su un emulatore Android 17, prima e dopo, con gli script in `tools/baseline-profile/`:
- **Baseline Profile** (`app/src/main/baseline-prof.txt`, circa 73.000 righe), registrato da un
  percorso d'uso reale. Android compila in anticipo il codice dell'app (avvio, schede,
  ricerca, scorrimento, player, commenti) invece di interpretarlo. Durante lo scorrimento il lavoro
  di Compose scende da 0,4 a 0,1 ms per fotogramma.
- **Impostazioni** come lista lazy, e lettura dello stato dei link fuori dal thread UI: il primo
  fotogramma passa da 57–73 ms a 20–22 ms.
- **ExoPlayer creato al primo video** invece che all'avvio dell'app.
- **Miniature decodificate a 640×360** (la sorgente è ≤ 480 px): circa 2,7 volte meno memoria per
  immagine e meno lavoro di rendering durante lo scorrimento.

Nota: dopo ogni installazione da APK Android applica il profilo alla prima ottimizzazione in
background, di solito con il telefono inattivo e in carica. Per farlo subito:
`adb shell cmd package compile -f -m speed-profile com.mauztube`.

### Logo
Logo originale (`docs/logo.svg`, `app/src/main/res/drawable/ic_logo.xml`): un cerchio cremisi
con un anello di avanzamento aperto e un "play" al centro. Nessun rettangolo arrotondato né
forme o colori del marchio di YouTube, quindi è distribuibile liberamente insieme al progetto. Varianti:
icona adattiva (Android 8+), monocromatica per le icone a tema (Android 13+), riquadro per
Android 7, icona piccola delle notifiche.

## Compilare

### Senza compilare in locale (GitHub Actions)
1. Crea un repository su GitHub e fai push del progetto:
   ```bash
   git init && git add . && git commit -m "MauzTube"
   git branch -M main
   git remote add origin git@github.com:<utente>/MauzTube.git
   git push -u origin main
   ```
2. **Actions → Build APK →** l'ultima esecuzione **→ Artifacts → `MauzTube-debug-…`**.
3. Scompatta lo zip e installa l'APK (abilita "Origini sconosciute").

### In locale
Requisiti: JDK 17+ (verificato con JDK 25; la CI usa il 21) e Android SDK con la piattaforma **android-37**
(Android Studio la installa da solo; da riga di comando:
`sdkmanager "platforms;android-37.0"`).

```bash
git clone https://github.com/<utente>/MauzTube.git
cd MauzTube
export ANDROID_HOME=$HOME/Android/Sdk      # oppure crea local.properties con sdk.dir=...
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

La debug ha applicationId `com.mauztube.debug` e convive con la release.

### Release firmata
1. Crea un keystore, una volta sola, e **conservalo**: senza lo stesso keystore non potrai
   aggiornare l'app installata.
   ```bash
   keytool -genkeypair -v -keystore mauztube-release.jks -alias mauztube \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. **Locale:** `cp keystore.properties.example keystore.properties`, compila i valori, poi
   `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
3. **CI:** nei Secrets del repository aggiungi `KEYSTORE_BASE64` (`base64 -w0 mauztube-release.jks`),
   `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. Il workflow produrrà anche l'artifact
   `MauzTube-release-…`.

In alternativa puoi firmare a mano l'APK non firmato:
```bash
zipalign -p 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks mauztube-release.jks --out MauzTube.apk aligned.apk
```

## Limiti noti

**Legali**
- Usare un client non ufficiale che salta gli annunci viola i
  [Termini di servizio di YouTube](https://www.youtube.com/t/terms) (accesso con mezzi
  automatizzati, aggiramento della pubblicità). Il rischio pratico per il singolo utente è
  basso, ma in teoria YouTube può bloccare IP o account. Questa app non usa account.
- NewPipeExtractor è **GPL-3.0**: se distribuisci l'APK, anche MauzTube va distribuito con
  licenza GPL-3.0 e con il sorgente.
- Niente Play Store: Google rimuove i client YouTube alternativi. Distribuisci l'APK direttamente
  (o tramite F-Droid / repository personale).

**Manutenzione**
- YouTube cambia spesso i meccanismi anti-scraping (cifratura delle firme, parametro `n`,
  PoToken/BotGuard, client InnerTube). Quando la riproduzione smette di funzionare quasi sempre
  basta **aggiornare `newpipeExtractor` in `gradle/libs.versions.toml`** alla release più recente
  di [TeamNewPipe/NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor/releases) e
  ricompilare. Conviene seguire le release.
- Il **PoToken** (attestazione BotGuard) non è implementato. L'estrattore usa client che non lo
  richiedono (Android/iOS/visionOS). Se YouTube lo rendesse obbligatorio ovunque, servirebbe
  un `PoTokenProvider` basato su WebView, come fa l'app NewPipe
  (`YoutubeStreamExtractor.setPoTokenProvider`).
- Gli stream DASH "OTF" (alcuni video appena caricati o ex-live) vengono scartati. In quel caso
  si ripiega su stream muxed o HLS.
- **Login Google**: Google può bloccare l'accesso dal WebView (in quel caso usa "Importa
  cookie"). Usare i cookie dell'account con un client non ufficiale è contro i ToS, e in teoria
  espone l'account a restrizioni: valuta un account secondario. Se la sessione scade, serve un
  nuovo accesso.
- **Velocità**: con il prefetch i video "previsti" si aprono subito. Gli altri richiedono
  comunque ~2 s di estrazione: è il costo delle ~8 richieste di NewPipeExtractor e non si può
  azzerare senza riscrivere l'estrattore.
- **Traduzione automatica dei sottotitoli** (`tlang`): YouTube la blocca con un 429 anti-bot
  senza token, quindi non è inclusa.
- **Video 18+**: con l'account collegato MauzTube chiede gli stream a YouTube come client
  TV autenticato (`AuthenticatedStreamFetcher`), come fa yt-dlp con i cookie. Quei formati
  però richiedono la decifratura del player JS di YouTube (firma e parametro `n`), e in
  NewPipeExtractor v0.26.5 è rotta per il player attuale. È verificato: la firma non viene
  trovata e `n` restituisce `[object Object]`; la correzione è nella PR upstream #1545. L'app
  riconosce il problema e mostra un messaggio chiaro. Quando uscirà una release con la
  correzione basterà aggiornare `newpipeExtractor`. Non è stato possibile provare il
  percorso con un account maggiorenne reale.
- Senza account le **iscrizioni** restano solo sul telefono; con l'account vengono sincronizzate
  con YouTube (la lettura delle iscrizioni e delle playlist dell'account non è stata provata
  con un account reale durante lo sviluppo).

## Legal

### Disclaimer
MauzTube is an independent, unofficial project. It is **not affiliated with, endorsed,
sponsored or approved by YouTube, Google LLC or Alphabet Inc.** "YouTube" and "Google" are
trademarks of Google LLC and are used here only to describe the service this app connects to.
The MauzTube name and logo are original works and are not derived from YouTube's branding.

MauzTube does not host, store or distribute any video content. All content is streamed
directly from YouTube's servers at the user's request, and remains the property of its
respective owners.

### Intended use
MauzTube is provided for **personal, non-commercial use only**. Using third-party clients
may conflict with YouTube's Terms of Service; by using this app you accept that responsibility.
Users are solely responsible for how they use the app and for complying with the laws of
their country and with the terms of the services they access.

### Downloads
The download feature is intended **only** for content you own, content released under a
license that permits downloading (e.g. Creative Commons), or cases explicitly allowed by
applicable law (such as private copying where permitted). **Do not use it to infringe
copyright.** The authors do not condone piracy and are not responsible for any misuse.

### Google account
Signing in is optional. Comments are posted only when you explicitly send them. Account cookies are stored only on your device and are sent only to
YouTube/Google to load your recommendations, subscriptions, history and playlists. Using
an account with an unofficial client may lead to restrictions imposed by Google; sign in
at your own risk.

### Privacy
MauzTube contains **no analytics, no tracking and no ads**, and the authors receive no data
from it. Settings, history, playlists and downloads are stored locally on your device.
The app connects only to:
- **YouTube / Google** (`youtube.com`, `googlevideo.com`, `ytimg.com`): video data, thumbnails
  and, if signed in, account features;
- **SponsorBlock** (`sponsor.ajay.app`): only a short hash prefix of the video ID is sent;
- **Return YouTube Dislike** (`returnyoutubedislikeapi.com`, optional): the video ID is sent
  to retrieve vote counts.

### No warranty
This software is provided "as is", without warranty of any kind, express or implied. In no
event shall the authors be liable for any claim, damages or other liability arising from
the use of this software. See the [license](LICENSE) for details.

### License
Copyright (C) 2026 MauzTube contributors

This program is free software: you can redistribute it and/or modify it under the terms of
the **GNU General Public License** as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version.
See [LICENSE](LICENSE) for the full text.

### Third-party software and data
| Component | License |
|---|---|
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | GPL-3.0-or-later |
| [jump3r](https://github.com/Sciss/jump3r) (MP3 encoder, LAME port) | LGPL-2.1-or-later |
| AndroidX, Jetpack Compose, Media3 / ExoPlayer, Hilt, Room, DataStore | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp), [Coil](https://github.com/coil-kt/coil), kotlinx.serialization / coroutines | Apache-2.0 |
| [Material Design Icons](https://github.com/google/material-design-icons) (vector paths) | Apache-2.0 |

- Skip segment data is provided by **[SponsorBlock](https://sponsor.ajay.app)** and is licensed
  under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). For this reason
  MauzTube must not be used commercially.
- Dislike counts are provided by **[Return YouTube Dislike](https://returnyoutubedislike.com)**.

### Contact and takedown
If you are a rights holder and believe this repository infringes your rights, please
[open an issue](../../issues) before filing a formal request: it will be addressed promptly.
