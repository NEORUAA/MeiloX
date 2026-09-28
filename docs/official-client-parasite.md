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
| TV | `com.netease.cloudmusic.tv` / `1.1.80` (`1001080`) | Selected for runtime prototyping; original login, session restoration, API 102 injection, and read-only business probes passed |
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
- Injected module reported runtime API `102`, framework `LSPosed`, version `2.2.0`.
- Official TV APK installation succeeded without replacing any existing package.
- Cold launch reached `com.netease.cloudmusic.app.LoadingActivity` and then the official main screen.
- Android displayed its page-size compatibility warning; the host continued in compatibility mode.
- The original player loaded artwork and lyrics. MediaSession reported `PLAYING`, then `PAUSED` after the test pause command.
- This is playback-state evidence, not a full-track, sound-quality, or premium-entitlement acceptance result.
- The user completed official QR login. Force-stop/cold-start retained the authenticated session.
- The official authentication predicates reported a user session, not an anonymous session; `nuser/account/get` matched the official session's user ID. No credentials were copied.
- The first probe exposed a Tinker classloader mismatch and crashed in an uninitialized copy of the Session class. Using the resumed host Activity's classloader fixed the issue. The corrected probe also contains linkage/static-initializer failures so they do not escape its worker thread.
- The corrected probe completed with the session unchanged. This is not long-running stability proof.

### Authenticated Read-Only Probe

The debug APK enables this probe only with `-PparasiteHostProbe=true`. Normal debug and
release builds do not run it. It runs once after the official main Activity resumes;
all business calls use the host's `network.f.c(...).k()` pipeline. Only named check
results, business codes, and presence booleans are logged through the framework.

| Operation | Result |
| --- | --- |
| `nuser/account/get` | Code 200; account matches the live session |
| `user/playlist` | Code 200; playlist data present |
| `v6/playlist/detail` | Code 200 |
| `v1/cloud/get` | Code 200 |
| `listen/together/status/get` | Code 200; no room created or invitation sent |
| `djradio/category/get` | Code 200 |
| `search/get` | Code 200 |
| `v3/song/detail` | Code 200; song data present |
| `song/lyric/v1` | Code 200; lyrics present |
| `song/enhance/player/url/v1` | Code 200; per-song code 200, URL present, no trial info for the sampled song |

This qualifies the host for the next runtime prototype. It does not establish every
feature's parameter/permission coverage, premium quality access, uploads, full-track
playback, or final listening-statistics acceptance. Those remain later-stage gates.

The standalone and module packages coexist. The module has no launcher entry; the
official APK has not been repackaged. Module scope contains only the TV package.

Screenshots and the temporary accessibility dump stay outside Git. Do not preserve an active login QR in this document.

## Verified DEX Entry Points

These names and method signatures were inspected using Android SDK `apkanalyzer dex code` on the exact TV APK above.
Login-controller entries remain static evidence; the session and request entries were
exercised by the authenticated probe.

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

**Classloader requirement:** `onPackageReady().classLoader` is not the final TV business
loader. Tinker replaces it during Application attachment. Resolve business classes
through the live Activity's defining classloader after initialization. The initial
loader contains duplicate, uninitialized Application/Session classes. Do not cache
business class handles before this transition. Module and host Kotlin classes were
confirmed distinct at runtime.

The host's `core.b.d()` and `core.b.c()` expose authenticated/anonymous booleans
without exporting credentials. The request base `network.v.e.f` provides `c()`
for cancellation, `d(int)` for connect timeout, and `i0(int)` for read timeout;
cancellation semantics still require dedicated integration tests.

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

### Activity and Resource Prototype

The opt-in `-PparasiteRuntimeProbe=true` build substitutes only the registered
`com.netease.cloudmusic.tv.test.TextMainActivity` through `Instrumentation.newActivity`.
It does not replace the official launcher, main screen, or login screen yet.

- Module Compose/AndroidX renders inside the TV process with the original Application retained.
- A module-resource Context wrapper loads the existing logo, SF Pro font, SF Symbols, theme,
  and glass controls. Screenshot and accessibility checks confirmed their rendering and interaction.
- TV's AutoSize library initially shrank the UI. Its existing external-adaptation API now
  excludes only the module Activity, restoring device density `3.0` without disabling host adaptation.
- Portrait transition triggered Activity recreation successfully.
- Background process termination after `STOPPED` exposed a saved-state classloader failure.
  Assigning the module loader before `onCreate`/`onRestoreInstanceState` fixed Compose parcel restoration.
  The interaction counter remained `1` after process recreation; no new crash appeared in the corrected run.
- LSPosed searches native libraries inside the APK, so legacy compressed JNI packaging failed.
  Uncompressed JNI packaging passes `zipalign -c -P 16 -v 4`; the existing BeatNet JNI loads in the host
  and rejects an invalid input shape as expected. This checks loading/binding, not inference quality.
- Full storage APIs, dependency injection, and the production player remain unverified.
  The Context wrapper is a prototype, not the final app container.

The probe is disabled in normal debug/release builds. All screenshots, crash evidence,
and accessibility dumps stay outside Git. Earlier failures remain documented; a successful
retry does not erase their existence or establish broader device compatibility.

### Background Audio Prototype

The same opt-in build reserves the host's registered main-process
`com.netease.cloudmusic.service.LocalMusicMatchService` for a module-owned diagnostic service.
The platform `AppComponentFactory.instantiateService(ClassLoader, String, Intent)` receives
a null Intent during creation; command validation belongs in `onStartCommand`, not the
instantiation hook. An earlier Intent filter missed substitution and caused a foreground-service
timeout. The corrected run instantiated the module service and entered foreground state.

- A legitimate, non-trial URL from the authenticated host probe is kept only in memory.
- Module Media3 runs under the host PID/UID, with its own platform MediaSession and notification.
- The official player is paused for this test. The diagnostic service does not send listening reports.
- In-app play/pause/stop, notification pause, notification return to the registered Activity,
  background playback, and screen-off playback were exercised. Notification permission was
  granted to the host during testing.
- A Media3 `TeeAudioProcessor` passes audio through unchanged and measures PCM16 aggregates.
  No PCM audio is recorded. The diagnostic sink explicitly uses PCM16; this is not a change
  to the production player's quality, effects, or AutoMix pipeline.
- Unit tests cover signed sample extrema, silence, accumulation, partial-sample rejection,
  and preservation of the input buffer's position/limit.
- An emulator media-play key also woke the official player and caused a focus interruption.
  Module controls resumed correctly, but system media-key ownership remains an explicit
  production takeover gate. Keeping both players available is not the final architecture.
- The user cannot hear the AVD's external audio, including before these changes. Acceptance
  uses decoded PCM, AudioTrack activity, progression, and end-of-stream evidence instead.
  Actual external audibility and sound quality remain unverified; emulator/system audio settings
  were not changed.

This service proves component and audio hosting only. It does not replace `MusicService`,
validate the production effects/visualizer, or establish listening-history settlement.

### Remaining Gates

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
| 1. Host and feature baseline | Passed for runtime prototyping: login/session/request gates above | Complete baseline; later feature-specific acceptance remains mandatory |
| 2. API 102 runtime | In progress: loading, identity, Compose/resources, Activity recreation, JNI, and background-service prototype passed | Complete container checks and production component routing remain |
| 3. Official-session login UI | Not started | Refresh/cancel/login/logout/restart behavior passes without module-owned credentials |
| 4. Core business migration | Not started | All core screens use host business transport |
| 5. Playback migration | Not started | Existing audio, download, timer, notification, and reporting behavior passes |
| 6. Remaining features | Not started | Every feature row above has implementation and appropriate verification evidence |
| 7. Cleanup and regression | Not started | Old NetEase transport removed; release build and full regression pass |

Do not advance to the broad frontend migration until the Activity/resource/service
prototype passes. Module loading alone does not complete stage 2.

## Reproduction Commands

Use Android SDK `aapt2 dump badging`, `apksigner verify --print-certs`, and
`apkanalyzer dex code --class <runtime-class> <apk>` for artifact checks.

```sh
./gradlew :app:testDebugUnitTest --tests 'com.ljyh.mei.parasite.*' :app:assembleDebug -PparasiteHostProbe=true
./gradlew :app:assembleDebug -PparasiteHostProbe=true -PparasiteRuntimeProbe=true
adb -s emulator-5554 shell getprop ro.product.cpu.abilist
adb -s emulator-5554 shell getconf PAGE_SIZE
adb -s emulator-5554 shell dumpsys package com.netease.cloudmusic.tv
adb -s emulator-5554 shell am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.app.LoadingActivity
adb -s emulator-5554 shell su -c 'am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.tv.activity.TvLoginActivity'
adb -s emulator-5554 shell su -c 'am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.tv.test.TextMainActivity'
adb -s emulator-5554 shell dumpsys media_session
adb -s emulator-5554 logcat -d -b crash
```

Package/media dumps can include user state. Inspect locally and report only the minimum result.
Never copy complete request headers, session cookies, QR payloads, or account responses into Git.
