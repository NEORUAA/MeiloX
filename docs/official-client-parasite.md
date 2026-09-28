# Official Client Parasite

## Delivery Contract

- Work only on `official_client_parasite`; commit verified increments locally without pushing.
- Keep the complete MeiloX UI, playback engine, AutoMix, effects, visualization, and feature set.
- Run the UI inside the selected official host process through modern libxposed API 102.
- Use `com.neoruaa.meilox.parasite` for the module APK; preserve the standalone installation.
- Let the host own authentication, credentials, signing, and NetEase business transport.
- Render login in MeiloX while delegating the authentication state machine to the host.
- Preserve third-party lyrics and direct image/media loading. Retain business parameter and response adapters.
- Use separate host-private storage for module data. Do not import the standalone app's data or cookies.
- Do not redistribute the host APK, decompiled sources, credentials, screenshots, or device logs in Git.
- Pause for user login and account cooperation. A blocked capability is not permission to remove a feature.

## Candidate Baseline

Source baseline: `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`.
Inspection date: 2026-09-29.

| Candidate | Package / version | Assessment |
| --- | --- | --- |
| TV | `com.netease.cloudmusic.tv` / `1.1.80` (`1001080`) | First candidate; arm64, traceable login and general request builder; runtime qualification pending |
| Watch | `com.netease.cloudmusic.watch` / `2.9.46` (`29046`) | Supplied APK has only `armeabi`; incompatible with the current arm64-only AVD |
| Car | `com.netease.cloudmusic.iot` / `6.2.81` (`6002081`) | arm64, but also has a separate OAuth request/session path; fallback candidate |
| Phone | `com.netease.cloudmusic` / `9.6.05` (`9006005`) | Existing extracted phone sources are `9.2.10`; require matching APK analysis before adaptation |

The file labelled as a modified Honor release is not the selected official host.
Matching version metadata alone does not establish that every extracted source matches an APK.
Use the supplied APK's DEX for runtime names and signatures.

### TV Artifact Identity

- File: `ncm_workspace/NeteaseCloudMusic_MusicTV_official_1.1.80.260122145233.apk_official_1.1.80.260122145233_3264.apk`
- APK SHA-256: `b0bba5915590d7ff397c564549718c0ec82711bb48eafec2b32f2de8dcd0c87b`
- Signer SHA-256: `54254d2be09daef48dedc2b4a4f497d153e14ed9d70814fc9c360ee9240827f7`
- V2 signature verification passed. This records the supplied artifact identity, not independent proof of its distribution source.
- Minimum SDK: 17; target SDK: 29; native ABIs: `arm64-v8a`, `armeabi-v7a`.
- 53 arm64 libraries; 37 contain load segments aligned below 16 KB.

### Device Evidence

- Device: `emulator-5554`, `sdk_gphone16k_arm64`, Android API 37.
- Supported ABI: `arm64-v8a`; page size: 16384 bytes.
- Installed framework metadata: LSPosed `v2.2.0 (7854)`.
- Actual libxposed runtime API detection remains part of the module load probe.
- Official TV APK installation succeeded without replacing any existing package.
- Cold launch reached `com.netease.cloudmusic.app.LoadingActivity` and then the official main screen.
- Android displayed its page-size compatibility warning; the host continued in compatibility mode.
- The original player loaded artwork and lyrics. MediaSession reported `PLAYING`, then `PAUSED` after the test pause command.
- This is playback-state evidence, not a full-track, sound-quality, or premium-entitlement acceptance result.
- The official login screen generated a QR code. User authorization, session restoration, and authenticated capability checks are pending.
- The crash buffer was empty at the login checkpoint; this is not long-running stability proof.

Screenshots and the temporary accessibility dump stay outside Git. Do not preserve an active login QR in this document.

## Verified DEX Entry Points

These names and method signatures were inspected using Android SDK `apkanalyzer dex code` on the exact TV APK above.
They are static evidence until exercised through the host classloader.

| Responsibility | Runtime class / method | Adaptation requirement |
| --- | --- | --- |
| Business request factory | `com.netease.cloudmusic.network.f.b(String)` | Returns the host's `network.v.e.a`; use the host classloader |
| String parameters | `com.netease.cloudmusic.network.f.c(String, Map)` | Uses the same official request implementation |
| Structured parameters | `com.netease.cloudmusic.network.v.e.f.h0(Object...)` | Check actual parameter encoding before replacing typed request bodies |
| Request execution | `com.netease.cloudmusic.network.v.e.a.k()` | Returns platform `org.json.JSONObject`; execute off the main thread |
| Login controller | `com.netease.cloudmusic.audio.c.b` | Public no-argument constructor; create and operate on the main thread |
| Login start / refresh | `audio.c.b.k0(boolean, long, boolean)` | Delegate to the original controller; do not reproduce authentication in module code |
| Login polling cleanup | `audio.c.b.o0()` | Removes pending handler callbacks; coroutine and observer teardown also needs verification |
| QR bitmap | `audio.c.b.x0()` | Host `MutableLiveData<Bitmap>`; bridge through the host Observer interface |
| QR expiration | `audio.c.b.r0()` | Host `MutableLiveData<Boolean>` |
| Account completion | `audio.c.b.w0()` | Host `MutableLiveData<Pair<Integer, Object>>`; convert the host model before entering module UI |
| Loading state | `audio.c.b.s0()` | Host `LiveData<Boolean>`; preserve lifecycle and cancellation behavior |
| Session singleton | `com.netease.cloudmusic.r0.a.c()` | Keep credentials and account persistence inside the host |
| Account / profile | `r0.a.a()` / `r0.a.d()` | Read public state through an adapter; do not cast to module copies of host classes |
| User ID | `r0.a.e()` | Returns `long`; do not infer authenticated state solely from a nonzero anonymous user ID |

For example, JADX's `C4399f.m13980b` is actually `network.f.b` in DEX.
Do not install hooks against the generated `C...`, `m...`, or `p393tv` aliases.

The extracted login implementation calls `login/anon/device`, `login/qrcode/unikey`,
`login/qrcode/client/login`, and `nuser/account/get`. On successful authorization it also
updates official account/profile state, expires the anonymous cookie, and notifies other
host components. Request success alone is not a replacement for those transitions.

## Feature and Request Migration Matrix

Every row is required. At this checkpoint, no row has been migrated to a module.
Paths identify the current business operations, not a claim that the TV session accepts them.
Existing request bodies, pagination, response models, and error behavior remain the baseline.

| Feature | Current request family / local implementation | Destination and acceptance |
| --- | --- | --- |
| Account and login | `NeteaseLoginScreen`, `nuser/account/get`, `w/nuser/account/get`, user detail, `subcount` | Official login state and account requests; remove WebView cookie detection and manual cookie entry |
| Home and discovery | `link/page/rcmd/resource/show`, `personalized/playlist`, `playlist/random/list/get`, `playlist/highquality/list` | Host requests; retain configured pages and navigation |
| Search | `search/get`, `search/suggest/web`, `search/pc/complex/page/v3` | Host requests; retain discovery, results, suggestions, pagination, and destinations |
| Playlists and daily songs | `v6/playlist/detail`, `user/playlist`, `v3/discovery/recommend/songs` | Host requests; preserve complete track expansion and ordering |
| Playlist mutations | `playlist/create`, `playlist/manipulate/tracks`, `playlist/subscribe`, `playlist/unsubscribe`, `playlist/remove` | Host requests; verify writes using disposable test resources only |
| Albums | `v1/album/{id}`, `album/sublist`, `album/sub`, `album/unsub`, `user/photo/album/get` | Host requests; retain detail, collection, and artwork behavior |
| Artists | `artist/head/info/get`, `artist/albums/{id}`, `v1/artist/songs`, `v1/artist/{id}`, `artist/sub`, `artist/unsub` | Host requests; preserve paging and followed state |
| Song detail and playback URLs | `v3/song/detail`, `song/enhance/player/url`, `song/enhance/player/url/v1` | Host authentication and rights; MeiloX playback engine; verify full duration, actual quality, trial restrictions, and cache keys |
| Favorites and radio | `song/like/check`, `radio/like`, `v1/radio/get`, `playmode/intelligence/list` | Host requests; retain state, FM, and intelligent queue behavior |
| Lyrics | `song/lyric`, `song/lyric/v1`, QQ lyrics/search, AMLL TTML | NetEase through host; preserve third-party transport, parsing, translation, timing, and lyric settings |
| Comments | `v2/resource/comments`, `resource/comment/floor/get` | Host requests; retain sorting, pagination, replies view, and layout |
| Podcasts | `djradio/category/get`, `djradio/recommend/v1`, `djradio/personalize/rcmd`, `djradio/hot`, `djradio/v2/get`, `dj/program/byradio`, `djradio/get/subed`, `djradio/sub`, `djradio/unsub` | Host requests; retain categories, episodes, subscriptions, and playback |
| Cloud library | `v1/cloud/get`, `cloud/del` | Host requests; preserve paging and cloud-song playback |
| Cloud upload | `cloud/upload/check`, `nos/token/alloc`, `upload/cloud/info/v2`, `cloud/pub/v2`, NOS binary transfer | Host-owned authorization and upload setup; preserve metadata, progress, cancellation, and publishing |
| Private messages and sharing | `msg/private/users`, `msg/private/history`, `msg/private/send`, `share/friends/resource`, `user/getfollows/{id}` | Host requests; use controlled substitutes for writes to real recipients |
| Song information | `song/play/about/block/page` | Host request; retain all supported information sections |
| Listen together | `listen/together/status/get`, room create/check, invitation accept, play command, playlist sync, heartbeat, end | Host requests; retain room lifecycle and real playback synchronization; requires a cooperating account for full acceptance |
| Song recognition | `music/audio/match`, `SongRecognitionEngine`, fingerprint assets | Host match request; preserve recording, fingerprint generation, cancellation, and result UI |
| Listening records | `v1/play/record`, `play-record/song/list` | Host requests; preserve account history and listening-rank views |
| Listening reports | `feedback/weblog`, NCBL, `PlaybackHistorySession`, `PlaybackHistoryReporter` | Preserve actual active-time accounting; integrate official request/reporting support without duplicating the host player reports |
| Local music and library | Room, MediaStore, local playlists, local history | Keep functionality in isolated module storage; no automatic standalone data import |
| Downloads and cache | `DownloadWorker`, `CacheManager`, `AutomaticCacheController` | Keep queue, progress, retry, cancellation, quality-specific cache, offline playback, and storage controls |
| Audio processing | `AudioPlayer`, `StableDeckPlayer`, `AutoMixController`, `BeatNetAutoMixAnalyzer`, `TenBandEqualizer` | Keep current implementation and user controls; validate module native library loading and audio focus |
| Player surfaces | Mini/full player, lyric rendering, glass, background and beat visualization | Preserve visual and interaction behavior; verify consecutive-frame motion in the host |
| Background and system integration | `MusicService`, media notification/session, sleep timer, system lyrics, ColorOS lyrics | Host component adapters; verify background playback, notification intents, timer completion, and integrations separately |
| Settings and utilities | Appearance, content placement, general/playback/lyrics/download settings, about, logs, file sharing | Preserve available routes and controls; replace application/context assumptions and redact sensitive logs |

Primary inventory sources are `ui/screen/Screen.kt`, `ui/navigation/MeloXNavigation.kt`,
`data/network/api`, `data/repository`, `di/repository`, and `playback` under
`app/src/main/java/com/ljyh/mei`.

## Runtime Boundaries for the Next Stage

- Pin package, version, and signing identity before installing host-specific hooks.
- Use `compileOnly` for the API 102 library; register only modern module entry points.
- Verify `getApiVersion()` in the actual injected process; metadata is insufficient.
- Preserve host Application initialization and network/session services.
- Isolate module Compose/Kotlin/AndroidX resources and types from host versions.
- Replace application-dependent Hilt entry points with a module-owned dependency graph.
- Prove Activity, media service, notification, resource, font, native library, and lifecycle handling before migrating all screens.
- Do not assume a service listed only in the module manifest is available under the host UID.
- Translate request parameters and host objects at a single boundary; retain coroutine cancellation and session-generation checks.
- Keep login observers and polling bounded by the login screen lifecycle.
- Separate local playback state from official account state; never accept anonymous state as user login.
- Disable host playback/reporting paths only after the replacement's required initialization is understood.
- Keep hot reload off until all hooks, views, observers, workers, and native resources can be released correctly.

## Stage Status

| Stage | Status | Exit condition |
| --- | --- | --- |
| 1. Host and feature baseline | In progress: static inventory and original-app startup/QR checks passed | User login, session restoration, and authenticated capability checks pass |
| 2. API 102 runtime | Not started | Injected UI and playback service operate inside the qualified host |
| 3. Official-session login UI | Not started | Refresh/cancel/login/logout/restart behavior passes without module-owned credentials |
| 4. Core business migration | Not started | All core screens use host business transport |
| 5. Playback migration | Not started | Existing audio, download, timer, notification, and reporting behavior passes |
| 6. Remaining features | Not started | Every feature row above has implementation and appropriate verification evidence |
| 7. Cleanup and regression | Not started | Old NetEase transport removed; release build and full regression pass |

A baseline documentation commit is a verified substep, not completion of stage 1.
Do not advance to the broad frontend migration while host qualification is pending.

## Reproduction Commands

Use Android SDK `aapt2 dump badging`, `apksigner verify --print-certs`, and
`apkanalyzer dex code --class <runtime-class> <apk>` for artifact checks.

```sh
adb -s emulator-5554 shell getprop ro.product.cpu.abilist
adb -s emulator-5554 shell getconf PAGE_SIZE
adb -s emulator-5554 shell dumpsys package com.netease.cloudmusic.tv
adb -s emulator-5554 shell am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.app.LoadingActivity
adb -s emulator-5554 shell su -c 'am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.tv.activity.TvLoginActivity'
adb -s emulator-5554 shell dumpsys media_session
adb -s emulator-5554 logcat -d -b crash
```

Package/media dumps can include user state. Inspect locally and report only the minimum result.
Never copy complete request headers, session cookies, QR payloads, or account responses into Git.
