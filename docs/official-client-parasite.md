# Official Client Parasite

## Delivery Contract

- Work only on `official_client_parasite`; commit verified increments locally without pushing.
- Keep the complete MeiloX UI, playback engine, AutoMix, effects, visualization, and feature set.
- Preserve the original page architecture, navigation, layout, controls, and interactions;
  adapt host dependencies underneath them. Debug page carriers are not a replacement UI.
- Run the UI inside the selected official host process through modern libxposed API 102.
- Use `com.neoruaa.meilox.parasite` for the module APK; preserve the standalone installation.
- Let the host own authentication, credentials, signing, and NetEase business transport.
- Render login in MeiloX while delegating the authentication state machine to the host.
- Preserve third-party lyrics and direct image/media loading. Retain business parameter and response adapters.
- Use separate host-private storage for module data. Do not import the standalone app's data or cookies.
- Do not redistribute the host APK, decompiled sources, credentials, screenshots, or device logs in Git.
- Pause for user login and account cooperation. A blocked capability is not permission to remove a feature.
- Record interface/response/session differences and their evidence in
  [Official Client API Differences](official-client-api-differences.md).

## Candidate Baseline

Source baseline: `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`.
Inspection date: 2026-09-29.

| Candidate | Package / version | Assessment |
| --- | --- | --- |
| TV | `com.netease.cloudmusic.tv` / `1.1.80` (`1001080`) | Runtime prototype passed; full-feature selection reopened because recording permission and PiP carriers are absent |
| Watch | `com.netease.cloudmusic.watch` / `2.9.46` (`29046`) | Supplied APK has only `armeabi`; incompatible with the current arm64-only AVD |
| Car | `com.netease.cloudmusic.iot` / `6.2.81` (`6002081`) | arm64, but also has a separate OAuth request/session path; fallback candidate |
| Phone | `com.netease.cloudmusic` / `9.6.05` (`9006005`) | Existing extracted phone sources are `9.2.10`; require matching APK analysis before adaptation |

The file labelled as a modified Honor release is not the selected official host.
Matching version metadata alone does not establish that every extracted source matches an APK.
Use the supplied APK's DEX for runtime names and signatures.

### Full-Feature Manifest Gate

The production component audit on 2026-09-29 found a capability gap that the
login/request/player prototype did not exercise. The following results come from
`apkanalyzer manifest print` on the supplied APKs, parsed as XML rather than inferred
from decompiled source names:

| Candidate | Target SDK | Requests `RECORD_AUDIO` | Requests `SYSTEM_ALERT_WINDOW` | Activities declaring PiP support |
| --- | --- | --- | --- | --- |
| TV 1.1.80 | 29 | No | No | 0 |
| Watch 2.9.46 | 26 | Yes | Yes | 0 |
| Car 6.2.81 | 33 | No | Yes | 0 |
| Phone 9.6.05 | 33 | Yes | Yes | 0 |

Additional inspected APK SHA-256 identities:

- Phone: `ac67e9684fdbf6f95184c919d4a73771b5e12ec49479adafb32a8a75a6fae737`.
- Watch: `521da6eab57d92768b5c7d038bb3e05c8ce10baa5cb11093464d14cf80e5c6e3`.
- Car: `c8c533e19f4e3f0481bb19feaf1ce71f2f86a08ce715a49213955d3d9f394121`.

The installed TV package's requested permissions were also checked with
`adb -s emulator-5554 shell dumpsys package com.netease.cloudmusic.tv`; neither
recording nor overlay permission is present. This was a read-only inspection:
no permission changes, microphone capture, or new PiP runtime test were performed.

Existing feature dependencies:

- `SongRecognitionScreen` requests microphone permission; `SongRecognitionRecorder`
  checks that permission and creates `AudioRecord` for microphone samples.
  Android requires [`RECORD_AUDIO`](https://developer.android.com/reference/android/media/AudioRecord)
  for this API. TV and car cannot use this unchanged under their installed identities.
- `FloatingLyricsPip.kt` calls `Activity.enterPictureInPictureMode` and exposes the
  existing playback controls inside PiP. Android requires the carrier Activity's
  [`supportsPictureInPicture` declaration](https://developer.android.com/develop/ui/compose/system/pip-setup).
  None of the inspected official APKs declares a suitable Activity. Overlay permission
  is a different capability and does not satisfy the existing PiP implementation.
- Substituting an Activity or Service inside the host process does not change the
  host's installed manifest. Permissions and PiP declarations in the module APK
  do not transfer to the host UID/component.

The TV prototype remains useful evidence, but is not full-feature host acceptance.
No current candidate resolves both requirements unchanged; the watch ABI restriction
and phone source/version mismatch also remain. A decision is required before broad
production routing: either approve a narrowly scoped module-process helper for
microphone capture and PiP lyrics, or continue looking for a compatible official host
while retaining the strict host-process contract. The helper is only a proposal,
not an approved contract change or a verified implementation. No system-server hooks,
package metadata changes, official APK repackaging, or feature removals were made.

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
the cancellation adapter and its current verification boundary are documented below.

The extracted login implementation calls `login/anon/device`, `login/qrcode/unikey`,
`login/qrcode/client/login`, and `nuser/account/get`. On successful authorization it also
updates official account/profile state, expires the anonymous cookie, and notifies other
host components. Request success alone is not a replacement for those transitions.

## Feature and Request Migration Matrix

Every row is required. At this checkpoint, no feature row is fully migrated and accepted.
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
- The scoped Context and dependency graph below are exercised by the prototype, not yet
  by every production feature. The production player still needs component adaptation.

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
- The PCM-instrumented sample reached `STATE_ENDED`: duration `329190 ms`, final position
  `329198 ms`, `14515200` stereo frames at `44100 Hz`, `28662786` nonzero samples,
  peak magnitude `32768`, normalized RMS `0.164523`. No seeking was used. This run included
  pause/resume and the focus interruption below, then continued through the end while asleep.
  The service/session were released at completion. AudioFlinger snapshots during playback
  showed an active, unmuted track owned by the host with zero underruns.
- An emulator media-play key also woke the official player and caused a focus interruption.
  Module controls resumed correctly, but system media-key ownership remains an explicit
  production takeover gate. Keeping both players available is not the final architecture.
- The user cannot hear the AVD's external audio, including before these changes. Acceptance
  uses decoded PCM, AudioTrack activity, progression, and end-of-stream evidence instead.
  Actual external audibility and sound quality remain unverified; emulator/system audio settings
  were not changed.

This service proves component and audio hosting only. It does not replace `MusicService`,
validate the production effects/visualizer, or establish listening-history settlement.

### Storage Container

`ModuleContext` retains host package/component identity but supplies module resources,
assets, classloader, and theme. Its application Context is module-owned, without replacing
the official Application or pretending that it is a Hilt Application.

- Files and SQLite databases live under the host data directory's `meilox_parasite` subtree.
  Cache, code cache, no-backup, external files/cache/media, and OBB paths each use the same
  namespace within their corresponding host directory. External file types remain children
  of the module's external-files root.
- SharedPreferences names use the `meilox_parasite_` prefix. File streams, listings,
  deletion, SQLite opening/deletion, and derived configuration/display/device-protected
  Contexts preserve module scoping. Automatic database/preference import is disabled.
- Database absolute paths are accepted only inside module data/no-backup directories.
  Unit tests reject traversal, invalid names, and misleading sibling-prefix paths.
- The debug device probe wrote/read/removed disposable file, preference, and SQLite markers;
  initialized the existing Room schema; and round-tripped a temporary Preferences DataStore.
  Original host marker paths were unchanged. Probe content is not user/account data.
- AVD checks passed for derived configuration/device-protected Contexts and available external
  namespaces. Cold-start probes also passed. After background process termination, the Activity
  restored counter `1`, density `3.0`, and the module logo/font/glass controls without a new crash.

This is storage namespacing within one UID, not a security sandbox. Production downloads,
file-provider sharing, work scheduling, runtime permissions, and all-feature persistence
still need their own component/feature acceptance tests.

### Module Dependency Graph

- Removed the Hilt Gradle plugin, runtime/compiler dependencies, entry-point annotations,
  generated-Application requirement, and obsolete keep rules. Plain Dagger `2.59.2` reuses
  the existing database, repository, and network provider modules.
- `AppGraph` owns one explicitly initialized component per module classloader/process.
  Its application-qualified Context is the scoped `ModuleContext`, not the official Application.
  The legacy `AppContext.instance` reference now holds that Context rather than requiring
  a module Application instance. The official Application remains untouched.
- All 29 existing ViewModels have explicit bindings. Compose uses the standard ViewModel
  provider with the Activity's module factory; navigation-entry owners retain their own stores.
  `MainActivity` and `MusicService` use explicit member injection. No screen or feature entry
  was removed as part of this migration.
- `LogViewModel` no longer requires `AndroidViewModel`/Application; it reads module-scoped
  log directories. Its file-sharing component adaptation is still pending.
- Three factory tests cover explicit registration, repeated creation, per-owner/per-key reuse,
  clearing, and provider failure propagation. The complete debug suite passed: 241 tests,
  zero failures or skips; debug assembly, diff checks, and 16 KB APK alignment passed.
- On the AVD, the real About, Log, and Storage ViewModels were created inside the official
  process. The factory reported all 29 bindings, the exact scoped Context, and successful
  storage loading. After background process termination, the graph initialized again and
  the Activity restored counter `1`; module rendering remained intact with no new crash.
- APK inspection found the module Dagger component/factory and no defined Hilt classes.

This dependency-graph checkpoint did not itself change NetEase transport; the subsequent
transport migration is documented below. The runtime Activity still creates only local
ViewModels; enabling the complete frontend before finishing its session/component adapters is not acceptance.
Full MainActivity/MusicService hosting and production navigation are still pending.

### Host Request and Session Core

- `HostRequestBridge` freezes business parameters and attaches a public session identity and
  generation to each call. It rejects credential overrides and non-relative business paths.
  Calls are single-use; copies retain the original account/generation rather than silently
  adopting a newly logged-in account. There is no module-owned cookie or signing state.
- `TvHostRequestBackend` uses the final Tinker loader and the original request factory,
  timeouts, synchronous JSON execution, and cancellation methods. Host exceptions are reduced
  to safe exception-class diagnostics; response bodies and credentials are not logged.
- Cancellation is forwarded even when requested during host request creation. The exact
  DEX method `network.v.e.f.f(): okhttp3.Call` creates the host's network Call; an after-hook
  cancels it before execution if cancellation happened before that Call existed. Only
  requests created by this adapter are tracked or canceled by this hook.
- Session generations change around profile account switches and official session-cookie
  writes/removals. Hooks inspect cookie names only, never values. DEX-verified entry points
  are `r0.a.p(Profile)` and `AbsCookieStore.saveCookies(List)`, `removeCookie(Cookie)`,
  and `removeAllCookie()`. Nested transitions stay blocked until all owners finish.
- The first device run exposed excessive invalidation when the official home screen refreshed
  the same account's profile. The corrected adapter ignores that ordinary refresh while
  retaining invalidation for session-cookie updates, including same-account reauthorization.
- Twelve unit tests cover parameter ownership, invalid paths/auth overrides, single execution,
  cancellation before/during dispatch, account changes, same-account reauthorization, nested
  transitions, stale copies, failures/resource closure, delayed generation delivery, and
  anonymous-to-authenticated transitions. The complete suite passed: 253 tests, no failures,
  errors, or skips. Debug assembly, diff checks, and 16 KB alignment also passed.
- The corrected AVD run bound the bridge inside the official process, observed a same-account
  profile refresh, and completed all ten authenticated read-only operations with code 200.
  Account matching and the final unchanged-session check passed. A separate bounded search
  cancellation observed a pending host request, rejected its result with an IOException, and
  joined the worker successfully. This does not prove that an upstream server never received
  the request or that a server-side mutation can be rolled back.

The bridge is exercised by opt-in qualification and Retrofit probes. QR/logout lifecycle
integration, account-scoped UI reset, binary upload, and complete
response-delivery guards remain required. Unit session transitions use test doubles; this
checkpoint did not log out the real account or perform real social writes. It is not full
login, cross-process session synchronization, or feature-level migration acceptance.

### Retrofit Transport Adaptation

- The production DI providers for `ApiService`, `WeApiService`, `EApiService`, both dynamic
  `MeloXDirectService` variants, and `AudioMatchService` now use `HostCallFactory` rather than
  a module OkHttp network pipeline. The old NetEase interceptor and debug trust-all SSL
  configuration are no longer installed by those providers. QQ transport remains separate.
- The adapter accepts only pinned official HTTPS origins and API path families. Legacy
  `/api/`, `/weapi/`, and `/eapi/` labels resolve to the same host business path, without
  selecting a module signing implementation. JSON scalars, nested JSON, and query values
  preserve their meaning; ambiguous query/body parameters, credential overrides, and binary
  bodies are rejected. Binary uploads require their own official adapter, not a fallback.
- Calls have a bounded worker pool/queue, host cancellation, timeout propagation, retained
  session ownership on clone, response-read checks, and single terminal callbacks. OkHttp
  call tags/listeners are implemented without fabricating socket/TLS events. Consumers still
  need account-scoped state reset and a publication guard after asynchronous transformations.
- Removed old `header`/`e_r` overrides from homepage, photo-album, and complex-search DTOs.
  Removed the repository's automatic alternate-signature retry because both paths now reach
  the same host transport and retrying writes could submit an operation twice.
- Host JSON is wrapped in a synthetic Retrofit response, explicitly tagged `official-json`.
  Playback diagnostics now record `hostAccepted` and leave the actual HTTP status unknown;
  a synthetic 200 is not reported as an observed wire response or final server settlement.
- The first AVD Retrofit run exposed truncated parameter-annotation arrays: a method with
  a body and a continuation returned one annotation slot for two parameters. Retrofit then
  treated its return type as `Object` instead of recognizing a suspend function. A narrowly
  scoped API 102 hook restores only that missing empty continuation slot for the six module
  service interfaces. It leaves all host interfaces and complete/unrelated arrays unchanged.
  This matches the [Android reflection contract](https://developer.android.com/reference/java/lang/reflect/Method#getParameterAnnotations()).
- The corrected device run reported the observed `2` parameters / `1` original annotation
  slot and completed eight original typed API operations: account, playlists, search, song
  details, lyrics, playback URL, subscription counts, and homepage. All business codes were
  200; the account matched the session, playlists and homepage blocks were present, and the
  final session check passed. The ten lower-level bridge probes and cancellation probe also
  passed. No new crash appeared in the tested process.

The complete unit suite passed with 269 tests, no failures, errors, or skips; debug assembly,
diff checks, and 16 KB APK alignment passed. The new tests include structured/large-number
parameters, typed DTOs, legacy route labels, invalid requests, clone/session ownership,
response-read rejection, queue/callback behavior, actual coroutine cancellation, timeout,
call tags/listeners, synthetic reporting semantics, and annotation compatibility.

This is transport/provider acceptance, not acceptance of every screen or request. Recognition
uses the new transport but still needs a real fingerprint test. Cloud binary upload, NCBL,
remaining direct NetEase helpers, login/Cookie UI state, production Activity/service routing,
and full visual/audio/feature regression remain open. No real listening report or social
write was sent by this probe; those tests used substitutes.

### Official QR Login Slice

- Replaced the MeiloX login WebView and manual Cookie entry with the existing pinned-page
  layout, official QR bitmap, status, and glass refresh control. No QR payload, host Profile,
  AndroidX object, or credential is copied into a module-owned login store.
- Verified the pinned APK's actual DEX entry points: `audio.c.b.k0(ZJZ)`, `o0()`,
  `x0()`, `s0()`, `w0()`, private `p0(audio.c.d)` and `G0()`, host `ViewModelStore.put/clear`,
  and `utils.d1.g(Context, boolean)`. The last entry is the full official logout flow,
  not the login-expiration handler that launches the official login Activity.
- A screen owns one host ViewModel in a host ViewModelStore. Reflection and a host-loader
  Observer proxy expose only Bitmap, Pair status codes, and enum names. Refresh/stop removes
  observers, clears the store (and its coroutine scope), removes polling callbacks, and
  suppresses later status transitions from that retired module-owned controller. Official
  controllers created by the host itself are not intercepted by this ownership guard.
- The host's `G0()` failure branch queues global logout outside ViewModelScope. Module-owned
  attempts suppress that queued cleanup so an old failed QR task cannot sign out a newer
  session. Failure still reaches the UI and retires its own observers/scope. Explicit user
  logout continues to call the full official logout entry with the raw host Context.
- The pure login controller rejects stale callbacks by attempt generation, handles synchronous
  observer delivery during creation, releases QR references on stop, and verifies the official
  authenticated state before publishing success. QR creation has a 30-second watchdog.
  Cancellation stops module UI/polling; it does not promise to revoke authorization already
  accepted by the server or undo cookies written by a request already in progress.
- The debug carrier can open the real `NeteaseLoginScreen` with `--ez meilox.login true`.
  Its Insets and glass backdrop providers match the page's required environment. Early
  device runs exposed missing providers; both were fixed before the passing runs below.
  A restored carrier also binds bridges using the final Tinker Activity loader, without
  requiring the original homepage to resume first.
- AVD verified the official QR bitmap, readable layout and icons, a visibly different QR
  after refresh, zero remaining observers/active attempts on refresh and backgrounding,
  foreground regeneration, system Back, and saved-state process recreation. The recreation
  test waited for STOPPED with a saved Bundle, killed the old PID, and resumed the same task;
  the new process reported `restored=true` and reached WAITING without a new crash.
- The host naturally returned EXPIRED after approximately five minutes; the QR disappeared,
  observers/active attempts returned to zero, and refresh generated a new QR. A 15-second
  airplane-mode test reached ERROR, exercised the guarded `G0()` branch, and cleaned up all
  observers. Airplane mode, Wi-Fi, and mobile-data settings were restored to their recorded
  values; after connectivity recovered, refresh returned to WAITING. The original session
  remained authenticated, and ten lower-level plus eight typed Retrofit read-only probes
  passed again, including account/session agreement and final unchanged-session checks.
- The full suite passed with 282 tests, no failures, errors, or skips; debug assembly, diff
  checks, and 16 KB APK alignment passed. Tests cover stale/synchronous callbacks, ownership,
  stop/re-entry, terminal states, unavailable binding, and failed-logout invalidation.

This is a login presentation/lifecycle slice, not stage 3 completion. Real scan authorization,
confirmation/success navigation, logout, account switching, interrupted authorization, and
the remaining Cookie-based account consumers still need acceptance. The real logout entry is
wired but was not invoked against the user's authenticated account. The official launcher,
MainActivity, and production player remain unchanged by this slice.
In particular, keep production routing gated until the entire cookie-to-profile authorization
window and canceled authorization responses are covered by session-publication guards.

### Public Account Presentation Slice

- Added a process-local `HostAccountStore` for public identity and profile presentation.
  It reads the official session and account endpoint, never writes credentials or identity
  copies into module preferences, and rejects a profile whose ID differs from the session.
  Binding wakes an already-created store; invalidation clears old-generation presentation.
  Refresh, account changes, logout, and close discard canceled results even when the loader
  does not cooperate with coroutine cancellation.
- `HostSessionBridge.withCurrent` makes short state publications atomic with transitions.
  Invalidation callbacks remain outside the monitor; delayed notifications cannot erase
  a newer profile. Account-page presentation also checks its loaded generation against the
  current account while old child requests are still unwinding.
- Global avatar routing, Settings, Account Home, and cloud listening history now consume
  official identity instead of module Cookie/user-ID preferences. Removed the General
  Settings Cookie-export control. Cloud history drops prior-generation remote records while
  retaining device-local history; merging local entries also rechecks session ownership.
- The gated account carrier uses the original `navigationEntry`, Navigation 3 back stack,
  per-entry ViewModels, and existing glass/resource providers. It does not replace production
  Activity/service routing or provide a production player connection.
- On the API 37 / 16 KB AVD, module Cookie and user-ID preference presence both reported
  false while the original Settings avatar/name, Account Home profile/details/playlists,
  and nonempty cloud recent-play history loaded through the official session. Covers and
  avatars rendered correctly; account refresh and General Settings were visually inspected.
  No Cookie-export entry remains. No remote mutation, logout, or track play was triggered.
- Saved-state navigation was tested from History into Account Home: after HOME, STOPPED,
  and a saved Bundle, the old process was killed and the same task resumed. The new process
  reported `restored=true`, restored Account Home with the official session, and Back returned
  to History. The tested cold-start and restored processes had no crash-buffer entries.
  Ten lower-level and eight typed Retrofit read-only probes passed again after returning to
  the official launcher; cancellation and final unchanged-session checks also passed.
- The complete suite passed with 295 tests, no failures, errors, or skips; debug assembly,
  diff checks, and 16 KB APK alignment passed. The new cases cover binding, anonymous state,
  account/generation changes, non-cooperative cancellation, mismatched identities, refresh,
  logout, delayed invalidation, close, and atomic publication versus transition.

This is scoped account presentation acceptance, not proof of fresh QR authorization or
listening-stat settlement. Existing cloud records are not attributed to module playback.
Library, Home, Podcast, Social, Playlist, Player, Listen Together, account-owned local caches,
and legacy report consumers still require migration. The authorization-window guard is
described below; production component gates remain open. No official session was exported
or cleared by this account presentation slice.

### Authorization Transaction Guard

- Pinned DEX/source inspection confirms that QR polling saves response cookies before
  `audio.c.b.p0(AUTH_SUCCESS)` starts the separate official profile request. Clearing a
  ViewModel does not synchronously stop its already-running blocking request. The prior
  per-mutation invalidations alone could therefore expose new credentials with an old ID.
- A module-owned attempt now keeps `HostSessionBridge` unavailable across this entire
  window. Its worker scope follows the verified `b.n0(Callable)` and non-suspending
  `b$h.invokeSuspend(Object)` / captured field `b` entries. Official account and anonymous
  response parsers, profile updates, and session-cookie writes check that ownership.
  A retired worker cannot enter another session write. An already-entered write may finish,
  including nested host writes, but blocks replacement and recovery until it leaves.
- The official profile helper `audio.c.a.c()` must finish against the same credential
  revision and match the host's public ID and anonymous/authenticated flags before releasing
  the session. Ordinary profile mutations invalidate older confirmation. Module-created
  controllers also reject queued `B0`/`I0` retries after retirement; original host-owned
  controllers continue through their existing entry points.
- Before starting, a namespaced SharedPreferences file commits one `pending` boolean.
  It contains no identity, cookie, QR key, or authorization response. If a mutation preceded
  cancellation, or the process stopped with this flag set, a background worker uses the
  official account helper to recover. It never restores old cookies, manufactures a profile,
  or performs a logout. Failure leaves the session unavailable and sign-in accessible;
  a new attempt supersedes stale recovery work. There is no automatic retry loop.
- Snapshot readers no longer hold the module session monitor while reading host identity
  (which can acquire cookie-store locks). Final state publication still checks the revision
  under that monitor. The history error path no longer nests a host identity read inside
  another publication block. Delayed invalidation retains the recovery sign-in affordance.
- AVD confirmed the new hooks and scoped callable worker, normal QR rendering/WAITING,
  and a persisted pending flag during the attempt. Force-stopping while WAITING left the
  flag intact; opening Settings in a new process kept public account data hidden until the
  official profile helper verified it, then restored the account and cleared the flag.
- A second interrupted-attempt test enabled airplane mode and disabled Wi-Fi/mobile data.
  Recovery returned false, the marker stayed set, and Settings showed a usable sign-in
  entry without the old avatar/name. Its avatar opened the QR route; the login later reached
  ERROR with all observers removed. Radio settings were restored to their original values
  (airplane 0, Wi-Fi 1, mobile data 1). Re-entering login reached WAITING; returning to Settings
  retried official recovery, which succeeded and restored account presentation. No tested
  process produced a crash-buffer entry. No real logout or fresh scan authorization occurred.
- The final installed package also passed ten lower-level and eight typed read-only request
  probes, active-request cancellation, account/session agreement, and unchanged-session checks.
- All 316 unit tests passed, with no failures, errors, or skips; debug assembly, diff checks,
  and 16 KB alignment passed. New tests cover stale writes, draining mutations, concurrent
  replacement, restart markers, failed recovery/persistence/dispatch, rejected revalidation,
  lock ownership, and recovery UI state. Cancellation-time writes use test substitutes,
  not fabricated credentials against the user's live host.

This implements the module-owned transaction protection, but does not complete stage 3.
Fresh QR authorization, cancellation during actual server acceptance, account switching,
logout/anonymous initialization, and process death during those real transitions still
require device acceptance. Keep production routing gated until those tests and the remaining
account consumers pass. The existing authenticated session was preserved throughout this slice.

### Session-Owned Home Feed

The original Home page now observes official session generations instead of the stored
`UserIdKey`. Its `HomeRepository` uses the existing typed API through the host transport
and an expected session stamp for reads, cache writes, errors, and final publication.

- Personalized cache files live in the module's host-private `home_accounts` directory,
  separated by public user/anonymous/guest identity. No credentials are stored. The old
  shared `home_page_data_1.json` and preference timestamp are not imported or consulted.
- Each cache envelope owns its timestamp and retains the existing 06:00 Asia/Shanghai
  freshness boundary. Missing, malformed, null, or future-dated entries trigger a fetch.
  Temporary-file/atomic replacement avoids publishing partial cache writes; cache I/O
  failure does not discard a valid network response.
- Session invalidation clears visible personalized content synchronously. Cancellation,
  generation checks, and publication guards reject obsolete requests, including same-user
  reauthorization and non-cooperative refreshes. Delayed invalidation cannot erase newer
  content or hide the session-recovery error behind a spinner.
- Original home cards and song rows can render while the real playback connection is
  pending. No substitute player was introduced. The previously blank error branch now
  uses the existing glass retry control, and refresh progress reflects actual loading.

Verification on 2026-09-29:

- All 335 unit tests passed (19 new home repository/ViewModel cases), with no failures,
  errors, or skips. Debug assembly, `git diff --check`, and 16 KB APK alignment passed.
- The debug carrier opened `home` in the TV process. The host-backed response populated
  six original home blocks; screenshots verified recommendation cards, cover loading,
  text layout, and the error/retry surface. Module Cookie and user-ID preferences remained
  absent. No request headers, public profile values, or raw responses were logged in Git.
- With airplane mode enabled and Wi-Fi/mobile data disabled, a force-stop/cold-start
  restored the current account's cached feed. Pull-to-refresh displayed the sanitized
  host transport failure and a retry control. After connectivity settled, retry fetched
  updated recommendations. Network settings were restored to airplane mode `0`, Wi-Fi
  `1`, and mobile data `1`. The tested final process had no crash-buffer entries.
- Cross-account isolation, anonymous transitions, cancellation, and late results were
  verified with controlled test identities, not by changing the user's live account.

This is a feed/session/cache slice, not complete Home playback acceptance or stage 4
completion. The carrier still has no production player connection. Intelligence-mode
state, play actions, service startup, and remaining account consumers require their own
migration and end-to-end tests. The recording/PiP host decision remains unresolved.

### Session-Owned Library Collections

The original Library songs/playlist presentation now observes `HostAccountStore` rather
than Cookie or user-ID preferences. The existing typed repositories still execute
NetEase calls through the official host transport.

- Schema 18 adds account-to-playlist membership, position, and an explicit liked-playlist
  flag. Additive migration `17 -> 18` does not import unowned cloud rows into the current
  account. Device-local playlists remain available; replacing one account's membership
  does not delete another account's membership or shared playlist metadata.
- Complete playlist and collected-album pagination validates each page and rejects
  non-advancing responses or business failures before accepting a partial collection.
  Transactional replacement preserves existing creation time and local playback statistics.
  Session/cancellation checks surround the write, and a failed final validation rolls it back.
- Session invalidation immediately clears account presentation. Request generations,
  cancellation, and guarded publication reject late results after refresh, logout, or
  account changes. Custom photo preferences and transient layout state are account-scoped.
  Shared `safeApiCall` now propagates coroutine cancellation instead of converting it to
  a display error. Home and Library prepare their displayed generation synchronously,
  so a delayed invalidation cannot erase a newer request before its coroutine starts.
- Existing cards, tabs, sorting, search, covers, and song rows are retained. Songs and
  Playlists gain the existing glass refresh control and an error surface. A failed load
  no longer claims the collection is empty; a successful empty response still does.

Verification on 2026-09-29:

- All 355 unit tests passed, with no failures, errors, or skips. New coverage includes
  pagination, business failures, cancellation, account changes, same-account refresh and
  reauthorization, late results, liked-membership removal, and ViewModel disposal.
  Debug assembly, `git diff --check`, and 16 KB APK alignment passed.
- The actual module-private AVD database migrated from version 17 to 18, with SQLite
  integrity `ok` before and after. A separate disposable Room probe also exercised the
  migration, two controlled account identities, shared metadata, per-account liked flags,
  local playlist retention, statistics preservation, and transaction cancellation rollback.
- The original Library rendered four liked songs through the debug carrier inside TV.
  Playlist collections were empty, matching this account's response containing only its
  liked playlist. Screenshots verified covers, rows, tabs, refresh, and layout.
- A force-stop/cold-start without connectivity rendered the sanitized error rather than
  a false empty state. Restoring connectivity and tapping refresh restored all four songs.
  Final network settings were airplane mode `0`, Wi-Fi `1`, and mobile data `1`; the
  tested final host process had no crash-buffer entries. Module Cookie and user-ID
  preferences remained absent. No credentials or raw device evidence were added to Git.

This is collection ownership/read presentation, not all Library tabs or stage 4 completion.
Real account switching was not performed; account races and multi-page responses used
controlled test substitutes because the live collection is small. Cloud, Podcast, Player,
playlist mutations, and other account consumers still need migration. Production playback
is not connected to this carrier, and the recording/PiP host decision remains unresolved.

### Page Glass Regression

The user's Library track-menu screenshot exposed a missing backdrop connection in the
debug page carrier. Its `GlassBackdropHost` recorded only a solid background, while the
actual navigation content was an unrecorded overlay. Popup glass also lacked the source
position needed for cross-window sampling, leaving sharp page text visible through it.
Earlier page-layout screenshots did not establish popup glass acceptance.

`HostAccountProbe` now follows the production Activity's existing rendering contract:
page controls sample a separate static base, the rendered page is recorded and tracked
in screen coordinates, and popup/sheet glass receives the shared page backdrop. Glass
colors follow the active light/dark theme. Existing blur/refraction shaders, materials,
opacity, and menu layout are unchanged.

Verification on 2026-09-29:

- Reproduced the reported Library song-menu transparency and captured settled before/after
  screenshots on the AVD. After the fix, page text behind the menu is blurred while menu
  text remains sharp, with the source anchored to the menu's screen position.
- Expanded the download-quality submenu without choosing a quality or starting a download.
  Consecutive screen-recording frames showed expansion and a stable sampled backdrop;
  the final submenu layout and Back dismissal were also inspected. The emulator recorder
  fell back from the native resolution to 720x1280; settled screenshots remained native.
- Debug assembly, all 369 tests in the current working tree, `git diff --check`, and 16 KB
  APK alignment passed. This includes 14 tests from the separate, not-yet-accepted Podcast
  migration. The tested TV process had no crash-buffer entries. Raw images/video stay
  outside Git. This is page-menu visual acceptance, not production player-glass acceptance.

### Session-Owned Podcast Browsing

The existing Podcast pages now use official session identity instead of a module Cookie
preference. Discovery, categories, subscriptions, detail, and program pages retain their
existing repositories, models, layouts, and controls.

- Each podcast operation carries its captured `HostSessionStamp` through a Retrofit
  request tag. The host bridge rejects an obsolete owner before creating or executing
  the call, including queued and cloned calls. The tag is not serialized into headers
  or business parameters and contains no credentials.
- ViewModels synchronously invalidate account-owned state, cancel obsolete work, and
  guard publication across refresh, account changes, and same-account reauthorization.
  Recovery failures become errors instead of indefinite loading. Subscription mutation
  rollback cannot undo a newer reload or erase concurrently appended program rows.
- Pagination advances by raw response count rather than deduplicated display count.
  Non-advancing pages fail explicitly. Full-program search/bulk loading only caches a
  complete result and does not retry automatically after a business failure.
- Detail search, selection, and pending bulk state are scoped to the official session.
  Failed search no longer claims an empty match set; it uses the existing error/retry
  surface. Subscription writes and production playback/download still need acceptance.

Verification on 2026-09-29:

- Debug assembly and all 377 unit tests passed, with no failures, errors, or skips.
  Nineteen podcast tests cover identity, guest access, raw pagination, cancellation,
  stale results, mutation ownership/rollback, cache invalidation, recovery, and partial
  bulk failure. Three transport tests cover expected-session tags and queued/cloned calls.
- AVD discovery, category switching, real covers, authenticated subscription empty state,
  and podcast detail rows rendered through the original pages in the TV process. An
  offline subscription request displayed a transport error; restoring connectivity and
  retrying recovered the genuine empty subscription state. No subscription was changed.
- A 792-episode podcast rendered its initial rows. Single-row selection and Done worked.
  Full select-all/search met the official rate-limit response and testing stopped; these
  operations are not accepted as complete on-device. Failure/retry pagination is covered
  with controlled substitutes, not claimed as a successful full 792-episode fetch.
- Module Cookie/user-ID preferences remained absent. Airplane mode is `0`, Wi-Fi `1`,
  and mobile data `1`. No real account changes, downloads, or social writes were made.
- The first launch immediately after the final APK replacement hit a process-attach
  timeout before any new module event. One subsequent launch succeeded with the official
  session intact and no crash-buffer entries for that process. The timeout's root cause
  is not established; repeated update/cold-start stability remains a separate gate.

### Original UI Contract Recheck

The user explicitly reaffirmed that the original MeiloX UI/UX and page architecture must
remain unchanged. The extra top-level refresh actions introduced during Library/Podcast
migration were removed. Original navigation entries, page components, search, tabs, menus,
and detail actions are retained; this is host adaptation, not a page redesign.

The debug carrier supplied `LocalSelectionToolbar` but omitted the original toolbar's
rendering slot. It now renders that same content with the production Activity's alignment,
spacing, bottom inset, and shared backdrop. The toolbar remains outside its sampled page
layer to avoid glass feedback. No new selection controls or material parameters were added.

AVD screenshots verified the original top-bar actions, the corrected Library song-menu
blur, and the restored original selection toolbar. The debug carrier still opens individual
routes and has no production player connection; it must not be mistaken for the final
application shell. Production takeover must reuse the original full navigation/player
composition. This recheck does not complete stage 2, stage 4, or stage 6.

### Original App Component Qualification

The opt-in debug flag `-PparasiteAppProbe=true` now carries the original `MainActivity`
and `MusicService` through the TV's registered test Activity and local-music service.
It enables the existing host/runtime probes as prerequisites. Default debug and release
builds leave this flag off, and the official launcher remains untouched.

- The original Compose tree, navigation, page hierarchy, mini/full player, glass materials,
  and animations are reused without alternate layouts. The Activity receives module
  resources, its original theme, saved-state class loader, and portrait orientation; the
  host's AutoSize adaptation excludes this Activity.
- Only explicit module Activity/service targets under the verified host package are
  mapped. Implicit intents, unrelated packages, and official component targets remain
  unchanged. Context service calls and Media3-created PendingIntents use that mapping.
- The original service owns its MediaSession with `addSession`, without constructing an
  otherwise unused self-controller against an unregistered module component. Activity
  disposal releases its PlayerConnection listener before unbinding.
- Notification small icons name the installed module resource package. Module-owned
  notification action icons are rasterized before crossing into SystemUI, retaining the
  original actions, labels, and PendingIntents. Live testing initially found invisible
  transport icons despite populated notification actions. The legacy
  [AOSP media-action path](https://android.googlesource.com/platform/frameworks/base/+/a3b7a10a15aa/packages/SystemUI/src/com/android/systemui/media/MediaDataManager.kt)
  rebinds resource IDs to the posting package; the rasterized icons rendered correctly on
  this AVD. Hooks affect only the module's isolated AndroidX classes.
- The pinned TV target-29 service uses the platform's legacy two-argument
  `startForeground`. This narrow Media3 adaptation neither fabricates a foreground-service
  type nor changes package metadata, permissions, or system-server behavior.

Verification on 2026-09-29:

- The original Home and Library, bottom navigation, mini player, and expanded player ran
  inside the verified TV process. Cold start and the portrait-triggered saved-state
  recreation succeeded. A subsequent module reinstall/host restart restored the paused
  playback snapshot and official account presentation.
- The reported Library song menu and its download-quality submenu blurred the recorded
  page while retaining sharp menu content. Consecutive recording frames showed submenu
  expansion and the original player glass transition/fluid-background motion. Native
  screenshots also showed the Home feed sampled behind the original bottom controls.
  No download was selected. Images and videos remain outside Git.
- A Library track played through the real MusicService, completed its 5:29 timeline, and
  advanced to another queue item. MediaSession position advanced in the background;
  AudioFlinger showed active 44.1 kHz stereo PCM output, unmuted state, and zero observed
  underruns. This is decoder/output-state evidence, not audible-quality acceptance.
- The registered host service held foreground notification 888. After the icon fix,
  SystemUI displayed artwork and all three original transport icons. Actual notification
  taps changed the real session from PLAYING to PAUSED and back; the content PendingIntent
  returned to the original MeiloX Activity without a second Activity in its task.
- `:app:testDebugUnitTest :app:assembleDebug -PparasiteAppProbe=true` passed all 379 tests
  in 56 suites. Explicit-component/resource policy has two focused tests. `git diff --check`
  and 16 KB APK alignment passed. The tested host processes had no crash-buffer entries.

This is debug component qualification, not production launcher takeover or complete
playback migration. Official playback services still initialize but their observed media
session stayed STOPPED. Preventing duplicate official playback/reporting, migrating old
account/reporting consumers, downloads, effects/AutoMix regression, lyric/beat verification,
and the recording/PiP host decision remain gates. Release runtime acceptance is pending.

### Session-Owned Search

- Original search discovery, suggestions, five result categories, navigation, list rows,
  track menus, and player surfaces remain intact. Discovery recommendations and every
  search/suggestion request now carry their captured official session stamp. No search
  query, raw suggestion response, or session credential is added to diagnostic logging.
- Session invalidation synchronously clears old results, suggestions, and per-category
  caches. Query/tab replacement and disposal cancel obsolete work. Publication is guarded
  after asynchronous work, including a non-cooperative canceled loader and same-account
  reauthorization. Failed official recovery displays an error rather than waiting forever.
- All five result categories support incremental paging in the existing list. The raw
  response count advances the offset; displayed IDs deduplicate without changing order.
  Append failure retains rows/cursor and exposes explicit retry through existing error and
  glass-button components. A non-advancing response cannot start an infinite paging loop.
- The first device result exposed a non-null `trp` assumption in the old DTO. The optional
  metadata now tolerates absent/null values. This and earlier interface differences are
  tracked separately in [the API difference ledger](official-client-api-differences.md).

Verification on 2026-09-29:

- The full debug suite passed 397 tests in 57 suites, with no failures, errors, or skips.
  Sixteen search tests cover guest access, all five paged result types, raw offsets,
  deduplication, cache ownership, debounce/cancellation, stale results, business failure,
  recovery, discovery retry, and disposal. Two transport tests cover session-tag handling
  without serializing the tag and obsolete-owner rejection before request execution.
- AVD search discovery displayed real recommendation covers. Two different queries
  produced suggestions and results. Suggestion selection and the soft-keyboard search
  action both opened the original results page; the IME first committed its composition
  before sending the search action. Keyboard settings were not changed.
- Song, artist, album, playlist, and podcast results rendered. Artist, album, playlist,
  and podcast detail entry and system Back succeeded. This is read/navigation acceptance,
  not acceptance of their subscriptions, mutations, or complete session migration.
- Scrolling a song search exposed 48 distinct title/artist row pairs across five bounded
  UI hierarchy snapshots, exceeding the 30-row initial page. Native screenshots confirmed
  the original song-menu blur, progressive header blur, and mini-player material. All
  hierarchy/screenshot evidence stays outside Git. Exact paging failure/account-change
  paths were exercised with controlled substitutes, not the real account.
- The installed opt-in app probe ran in the verified TV process with API 102. The tested
  process had no crash-buffer entries. Debug assembly, diff checks, and 16 KB APK alignment
  passed. Playback remained paused; no follow, subscription, download, social send, logout,
  or audio/network-setting change was performed during this search verification.

This increment does not complete stage 4. Search-result artist follow still has the
pre-existing placeholder handler; the original control is retained and its implementation
belongs to the remaining artist/mutation work. Album/playlist/account ownership, remaining
core pages, production routing, and final release regression are still outstanding.

### Session-Owned Album Details

- The original album page, cover, song list, search, multi-selection, glass menus, and
  mini player remain unchanged. Album details and the official TV collection flag are
  read under one captured session; the unscoped legacy album table no longer determines
  the detail page's account state. Missing collection state is an explicit load failure,
  not a fabricated uncollected flag. See API-006 in
  [the API difference ledger](official-client-api-differences.md#api-006-album-collection-state-is-owned-by-the-official-account).
- Session invalidation clears presentation synchronously. Page/account replacement and
  disposal reject late results. Guest reads remain available; writes require official
  authentication. Collection mutations are serialized, checked for business code 200,
  and rolled back on rejection. Refresh waits for an active write to settle. Accepted
  writes notify only the current account's Library; observer failure cannot undo an
  already accepted write in the detail presentation.
- Song URL requests made by this page preserve the captured session across quality
  fallbacks and validate page ownership before returning. This does not qualify the
  downstream download database, worker, or full playback pipeline.

Verification on 2026-09-29:

- `:app:testDebugUnitTest :app:assembleDebug -PparasiteAppProbe=true` passed 420 tests in
  59 suites, with no failures, errors, or skips. The 23 new tests cover the TV request
  contract, true/false/missing state, business failures, guest writes, reauthorization,
  obsolete album/account results, concurrent refresh, duplicate writes, disposal,
  Library invalidation, and session tags rejected before dispatch without serialization.
- The installed debug app probe cold-started successfully in the verified TV process.
  A real album displayed its original cover and ten songs with an identity-checked
  official uncollected flag. Multi-selection selected a row and system Back exited it;
  in-page search filtered the songs and closing search restored the list. Refresh and
  background/resume retained the album presentation. System Back returned to search.
- Native screenshots show the actual cover blurred behind the original menu while menu
  text stays sharp. The original mini player remains visible and paused. Screenshots
  and device output remain outside Git. `git diff --check`, 16 KB APK alignment, and
  debug assembly passed; the tested process had no crash-buffer entries.

This is an album read/session and controlled-mutation increment, not full stage 4
acceptance. No real collection/uncollection, logout, account switch, download, social
send, or audio/network-setting change was performed. Live mutation acceptance remains
pending; canceling an old request cannot reverse a server-side write. Shared song-menu
playlist actions, playlist/artist/account migration, downloads, production routing,
the recording/PiP host decision, and release regression remain outstanding.

### Session-Owned Playlist Detail and Paging

- Original playlist page composition, cover/header, actions, search, multi-selection,
  glass menus, and mini player are retained. Details, ID-based pages, full-list search,
  collection writes, and detail-page URL requests carry the captured official session.
  Invalidation clears detail/collection presentation synchronously; replaced pages,
  same-account reauthorization, canceled loaders, and disposed ViewModels cannot publish
  obsolete results. Selection/search/overlay composition is keyed to the page owner.
- All playlist names, including liked-music names, use authoritative `trackIds` for
  ordering and pagination. Requested ID ranges advance the cursor even when a song is
  unavailable. Responses are reordered and deduplicated; extra IDs are ignored. Full
  search loads missing metadata in batches of at most 200 and rejects incomplete results.
  Initial/append failures expose retry in the original list using existing glass controls.
- Collection now uses the official TV multi-terminal routes and host security generator.
  Caller-provided security tokens are rejected on these routes. Failure restores the
  prior flag without deleting shared local rows; duplicate writes are suppressed and
  refresh waits for settlement. Accepted writes refresh the account-owned Library through
  captured-session user-playlist pages. Live mutation acceptance is still pending.
- Successful authenticated detail entry preserves local access metadata only for a
  playlist already associated with that account. A Room transaction validates ownership
  before and after the access update. No schema change or standalone-data migration is
  introduced. This guards access publication; it does not redesign historical local
  playlist statistics into a new per-account statistics schema.
- Source, parameter, and paging differences are recorded as API-007 and API-008 in
  [the API difference ledger](official-client-api-differences.md).

Verification on 2026-09-29:

- `:app:testDebugUnitTest :app:assembleDebug -PparasiteAppProbe=true` passes 453 tests
  in 63 suites with no failures, errors, or skips. The 33 new tests include
  includes sparse/reordered/duplicate/extra songs, empty intermediate pages, more than
  400 search IDs, incomplete search retry, business errors, session-tag dispatch,
  generator policy, stale detail/mutation results, access validation, and cancellation.
  A forced rerun of `:app:testDebugUnitTest --rerun -PparasiteAppProbe=true` also passes.
  Bulk preparation and confirmation retain the original detail snapshot so refreshing
  the same playlist cannot authorize an older pending operation against the new snapshot.
- The updated module was installed without replacing standalone MeiloX or clearing host
  data. The opt-in original-app carrier cold-started in the official TV process and
  retained the logged-in account and paused mini player. The generator method resolves
  from the pinned host DEX; it was not invoked through a real collection mutation.
- A real 63-song playlist opened from search. Ten bounded hierarchy snapshots contained
  63 distinct title/artist row pairs, ending at the visible last row. Searching that last
  song produced the correct single result. Filtered multi-select/all-select, system Back
  out of selection, clearing the query, refresh, background return, and Back to search
  all succeeded. This is frontend paging evidence, not proof that every row needed an
  additional network request; the initial detail response can already contain metadata.
- Native screenshots confirm the cover sampled behind the original glass menu, blurred
  content behind the pinned header, and the paused mini player. Screenshot/hierarchy
  evidence stays outside Git. APK 16 KB alignment and `git diff --check` pass, and the
  tested process has no crash-buffer entries. No subscription, edit, create, delete,
  download, social send, logout, or audio/network-setting change was performed.

This increment does not complete playlist migration or stage 4. The shared playlist
picker, add/remove-song/create/delete flows, daily recommendations, legacy like-table
updates, and downstream download work still require account isolation and acceptance.
Real collection/account-change tests, full playback/reporting, production routing, the
recording/PiP host decision, and release regression remain gates.

### Shared Playlist Actions and Picker

- The original Library/song-list and expanded-player picker/creation sheets now share
  the same PlaylistViewModel path. The player's duplicated global playlist cache,
  preference-owned user ID lookup for the picker, and first-100-only refresh are removed.
  Account-membership reads are filtered to created playlists, preserve full pagination,
  and show a retry state without replacing the original sheet composition.
- Every add/remove/create/delete call captures the official session and sends its tag
  through Retrofit. Invalidation clears picker/results synchronously, cancels obsolete
  work, and closes an obsolete sheet. Duplicate writes in a ViewModel are serialized;
  delayed callbacks cannot mutate another account or a replacement detail page. Accepted
  writes refresh only their captured account's Library. Failed writes never delete
  shared local rows; a refresh failure cannot cause an accepted write to be retried.
- Add and creation parameters follow the inspected TV callers, including host-generated
  security parameters. Duplicate results no longer depend on stale counts or localized
  message matching. Creation checks a positive, consistent returned playlist identity.
  Supplemental remove-song/playlist-delete contracts still require live acceptance.
  See API-009 and API-010 in [the difference ledger](official-client-api-differences.md).
- The existing create-sheet privacy control exposed a pre-existing shared GlassToggle
  defect: one touch reached both the switch and its clickable parent, and remembered
  gesture callbacks captured old values. Consuming switch gestures, current-state
  callbacks, and animation-identity pointer keys repair that behavior without changing
  the glass geometry, shaders, animation, or page architecture. Other drag/highlight
  users retain the non-consuming default. Switch accessibility state follows the
  controlled value; canceled and disabled gestures must not commit a change.

Verification on 2026-09-29:

- Debug assembly and 473 unit tests in 64 suites pass, including 20 new tests for picker
  ownership/full-list snapshots, cached failure/retry, cancellation, repeated clicks,
  guest and foreign targets, delayed callbacks, business codes, identity validation,
  host parameters, and captured-session transport. A full `--rerun-tasks` unit-test
  rebuild also passes. It exposed two old playback test doubles missing the existing
  session argument; their signatures now match the production service.
- The host Library menu opened the unchanged picker with the current account's created
  playlist and cover/count. The unchanged creation sheet opened above the IME; entering
  a draft did not submit a request. After the switch repair, direct on/off taps and a
  parent-row tap keep the icon, privacy caption, and switch value consistent. Cancel
  restores Library with the mini player still paused.
- The expanded player's original More menu opens the same account-owned picker and
  creation sheet. The sheet retains the player backdrop, fits above the IME, and Cancel
  restores the paused player; Back returns to Library. The Library track menu blurs the
  underlying text. Device screenshots remain outside Git.
- An initial Compose-rule instrumentation attempt could not initialize Espresso on
  API 37 (`InputManager.getInstance` is missing). The focused switch tests instead use
  ActivityScenario and actual Android MotionEvent dispatch, with bounded waits for
  recomposition; all three pass without patching framework methods or skipping assertions.
  They cover repeated taps and updated callbacks, canceled/disabled input, final drag
  position, external state updates, and preventing duplicate parent clicks.
- The connected-test runner removed the module APK during cleanup, which also removed
  its LSPosed enable/scope records. Reinstalled the tested debug APK and restored only
  MeiloX Parasite's enabled state and static TV scope through the manager. After a cold
  host restart, injection and the existing official session were intact. Future focused
  device runs should install the test APK and invoke instrumentation directly to avoid
  this cleanup, or explicitly restore the module before host verification. Other modules
  and official application data were not changed.
- `zipalign -c -P 16 4` and `git diff --check` pass. The restarted host process has no
  entries in its crash buffer during this regression. These checks do not qualify real
  server writes, device-wide audio, or release obfuscation.

This checkpoint is not live write acceptance. No existing user playlist is modified;
permission to create and clean up a temporary private test playlist is pending. Real
create/add/duplicate/remove/delete, canceled writes already accepted by the server,
account switches, daily recommendations, legacy like-table ownership, and downstream
download/playback work remain unqualified. The host capability and production-routing
gates are unchanged.

### Daily Recommendations and Complete Liked Reads

- Daily recommendations now carry a captured official session and use the inspected
  TV normal-scene parameters. The page's read starts and stops with its composition;
  invalidation clears results synchronously, cancels obsolete work, and refreshes only
  while requested. Empty results and optional empty translation arrays are safe. Errors
  expose a retry in the existing error area using the shared glass control.
- The original daily header, cover, song list, search, navigation, and player composition
  remain intact. Filtered selection resolves its position in the full returned queue;
  Play All and shuffle use the same current-owner snapshot guard. The existing shuffle
  action now requests shuffle rather than falling through to ordinary playback.
- Library liked-song reads require the current account's marked membership and matching
  cached/returned creator. Every detail and missing-song batch carries the owner. Partial
  fallback is removed: incomplete full-list reads remain errors with the existing Library
  refresh path. Changes to membership metadata reload an unchanged playlist ID, while
  canceled/replaced reads cannot overwrite newer results.
- Removed the song-list title-suffix effect that wrote a global Like table. The database
  schema and existing data are preserved. The player's heart read/write state machine
  remains outside this increment. Contracts and uncertainties are recorded as API-011
  and API-012 in [the difference ledger](official-client-api-differences.md).

Verification on 2026-09-29:

- Debug assembly and 493 unit tests in 65 suites pass, including 20 new tests for daily
  parameters and transport tags, empty/error/retry behavior, ownership, cancellation,
  obsolete queue snapshots, complete liked reads beyond 400 songs, and Library refresh
  replacement. No test is skipped. `zipalign -c -P 16 4` and `git diff --check` pass.
- Installed the debug APK without running connected-test cleanup; the existing module
  enable/scope and official login survive a cold host restart. The four-song Library
  collection loads without error. Home opens the unchanged daily page, including cover,
  search/IME, filtered rows, and complete-list restoration when search closes.
- The first daily read returned 33 songs. Selecting the filtered second song produced
  a 33-item Media3 queue at active index 1 with matching metadata. Playback advanced to
  26,086 ms before being paused, with no playback error. The original official player
  stayed stopped. This proves short selected-item playback and queue handoff only, not
  audible output, full-song completion, reporting, or later server statistics.
- Back returns to Home; re-entry reads 30 songs without a layout change. Returning to
  Library restores its four-song list and paused mini-player. The current host process
  has no crash-buffer entries during this check. Screenshots and device state remain
  outside Git. No playlist/like/social mutation, logout, download, or audio/network
  setting change is performed.

This checkpoint leaves stage 4 in progress. Real account replacement, device offline
recovery, player heart behavior, and authorized write acceptance remain unqualified.
The temporary-private-playlist authorization, host recording/PiP decision, production
routing, full playback, and release gates are unchanged.

### Remaining Gates

- Pin package, version, and signing identity before installing host-specific hooks.
- Use `compileOnly` for the API 102 library; register only modern module entry points.
- Verify `getApiVersion()` in the actual injected process; metadata is insufficient.
- Preserve host Application initialization and network/session services.
- Isolate module Compose/Kotlin/AndroidX resources and types from host versions.
- Keep the module-owned dependency graph independent of the official Application.
- Prove Activity, media service, notification, resource, font, native library, and lifecycle handling before migrating all screens.
- Do not assume a service listed only in the module manifest is available under the host UID.
- Resolve the recording/PiP manifest gate with the user before committing to a production host or changing process boundaries.
- Translate request parameters and host objects at a single boundary; retain coroutine cancellation and session-generation checks.
- Keep login observers and polling bounded by the login screen lifecycle.
- Separate local playback state from official account state; never accept anonymous state as user login.
- Disable host playback/reporting paths only after the replacement's required initialization is understood.
- Keep hot reload off until all hooks, views, observers, workers, and native resources can be released correctly.

## Stage Status

| Stage | Status | Exit condition |
| --- | --- | --- |
| 1. Host and feature baseline | Reopened: runtime prototype passed, but recording/PiP manifest gate requires a user decision | Select a host or explicitly approve a process-boundary exception without removing features |
| 2. API 102 runtime | In progress: identity, Compose/resources, recreation, JNI, storage, module dependency graph, original app shell, real music service, and notification qualification passed | Production component routing and host capability decision remain |
| 3. Official-session login UI | In progress: QR lifecycle, first account consumers, and guarded recovery passed | Real authorization/abort/logout/account changes and remaining account consumers remain |
| 4. Core business migration | In progress: shared Retrofit transport, eight typed operations, Account Home, cloud History, session-owned Home feed/cache, Library collection and complete liked reads, search discovery/results/paging, album detail reads, playlist detail/paging/search, shared picker reads, and daily recommendations passed their documented checkpoints; collection and playlist writes have substitute-test coverage only | All core screens use host business transport and pass UI/session acceptance |
| 5. Playback migration | In progress: original player/service, initial queue playback, and notification qualification passed | Full audio, effects/AutoMix, download, timer, account ownership, and official reporting behavior passes |
| 6. Remaining features | In progress: podcast session ownership and initial read presentation; full paging and writes remain unaccepted | Every feature row above has implementation and appropriate verification evidence |
| 7. Cleanup and regression | Not started | Old NetEase transport removed; release build and full regression pass |

Do not advance to the broad frontend migration until the Activity/resource/service
prototype passes. Module loading alone does not complete stage 2.

## Reproduction Commands

Use Android SDK `aapt2 dump badging`, `apksigner verify --print-certs`, and
`apkanalyzer dex code --class <runtime-class> <apk>` for artifact checks.

```sh
./gradlew :app:testDebugUnitTest --tests 'com.ljyh.mei.parasite.*' :app:assembleDebug -PparasiteHostProbe=true
./gradlew :app:assembleDebug -PparasiteHostProbe=true -PparasiteRuntimeProbe=true
./gradlew :app:testDebugUnitTest :app:assembleDebug -PparasiteAppProbe=true
adb -s emulator-5554 shell getprop ro.product.cpu.abilist
adb -s emulator-5554 shell getconf PAGE_SIZE
adb -s emulator-5554 shell dumpsys package com.netease.cloudmusic.tv
adb -s emulator-5554 shell am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.app.LoadingActivity
adb -s emulator-5554 shell su -c 'am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.tv.activity.TvLoginActivity'
adb -s emulator-5554 shell su -c 'am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.tv.test.TextMainActivity'
adb -s emulator-5554 shell su -c 'am start -W -n com.netease.cloudmusic.tv/com.netease.cloudmusic.tv.test.TextMainActivity --ez meilox.account true --es meilox.route history'
adb -s emulator-5554 shell dumpsys media_session
adb -s emulator-5554 logcat -d -b crash
```

Package/media dumps can include user state. Inspect locally and report only the minimum result.
Never copy complete request headers, session cookies, QR payloads, or account responses into Git.
