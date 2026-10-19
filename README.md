# MauzTube

<img src="docs/logo.svg" width="96" alt="MauzTube logo">

Lightweight, ad-free YouTube client for Android with SponsorBlock, no Shorts, background &amp; pop-up playback, downloads (MP4/MP3) and optional Google account sync. Kotlin + Jetpack Compose + Media3, powered by NewPipeExtractor.

> **MauzTube is not affiliated with, endorsed or sponsored by YouTube or Google.**
> Licensed under the [GNU GPL v3.0](LICENSE). See the [Legal](#legal) section below.

**Download:** the latest APK is on the [Releases](../../releases) page (Android 7.0 or later).

Kotlin · Jetpack Compose · Media3/ExoPlayer · NewPipeExtractor · Room · DataStore · Hilt · MVVM

## Architecture

```
UI (Compose)  ──►  ViewModel (StateFlow)  ──►  Repository  ──►  NewPipeExtractor / SponsorBlock REST / Room / DataStore
                         │
                         └──► PlaybackManager (single ExoPlayer for the whole app, survives rotations)
```

| Package | Contents |
|---|---|
| `data/extractor` | `NewPipeDownloader`: every HTTP request made by NewPipeExtractor goes through OkHttp |
| `data/net` | `CachingDns` and `NetworkWatcher`: resilient networking (see [Network resilience](#network-resilience)) |
| `data/model` | Domain models, `ContentMapper` (NewPipe → domain), `ShortsFilter` |
| `data/local` | Room: playback positions/history, SponsorBlock cache, subscriptions, search history, posted comments |
| `data/remote` | `SponsorBlockApi` (hash-prefix endpoint) |
| `data/prefs` | `SettingsRepository` (DataStore): preferences and active session |
| `data/repository` | `YouTubeRepository`, `SponsorBlockRepository`, `PlaybackRepository`, `SubscriptionRepository`, `CommentsRepository`, `SearchHistoryRepository` |
| `player` | `PlaybackManager`, `MediaSourceBuilder` (stream selection + googlevideo headers), `SubtitleLoader`, `SponsorSkipper` |
| `data/account` | `AccountManager`: Google session (cookies from the login WebView, `SAPISIDHASH` header) |
| `data/innertube` | `InnerTubeClient` + `InnerTubeParser`: account recommendations and personalised related videos |
| `ui/*` | Screens: Home, Login, Search, Channel, Subscriptions, History, Settings, Player, Comments |
| `util` | `I18n` (`tr(en, it)`), `CrashLog` / `SafeHandler`, `DeviceProfile` |

## Core design

### 1. Ad blocking
YouTube ads are not part of the video file: the official player (or the IMA SDK) receives them
from the `/player` response (`adPlacements`, `playerAds`) and plays them before or during the
video. NewPipeExtractor only reads the `googlevideo.com` stream URLs from that response, and
ExoPlayer plays those directly. Ads are never requested, so there is nothing to filter.

It is the only approach that really works. A `WebView` with `youtube.com` plus a network
filter (uBlock-style) breaks with every server-side change. YouTube also does server-side ad
insertion, i.e. ads stitched into the stream. A DNS/hosts block cannot tell ad domains apart
from video domains. The official player (IFrame / Android Player API) contractually forbids
touching ads.

### 2. SponsorBlock
- `SponsorBlockApi`: `GET /api/skipSegments/{sha256(videoId)[0..4]}?categories=[…]&actionTypes=["skip"]`.
  The hash-prefix endpoint does not reveal to the server which video you are watching. A 404 means no segments.
- `SponsorBlockRepository`: downloads **all** categories and stores them in Room. The categories
  chosen by the user are filtered only when used. The cache lasts 6 h; if the network fails the
  expired cache is used.
- `SponsorSkipper`: every 200 ms during playback, if the position is inside a segment
  → `seekTo(end)`. If the user seeks into a segment, that segment is not skipped.
  The "Skipped: Sponsor" snackbar has an **Undo** button. Segments are shown as markers on the
  timeline.

### 3. Resuming playback
Positions are saved in `playback_state` (Room, key `videoId`):
- every N seconds during playback (default 5, configurable): protects against kills and crashes;
- when the player pauses (manually, buffering, headphones unplugged, audio focus loss);
- on `ON_PAUSE` and `ON_STOP` of the Activity (after onStop Android may kill the process without
  further callbacks). On `ON_STOP` the video is also paused;
- when a video is closed or replaced.

Writes go to an application `CoroutineScope` with a serial dispatcher, so they are not
cancelled together with a ViewModel and keep their order.

The active `videoId` is stored in DataStore. On start-up `MainActivity` reopens it and the
player calls `setMediaSource(source, savedPosition)` **before** `prepare()`, so it restarts from
that exact millisecond. Leaving the player with "back" is a deliberate exit: the position stays
saved, but the video is not reopened automatically on the next start.

### 4. Shorts hidden at the data-model level
Every NewPipe `InfoItem` goes through `ContentMapper.map()`, which returns `null` for Shorts
(`isShortFormContent`, `/shorts/` URLs, optionally duration ≤ 60 s). No Short ever leaves the
repositories, so home, search, channels, subscriptions and related videos need no filtering.
Moreover, only the **Videos** tab of a channel is loaded (never the Shorts tab), and a
`/shorts/…` link opened from outside is rejected by `YouTubeRepository.videoDetails()`.

## Google account, speed, subtitles

### Recommendations from your Google profile
- **Sign-in** (`LoginScreen`): Google's sign-in page in a WebView with a mobile Chrome
  User-Agent. Once signed in, the youtube.com cookies stay in the system `CookieManager`.
  If Google rejects the WebView ("browser not secure"), use **Settings → Import cookies**:
  paste the `Cookie` header copied from a signed-in desktop browser.
- **Home**: the account's recommendations (`browse` with `FEwhat_to_watch` on the website's
  InnerTube API, authenticated with `SAPISIDHASH`), with infinite scrolling.
- **Related videos** in the player: the account's personalised ones (`next` endpoint). When
  signed out, NewPipe's are used.
- **History**: after 10 s of watching, the video is marked as watched on the account, with the
  same `playbackTracking` pings as the website, so recommendations also learn from what you watch
  here. It can be turned off in Settings.
- `InnerTubeParser` does not depend on exact JSON paths: it looks for video renderers
  (`lockupViewModel`, `videoRenderer`, …). It skips whole subtrees of **in-feed ads**
  (`adSlotRenderer`, …) and **Shorts** (`reelShelfRenderer`, `shortsLockupViewModel`, …).

### Recommendations without an account (`LocalRecommender`)
Without signing in, the home page suggests videos computed **on the phone** from two sources
already stored in Room:
- the **related videos of watched videos**, saved every time you open a video with no extra
  requests. A video that appears among the related videos of several watched videos ranks higher;
- the **latest videos of the channels** you follow and of those you watch most, weighted by
  affinity (subscription, number of views) and freshness.

Already watched videos are excluded, there are at most 2 videos per channel in the top
positions, and the order varies slightly at every refresh. The computation uses no network, so
the home page appears immediately. The sources are refreshed in the background: channels older
than 3 h, missing related videos only on Wi-Fi.
With an account, the home page shows the account's recommended feed instead, and the
Subscriptions tab the account's subscription feed (a single request). If the account does not
respond, the home page falls back to local recommendations.

### Watch later and sharing
- **⋮** menu on every video and buttons in the player: *Watch later*, *Share* and
  *Share from current time* (`youtu.be/…?t=` link).
- The list is local (**Playlists** tab). With an account, changes are also sent to YouTube's
  "Watch later" playlist, and that playlist is imported.

### Quality and appearance
- **Minimum and maximum quality**: all resolutions within the range go into a single adaptive
  DASH manifest. ExoPlayer starts with what the network can handle right away and steps up.
  The quality can be fixed manually from the player's gear menu.
- **Theme** System / Light / Dark and a **System colours (Material You)** switch. When off,
  MauzTube's palette is used.

### Opening speed
Real extraction measurements (NewPipeExtractor v0.26.5):

| | extraction time |
|---|---|
| first video after start-up | ~5 s |
| following videos | ~2 s (about 8 sequential HTTP requests, `next` ≈ 1 s) |

What is done about it:
1. **Warm-up at start-up**: TLS connections opened and the WEB client version fetched right
   away. Removes most of the cost of the first video.
2. **Prefetch** (like the official app): the first 3 home recommendations and the first 2
   related videos (after 5 s of playback) are extracted in advance, with a 30 min cache and
   merging of in-flight requests. Opening one of these videos is almost instant. Prefetching
   only happens on unmetered networks (Wi-Fi). If you open a video whose prefetch is still
   queued behind others, it is extracted immediately instead of waiting for its turn.
3. **Early start** (`EarlyPlayerHub`): NewPipeExtractor makes about 7 sequential requests
   (visitor_id, Android player, visitor_id, visionOS player, visitor_id, web player, `next`), but
   the stream URLs it ends up using come from the visionOS response; the web player and `next`
   (the slowest) are only needed for metadata, related videos and previews. When you open a
   video, the downloader hands that response over as soon as it arrives and playback starts,
   with the same URLs NewPipe would build (direct URL + `cpn`), while the full extraction keeps
   going and later fills in description, related videos and previews without touching playback.
   If that response is not enough (unplayable, live, URLs needing deciphering) the app simply
   waits for the full extraction as before. Measured on 9 videos: streams ready in ~0.4 s instead
   of ~2.3 s, first frame in ~2.6 s instead of ~4.7 s (medians).
4. **Visitor ID reuse**: NewPipeExtractor asks for a fresh `visitor_id` for every client of every
   video (3 sequential requests per video); like a browser cookie, the one obtained in the last
   30 minutes for the same client is reused.
5. **Video server pre-connection**: as soon as the response with the streams arrives, two
   connections to the indicated `googlevideo.com` server are opened with `generate_204`, like
   YouTube's own player does, only for the video being opened (not for prefetches). Video data
   uses its own connection pool: connections idle for more than 30 s are dropped and 8 s without
   data mark a connection as dead, so ExoPlayer retries at once on a new one.
6. **Instant preview**: the tapped video's title and thumbnail appear in the player at once.
7. **Chunked DASH**: every stream is played through a DASH manifest generated from the itag
   metadata (`YoutubeProgressiveDashManifestCreator`, like NewPipe) instead of one open
   progressive request. ExoPlayer downloads range-limited chunks, so start-up and seeking are
   faster and there is no throttling.
8. **Start-up buffer** of 1.5 s (2.5 s after a stall) and a 30–50 s reserve buffer, so a
   momentary network drop does not stop the video. Automatic quality uses 65% of the estimated
   bandwidth, so it does not climb to a resolution the network cannot sustain.

### Subtitles that never delay the video
YouTube subtitle tracks (manual and auto-generated, TTML) are listed in the player's **CC** menu.
They used to be merged into ExoPlayer's source: the video could not start until *every* track
(up to 4) had been downloaded, and YouTube's subtitle server sometimes answers after ~10 s.
Measured: first frame after 13–14 s instead of 2–3 s on videos with subtitles.
Now subtitles are completely separate (`SubtitleLoader`): the video starts right away, only the
chosen track is downloaded, parsed once with Media3's parser and shown by a Compose overlay
(binary search on the cue list, 4 checks per second, recomposition only when the line changes).
They can be turned on automatically in the app language from Settings.

### Network resilience
Mobile networks drop connections, change between Wi-Fi and data, and sometimes have slow DNS.
Measured on a flaky network: requests stuck on a dead pooled connection for **30 s** (the old
timeout) and DNS lookups failing after 10–17 s.
- **Shorter timeouts with retry**: extractor API requests give up after 7 s without data and are
  retried once on a fresh connection; other requests after 15 s.
- **Network change**: when the network changes or is lost, pooled connections are dropped
  immediately (`NetworkWatcher`) instead of being reused and hanging.
- **DNS cache** (`CachingDns`): an address resolved recently is reused at once (and refreshed in
  the background after 5 min). If DNS does not answer, the last valid addresses are used.

### Sponsor skipping without stalls
With one open progressive request, a seek outside the buffer (like a sponsor skip) opened a new
connection to `googlevideo.com` with an unlimited `Range: bytes=X-`. That kind of request can be
rejected (403) or throttled; the 403 triggered automatic re-extraction, with the video stuck
loading for a few seconds. With chunked DASH the seek downloads only the needed chunk, and after
a stall 1.5 s of buffer is enough to restart. On errors, re-extraction now bypasses the cache,
because URLs may have expired.

### Player
- **One player for the whole app** (`PlaybackManager`), independent of screens:
  - **mini player** at the bottom right while browsing (its size adapts to the screen). Tap it
    to reopen the video; drag it down or right to close it;
  - **background and screen off**: `PlaybackService` (Media3 `MediaSessionService`) provides the
    notification with controls, lock screen and headphone buttons. Outside the app the video
    track is disabled (audio only: less data and battery) and re-enabled on return. Can be
    turned off.
- **Custom controls** in Compose, using the theme colours: seek bar with buffer and SponsorBlock
  segments, double tap ±10 s (further quick taps on the same side add 10 s each), **Quality**
  (automatic or fixed), **Speed** and **Subtitles** menus, **Audio only** button.
- At the top of the player: **Autoplay** (when the video ends, the first related video not yet
  watched starts; off by default, the choice is remembered) and **Loop** (off by default).
- **Gestures**: in portrait, dragging the video down turns it into the mini player; in
  full screen, dragging up shows the related videos.
- **Fallback decoder**: if the phone's hardware decoder fails, the video restarts from the same
  point with another decoder instead of stopping with an error.
- **Stutter protection**: if the phone keeps dropping frames (typical of 60 fps or 1080p on
  low-end phones), the player lowers the load by itself, first to 30 fps and then to a lower
  resolution, for the rest of the session. Short bursts right after seeks or quality changes are
  ignored; a quality chosen manually always wins.
- **Upload date** in European format (dd/mm/yyyy).

### Comments, searches, incognito mode
- **Comments** below the video (loaded only when you scroll there, so opening a video stays
  fast), with replies and progressive loading. With an account you can **post comments**.
  **History → Your comments** lists the comments posted from MauzTube with their videos, plus
  Google's activity page with all the account's comments.
- **Search history** (on by default, can be turned off and cleared in Settings): recent
  searches appear when you tap the search box and can be removed one by one.
- **Incognito mode** (icon at the top): while it is on, history, resume positions and searches
  are not saved and the account is not used. It lasts until the app is closed, or can be turned
  off from the banner at the top.
- **History**: with an account it is the Google account's history, with the local resume point
  overlaid; otherwise it is the local history.

Defaults: **dark** theme, system colours **off**, resume and automatic reopening **off**
(they can be enabled in Settings).

### Queue, playlists, first start
- **Queue** (`PlaybackManager.queue`): "Add to queue" from the ⋮ menu of every video and with the
  dedicated icon on every video in the player's "Up next". The queue bar appears across the app
  only when the queue has videos, and opens with a tap or by dragging it up. Tapping a queued
  video lets you play it now, move it (to the top, up, down) or remove it. The next video is
  extracted in advance, so the switch is instant. When the queue is empty the bar disappears.
  The queue lives in memory only: once the app is closed, it is gone.
- **Playlists**: "Watch later", local playlists (create, rename, delete, "Play all" and "Add all
  to queue") and, with an account, the Google account's playlists. "Save to playlist" is
  available from the ⋮ menu and from the player.
- **Subscriptions synced** with the account: subscribing and unsubscribing also applies on
  YouTube. On first sign-in, local subscriptions are added to the account; afterwards the account
  is the source of truth.
- **First start**: choose between "Sign in with Google" and "Continue without an account".

### Any screen size
Tested on a small phone (720×1280) with 130% font size, on a 1080×2400 phone in portrait and in
landscape.
- **First-start screen**: centred when it fits, scrollable when it does not, more compact on short
  screens. It used to cut off the buttons on small phones, making the app unusable.
- **Navigation**: bottom bar on phones (with a short label, e.g. "Subs", when a tab has little
  room); side rail on wide screens (landscape phones, foldables, tablets), compact and
  scrollable when the height is small.
- **Wide screens**: video cards become rows (thumbnail on the left, details on the right) and
  the player sits on the left with the details on the right, instead of a video taller than the
  screen. The player is composed in a single place, so switching layouts never recreates it.
- The real window size is measured (`ui/components/Adaptive.kt`), so the layout follows rotation,
  multi-window and foldables. Only the language is overridden in the Activity configuration;
  before, a full copy of the configuration made the UI believe it was still in portrait after a
  rotation.

### Smoothness and animations
- **Navigation transitions** (≤ 260 ms, Navigation's default is 700 ms): the next screen slides
  in slightly from the right, the player rises from the bottom like on YouTube and slides down
  when closed, tab changes use a fade with a slight zoom ("fade through").
- **Micro-animations** (`ui/components/Motion.kt`): video cards sink slightly when pressed, the
  play/pause button morphs, the ±10 s buttons spin when tapped, double tap shows a light arc
  with the skipped seconds, Subscribe ⇄ Subscribed changes colour and text, Like/Dislike bounce,
  rounded search box with animated background, recent searches and results fade in, the mini
  player rises in, subtitles move up when the controls appear.
- The press effect on cards is an `Indication` (`PressScaleIndication`): with
  `clickable(interactionSource = null, …)` Compose creates it only on the first touch, so cards
  scrolling by cost nothing extra. Thumbnails have no fade-in: while scrolling, every new
  thumbnail would be drawn twice for 150 ms.
- All of them change only opacity, scale and position inside `graphicsLayer`: the values are read
  at draw time, so an animation frame neither recomposes nor re-measures the screen. Measured on
  the emulator: the app's work per frame while scrolling stays below 1 ms, as without
  animations.
- Instant tap on the player: double tap ±10 s does not delay single taps.
- `profileinstaller`: Compose Baseline Profiles are compiled even for sideloaded APKs.
  **Install the release APK for a smooth app**: the debug build is "debuggable" and Compose is
  much slower in it. Without a personal keystore the release is signed with the debug key, both
  locally and in CI (artifact `MauzTube-release-debugkey-…`).
- List thumbnails at 480p instead of 720p, HTTP connections kept alive longer and reopened when
  returning to the app, prefetch of videos that stay on screen for 1.2 s (Wi-Fi only, at most 2).

### Stability and resource usage
- **Duplicate keys**: YouTube sometimes repeats a video in related videos, comments or
  playlists (a playlist may contain the same video twice). In a Compose lazy list a duplicate key
  closes the app: every list is now de-duplicated or uses position-based keys.
- **Background errors never close the app**: every background operation (network, database)
  runs with a safety handler (`SafeHandler`) that records the error instead of crashing.
- **Error report**: crashes and handled errors are written to a small local file. After a crash
  the app says so on the next start, and **Settings → Error report** lets you share or delete
  it. Nothing is ever sent automatically.
- **Low-memory phones** (`DeviceProfile`): smaller video buffer (15–30 s, at most 24 MB instead of
  up to 130 MB) and a smaller image cache.
- **No disk or network work on the UI thread**: checked with StrictMode (enabled only in debug
  builds); the only remaining read is the app language, once per process.
- Stress-tested with 22,000 random monkey events (taps, swipes, rotations, app switches): no
  crashes or ANRs.

### Language, pop-up, ratings
- **Language**: English by default, or Italian. Chosen at first start and changed from Settings;
  the change is immediate. YouTube dates and counters follow it too (`util/I18n.kt`, function
  `tr(en, it)`).
- **Pop-up (Picture-in-Picture)**: button in the player and automatic activation when leaving
  the app with a video playing (can be turned off). Closing the pop-up stops the video.
- **Like / Dislike**: saved locally ("Liked videos" list among the playlists) and, with an
  account, synced with YouTube. The account's rating wins. Dislike counts come from Return
  YouTube Dislike (can be turned off).
- **Seek-bar preview**: dragging the bar shows the frame at the chosen point (YouTube storyboard,
  sprites of about 25 KB loaded only while dragging).
- **Full screen and camera cutout**: the video uses the whole display, including the
  cutout/notch area, while the controls stay outside it, like the official client.

### Downloads
- **MP4 video**: all H.264 qualities up to 1080p (plus the single 360p file, faster).
  Video and audio are downloaded separately and merged **without re-encoding** with Android's
  `MediaMuxer`: fast and almost CPU-free.
- **MP3 audio** at 320/192/128 kbps: the original audio (AAC) is decoded with the system decoder
  and encoded to MP3 with LAME (jump3r, pure Java, no native library), with title and artist ID3
  tags. There is also the **original M4A**, without re-encoding.
- Downloading in 8 MB chunks with `Range`, so YouTube does not throttle. Automatic retry with
  fresh links if the old ones have expired. Foreground service with a progress notification.
  Files go to **Download/MauzTube**. The "Downloads" page (from the Playlists tab) lets you open,
  share, retry and delete.
- jump3r uses `javax.sound.sampled.AudioFormat`, which does not exist on Android: the app ships a
  minimal version of it (`app/src/main/java/javax/sound/sampled/`), a plain data holder.

### Measured smoothness
Measurements on an Android 17 emulator, before and after, with the scripts in
`tools/baseline-profile/`:
- **Baseline Profile** (`app/src/main/baseline-prof.txt`), recorded from a real usage path.
  Android compiles the app's code ahead of time (start-up, tabs, search, scrolling, player,
  comments) instead of interpreting it. While scrolling, Compose's work drops from 0.4 to 0.1 ms
  per frame.
- **Settings** as a lazy list, and the link status read off the UI thread: the first frame goes
  from 57–73 ms to 20–22 ms.
- **ExoPlayer created with the first video** instead of at app start-up.
- **Thumbnails decoded at 640×360** (the source is ≤ 480 px): about 2.7× less memory per image
  and less rendering work while scrolling.
- **Hardware bitmaps** (Coil's default): images are uploaded to GPU memory while being decoded,
  in the background. With RGB_565 (used before) every new thumbnail was uploaded to the GPU while
  drawing the frame, right during scrolling, which costs dropped frames at 90–120 Hz. Hardware
  bitmaps also do not use the app's Java heap.
- Compose work per frame while scrolling search results: 0.5–0.6 ms → 0.3 ms (median).

Note: after each APK installation Android applies the profile at the first background
optimisation, usually when the phone is idle and charging. To do it right away:
`adb shell cmd package compile -f -m speed-profile com.mauztube`.

### Logo
Original logo (`docs/logo.svg`, `app/src/main/res/drawable/ic_logo.xml`): a crimson circle with
an open progress ring and a "play" symbol in the middle. No rounded rectangle and no shapes or
colours of the YouTube brand, so it can be freely distributed with the project. Variants:
adaptive icon (Android 8+), monochrome for themed icons (Android 13+), square for Android 7,
small notification icon.

## Building

### Without building locally (GitHub Actions)
1. Fork the repository (or push the project to your own GitHub repository).
2. **Actions → Build APK →** the latest run **→ Artifacts → `MauzTube-release-debugkey-…`**
   (or `MauzTube-debug-…`).
3. Unzip it and install the APK (allow "Install unknown apps").

### Locally
Requirements: JDK 17+ (tested with JDK 25; CI uses 21) and the Android SDK with the
**android-37** platform (Android Studio installs it by itself; from the command line:
`sdkmanager "platforms;android-37.0"`).

```bash
git clone https://github.com/<user>/MauzTube.git
cd MauzTube
export ANDROID_HOME=$HOME/Android/Sdk      # or create local.properties with sdk.dir=...
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

The debug build (`./gradlew assembleDebug`) has applicationId `com.mauztube.debug` and can be
installed next to the release.

### Signed release
1. Create a keystore, once, and **keep it safe**: without the same keystore you will not be able
   to update the installed app.
   ```bash
   keytool -genkeypair -v -keystore mauztube-release.jks -alias mauztube \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. **Locally:** `cp keystore.properties.example keystore.properties`, fill in the values, then
   `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
3. **CI:** add `KEYSTORE_BASE64` (`base64 -w0 mauztube-release.jks`), `KEYSTORE_PASSWORD`,
   `KEY_ALIAS` and `KEY_PASSWORD` to the repository Secrets. The workflow will also produce the
   `MauzTube-release-…` artifact.

Alternatively you can sign the unsigned APK by hand:
```bash
zipalign -p 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks mauztube-release.jks --out MauzTube.apk aligned.apk
```

## Known limitations

**Legal**
- Using an unofficial client that skips ads violates
  [YouTube's Terms of Service](https://www.youtube.com/t/terms) (access by automated means,
  circumventing advertising). The practical risk for an individual user is low, but in theory
  YouTube can block IPs or accounts, including the Google account used to sign in.
- NewPipeExtractor is **GPL-3.0**: if you distribute the APK, MauzTube must also be distributed
  under GPL-3.0 together with its source code.
- No Play Store: Google removes alternative YouTube clients. Distribute the APK directly (or
  through F-Droid / a personal repository).

**Maintenance**
- YouTube often changes its anti-scraping mechanisms (signature cipher, `n` parameter,
  PoToken/BotGuard, InnerTube clients). When playback stops working, **updating
  `newpipeExtractor` in `gradle/libs.versions.toml`** to the latest release of
  [TeamNewPipe/NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor/releases) and
  rebuilding is almost always enough. Following their releases is recommended.
- **PoToken** (BotGuard attestation) is not implemented. The extractor uses clients that do not
  require it (Android/iOS/visionOS). If YouTube made it mandatory everywhere, a WebView-based
  `PoTokenProvider` would be needed, as the NewPipe app does
  (`YoutubeStreamExtractor.setPoTokenProvider`).
- DASH "OTF" streams (some freshly uploaded or former live videos) are discarded. In that case
  muxed or HLS streams are used instead.
- **Google sign-in**: Google may block sign-in from the WebView (use "Import cookies" then).
  Using account cookies with an unofficial client is against the ToS and in theory exposes the
  account to restrictions: consider a secondary account. If the session expires, sign in again.
- **Speed**: thanks to prefetching, "predicted" videos open instantly. Others still need ~2 s of
  extraction: that is the cost of NewPipeExtractor's ~8 requests and cannot be removed without
  rewriting the extractor.
- **Automatic subtitle translation** (`tlang`): YouTube blocks it with an anti-bot 429 without a
  token, so it is not included.
- **18+ videos**: with an account, MauzTube asks YouTube for the streams as an authenticated TV
  client (`AuthenticatedStreamFetcher`), like yt-dlp does with cookies. Those formats, however,
  require deciphering YouTube's JS player (signature and `n` parameter), which is broken for the
  current player in NewPipeExtractor v0.26.5. Verified: the signature function is not found and
  `n` returns `[object Object]`; the fix is in upstream PR #1545. The app recognises the problem
  and shows a clear message. Once a release with the fix is out, updating `newpipeExtractor` will
  be enough. This path could not be tested with a real adult account.
- Without an account, **subscriptions** stay on the phone only; with an account they are synced
  with YouTube. Reading the account's subscriptions and playlists, and posting comments, could
  not be tested with a real account during development.

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
Signing in is optional. Comments are posted only when you explicitly send them. Account
cookies are stored only on your device and are sent only to YouTube/Google to load your
recommendations, subscriptions, history and playlists. Using an account with an unofficial
client may lead to restrictions imposed by Google; sign in at your own risk.

### Privacy
MauzTube contains **no analytics, no tracking and no ads**, and the authors receive no data
from it. Settings, history, playlists, downloads and the error report are stored locally on
your device; the error report leaves the phone only if you choose to share it.
The app connects only to:
- **YouTube / Google** (`youtube.com`, `googlevideo.com`, `ytimg.com`): video data, thumbnails,
  subtitles and, if signed in, account features;
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
