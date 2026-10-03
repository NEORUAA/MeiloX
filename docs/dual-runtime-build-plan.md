# Dual Runtime Build Plan

## Revision and Scope

Approved target revision: 2026-09-29. This document supersedes the module-only end
state in [Official Client Parasite](official-client-parasite.md). Work stays on
`official_client_parasite`; the eventual integration target is `main`. Do not merge
or push as part of this planning checkpoint. Commit verified increments locally.

The same checkout must produce two independently installable APKs with one shared
MeiloX frontend. Future shared frontend edits must reach both builds without copying
screens or maintaining two frontend branches. This task covers backend/session and
runtime adaptation, not unrelated frontend problems inherited from `main`.

| Variant | Production package | Authentication and execution |
| --- | --- | --- |
| `standalone` | `com.neoruaa.meilox` | Original standalone Application and components; original WebView/Cookie login, including the existing manual Cookie entry; standalone NetEase transport and reporting |
| `parasite` | `com.neoruaa.meilox.parasite` | libxposed API 102 in the verified TV host; official login/session, signing, request pipeline and reporting; no standalone music-app launcher |

Both retain the same navigation, screens, glass, player, AutoMix, effects, visualization,
feature entries and shared resources. Login's backend-specific content is an intentional
difference: the standalone WebView/Cookie flow and parasite official QR flow remain.
Do not redesign either flow as part of extracting its backend boundary.
Backend-owned account settings also preserve standalone's original explicit MUSIC_U
export, while parasite never exposes official credentials through that shared page.

Superseded requirements:

- Do not delete the standalone Cookie/signing/transport/reporting implementation from
  the project. Restore required baseline code into standalone-only source sets.
- Disable standalone startup only in the parasite artifact, not in the shared manifest.
- Replace module-only final acceptance with a two-variant release and regression gate.
- Existing host evidence remains valid only within its recorded scope. It does not
  qualify a newly restored standalone backend or either new flavor automatically.

## Current Validation Environment

From 2026-10-03, use only the user's `HyperOS_4_Official_API_37` AVD for device
validation. Its migration-time serial is `emulator-5574`, Android API 37, arm64,
4 KB pages, KernelSU root and LSPosed 2.2.0/API 102. Identify the AVD by name before
selecting a serial; emulator ports may change. This remains emulator evidence,
not physical-device acceptance. The unscoped standalone-process qualification below
does not require uninstalling/disabling the device's framework.

The user enabled `org.lsposed.corepatch` and authorized rebooting this same AVD
for cross-signature preserving installs. The production-certificate checkpoint below
uses that configuration; it is not evidence of cross-signature upgrade compatibility
on an unmodified package manager. No AVD clone, disk backup or snapshot is created.

Do not run further tests on `Pixel_10_Pro` (`emulator-5554` at migration time).
Existing Pixel/16 KB checkpoints remain historical, bounded evidence. The user
authorized local application/data migration and closing Pixel after verification,
not deletion of its AVD or original data. The transfer is verified below and the
user has resumed implementation; device validation now stays on HyperOS 4.

## Source and Build Boundaries

Keep the existing `:app` project and introduce one `runtime` flavor dimension, rather
than cloning the application or creating a new Gradle module per feature.

```text
app/src/main
  Shared Compose UI, navigation, resources and ViewModels
  Shared domain models, player/queue/AutoMix/effects and local processing
  Backend-neutral session/request/account/reporting contracts
                |
        Flavor-specific DI bindings
          /                     \
app/src/standalone          app/src/parasite
  WebView/Cookie session      Official session and QR login
  Legacy transport/signing    Host request/report bridges
  Standalone reporting       API 102 hooks and host carriers
  Own app components         Isolated module context/storage
          |                     |
   MeiloX standalone APK    MeiloX parasite APK
```

Build targets (both debug/release variants build; paired release runtime qualification remains pending):

```sh
./gradlew :app:assembleStandaloneDebug :app:assembleParasiteDebug
./gradlew :app:testStandaloneDebugUnitTest :app:testParasiteDebugUnitTest
./gradlew :app:assembleStandaloneRelease :app:assembleParasiteRelease
```

- Move `META-INF/xposed`, scope, hooks, host reflection, probe code and libxposed
  `compileOnly` dependency to the parasite source set/configuration. The standalone
  artifact must not register a module or require any host class at startup.
- Move standalone Cookie storage, NetEase interceptors/crypto and standalone reporting
  dependencies to its source set. Parasite must not fall back to these when the host
  session or an operation fails. Shared QQ/AMLL/artwork/media paths remain shared.
- Keep Dagger and the existing ViewModel factory common. Use flavor-specific bindings
  and bootstrap code; do not restore a second Hilt frontend solely for standalone.
- Remove concrete host types from shared consumer contracts. Generalize session
  identity/generation/recovery and cancellation semantics without exposing credentials.
  The standalone implementation owns its Cookie; the parasite implementation owns
  only a reference to the official session, never a copied Cookie.
- Reuse existing repository APIs and DTOs where the contracts match. Where TV endpoint,
  payload, response or business semantics differ, use feature-local backend adapters
  producing the same frontend model. Selecting an OkHttp factory alone is insufficient.
- Isolate component attachment, context, WorkManager, foreground services, media buttons,
  notifications and lifecycle hooks behind narrow runtime bindings. Keep the shared
  Activity/service/frontend implementation; do not scatter flavor branches through UI.
- Split manifests, variant-specific dependencies, R8 rules, tests and native packaging
  as required. Verify merged manifests and APK contents, not just Gradle configuration.

## Baseline and Data Safety

At revision time, local `main` is `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29` and
the parasite checkpoint is `2afb0d06`. Recheck these refs before integration rather
than assuming `main` will stay unchanged.

The audit found host-specific references in shared network injection, account/login,
repositories, ViewModels and playback, plus removed standalone NCBL files/dependency.
Restore standalone behavior selectively from the baseline, retaining valid shared work
instead of reverting the branch or copying whole frontend trees.

- Standalone release keeps its original application ID, compatible signing identity,
  settings/database paths and existing user data. Test upgrades on disposable fixtures
  first; installing over the user's real standalone app requires explicit approval.
- Use a separate standalone debug test identity/storage by default to avoid replacing
  the existing app. Preserve the currently enabled parasite identity for AVD testing.
- Do not copy cookies, databases or media between flavors or between standalone and TV.
- Audit Room 17-to-current migration and WorkManager compatibility explicitly. Current
  parasite migration 18-to-19 clears legacy download URLs and changes pending states;
  this policy must not be applied blindly to the existing standalone database.
- Verify completed downloads, pending work, stored file references, playback queue,
  account preferences and caches survive the intended standalone upgrade. Do not use
  destructive migration, clear data or uninstall as an upgrade workaround.
- The user's real download has passed. Preserve that result and its file; do not repeat
  quota-consuming downloads without authorization to qualify a different variant.

## Revised Milestones

| Step | Work | Exit condition |
| --- | --- | --- |
| D0. Freeze and classify | Record this revision; classify existing shared/standalone/parasite changes and restore the ordinary probe-disabled AVD artifact | Original evidence retained; no main-only frontend fixes or unverified code represented as accepted |
| D1. Backend-neutral boundaries | Extract session/account/login/reporting and feature-specific API adapters; isolate runtime bootstrap and component operations | Shared frontend and ViewModels have no required host/framework implementation imports; existing host contract tests still pass |
| D2. Dual build skeleton | Add flavors, manifests, source sets, dependencies, resources, R8 rules and variant test wiring | Both debug APKs build; standalone launches without TV/LSPosed; parasite still injects into TV; package-content isolation verified |
| D3. Standalone restoration | Restore original WebView/manual Cookie login, session persistence, transport/signing/reporting and component startup through the shared contracts | Independent login/read/playback flow restored; upgrade/data-preservation tests pass; user performs required authorization |
| D4. Complete business migration | Continue the existing feature matrix against both backend implementations, including current comment work, uploads, download grants and reports | Same frontend contracts fulfilled without host-only endpoints leaking into standalone or standalone fallback in parasite; ledger records differences |
| D5. Paired regression | Build/test both variants; qualify lifecycle, background playback/downloads, session changes, UI consumption and permission/capability boundaries | Debug acceptance documented per variant; substitutes distinguished from real account/server evidence; unresolved capabilities remain explicit |
| D6. Release and merge readiness | Produce both minified release APKs; adapt CI artifact/signing/release paths and names; review against current `main` | Both release artifacts qualified, standalone upgrade safe, API 102 metadata confined to parasite, merge diff reviewed; no push/release/merge without authorization |

D1 and D2 may be split into smaller compilable commits. Existing verified host work is
not discarded or restarted. Further endpoint migration must use the dual-backend
boundary rather than adding new mandatory `Host*` dependencies to common consumers.

### Current Exit-Gate Summary (2026-10-03)

Historical checkpoints below record bounded evidence, not additional milestones.
The latest acceptance state is:

| Step | Established evidence | Remaining exit gates |
| --- | --- | --- |
| D1 | Shared consumers/graph contracts no longer require concrete host or framework implementations; both backend contract suites pass | Dependency-boundary exit condition met; feature/device parity is not inferred |
| D2 | Both debug artifacts build and execute separately; package isolation is verified; the production-ID standalone R8 client cold-starts with TV stopped, no LSPosed scope, no framework/module mappings and no host/libxposed classes or module metadata | Build-skeleton and independent-runtime exit condition met under the clarified process-level requirement; whole-device framework removal/another AVD is not required |
| D3 | Original standalone login controls, owned Cookie verification, transport/signing/reporting and isolated read/playback/recovery work; development-signed v17-to-v21 migration and production-signed original-ID preserving upgrade/runtime verified on the configured HyperOS 4; production certificate matches the previous release | Real authorization/expiry/account matrix; the cross-signature install uses the user's Core Patch configuration, not standard signature-check acceptance |
| D4 | Core feature adapters/session-owned actions have paired contracts; both R8 clients read distinct authenticated server records and exchange one native test text per direction with server-history/recipient proof; production-signed weekly-rank forced refresh fails offline and recovers online in both runtimes without changing their account/settings/queue stores; TV creates and ends a real Together room; standalone joins the user's iPhone-created room, follows a track change and matches the server's paused progress | TV-created invitation is rejected with HIGH_V_REJECTED; unscoped phone 9.6.05 native invitations fail, and one authorized passive diagnosis observes business code 491 without establishing its cause; parasite's cooperating-room acceptance and host selection remain unresolved; broader business/failure/account and upload/write coverage remain |
| D5 | Paired device substitutes and bounded minified navigation/playback/background/timer/notification paths verified; same-APK saved-task process recreation passes; stable-name R8 state Parcelables pass native Android cross-loader transfers in both directions; the user accepts the early pre-keepnames task limitation under the existing force-stop/restart update procedure only; module microphone and PiP helpers execute on HyperOS 4, including real fingerprint/match, immutable playback actions, continuous lyric frames and host-death cleanup; both production-signed runtimes pass actual native PiP play/pause/previous/next/expand/close clicks; LibraryPage wire identity now has a parasite-only fix and CI gate, with 54 native transfers and a controlled cross-R8 task restoring the detail/back stack/Podcasts tab | Complete paired lifecycle/permission/regression matrix; full cross-R8 page-state restoration, including the observed detail-scroll reset and absent mini-player after saved-task recovery; historical module-update executed-code mismatch, near-end playback failure and intermittent process-start timeout attribution remain unqualified |
| D6 | Both production R8 artifacts build, are signed with the matching production certificate and now execute on HyperOS 4; preserving standalone data and matching TV executed-code identity are verified; exact workflow preparation and real-SDK package gates pass | Remaining full paired runtime qualification and complete review against current main; no push/release/merge authorized |

Do not reopen usable cloud flows to fill unrelated gates, count substitute success as
server acceptance, or mark the overall goal complete while these exit gates remain.

### Merge Review Coverage (2026-10-02)

The review base is main `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`. The page and
runtime-carrier audit below was performed at `7327e7d2`; subsequent scoped reviews
and repairs are recorded through `d962a931` and the catalog-navigation follow-up
below. This reconciles earlier grouped coverage, including the `20ce19f1` handoff
review; it does not claim a fresh full-body audit of every current file. Source
review is separate from the remaining exit gates above. Resume open groups instead
of repeating qualified groups unless their source changes or a new failure is reproduced.

| Group | Bounded review evidence | Status |
| --- | --- | --- |
| Flavor/manifest/dependencies/R8, network providers and signing | Original standalone transport/codec restoration and host-only call boundaries reviewed; native R8 evidence is recorded below | Reviewed at source level; production upgrade/runtime gates remain separate |
| Flavor login/session boundaries | Full bodies of the six files named below reviewed at `d55c`, plus the 30-line HostSessionBridge contract; poller/login integration and changed standalone controller/store sections reviewed in the retry follow-up | Named source scope reviewed; closed retry/publication tests do not qualify real WebView/QR authorization, expiry/logout/account switching or all login dependencies |
| Bootstrap, ViewModel factory and navigation | Graph/context injection, 29 unscoped provider bindings and owner/key lifecycle reviewed; navigation body matches main except its comment | Reviewed at source level; factory lifecycle tests remain paired |
| Shared player, queues, persistence, history, cache and lyrics | Session/source ownership, invalidation, serialization, FM/intelligence, catalog/Home/library/search/podcast/recognition/wiki/message song handoff and owned offline-history policy, direct QQ/AMLL and resource handling reviewed; no new engine/layout repair | Listed paths reviewed at source level; full lifecycle, continuation and actual playback/reporting acceptance remain separate |
| Together session coordinator | Room/player generations, entire job-tree retirement, command suppression and captured request owner reviewed; original queue synchronization bodies retained | Reviewed at source level; real cooperating-account acceptance remains open |
| Database and download runtime | Room 17-to-21 additive migrations, flavor-specific ownership policy, legacy WorkSpec conversion/startup fence, owner-bound queue/worker, transfer/publication receipts, notifications and backend download semantics reviewed | Reviewed at source level; original-install upgrade and complete runtime acceptance remain separate |
| Catalog and comment consumers | Collection endpoint adapters, artist consumers and comment paging/reply ownership reviewed; album, playlist, both podcast and category-discovery ownership repairs are verified below; the seven complete catalog page/list/menu bodies named in the latest consumer review are now inspected | Listed consumer ownership and named page integration reviewed at source level; remaining catalog/UI integration and actual account/server matrices remain open |
| Changed DTOs and Retrofit declarations | Public source/download ownership fields, nullable comment/search/mutation responses, raw podcast pagination counts and session tags inspected; standalone-owned header/e_r restoration retained; the named dynamic transport adapter scope below is now reviewed | Named declaration/adapter source scope reviewed; complete Repository/page consumer integration and real business acceptance remain separate |
| Utility/context consumers | About/cache behavior and the unused legacy ShareViewModel retain baseline bodies; log sharing needs the host provider adaptation recorded below | Listed utility consumers reviewed at source level; native provider/permission evidence remains bounded |
| Active account/detail/rank/history reads | Required dynamic owner tuples, generation-owned rank cache and retained queue callbacks reviewed and covered by paired tests below; original page/control bodies preserved | Listed consumers reviewed at source level; real account/expiry and complete runtime acceptance remain separate |
| Production diagnostic entry selection | API/version/signature/process eligibility, app/probe carrier selection, receiver/worker opt-in guards and ordinary R8 manifest/receiver behavior inspected; stale current-schema Room diagnostic repaired below | Listed entry points qualified within the recorded source/package scope; this is not full probe/test or business acceptance |
| Business request adapters and remaining session ViewModels | User/Search/Recognition repositories and Search/Recognition/Social ViewModels reviewed; MeloX request, wiki, retained parser/report helpers and the account retry repair are recorded below | Listed source boundaries reviewed; full page integration and real business matrices remain separate |
| Diagnostic helper ownership | Retrofit/capability helpers now pin every request; account/cloud/work/foreground/download/storage/publication helpers and the closed DownloadWorkerFixture inspected | Listed helper source reviewed; native substitutes qualify only the recorded scenarios |
| Module microphone and PiP helpers | All nine helper source bodies, shared recognition capture/ViewModel and host PiP source reviewed; manifest/runtime registration, permission-result wiring, fingerprint asset interception and original PiP-render extraction hunks inspected | The later live OEM launch-confirmation timeout is fixed and verified below; existing permission, motion, Binder/action and host-death evidence stays bounded |
| Remaining page/runtime-carrier and test support changes | At `7327e7d2`, changed hunks in the 47 UI paths named below and five complete runtime/prototype files were reviewed; later account-intent/probe, Social/Library and six catalog-navigation-family repairs, with bounded FindMusic, Search, AccountHome, Podcast and Wiki native callbacks, are recorded below. Test audit covers 149 source scans, 31 full reads and nine targeted safety entries, plus the new repairs | Named source scopes reviewed; the 13 catalog-navigation entries have source/JVM guards and bounded native coverage, not complete page integration, unchanged-body or full runtime acceptance |

#### Reconciled Page and Runtime-Carrier Inventory

The original audit reported grouped names, not a per-path manifest. The following
47 paths expand those groups against the current diff; they are a reconciled
changed-hunk inventory, not new full-body review evidence. Prefix:
`app/src/main/java/com/ljyh/mei/ui/`.

```text
component/GlobalProfileAvatarButton.kt
component/player/FloatingLyricsPip.kt
component/player/OverlayState.kt
component/player/Player.kt
component/player/component/applemusic/AppleMusicPlayer.kt
component/player/component/classic/ClassicImmersiveLayout.kt
component/player/component/classic/ClassicPhoneLayout.kt
component/player/component/classic/ClassicTabletLayout.kt
component/player/overlay/CommonOverlayHandler.kt
component/player/overlay/PlayerOverlayHandler.kt
component/playlist/AddToPlaylistSheet.kt
local/AccountState.kt
navigation/MeiNavigation.kt
screen/about/AboutScreen.kt
screen/account/AccountHomeScreen.kt
screen/account/NeteaseLoginScreen.kt
screen/album/AlbumDetailScreen.kt
screen/artist/ArtistScreen.kt
screen/artist/ArtistSongsScreen.kt
screen/cloud/CloudMusicScreen.kt
screen/comment/CommentScreen.kt
screen/comment/component/FloorCommentItem.kt
screen/history/HistoryScreen.kt
screen/listentogether/ListenTogetherScreen.kt
screen/log/LogScreen.kt
screen/main/findmusic/FindMusicScreen.kt
screen/main/home/HomeScreen.kt
screen/main/library/LibraryScreen.kt
screen/main/library/component/LibraryMobileLayout.kt
screen/playlist/CommonSongListScreen.kt
screen/playlist/EveryDay.kt
screen/playlist/PlaylistScreen.kt
screen/playlist/component/PlaylistActionOverlay.kt
screen/playlist/component/PlaylistTrackList.kt
screen/playlist/component/StandaloneTrackActionOverlay.kt
screen/podcast/PodcastScreen.kt
screen/recognition/SongRecognitionScreen.kt
screen/search/SearchLandingScreen.kt
screen/search/SearchResultScreen.kt
screen/search/SearchScreen.kt
screen/setting/DownloadManageScreen.kt
screen/setting/GeneralSettings.kt
screen/setting/SettingScreen.kt
screen/setting/StorageManagementScreen.kt
screen/social/NeteaseShareSheet.kt
screen/social/SocialScreens.kt
screen/song/SongWikiScreen.kt
```

`screen/account/NeteaseLoginScreen.kt` is the removed common login file; its two
flavor implementations are not qualified by that deletion review. The current
72-path common UI diff also includes 17 separate ViewModel files, two paging
sources, three player-state files and three explicitly retained historical glass
files, covered by their separate scopes rather than this 47-path count.

The five full-body runtime/prototype files at `7327e7d2` have prefix
`app/src/parasite/java/com/ljyh/mei/parasite/`:

```text
MeiloXModule.kt
HostRuntimeProbe.kt
HostRuntimeProbeActivity.kt
HostRuntimeProbeService.kt
Pcm16Meter.kt
```

Later module/probe/service owner changes are covered by the `de14777e` checkpoint;
the Activity and meter did not change. The original dependency inspection read
HostIdentity, HostComponentMapping, HostPlaybackHooks and AppGraph fully, but only
selected ModuleContext/Work hook sections. It does not qualify all host SDK internals.
The 2026-10-03 follow-up reads complete ModuleContext, ModuleStorage,
HostWorkManager/HostWorkPolicy, HostWorkForeground/HostWorkForegroundPolicy,
HostAppComponentHooks, HostComponentRuntime (including HostLogShareFiles),
HostComponentMapping, HostMediaButtons, HostPlaybackHooks and HostRuntimeProbe,
plus common ComponentRuntime and StandaloneComponentRuntime. The changed
MainActivity initialization, graph binding, disposal and restoration sections are
reviewed separately. This closes the named context/Work/component source-review
gap, not all host SDK internals, full page integration or runtime acceptance.
The audit's retained account intents, logout and diagnostic offer findings have
their scoped repairs below. Social/Library navigation is repaired at `d962a931`;
AccountHome, FindMusic, SearchLanding, non-song SearchResult, Podcast list and SongWiki
follow-up repairs the other six audited families (13 entries) at bounded source/JVM
scope and adds native FindMusic callback cases. Later paired closed native checkpoints
qualify Search recommendation/four non-song results, AccountHome rankings/playlist,
Podcast's three list sections and SongWiki playlist/contribution callbacks. These are
the named families, not complete shared-page coverage or real account/expiry behavior.
No duplicate frontend tree or new page architecture is added.

#### Flavor Login Review Scope

The six full-body reads are limited to these files; they do not imply complete
host SDK, WebView, graph or platform behavior review:

```text
app/src/standalone/java/com/ljyh/mei/ui/screen/account/NeteaseLoginScreen.kt
app/src/parasite/java/com/ljyh/mei/ui/screen/account/NeteaseLoginScreen.kt
app/src/parasite/java/com/ljyh/mei/parasite/HostLoginController.kt
app/src/parasite/java/com/ljyh/mei/parasite/TvHostLoginBackend.kt
app/src/standalone/java/com/ljyh/mei/standalone/StandaloneTransport.kt
app/src/standalone/java/com/ljyh/mei/standalone/StandaloneSessionStore.kt
```

`app/src/parasite/java/com/ljyh/mei/parasite/HostSessionBridge.kt` is separately
reviewed as a 30-line forwarding contract. The subsequent retry correction reviews
the new WebCookieLoginPoller, its login-screen integration and changed standalone
controller/store sections, not every unchanged dependency or real
authentication transition. Exact code/test/runtime evidence remains separated below.

Twenty-nine explicitly selected baseline blobs are identical: AudioPlayer, StableDeckPlayer,
TenBandEqualizer, PlaybackBeatMeter, BeatNet analyzer/native weights, playback timer,
sleep timer/notification/sheet, system lyric publisher/access, QQ API/QRC/TTML parsers,
player background/lyric/slider/shader components, equalizer settings and recognition
fingerprint assets. AutoMix changes only the optional prepare flag used when retiring
authorization. This is source-preservation evidence, not audible-output, visual/motion
parity or attribution of the open near-end playback failure.

The download/upgrade review also runs 11 current Android fixture cases successfully
in 2.616s (`StandaloneDatabaseMigrationDeviceTest`,
`StandaloneDownloadRecoveryDeviceTest`). They use private v17 files/databases and
UUID-scoped delayed WorkSpecs, check rollback/reopen/idempotence/affinity/destinations,
and clean up their own fixtures. They do not transfer real songs, consume grants,
upgrade the original app or qualify production signing. Evidence is local at
`/tmp/meilox-merge-download-upgrade-device-2026-10-02.log`.

### Execution Priority Reset (2026-10-01)

- The user considers the current cloud flow usable. Stop repeated cloud-only
  investigation and do not resume the withdrawn private remote-history experiment.
  Residual cloud gates remain on final acceptance; they are not silently completed.
- Prioritize independent standalone startup, data-preserving standalone upgrade and
  paired minified release execution. Reopen a cloud issue only for a reproduced
  failure or a clearly identified mandatory acceptance gap, with bounded verification.
- Creating a temporary framework-free AVD and replacing the original standalone
  installation remain separate permission requests. Neither operation is performed
  while awaiting the user's reply. Keep the existing rooted AVD running.

### D1 Checkpoint: Shared Session and Reporting Contracts (2026-09-29)

- Extracted credential-free `SessionStore`, `SessionIdentity`, `SessionStamp` and
  account presentation into shared `data/session`. Repositories, ViewModels, playback,
  cache and download ownership now consume the common session type. The official QR
  adapter still owns host login; credentials are neither exported nor duplicated.
- Introduced `PlaybackReportSink`. Actual playback event timing remains shared while
  the host adapter still owns SDK metadata, signing and delivery. Current Dagger
  bindings supply the same session instance to account, network, playback and reporting;
  a device graph test checks that identity rather than assuming matching types suffice.
- Adapted the existing comment work to these contracts without new controls or page
  changes. Read-only host checks displayed recommend/hot/time first pages and an
  expanded three-reply thread through the original screen. See API-018 in the interface
  ledger for payload, pagination, ownership and acceptance limits. No comment write,
  account mutation or download grant was issued for this checkpoint.
- 641 JVM tests pass, including backend-independent session, account and reporting
  tests. All 43 parasite device tests pass, including the shared graph identity check;
  these are not standalone or dual-flavor acceptance. Proposed frontend retry controls
  remain withdrawn. No frontend bug inherited from `main` was changed.
- `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` and `assembleRelease`
  pass. The unsigned R8 APK passes `zipalign -c -P 16 4`; its mapping retains session
  parameters on both comment methods and the module entry class. This is package/build
  evidence only, not release runtime acceptance.
- Reinstalled the ordinary debug and instrumentation APKs; verified APP=true and all
  HOST/RUNTIME/WORK probes=false. After force-stop, the resolved original TV launcher
  cold start completes and displays the original portrait Home, account avatar and
  paused mini-player. Host logs confirm API 102, official session/request/report
  bindings, isolated WorkManager and one music-service session. The current process
  has no crash-buffer entries. The existing real media row remains published and
  TV-owned at 22,705,573 bytes; no new authorization grant was requested.

This was the first D1 increment, not its exit condition. At that point, shared
Activity/service attachment, login content, network injection, notifications/WorkManager
and graph bootstrap still referenced concrete host implementations.

### D1 Checkpoint: Component and Transport Bindings (2026-09-29)

- Added `ComponentRuntime` for component Context attachment, Activity initialization,
  media-button startup ownership, platform session binding and service diagnostics.
  Shared `MainActivity` and `MusicService` no longer reference `Host*` implementations
  or parasite build flags. `HostComponentRuntime` retains the existing host-only
  wrapping/orientation and restore-before-media-key behavior without changing layouts,
  player state serialization, controls or lifecycle order.
- Shared Retrofit providers now accept named `Call.Factory` bindings for NetEase API,
  WeAPI and audio matching. Parasite selects the same official factory for all three;
  standalone can supply its original separate transports. These bindings alone do not
  resolve endpoint/response differences or qualify standalone requests.
- Shared download notifications no longer explicitly call host routing or depend on a
  host notification-ID policy. Their progress ID remains unchanged. Runtime-owned
  PendingIntent hooks perform carrier routing, as they already do for Media3; the host
  foreground adapter owns probe selection and dispatcher completion callbacks.
- 642 JVM tests and all 46 parasite device tests pass. New cases check independent
  request factories, graph runtime identity, host/foreign Context boundaries, disabled
  behavior and Activity orientation. Actual platform MediaSessions exercise adapter
  binding, replacement disposal and delivery; this is not audible-output evidence.
- In-host synthetic DENIED work starts naturally after its configured delay, promotes
  the foreground carrier, completes one substitute authorization call with zero resource
  transfers, and leaves FAILED work/task rows with no artifact. The resulting notification
  opens the original portrait MeiloX Home. Its private records/channel are cleaned up;
  the real account, queue and downloaded song are not qualification substitutes.
- Debug, instrumentation and unsigned R8 builds pass; release 16 KB alignment passes.
  The final ordinary debug package has APP=true and HOST/RUNTIME/WORK probes=false.
  Its original launcher cold start restores portrait Home and the account/player;
  one module music service is reported and the current process has no crash-buffer
  entries. A system media PLAY then PAUSE advances the active module session from
  47,014 ms to 74,255 ms and leaves it paused; the official session stays inactive.
  This proves live control/progress, not audible output or server listening statistics.
  The pre-existing real media row remains published, TV-owned and 22,705,573 bytes.

Local-only evidence: `/tmp/meilox-runtime-bindings-build.log`,
`/tmp/meilox-runtime-bindings-device.log`, `/tmp/meilox-runtime-bindings-final-build.log`,
`/tmp/meilox-runtime-notification.png`, `/tmp/meilox-runtime-notification-entry.png`
and `/tmp/meilox-runtime-final-home.png`. No logs, screenshots or APKs are committed.

D1 remained partial at this checkpoint. Graph/bootstrap accessors, backend-specific
login content and host qualification fixtures still needed flavor-owned source boundaries.

### D2 Checkpoint: Parasite Source-Set Isolation (2026-09-30)

- Added the `runtime` dimension and the `parasite` flavor, preserving its installed
  application ID. Host hooks, bridges, module Context/storage, QR login implementation,
  qualification fixtures and `META-INF/xposed` now belong to `src/parasite`; host tests
  belong to `testParasite`/`androidTestParasite`. API 102 is `parasiteCompileOnly`.
  Existing host behavior and the QR page were moved without modification.
- Shared graph construction uses flavor-owned `RuntimeBackendModule` and
  `RuntimeComponent`. Concrete host bootstrap accessors no longer appear in common
  graph declarations. Common Activity, service, navigation, ViewModels and resources
  remain single-source; no UI layout or navigation change is part of this increment.
- Preserved the original interceptor, headers, RSA/EAPI/WeAPI signing and Chinese-IP
  helper under `src/standalone`. Shared media-ID mapping, random MAC generation and QQ
  crypto remain shared. Bounded playback-response reading is common and tested without
  constructing either backend. Standalone interceptor tests were retained in
  `testStandalone`, not deleted or counted as currently executed tests.
- Separated the manifests and R8 rules. The module no longer registers its own music
  Activity/service; existing hooks still instantiate the common classes using official
  host carriers. Original standalone component/launcher declarations are preserved in
  its manifest. The parasite 18-to-19 download migration is now flavor-owned with the
  same SQL; standalone upgrade policy must be supplied and tested independently.
- Current commands are `:app:testParasiteDebugUnitTest`, `:app:assembleParasiteDebug`,
  `:app:assembleParasiteDebugAndroidTest` and `:app:assembleParasiteRelease`. APKs now
  reside under `app/build/outputs/apk/parasite/{debug,release}`. The existing CI build,
  signing directory and metadata path use the parasite target; push/manual-release
  semantics are unchanged. No remote run was triggered; paired CI remains D6 work.
- 642 JVM tests pass in 83 suites. Five new common bounded-body tests replace the five
  interceptor-specific tests in this variant's count; the latter remain pending under
  `testStandalone`. All 49 parasite device tests pass, including three new installed-APK
  checks for absent standalone components/signing classes and retained TV-only Xposed
  metadata. Existing isolated Room migration tests still pass. These tests do not
  qualify standalone upgrades, real download grants or host-process playback.
- Debug/instrumentation and unsigned R8 builds pass; release 16 KB alignment passes.
  The release mapping retains the module entry and shared component classes even
  though they are no longer registered in the module manifest. Installed ordinary
  debug flags remain APP=true, HOST/RUNTIME/WORK=false. The resolved original TV launcher
  cold-starts the portrait MeiloX Home, restores its account/avatar and paused queue,
  and creates one module music-service session. No current-process crash is recorded.
- A system PLAY then PAUSE advances the active module session from 74,257 ms to
  94,809 ms and leaves it paused; the official session remains inactive. This is live
  routing/progress evidence, not audible-output or server-statistics acceptance.
  The existing real media row stays published, TV-owned and 22,705,573 bytes; no real
  download authorization, logout or account mutation was requested.

Local-only evidence: `/tmp/meilox-source-boundary-build.log`,
`/tmp/meilox-source-boundary-release.log`, `/tmp/meilox-source-boundary-device.log`
and `/tmp/meilox-source-boundary-home.png`. No generated artifacts are committed.

At this checkpoint the standalone source directory was not yet registered/buildable.
The following increment adds its initial executable backend. D1 feature-specific request
semantics remain under audit; factory selection alone is not dual-backend parity.

### D2/D3 Checkpoint: Paired Debug Bootstrap and Owned Cookie Session (2026-09-30)

- Both `standaloneDebug` and `parasiteDebug` now build from the same shared frontend,
  player, resources, navigation and ViewModel graph. Standalone debug uses
  `com.neoruaa.meilox.standalone.debug`, with the launcher label `MeiloX Standalone Debug`.
  Standalone release retains `com.neoruaa.meilox` but is **not qualified for installation
  over existing data**. Parasite retains its existing installed module identity.
- A flavor-owned Application bootstrap initializes the standalone graph and loads its
  own existing `settings` DataStore keys. The module Application does not start a music
  runtime; only its verified official Application hook does that. Standalone uses normal
  registered Activity/service components without module Contexts or carrier routing.
- Restored the baseline WebView/manual Cookie page without changing layout or navigation.
  Only the ViewModel/backend bindings and success handling change. A detected WebView
  Cookie is verified before the page reports success. At this initial checkpoint the
  polling loop suppressed an unchanged rejected value permanently; the bounded retry
  correction below fixes that source-adaptation gap. No credential is copied from TV
  or the original standalone installation into the debug app.
- `StandaloneSessionStore` owns credentials and persists successful login atomically.
  Saved Cookie/UserId pairs are not treated as proof of identity: startup verifies the
  saved Cookie privately before publishing an authenticated session. Candidate login
  never temporarily overwrites the active Cookie. Generations invalidate old requests,
  latest-attempt tokens reject late login results, and logout fences publication while
  clearing preferences and the standalone WebView session. Failed/canceled verification
  preserves the previous live account. Real logout/account switching remains untested.
- `SessionCallFactory` captures ownership at creation, checks dispatch/callback/body reads,
  cancels in-flight work on invalidation and keeps clones on their original owner. The
  standalone interceptor reads only captured credentials, not the global Cookie preference.
  Original EAPI/WeAPI/API signing is restored with default TLS verification; the old debug
  trust-all TLS setup is not restored. Signed business redirects are disabled to avoid
  forwarding a captured Cookie to a different origin. Audio matching retains a separate
  plain Android-UA transport. No standalone transport is used by parasite.
- Standalone weblog `startplay`/`play` delivery uses actual shared playback events and the
  original signing profile, with explicit owner tags. **NCBL remains to be restored**;
  this is not full standalone reporting parity or listening-statistics acceptance.
- Standalone Room migration currently adds ownership columns while preserving legacy
  URLs/statuses/rows. This does not yet adapt old WorkManager inputs or associate legacy
  work with verified ownership. Pending-work conversion, completed file references,
  queue/cache upgrade and foreground-work semantics remain mandatory D3 work. Do not
  install this checkpoint over the real standalone app as an upgrade test.
- Both debug/instrumentation builds pass. Standalone has 555 JVM tests across 71 suites;
  parasite has 654 across 84 suites, with zero failures/skips. These totals include common
  tests in each variant, not 1,209 distinct tests. New coverage includes private candidate
  verification, late login/logout races, canceled/persistence-failed login, same-account
  renewal, request/body invalidation, clone ownership and actual signed wire fields.
  Original standalone interceptor tests are now executed again.
- Three standalone package/graph device tests and all 49 parasite device tests pass.
  The standalone APK has its own launcher/service, no Xposed metadata or host adapter
  classes, and initializes its graph without host bootstrap. With TV force-stopped, it
  cold-launches an anonymous Home with live content/images, then opens the original
  WebView login and manual Cookie sheet. LSPosed remains enabled on this AVD; a separate
  framework-free device run has not been performed. No standalone account was authorized
  automatically. The user has been asked to complete its login locally.
- The updated parasite package still cold-launches the original portrait Home with the
  official account/avatar and paused queue; no current-process crash is recorded.
  The original standalone package's install/update timestamps remain unchanged, and the
  existing real TV download remains published, TV-owned and 22,705,573 bytes. No real
  download, upload, social write, logout or account switch was executed.

Local-only evidence: `/tmp/meilox-paired-bootstrap-build.log`,
`/tmp/meilox-standalone-restoration-build.log`, `/tmp/meilox-standalone-signing-build.log`,
`/tmp/meilox-standalone-package-device.log`, `/tmp/meilox-paired-parasite-device.log`,
`/tmp/meilox-standalone-first-home.png`, `/tmp/meilox-standalone-account.png`,
`/tmp/meilox-standalone-cookie-sheet.png` and `/tmp/meilox-paired-parasite-home.png`.
The installed standalone smoke-test artifact predates only the default-preserving
device-ID-provider testability change in `NeteaseInterceptor`; the final source rebuild
and five wire-signing tests pass. Reinstall after the user's login interaction, not while
they are entering credentials. Signed paired releases and remote CI remain unqualified.

### D3 Checkpoint: Standalone Reporting Restoration (2026-09-30)

- Restored the original NCBL v3 codec, payload builder, immutable metadata and upload
  client into `src/standalone`. The codec and payload are unchanged from the recorded
  `main` baseline; its Zstd AAR/JVM test dependency is standalone-only. This supersedes
  the previous checkpoint's missing-NCBL implementation item, not its runtime or
  server acceptance limits.
- Shared playback captures title, artist, duration and exact millisecond start time
  once. The reporting contract now supports suspension, so standalone network calls
  can be canceled and queued start/end events still serialize. Local details do not
  enter parasite SDK fields; its native builders and delivery path remain unchanged.
- Standalone weblog and NCBL are independent channels. Failure in either does not
  suppress the other or prevent the matching end event. NCBL start/end retain the
  same session ID, source, device and credentials, keyed by account generation, song
  and exact start time. Stale authorization clears old contexts; abandoned contexts
  are bounded, and cancellation releases a pending start context. Active seconds
  retain the common timer's truncation and exclusion of pause/buffering time.
- NCBL captures credentials from the verified standalone session, never from TV or
  a fresh global Cookie read at upload time. Dispatch, response/body consumption and
  result publication are owner-checked. Its transport restricts the upload endpoint,
  requires matching credentials, disables redirects and implicit connection retries,
  and cancels in-flight calls on invalidation. File acceptance requires HTTP/business
  success and the exact submitted filename in `successfiles`; it is not a listening
  statistics receipt.
- Both debug and instrumentation APKs build. All 578 standalone JVM tests in 76 suites
  and 656 parasite JVM tests in 84 suites pass, with no failures/skips (common tests
  are counted twice). Coverage includes codec golden vectors/Zstd round trips,
  bounded response parsing, channel independence, metadata capture, same-song starts
  within one second, invalidation during context creation, queued event cancellation,
  both underlying transport cancellations and unchanged official SDK field keys.
- APK/DEX inspection confirms NCBL/Zstd are present only in standalone; parasite keeps
  its TV-only Xposed metadata and standalone has none. Both debug APKs pass 16 KB ZIP
  alignment; the restored arm64 Zstd library's LOAD segments use `0x4000` alignment.
  These static checks do not prove native loading on the AVD. A no-network standalone
  instrumentation codec smoke test is compiled but has **not been executed**.
- Both minified unsigned release APKs build with their intended production package IDs.
  Each passes 16 KB ZIP alignment; Zstd remains standalone-only and Xposed metadata
  remains parasite-only. R8 retains the reachable standalone reporter/codec and Zstd's
  JNI names, plus the parasite module entry. This is the first paired release build
  checkpoint, not signed-artifact, installation, runtime or upgrade qualification.
- No AVD install, instrumentation, screen change or account interaction was performed
  for this checkpoint while the user's standalone login response is pending. Installed
  artifacts therefore predate this restoration. Real standalone authentication,
  authenticated playback, both report channels on device, account changes, full-song
  playback and server listening history/statistics remain unaccepted. Legacy standalone
  upgrade/work compatibility also remains mandatory; do not install release over the
  user's original app.

Local-only build evidence: `/tmp/meilox-dual-reporting-build.log`,
`/tmp/meilox-dual-reporting-final-build.log` and `/tmp/meilox-dual-reporting-release.log`.
No generated artifact or device data is
committed. This is a D3 code/package checkpoint, not D3 or D5 completion.

### D3 Audit: Legacy Standalone Download Work (2026-09-30)

- The baseline `main` worker persisted `song_ids_json`, `playlist_name` and
  `download_path` in WorkManager inputs. Download rows had a playback URL but no
  account/request ownership. The current worker expects `song_id` and `owner_id`;
  merely retaining Room rows cannot make those existing work requests executable.
- Destination metadata must survive conversion, completed local/MediaStore references
  must remain intact, and legacy work must not be silently assigned to a newly logged-in
  account. An unverified stored user ID is affinity evidence, not authorization.
- Standalone's original playback-URL download behavior differs from the module's
  dedicated download-grant endpoint, which may affect quota. The user has been asked
  whether standalone should preserve the original behavior or adopt that new endpoint.
  No default has been silently selected and no real grant/download was requested.
- `PlaybackPersistence.kt` is unchanged from the recorded `main` baseline, including
  snapshot/checkpoint formats and settings-store keys. That source comparison does
  not qualify persisted queue restoration or media-cache compatibility on upgrade.
  Worker conversion, cache ownership and full Room 17-to-current fixture execution
  remain open. See ABI-010 in the interface ledger.

### D6 Checkpoint: Paired CI Artifacts and Signing (2026-09-30)

- CI now runs both JVM test targets and minified release targets using the repository's
  Gradle wrapper. It checks each output-metadata file for its intended production
  application ID, variant, one unsplit APK, valid version and existing file; both
  version names/codes must agree before signing.
- Preserved the `release` environment and original `SIGNING_KEY`, `ALIAS`,
  `KEY_STORE_PASSWORD` and optional `KEY_PASSWORD` secret bindings. SDK `apksigner`
  signs each runtime explicitly, with passwords passed through environment references.
  The decoded keystore is private and removed on successful or failed signing. No
  production secret or original app signing key was accessed during local testing.
- Updated obsolete checkout/Java action runtimes and replaced the old Gradle build
  action with setup plus an explicit wrapper command. The selected official actions
  declare Node 24 ([checkout](https://github.com/actions/checkout/blob/v7/action.yml),
  [Java](https://github.com/actions/setup-java/blob/v6/action.yml),
  [Gradle](https://github.com/gradle/actions/blob/v6/setup-gradle/action.yml)). Gradle
  caching uses its basic provider; build-scan publication is not enabled. Android
  platform/build-tools 37 are installed explicitly for compilation and verification.
- Before upload, both signed files must pass SDK signature verification, 16 KB ZIP
  alignment and actual manifest package/version checks. Artifacts are named
  `MeiloX-standalone-<version>-<run>` and `MeiloX-parasite-<version>-<run>`, containing
  `MeiloX-standalone.apk` and `MeiloX-parasite.apk` respectively. The ambiguous
  `app-release.apk` asset is replaced by those two explicit release assets.
- Push still only builds/uploads artifacts; only `workflow_dispatch` creates the
  version tag and release, after the paired build succeeds. Release name/date, notes
  input/fallback, prerelease detection and permission separation remain unchanged.
  The app's existing update checker opens the release page and does not select or
  automatically install the first APK, so no frontend change is needed for this split.
- Local verification passes: actionlint 1.7.12, shell syntax checks, workflow wiring,
  13 metadata cases, 9 signed-preparation cases and 4 signing/cleanup cases. The same
  workflow shell blocks also sign the actual paired release APKs with a temporary
  fixture certificate, verify their identity/version/alignment, and reject swapped
  or unsigned pairs. All fixture keys/APKs are removed afterward. This proves local
  packaging logic, not hosted-runner execution, production signing compatibility,
  installation, automatic updates or application runtime acceptance.

Reproduce the workflow checks locally with:

```sh
ruby .github/tests/dual_runtime_release_test.rb
ANDROID_HOME=<sdk-path> ruby .github/tests/dual_runtime_release_test.rb --built-apks
```

The second command requires the two built unsigned release APKs and SDK build-tools
37.0.0; it uses a disposable test key and never installs or uploads the outputs.
Local evidence: `/tmp/meilox-dual-release-workflow-final.log` and
`/tmp/meilox-dual-release-fixtures-final.log`. No remote workflow, push, tag, release
or merge was triggered. D6 remains incomplete until runtime, upgrade and merge-readiness
gates are qualified; this checkpoint changes only CI and documentation.

### D6 Checkpoint: Hosted Runner SDK Initialization (2026-10-03)

- The user-reported [Actions job](https://github.com/NEORUAA/MeiloX/actions/runs/37089492500/job/111106585328)
  stopped before Gradle with `sdkmanager: command not found` (exit 127). That run
  did not build, sign or upload either APK.
- Added explicit [Android SDK setup](https://github.com/android-actions/setup-android)
  after Java initialization and before installing platform/build-tools 37. APK
  preparation resolves `apkanalyzer` from the configured PATH instead of assuming
  a `cmdline-tools/latest` directory. Triggers, signing identities, release
  permissions and the four paired Gradle targets are unchanged.
- Local Ruby/shell syntax and workflow fixtures pass, including setup ordering,
  versioned-only command-line tools with spaces in paths, and rejection when the
  analyzer is absent from PATH. The existing two release APKs also pass
  `dual_runtime_release_test.rb --built-apks` with the real SDK and a disposable
  fixture signing key; temporary keys/APKs are removed.
- No app source/UI, installation, device state or production credentials changed.
  No push, remote retry, dispatch, tag or release was performed. This is local
  configuration/package evidence, not a successful hosted-runner rerun or D6
  completion.

### D6 Checkpoint: Published API 37 Platform Identifier (2026-10-03)

- The next user-reported [Actions job](https://github.com/NEORUAA/MeiloX/actions/runs/37092493982/job/111115595221)
  initializes the SDK successfully but stops before Gradle with
  `Failed to find package 'platforms;android-37'` (exit 1). The deprecation warning
  is not this failure's cause; neither APK was built or signed by that run.
- Google's [SDK package catalog](https://dl.google.com/android/repository/repository2-3.xml)
  publishes the stable platform as `platforms;android-37.0` (revision 2,
  channel 0), matching the existing local SDK. Corrected only that installation
  identifier; compile/target API 37 and build-tools 37.0.0 remain unchanged.
- Earlier fixtures and existing-APK checks did not exercise a fresh SDK download.
  This time the exact workflow install command passes in a new temporary SDK
  using setup-android v4's default command-line tools 22.0. Both installed package
  metadata records, the platform `android.jar` and executable `apksigner` are
  checked. The downloaded command-line tools match Google's catalog checksum;
  the temporary SDK is removed without modifying the project SDK or AVD.
- The regression contract now requires the published identifier; real-APK checks
  also inspect the installed stable platform's package identity and API metadata,
  rejecting previews or renamed directories. Local Ruby/shell syntax, workflow
  fixtures and the existing unsigned pair's real-SDK disposable-signing checks
  pass. No production credentials, app source/UI or device state changed.
- No push, remote retry/dispatch, release or merge was performed. A successful
  hosted-runner build is still unverified; this fixes the reported prerequisite,
  not the remaining D6 runtime/merge gates.

### D6 Checkpoint: Bounded CI Compiler Memory (2026-10-03)

- The user-reported [Actions job](https://github.com/NEORUAA/MeiloX/actions/runs/37095107054/job/111123322872)
  passes SDK setup and installation, then fails after 14m57s with
  `OutOfMemoryError: GC overhead limit exceeded`. The full job log reports failures
  in standalone Debug, parasite Debug and parasite Release Kotlin compilation,
  not only the parasite Release exception quoted by the user. Signing, artifact
  upload and release are skipped. The later checkout submodule-cleanup warning is
  separate from this compilation failure.
- The unchanged project settings give Gradle a 2048 MiB heap and do not explicitly
  configure the Kotlin daemon. [Kotlin's documented inheritance](https://kotlinlang.org/docs/gradle-compilation-and-caches.html#gradle-daemon-arguments-inheritance)
  allows that heap limit to apply to the Kotlin compiler as well. The OOM and
  overlapping variant compiles motivate explicit heaps and worker limits; the
  remote log does not directly dump daemon JVM arguments or total RSS.
- Set CI-only command-line overrides: Gradle and Kotlin each receive `-Xmx4g`,
  `--max-workers=1 --no-parallel` prevents overlapping compilation workers, and
  `--no-daemon` confines the Gradle daemon to this invocation. Disable Kotlin's
  [in-process fallback](https://kotlinlang.org/docs/compiler-execution-strategy.html#fallback-strategy)
  so a daemon failure is reported rather than unexpectedly compiling in Gradle's
  heap. Keep all four paired test/release targets in one fail-fast invocation.
  The two heap ceilings total 8 GiB; they are not a total-process memory measurement.
  Project-wide developer defaults, SDK/API levels, signing, permissions and
  artifact/release gates remain unchanged.
- The workflow contract parses shell arguments with `Shellwords`. A disposable
  wrapper executes the actual YAML shell command, verifies intact quoted Gradle
  JVM arguments and all paired targets, and propagates both success and compilation
  failure exit codes. It never launches an app, installs an APK or accesses signing
  credentials. Ruby/shell syntax and these workflow fixtures pass.
- Local full-build verification uses the actual YAML command plus test-only
  `--rerun-tasks --no-build-cache --console=plain --info`. The real Gradle and
  Kotlin daemon command lines show `-Xmx4g`, and Gradle reports one worker lease.
  Both Debug test suites are freshly executed: standalone 1083 cases/112 suites,
  parasite 1096 cases/116 suites, with zero failures, errors or skips.
  All 228 XML reports are newer than this run's start. Both Release APKs, including
  Kotlin compilation, R8 and vital lint, build successfully in 6m48s, with all 163
  actionable tasks executed. No OOM or in-process fallback is reported.
- The newly produced pair also passes `dual_runtime_release_test.rb --built-apks`:
  workflow fixtures plus real-SDK temporary-key signing, package/version identity,
  helper/module isolation, stable Parcelable declarations, 16 KB alignment and
  swapped/unsigned/missing-scope rejection. Temporary fixture keys/APKs are removed.
  Private evidence stays in `/tmp/meilox-ci-memory-paired-build-20261003.log` and
  `/tmp/meilox-ci-memory-release-gates-20261003.log`, outside Git. This is a local
  macOS/JDK 21 rebuild, not an Ubuntu hosted-runner memory or release acceptance.
- No app source/UI, local `gradle.properties`, AVD state or production credentials
  change. No push, remote retry/dispatch, tag, release or merge is performed. Local
  verification does not establish a successful hosted-runner rerun or complete D6.

### D1/D4 Checkpoint: Runtime-Owned Playlist Collection (2026-09-30)

- Extracted playlist collection/uncollection into `PlaylistCollectionBackend`. Shared
  Repository, ViewModel, navigation and screens remain single-source. Flavor-owned
  adapters choose only the endpoint and security payload; shared validation accepts
  business code 200 and rejects invalid IDs, guests and stale session generations.
- Standalone restores the original EAPI subscription routes and signing controls from
  the recorded `main` baseline. Parasite retains TV multi-terminal routes and official
  token generation. Both carry their own session tag; neither backend retries through
  the other. The original standalone transport's constant compatibility token is not
  moved into common Repository code or the host adapter. See API-007 in the ledger.
- Shared tests continue to cover collection rollback, serialization and current-account
  Library refresh. New substitute tests inspect standalone encrypted wire requests,
  both action payloads and Cookie ownership, error codes, stale dispatch and late
  responses. Existing host request-policy tests now exercise the selected host adapter.
- The audit also found shared TV-only album/artist collection-state reads. Their
  standalone equivalents remain open; neither successful paired builds nor this
  narrower collection-write checkpoint qualifies them. No UI cleanup is bundled here.
- Verification: 585 standalone tests in 77 suites and 659 parasite tests in 84 suites
  pass with no failures/errors/skips (shared tests execute in both variants). Both
  debug, instrumentation and minified unsigned release APKs build. Generated Dagger
  graphs select the matching adapters; actual release DEX retains the body/session-tag
  annotations and generic response signatures for both methods. Each release contains
  its own collection route and not the other runtime's route; both pass 16 KB ZIP
  alignment. `git diff --check` passes.
- No AVD install, instrumentation run, account mutation or download was performed in
  this increment. Standalone login remains awaiting user confirmation. The new release
  packages are build/package evidence only, not device or real-server acceptance.
  Local logs: `/tmp/meilox-dual-playlist-collections-build.log` and
  `/tmp/meilox-dual-playlist-collections-release.log`; neither is committed.

### D1/D4 Checkpoint: Runtime-Owned Album and Artist Collections (2026-09-30)

- Added `CatalogCollectionBackend` for album state, artist state and artist follow
  mutations. Shared Repository/ViewModel contracts and all frontend files remain
  unchanged apart from Repository injection/delegation. TV collection endpoints and
  response DTOs move to parasite-only sources without changing their wire contracts.
- Standalone album state uses its existing Cookie-authenticated `album/sublist`
  contract with complete, cancellable pagination rather than an unscoped legacy
  table. It checks page shape/progress and cannot turn a missing cursor or a partial
  list into an uncollected result. Standalone artist state reads the artist flag
  already modeled in the existing `v1/artist/{id}` response, not the associated
  user's follow flag. Both paths reject unknown or stale state.
- Standalone artist mutations use the original primary WeAPI route and both original
  ID fields. Uncertain/rejected writes are not retried through another protocol by
  the adapter. Parasite keeps its exact official sub/unsub payloads and does not fall
  back to Cookie transport. API-006/API-014 record these differences and evidence limits.
- Shared tests exercise delegation, guest/invalid/stale rejection and late-result
  ownership. Flavor tests exercise actual Retrofit mapping, standalone signing/Cookie
  ownership, pagination, malformed/error responses and the host's unchanged schemas.
  No account mutation, quota request or AVD interaction is part of this increment.
- Verification: 598 standalone tests in 78 suites and 668 parasite tests in 85 suites
  pass without failures/errors/skips; shared cases run in both. Both debug,
  instrumentation and unsigned minified release builds pass. Generated Dagger graphs
  select the intended implementations; debug APKs contain only their runtime-specific
  collection classes. Release DEX checks confirm isolated TV/WeAPI routes, retained
  Retrofit method/parameter annotations and generic signatures, and preserved nullable
  projection fields/list element types. Both release APKs pass 16 KB ZIP alignment.
- `git diff --check` passes. Local evidence is in
  `/tmp/meilox-dual-catalog-collections-build.log` and
  `/tmp/meilox-dual-catalog-collections-release.log`; no generated artifacts are
  committed. No AVD install or instrumentation run was performed while standalone
  login confirmation remains pending. Live standalone collection reads, mutation
  acceptance and release runtime qualification remain open, as do other D4 features.

### D1/D4 Checkpoint: Runtime-Owned Song Favorites and Playlist Tracks (2026-09-30)

- Added `SongFavoritesBackend` and `PlaylistTracksBackend`. Shared Repositories retain
  identity/session validation, cancellation and existing caller-facing results; each
  flavor owns its request routes, DTOs and favorite response interpretation. Existing
  player, playlist, picker, Library, navigation and resources are not modified.
- Standalone restores the original single-song liked query, legacy radio-like payload
  and playlist track endpoint with `imme=true`. Parasite retains official ordinary-song
  favorites, full liked snapshots and the TV track endpoint with add-only `reverse`.
  Security/Cookie ownership stays in the matching transport. API-009/API-013 document
  the legacy compatibility fields and the limits of these source-confirmed contracts.
- Only parasite reconciles favorite 502/404 results with an authoritative state read.
  Standalone rejects failed/unknown mutations; neither backend repeats an uncertain
  write through another route. Shared song-ID normalization and before/after session
  checks prevent invalid dispatch and old-account result publication.
- Verification: 608 standalone tests in 79 suites and 678 parasite tests in 86 suites
  pass without failures/errors/skips; common tests execute in both variants. Both
  debug APKs and instrumentation APKs build. New wire substitutes inspect both routes,
  payloads, private Cookie ownership and error/partial results. Existing player and
  playlist state tests continue to cover rollback, serialization and refresh behavior.
- Both unsigned minified release APKs build. Generated Dagger graphs select the matching
  backend implementations. Actual release DEX retains Retrofit body/session annotations,
  generic response signatures and nullable DTO fields/list element types; each artifact
  contains only its own favorite/track mutation routes. Both pass 16 KB ZIP alignment.
  `git diff --check` passes. These are package checks, not release device execution.
- No AVD install, instrumentation run, real account mutation or download grant is part
  of this increment. Standalone login confirmation is still pending. Build/substitute
  evidence does not qualify live server acceptance, release runtime or all D4 features.
  Local build logs: `/tmp/meilox-dual-song-mutations-build.log` and
  `/tmp/meilox-dual-song-mutations-release.log`; generated artifacts and device/private
  data are not committed.

### D1/D4 Checkpoint: Runtime-Owned Cloud File Transfer (2026-09-30)

- Removed the shared direct-NOS client. `CloudBinaryUploader` selects the standalone
  NOS implementation or a parasite bridge to the pinned official upload SDK. Shared
  business orchestration uses each flavor's existing authenticated transport; it never
  borrows the other flavor's credentials/client. No frontend source is changed.
- All upload phases now share a captured owner. Canceled/stale requests and rejected
  check/allocation/registration/publication responses stop the remaining sequence.
  Byte transfer alone cannot report completion. A private, cancellable snapshot keeps
  file size/digest/metadata/bytes consistent without modifying the source URI; ordinary
  exits delete it and later initialization cleans only owned process-death leftovers.
- TV source and APK DEX confirm the generic NOS uploader, setters, builder and callback
  signatures. Its high-level cloud allocation/refresh helpers are stubs, so the existing
  business authorization is supplied through a Java callback. Token refresh is not
  invented or implemented as a transport/account fallback. See API-019 for the exact
  contract and the correction to the earlier broad binary-upload rejection claim.
- Standalone retains its NOS route with bounded lookup, HTTPS destination validation,
  no account-Cookie forwarding/redirect/retry, streamed file-integrity checks and current-call
  cancellation. Tests caught an IOException replacing coroutine cancellation; both
  runtime bridges now recheck their owner/job before propagating a transport failure.
  A bounded standalone NOS receipt must acknowledge the complete file length before
  metadata registration, not merely return HTTP 200.
- Verification: 627 standalone tests in 81 suites and 692 parasite tests in 88 suites
  pass without failures/errors/skips, including the shared coordinator and both runtime
  upload substitutes. Both debug, instrumentation and unsigned minified release APKs
  build. Generated Dagger graphs and debug APK classes select the matching uploader;
  release DEX retains the SDK reflection names and standalone receipt guard, and the
  standalone NOS URL is absent from parasite. Both release APKs pass 16 KB ZIP alignment.
  `git diff --check` passes. A combined debug/release
  run exhausted the local Kotlin compiler heap; the debug gate passed after a scoped
  retry with `--max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx3g`, without changing repository
  build settings. The final paired build uses the same scoped flags. Local logs:
  `/tmp/meilox-dual-cloud-debug-verified.log`,
  `/tmp/meilox-dual-cloud-release-verified.log`, and `/tmp/meilox-dual-cloud-final.log`.
- No AVD install, user-file transfer, cloud mutation or device test was performed while
  standalone login confirmation is pending. Official SDK invocation, real publication,
  Android document providers and cloud page account/paging ownership remain unqualified.
  This increment does not complete the cloud feature, D4, or release runtime acceptance.

### D1/D4 Checkpoint: Session-Owned Cloud Library and Page Actions (2026-09-30)

- Introduced a shared `CloudMusicSource` and a cloud library business adapter using the
  selected runtime's existing authenticated service. Cloud reads and single-song
  deletions now carry explicit session stamps; upload preparation inherits the owner
  captured before document selection rather than taking a fresh account afterward.
  There is no Cookie/host fallback. API-020 records the request and identity contracts.
- The existing list receives a complete, validated snapshot from automatic pagination.
  It rejects malformed/partial/stalled pages, duplicate cloud identities and detected
  count changes. Failed refreshes preserve only the current account's previous snapshot;
  they do not turn a partially fetched library into success. This does not promise a
  server-atomic snapshot if undetectable concurrent changes occur.
- Account invalidation clears the shared page synchronously and cancels old work.
  Latest-refresh ordering, duplicate-write reservation, stale picker rejection and
  independent upload/delete flags are covered by substitute tests. Both the standalone
  cloud page and the embedded Library entry guard their rendered callback owner. Layouts,
  resources, navigation and controls stay unchanged; no flavor-specific frontend is added.
- All 30 new contract/state tests pass under both backends, bringing the paired JVM
  suites to 657 standalone and 722 parasite tests, without failures/errors/skips.
  These tests simulate cancellation, account replacement, same-account renewal, late
  callbacks, unavailable session readers and in-flight invalidation after disposal;
  they do not delete or upload any real user file.
- Both debug APKs, instrumentation APKs and unsigned minified release APKs build from
  the final source. Both releases retain the shared cloud/Library frontend and their
  intended production package IDs, pass 16 KB ZIP alignment, and preserve dynamic
  service session-tag annotations/generic signatures. Xposed metadata remains absent
  from standalone and present only in parasite. These are package checks, not release
  device execution. `git diff --check` passes. The final build uses scoped
  `--max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx3g`; local evidence is
  `/tmp/meilox-dual-cloud-library-frozen.log`. No generated artifact is committed.
- Private-cloud playback/download owner translation, real deletion/publication,
  provider lifecycle and paired runtime acceptance remain open. This is a bounded
  cloud ownership checkpoint, not complete D4 acceptance.

### D3/D5 Checkpoint: User-Supplied Cookie Login and Read-Only Device Checks (2026-09-30)

- The user supplied a Cookie for testing, resolving the earlier standalone-login wait.
  Updated only `MeiloX Standalone Debug`, then entered the credential through the existing
  manual Cookie sheet. The exact input was checked privately before submission; no
  credential content was printed, logged, committed, or copied from the official host.
  Live verification succeeded and the original UI displayed successful login.
- Authenticated Library and cloud rows rendered through the shared frontend. A later
  process-cold launch restored the account/avatar without re-entry. These are real
  Cookie/read/persistence observations, not proof of every endpoint, all pagination
  cases, interactive WebView authentication or an upgrade of the original installation.
- All four scoped standalone device tests pass: independent registered components,
  shared graph/session identity, absence of host/Xposed classes/metadata and packaged
  Zstd loading with NCBL envelope encoding on the API 37, 16 KB AVD. Encoding does not
  qualify network report delivery or server listening statistics. The AVD still has
  LSPosed enabled; no framework-free device is qualified here.
- Reinstalled the final standalone debug artifact, repeated those four tests, then
  cold-started the app with its retained Cookie. The authenticated Home/avatar returned
  and the current process had no fatal crash entries. This is the isolated debug
  package's update/restart check, not a legacy production-data upgrade.
- No TV/module update, logout, account switch, playback, upload, deletion or real download
  grant was performed. The original standalone app and existing TV-owned download were
  not modified. Legacy standalone download policy/work conversion, signed release
  upgrades and full paired regression remain pending. Local evidence includes
  `/tmp/meilox-standalone-cookie-device-final.log`, `/tmp/meilox-standalone-cookie-accepted.png`,
  `/tmp/meilox-standalone-library-authenticated.png`, `/tmp/meilox-standalone-cloud-tab.png`
  `/tmp/meilox-standalone-cookie-restored.png` and
  `/tmp/meilox-standalone-cookie-final-home.png`; none are committed.

### D3/D5 Checkpoint: Live Playback and Recovery-Safe Queue Restore (2026-09-30)

- Used only the authenticated standalone debug package. A real catalog song reached
  its natural next-track transition, with pause/resume, background/screen-off progress,
  an active unmuted AudioFlinger track and media-notification re-entry observed. The
  next song retained position across an exhigh-to-standard selection and resumed.
  The AVD screen was explicitly awakened afterward; no audible-output claim is made.
- Both standalone report channels accepted actual start/end events. A separate live
  recent-history read matched the selected song and observation window. NCBL reported
  257 active seconds for a 236,434 ms timeline, so precise duration/statistics acceptance
  remains open; transport/file acceptance is not settlement proof. API-015 and ABI-009
  record the evidence and limits. No fake listening event was submitted.
- Added three explicitly opt-in `StandaloneLiveReadDeviceTest` checks for full playback
  sources, standalone album/artist collection schemas and server recent history. They
  use the existing isolated app login and never accept, print or duplicate a Cookie.
  The playback read uses the graph's service; collection/history checks construct the
  existing production adapters/factories with that same session store. They do not
  replace the UI, test a second transport implementation or mutate collections.
  Without `standaloneLiveReads=true`, all three report assumption skips before their
  request bodies execute. This does not disable the app's ordinary session bootstrap.
- Cold-start regression exposed a migration defect: asynchronous Cookie recovery
  canceled the local playback snapshot read without retry. Local queue restoration now
  survives invalidation; captured-session policy suppresses automatic primary source
  preparation/playback after recovery or account changes. The existing Play action can
  resume afterward. The original 1,517-entry queue, current item and 35,153 ms position
  returned after update/restart, then explicit playback advanced to 59,712 ms. ABI-011
  describes the lifecycle boundary. No UI, navigation or persisted schema changed.
- Both debug APKs and instrumentation APKs build; 666 standalone and 731 parasite JVM
  tests pass with no failures/errors/skips, including nine new shared restore-policy
  cases. Shared cases execute in both variants. The new live read checks also passed
  individually on the AVD, and their disabled-mode skips were verified explicitly.
- Both final unsigned minified release APKs build and pass 16 KB ZIP alignment. Their
  DEX retains the session-bound restore policy; production package IDs remain unchanged,
  with zero Xposed metadata entries in standalone and three in parasite. All seven
  selected standalone device tests pass on the final debug build (package/graph, native
  codec and live reads). A final cold start restores the current song at 60,347 ms,
  does not autoplay, displays the authenticated Home/mini-player and has no fatal
  crash entries. Release build/package checks are not release-device acceptance.
- The original standalone installation, TV process/session and accepted TV download
  remain untouched. No upload, deletion, collection mutation or quota-consuming download
  was performed. Legacy work conversion, framework-free startup, private-cloud owner
  mapping, full dual-runtime behavior and release-device qualification remain open.

Opt-in read-only invocation after authorized manual playback (all values are public
song identity/timing, not credentials; the supplied epoch-ms window must already have
ended and must contain that playback):

```sh
adb shell am instrument -w \
  -e class com.ljyh.mei.standalone.StandaloneLiveReadDeviceTest \
  -e standaloneLiveReads true \
  -e standaloneReadSongId "$SONG_ID" \
  -e standaloneHistorySinceMs "$PLAYBACK_SINCE_MS" \
  -e standaloneHistoryUntilMs "$PLAYBACK_UNTIL_MS" \
  com.neoruaa.meilox.standalone.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Local evidence: `/tmp/meilox-standalone-live-playback-reports.log`,
`/tmp/meilox-standalone-live-reads-device.log`,
`/tmp/meilox-standalone-live-reads-disabled-raw.log`,
`/tmp/meilox-dual-playback-restore-debug.log`,
`/tmp/meilox-dual-playback-restore-release.log`,
`/tmp/meilox-standalone-playback-final-device.log`,
`/tmp/meilox-standalone-playback-notification.png`,
`/tmp/meilox-standalone-notification-entry.png`,
`/tmp/meilox-standalone-standard-playing.png` and
`/tmp/meilox-standalone-restore-fixed.png`, plus
`/tmp/meilox-standalone-playback-final-home.png`. None are committed.

### D3 Checkpoint: Isolated Standalone v17 Database Upgrade (2026-09-30)

- Added a frozen, synthetic v17 SQLite fixture based on the 13 entity tables at
  `main` commit `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`. It is created independently
  of current Room code, not by creating v20 and lowering its version. Each test uses
  a UUID-named private database and dummy file under the isolated standalone debug
  package; cleanup checks remove only those fixture paths. The fixture code never
  opens the installed app database, loads credentials or accesses real media/work.
  Instrumentation still runs the debug application's ordinary session bootstrap.
- The actual 17-to-18, standalone 18-to-19 and 19-to-20 migrations pass Room's complete
  v20 schema validation. Every original column/value/type is compared before and after
  migration and again after reopening. Songs, favorites, QQ mappings, playlists/order,
  album/artist relations, lyrics/colors, playback history/counts and all five legacy
  download states retain their fixture data. Integrity and foreign-key checks pass.
- DAO reads still expose completed downloads and their original path/URI strings;
  the private dummy file retains its bytes. Legacy pending URLs/progress remain intact,
  unlike the parasite policy. New request/account fields retain unowned defaults, and
  no account playlist membership or download publication receipt is fabricated.
  Legacy remote playlist rows remain stored but require an authoritative account
  refresh to appear in the account-filtered list. Local playlist visibility is retained.
- Three AVD tests pass: populated upgrade/reopen, empty upgrade, and injected final-step
  failure followed by rollback/retry. The failure rolls back all preceding schema/data
  changes to v17, and the successful retry also preserves the history autoincrement
  sequence. Together with the existing package/graph/native-codec checks, all seven
  selected standalone device tests pass. Only the instrumentation APK was updated;
  no TV/module or standalone application APK was installed for this checkpoint.
- Both debug builds and the standalone instrumentation build pass. The paired JVM
  gate remains 666 standalone tests in 84 suites and 731 parasite tests in 91 suites,
  with no failures/errors/skips. `git diff --check` passes. No production, UI or migration
  code changed, and no new release-runtime claim is made. The standalone Home was
  reopened after instrumentation; its retained account and paused mini-player returned.
- This qualifies the fresh-v17 entity-schema fixture only. Earlier historical migration
  paths, DataStore/settings/serialized queues, WorkManager conversion, provider access
  grants, valid audio decoding and signed production upgrades remain separate gates.
  Preserved pending rows do not prove resumable work. ABI-010's download-policy decision
  was open at this checkpoint and is confirmed below. D3, D5 and the overall goal are
  not complete.

Local evidence: `/tmp/meilox-dual-v17-migration-build.log`,
`/tmp/meilox-standalone-v17-migration-device.log`,
`/tmp/meilox-standalone-v17-regression-device.log` and
`/tmp/meilox-standalone-v17-final-home.png`. None are committed.

### D3/D4 Checkpoint: Runtime-Owned Download Sources (2026-09-30)

- Confirmed user choice: standalone retains original playback-link downloads;
  parasite retains dedicated official download authorization. Shared download preflight
  and Worker consume one `DownloadSourceBackend`; endpoint/response/fallback differences
  are flavor-owned. No UI, navigation, quality control or publication layout changed.
  See API-017 and ABI-010 for the contracts, current adaptation and evidence limits.
- Moved official grant types/resolver and its 17 tests to parasite. Standalone uses
  the original numeric player V1 batch/quality-fallback contract through its own Cookie
  signing/session transport, with complete-array, metadata, expiry and trial checks.
  Both implementations reject stale/recovering/account-changed operations. No dedicated
  download endpoint or host source implementation remains required by shared consumers.
- Both debug APKs and instrumentation APKs build. Paired JVM acceptance is 667 standalone
  tests in 85 suites and 735 parasite tests in 92 suites, with no failures/errors/skips.
  This includes four new shared delegation/session tests and 14 standalone backend
  cases; relocating official tests explains the differing flavor counts.
- Five standalone direct Worker device cases pass using the production standalone
  adapter/signing, private Room and actual MediaStore with closed request/media
  substitutes. They qualify publication, completed retry, denial/trial, corrupt/truncated
  bytes, changed owner and session invalidation, not WorkManager scheduling. Seven
  standalone package/graph/codec/v17 migration cases and 12 parasite source/Worker cases
  also pass. All uniquely named synthetic media/receipts are cleaned up.
- One additional opt-in standalone live check obtains a complete standard-quality source
  for public song `30245467` through the graph-selected backend and retained isolated
  login, without printing/transferring its URL. Total selected AVD cases: 25. No real
  download grant/transfer, upload, logout, account switch or social mutation was issued.
- Both final unsigned R8 release APKs build and pass 16 KB ZIP alignment. Correct
  production IDs and zero/three Xposed metadata entries remain. Standalone DEX contains
  the player route but not the dedicated download route; R8 retains each adapter's
  Retrofit method, session tag and nullable DTOs. Release runtime remains pending.
- The user-provided `~/.android/avd/Pixel_10_Pro-root/Start-Rooted.command` restores the
  rooted AVD without snapshots. Root/LSPosed modules and retained TV-only orientation
  overrides are verified after boot. Updated debug apps restore their authenticated
  original Home and paused mini-players; the accepted TV media row remains published
  at 22,705,573 bytes, with no crash-buffer entries. The original standalone installation
  is not replaced. The AVD remains awake; audible output is not claimed.
- This resolves the download-source policy, not legacy work conversion. Old
  `song_ids_json` inputs, affinity/destination reconstruction, startup scheduling,
  real ownership changes and signed production upgrades remain separate required
  gates. D3-D6 and the overall goal remain incomplete.

Local evidence: `/tmp/meilox-dual-download-backends-debug.log`,
`/tmp/meilox-dual-download-backends-release.log`,
`/tmp/meilox-standalone-download-worker-device.log`,
`/tmp/meilox-standalone-download-regression-device.log`,
`/tmp/meilox-standalone-download-source-live.log`,
`/tmp/meilox-parasite-download-backend-device.log`,
`/tmp/meilox-download-final-standalone-ready.png`,
`/tmp/meilox-download-final-host-ready.png`,
`/tmp/meilox-download-final-host-session.log` and
`/tmp/meilox-download-final-crash.log`. None are committed.

### D3/D5 Checkpoint: Legacy Work Conversion and Startup Coordination (2026-10-01)

- Standalone now supplies an on-demand WorkManager configuration/factory through its
  flavor-owned Application base; only its default WorkManager initializer is removed.
  Parasite retains ordinary Application inheritance and its existing host scheduler.
  Shared screens, navigation, components and Room version remain unchanged.
- Freeze the pre-verification public account ID once before asynchronous Cookie login
  can overwrite it. This is durable legacy affinity, not authentication or a Cookie
  copy. The standalone converter recovers original unique-work input Data from the
  pinned WorkManager 2.11.2 DAO, isolated from common/parasite consumers. The recorded
  `main` uses the same dependency version; no WorkManager schema upgrade is introduced.
- Legacy pending/downloading tasks with complete matching metadata receive fresh UUIDs,
  original playlist/directory, old-account affinity and no cached URL. Paused/failed
  states, song metadata and creation times survive; completed records/paths/files remain
  untouched. Old canceled work stays paused. Room commits before retiring old work;
  the gated factory prevents old implementations from executing across that boundary.
- The preparation gate also protects modern downloads before graph/metadata readiness.
  Cookie recovery retries without failing pending rows or obtaining a source. Shared
  queue recovery runs when an authenticated session becomes available, repairs only
  eligible same-account requests and rejects changed ownership. Explicit resume keeps
  the recovered original destination rather than substituting current preferences.
- Unknown affinity or absent/pruned/ambiguous/malformed metadata cannot recover an
  original authorized intent. Those rows remain visible but unowned/failed (paused
  stays paused), with no automatic dispatch or stale URL use. The existing download menu
  can issue a fresh intent. The implementation does not invent lost owner/directory
  data or silently assign old tasks to the account that logs in next. ABI-010 records
  these policies and remaining acceptance limits.
- Nine new standalone policy tests pass. Final paired JVM results are 676 standalone
  tests in 86 suites and 735 parasite tests in 92 suites, with zero failures/errors/skips.
  Both debug APKs and both instrumentation APKs build; `git diff --check` passes.
- All 23 selected standalone and 19 parasite device cases pass. Recovery coverage uses
  private v17 tables/files and UUID-named delayed work to verify conversion, completed
  data preservation, reopen/idempotence, recovery/changed-account exclusion, explicit
  paused resume, missing metadata, commit-before-cancel fault injection and durable
  affinity. A naturally scheduled unavailable old worker name proves factory retirement.
  Closed Worker cases prove preparation wait/cancel/retry followed by publication after
  fixture recovery. All private Room/WorkSpec/DataStore/media fixtures are cleaned up.
- One nullable-Long assertion was corrected; an initial parasite instrumentation process
  hit Android's 10-second attach timeout before tests started. Its crash buffer was
  empty, diagnostic output was retained and the scoped rerun passed all 19 tests.
  The timeout's cause is not determined or attributed to a download implementation.
- Both unsigned minified release APKs build and pass 16 KB ZIP alignment. Production IDs,
  zero/three Xposed metadata entries and flavor isolation remain correct; standalone's
  provider/factory/gate/converter are present under R8 and its legacy coordination
  markers are absent from parasite. Merged manifests confirm standalone initializer
  removal and parasite retention. This remains build/package, not release-device proof.
- Both updated debug cold starts restore authenticated original Home and paused players
  at 60,347/94,813 ms. The accepted TV-owned 22,705,573-byte publication remains intact.
  No original standalone app update, real download authorization/transfer, logout,
  account switch, upload, social write or screen-off action was performed.
- Legacy conversion is implemented and synthetic upgrade/work gates now pass. Actual
  signed production upgrades, real owned download/process-reboot completion, external
  provider grants, earlier upgrade histories, framework-free launch, remaining business
  differences/capabilities and final paired regression still require their own evidence.
  D3-D6 and the overall goal remain incomplete; fixture acceptance does not replace them.

Local evidence: `/tmp/meilox-legacy-download-debug.log`,
`/tmp/meilox-legacy-download-device-build.log`,
`/tmp/meilox-legacy-download-instrument-final-build.log`,
`/tmp/meilox-legacy-download-final-jvm.log`,
`/tmp/meilox-legacy-download-final-device.log`,
`/tmp/meilox-legacy-download-parasite-final-device.log`,
`/tmp/meilox-legacy-download-attach-diagnostic.log`,
`/tmp/meilox-legacy-download-release.log`,
`/tmp/meilox-legacy-download-standalone-cold.png`,
`/tmp/meilox-legacy-download-standalone-session.log`,
`/tmp/meilox-legacy-download-tv-cold.png`,
`/tmp/meilox-legacy-download-tv-session.log` and
`/tmp/meilox-legacy-download-final-crash.log`. None are committed.

### D1/D3/D4 Checkpoint: Private-Cloud Playback and Durable Source Ownership (2026-10-01)

- Added a shared credential-free source identity separating the cloud UI/deletion
  entry, audio ID, file owner and authenticated account. Own-library parsing retains
  explicit owners or the response's captured account fallback. Missing audio IDs,
  malformed owners/metadata and conflicting source identities fail closed. API-020
  records the pinned official model evidence and live response-shape observations.
- The same existing frontend now forwards this identity through player loader keys,
  AutoMix resolution, recovery, account-scoped caches, automatic cache and download
  producers. No layout, control, navigation or page architecture was changed. Cloud
  sources use owner tuples; ordinary numeric requests retain their prior behavior.
  Parasite dedicated grants and standalone playback-link downloads stay separate.
- Queue snapshot v3 and Room v21 persist source ownership across recreation/work
  recovery. Old ordinary snapshots and old task data remain compatible; no cloud
  identity is invented for old records. Local cloud playback requires matching
  completed source/account metadata rather than adopting a colliding numeric file.
- Paired JVM suites pass 688 standalone and 747 parasite tests (1,435 total), with zero
  failures/errors/skips. Both debug/instrumentation APKs build. Selected AVD tests pass
  32 standalone and 35 parasite cases, including source-key/metadata/snapshot boundaries,
  cache affinity, queue recovery, closed Workers/publication, frozen v17 upgrades and
  v20-to-v21 rollback/retry/reopen. One parasite run timed out while attaching before
  executing tests; its scoped retry passed. No application crash was established.
- Standalone's real Cookie account passes an opt-in cloud/player-metadata read; its
  download adapter still obtains playback metadata without a dedicated quota request.
  The official test account was empty until the user manually uploaded one song. Its
  authenticated Library then displayed that entry, and the module played its 123,871 ms
  timeline through natural repeat while the official player stayed inactive. A cloud
  source survives queue persistence and occupies a separate byte-cache key; an active,
  unmuted 48 kHz PCM track proves output-pipeline activity, not audible AVD sound.
- After installing the final ordinary probe-disabled debug, an explicit original TV
  launcher cold start displays portrait MeiloX with its official session intact. The
  cloud queue restores one item at 41,740 ms, retaining its persisted cloud source key;
  system media PLAY advances to 77,797 ms and PAUSE works. The emulator is left awake
  on the authenticated cloud page with the module player paused. No system hook or
  global rotation setting was changed.
- Both unsigned R8 releases build and pass 16 KB ZIP alignment with production package
  IDs retained and API 102 metadata confined to parasite. This is package/build proof,
  not minified release-device acceptance. The accepted TV publication remains TV-owned,
  published and 22,705,573 bytes. No original standalone app replacement, logout/account
  change, real cloud download grant, upload/delete/social write or screen-off action
  was performed by the agent.
- This implements the source-identity portion, not complete private-cloud parity or
  D4/D5/D6 acceptance. Cloud lyrics, favorite/playlist/reporting identity, source metadata
  from other endpoints, unmatched/other-owner real files, real quota/transfer/provider
  lifecycle, account switching and final paired minified release regression remain open.

Local evidence: `/tmp/meilox-cloud-source-reviewed-validation.log`,
`/tmp/meilox-cloud-source-reviewed-standalone-device.log`,
`/tmp/meilox-cloud-source-reviewed-parasite-device.log`,
`/tmp/meilox-cloud-source-reviewed-standalone-live.log`,
`/tmp/meilox-cloud-source-new-upload-list.png`,
`/tmp/meilox-cloud-source-new-upload-player-expanded.png`,
`/tmp/meilox-cloud-source-cold-restored-cloud.png` and
`/tmp/meilox-cloud-source-cache.db`. None are committed; no credential or signed URL
is printed as evidence.

### D1/D4 Checkpoint: Source-Aware Cloud Lyric Requests and Worker Preparation (2026-10-01)

- Implemented shared `SongLyricBackend` over the selected runtime transport. Catalog
  V1 keeps its body; private-cloud lyrics use the audio/file-owner identity, explicit
  captured session and flat-string response adapter. Native `404` is no lyrics; null
  fields are not pure music, and malformed/denied/network/stale results cannot fall
  back to a catalog ID or another session. API-021 records the official difference.
- Repository calls use the adapter. Both Workers now forward the durable full source
  key to embedded-lyric preparation; AMLL uses the audio identity and official fallback
  keeps file/account affinity. Native cloud karaoke is retained separately rather than
  relabeled YRC. No screen, layout, resource or navigation change is part of this work.
- Full paired JVM suites pass 697 standalone and 756 parasite cases (1,453 total),
  zero failures/errors/skips. Debug/instrumentation builds pass; 17 selected standalone
  and 14 parasite device tests pass against closed sources and private media fixtures.
  Coverage includes exact cloud Retrofit parameters, session rejection and both actual
  Worker implementations receiving the persisted key for lyric preparation.
- One new standalone read-only cloud-lyric test passes using the isolated debug's
  existing Cookie account. A gated host read on the user's persisted cloud queue also
  passes through the official session/request bridge, with no LRC/karaoke text present.
  No lyric upload, download grant/quota, real media tagging, login/logout, account change
  or social write was used. The accepted TV-owned publication remains 22,705,573 bytes.
- Both unsigned R8 releases build and pass 16 KB ZIP alignment; production identities
  and parasite-only API 102 metadata remain correct. The normal debug/release host flags
  disable the qualification command. Build/package proof is not release-device proof.
- Reinstalled the normal probe-disabled module and cold-started the original TV
  launcher: portrait authenticated MeiloX Home and the one-entry cloud queue restore
  with the player paused at 78,338 ms. The official player remains inactive, the emulator
  remains awake and the new host PID has no crash-buffer entries. Both release DEXes
  retain all four typed cloud-lyric fields and their runtime Gson annotations.
- The source-aware request/Worker portion is implemented, not the complete display
  feature. Current/preloaded lyric state, memory/Room/QQ cache affinity, System Lyrics
  publication, native karaoke timing/parser integration and real text rendering remain
  open. The shared numeric-entry consumers have not been declared cloud-complete.
  D1/D4/D5/D6 and the overall goal remain active; no feature is hidden as a substitute.

Local evidence: `/tmp/meilox-cloud-lyric-verified-debug.log`,
`/tmp/meilox-cloud-lyric-standalone-device.log`,
`/tmp/meilox-cloud-lyric-parasite-device.log`,
`/tmp/meilox-cloud-lyric-standalone-live.log`,
`/tmp/meilox-cloud-lyric-host-read.log`, `/tmp/meilox-cloud-lyric-release.log`,
`/tmp/meilox-cloud-lyric-final-debug.log` and `/tmp/meilox-cloud-lyric-final-home.png`.
No generated artifact, device log, credential
or private lyric content is committed.

### D1/D4 Checkpoint: Source-Owned Current and Preloaded Lyrics (2026-10-01)

- Current loads, forced reloads and manual QQ selections now retain the full cloud
  entry/audio/file-owner/account identity plus a captured session and distinct request
  batch. Sampled source results, background parsing, QQ fallback/search, duet updates
  and UI/cache publications cannot be relabeled as a newer batch. Preloading captures
  the same owner and rejects canceled/stale results; AMLL cancels its real OkHttp call.
- Memory/Room lyrics and QQ mappings use the complete cloud key without numeric-entry
  or foreign-account fallback. Catalog V1 requests, numeric cache namespaces and existing
  lyric precedence remain unchanged. Song Info and all existing player mapping-reset
  controls forward metadata to source-owned operations. Source changes invalidate the
  unchanged player/PiP effects and System Lyrics track identity; no screen tree, layout,
  navigation, feature entry or visual setting is redesigned.
- Invalidation/recovery clears current private state and session-affine memory caches,
  cancels outstanding work and retries the same metadata only after its source/account
  becomes valid. A different account cannot dispatch requests for the old cloud source.
  Same-account renewal and initial asynchronous recovery do not require a page remount.
  See API-022 for the original mismatch, adaptation and residual boundaries.
- Final full JVM results: 697 standalone tests across 88 suites and 756 parasite tests
  across 94 suites, all passing without skips. The new closed fixture's 16 cases pass
  on both APKs; selected device regressions total 33 standalone and 39 parasite passes,
  including source persistence and actual Workers using synthetic resources only.
  Coverage exercises exact source/AMLL IDs, numeric/foreign cache collisions, same-ID
  file changes, forced/manual batch switches, late noncooperative callbacks, recovery,
  same-account transition, wrong-account rejection, QQ lookup/reset and cancellation.
- The first final standalone instrumentation launch failed before tests began with
  the platform process-attach timeout (PID 30626, no crash-buffer entries). Its retry
  ran the tests and exposed a fixture observation race: a background read saw the
  manager's key cleared before the rest of its main-thread reset. The fixture now
  observes predicates on Main; no production behavior was changed to hide the failure.
  The final 33/39-case runs pass. No underlying attach-timeout cause is claimed.
- One opt-in standalone cloud-lyric read passes against its existing Cookie account,
  without logging private content or identities. Normal probe-disabled module debug
  cold-starts the original TV launcher into authenticated portrait MeiloX (PID 30928)
  and restores the cloud queue paused at 78,338 ms. Opening its unchanged lyric view
  renders the existing QQ QRC/translation fallback. A read-only local database snapshot
  confirms one source-keyed cloud QQ mapping and one cloud lyric row (QQMusic/QRC),
  without printing identities/text; the temporary database copies are removed.
  This is public-fallback/source-cache evidence, not native official KRC qualification.
- The isolated standalone debug cold start restores authenticated Library and its
  paused queue at 60,347 ms (PID 32009). Both current PIDs have no crash-buffer entries;
  the official TV player stays inactive. Existing TV media row 820 remains published
  at 22,705,573 bytes. No play, upload/delete/social write, real download grant/transfer,
  account mutation or screen-off action is performed in this checkpoint.
- Paired Debug/instrumentation and unsigned minified release builds pass. Release APKs
  keep their production package IDs, pass 16 KB ZIP alignment and retain zero/three
  standalone/parasite Xposed metadata entries; the module remains API 102, TV-only and
  without hot reload. Host/runtime/work probes are disabled in the installed module.
  Release packages were not installed or executed, so signed/runtime gates remain open.
- Native cloud karaoke parsing/timing, real official cloud lyric text, external System
  Lyrics provider acceptance, real account-switch cooperation and real embedded tags
  remain required. Source ownership does not complete cloud parity, D4/D5/D6 or the
  overall dual-runtime goal. No functionality is hidden to substitute for acceptance.
  Subsequent local source review finds historical watch KRC suspension/cumulative
  timing differs from YRC. The reviewed pinned TV display path does not qualify that
  format; API-022 records the evidence and no speculative parser conversion is applied.

Local evidence: `/tmp/meilox-cloud-lyric-owner-final-validation.log`,
`/tmp/meilox-cloud-lyric-owner-final-device-build.log`,
`/tmp/meilox-cloud-lyric-owner-final-standalone-device-passed.log`,
`/tmp/meilox-cloud-lyric-owner-final-parasite-device.log`,
`/tmp/meilox-cloud-lyric-owner-standalone-live.log`,
`/tmp/meilox-cloud-lyric-owner-host-home.png`,
`/tmp/meilox-cloud-lyric-owner-host-player.png`,
`/tmp/meilox-cloud-lyric-owner-host-lyrics.png` and
`/tmp/meilox-cloud-lyric-owner-standalone-home.png`. No device log, generated artifact,
credential, official decompiled source or private lyric content is committed.

### D1/D4 Checkpoint: Source-Owned Cloud Favorites (2026-10-01)

- The shared favorite backend now receives the complete cloud source instead of the
  logical UI/deletion entry ID. Ordinary numeric callers remain compatible. Remote
  cloud favorite reads use the audio ID; TV writes additionally supply the separate
  file owner in lowercase `userid`. Source/account affinity, cancellation and current
  session are checked before dispatch and after results. Pending recovery also rejects
  calls/results even if the generation or public account ID has not changed.
- The standalone Cookie implementation retains its original single-track check and
  `radio/like` compatibility write fields, using the audio rather than entry ID. It
  does not import TV owner parameters, duplicate reconciliation or routes. Real Cookie
  cloud-write behavior, particularly unmatched private files, remains unqualified.
- Favorite selection retains logical entry ID plus the complete source key. A different
  audio/file owner under the same entry clears/reloads star state and rejects old
  results/clicks. Same-account renewal, foreign accounts, recovery and malformed sources
  are covered. The shared player only forwards metadata/effect ownership; no controls,
  page architecture, layout, icons, navigation or glass rendering are changed.
- Pinned APK DEX confirms the official favorite task's `getMusicLibraryId()` and
  `getCloudSongUserId()` arguments and the remote MusicInfo library/matched/filter-ID
  chain. The original task's `canSub()` privilege gate is documented, not assumed to
  be equivalent to this identity adapter. No new JADX-derived hook target is added.
- A real host-process read exposed a separate backend integration regression: five
  extracted Retrofit interfaces were absent from the existing suspend-annotation
  compatibility registry. The exact module-class registry now includes favorites,
  playlist tracks/collections, catalog collections and download authorization. It
  still pads only one missing continuation slot; Body/Tag annotations, complete or
  otherwise malformed arrays and unrelated/host methods remain untouched. Real host
  logs confirm the two-parameter/one-slot case and the corrected cloud favorite read.
- Final JVM results: 708 standalone tests across 88 suites and 770 parasite tests
  across 94 suites pass, with zero failures/errors/skips. Paired debug, instrumentation
  and unsigned minified release builds pass. Both release APKs retain their respective
  favorite DTO fields/annotations and pass 16 KB ZIP alignment. Production package IDs
  remain distinct; standalone has no Xposed metadata, and parasite scope is TV-only,
  API 102, with hot reload disabled. Release execution remains a separate gate.
- Selected AVD closed fixtures pass: 31 standalone and 32 parasite tests, including
  six new cloud-favorite tests, the 16 source-owned lyric tests, nine cloud identity
  tests and the parasite graph check. No fixture sends real mutations. One opt-in
  standalone cloud-favorite server read passes under its existing Cookie account;
  either liked Boolean is valid. An initial attempt timed out and is not counted as
  acceptance; a fresh authenticated launch and staged library/favorite retry completed
  in 9.029 s without changing credentials or account data.
- The TV probe is debug-gated and reads only the persisted one-entry cloud source.
  Shell delivery to the unexported carrier was denied; authorized root AVD delivery
  reaches that carrier without changing its manifest or scope. The initial host read
  failed during Retrofit call-adapter creation before dispatch; after registry repair,
  source-owned/session-unchanged read acceptance is observed. This is not proof of
  favorite write acceptance or of every registered feature's live business behavior.
- The normal probe-disabled module is restored after qualification. Original launcher
  cold start remains portrait authenticated MeiloX, the cloud queue stays paused at
  78,338 ms and the official player remains inactive. Existing player views render the
  cloud metadata and filled source-owned star without touching transport/favorite
  controls. Standalone's cold authenticated Home also restores its queue at 60,347 ms
  without autoplay. Both current-PID crash buffers are empty. The AVD remains awake;
  accepted TV MediaStore row 820 remains 22,705,573 bytes, TV-owned and not pending.
- Real favorite/playlist writes, cloud reporting IDs, privilege handling, native cloud
  karaoke timing, account-switch cooperation and paired release/device lifecycle gates
  remain open. No actual playback, download grant, upload/delete/social write, session
  transfer, screen-off action, global orientation change, push or merge is performed.
  This is a cloud-favorite ownership checkpoint, not complete D4/D5/D6 acceptance.

Local evidence: `/tmp/meilox-cloud-favorites-final-source-validation.log`,
`/tmp/meilox-cloud-favorites-final-device-build.log`,
`/tmp/meilox-cloud-favorites-final-source-standalone-device.log`,
`/tmp/meilox-cloud-favorites-final-source-parasite-device.log`,
`/tmp/meilox-cloud-favorites-reviewed-standalone-live.log`,
`/tmp/meilox-cloud-favorites-host-read-diagnostic.log`,
`/tmp/meilox-cloud-favorites-host-registry-read.log`,
`/tmp/meilox-cloud-favorites-host-expanded-stable.png`,
`/tmp/meilox-cloud-favorites-final-standalone-ready.png` and
`/tmp/meilox-cloud-favorites-final-tv-home.png`. No generated artifact, device log,
credential, official source or private
server payload is committed. API-023 and ABI-001 record the protocol/runtime differences.

### D1/D4 Checkpoint: Source-Owned Cloud Playlist Mutations (2026-10-01)

This is progress on the existing dual-runtime goal, not complete playlist, cloud,
release or D4 acceptance. API-024 records the separate entry/audio/file-owner contract.

- The pinned TV APK's actual DEX confirms the native add caller uses MusicInfo's audio
  `getId()`. Its v1 playlist mutation body has quoted numeric track IDs, add-only
  `reverse=true` and official security fields, but no favorite-style `userid` or
  playback/download file-owner tuple. Standalone retains its original route and
  `imme=true`; native remove callers and real cloud writes remain unqualified.
- All existing shared add overlays now retain complete MediaMetadata. Player, track
  menu and downloaded-item entry points share the original picker and ViewModel;
  metadata entry/source matching is checked inside guarded mutations. The Repository
  carries typed sources to each backend, validates every account affinity before
  mapping distinct audio IDs, and never silently retries with a cloud deletion entry.
  The existing remove flow also forwards metadata, without adding UI or navigation.
- Pending recovery, stale account generations, malformed/foreign metadata, cancellation
  and late results are guarded before dispatch and publication. Recovery clears the
  owned picker and prevents old callbacks or Library refreshes. Existing duplicate,
  partial and rejection outcomes remain business outcomes, not count comparisons.
- Paired source validation passes: 721 standalone JVM tests in 88 suites and 783
  parasite tests in 94 suites, zero failures/errors/skips. Both debug/instrumentation
  and unsigned minified release builds pass, including release vital lint. Release
  APKs retain their route-specific DTO fields, production IDs and 16 KB ZIP alignment.
  Standalone has no Xposed metadata; parasite remains API 102, TV-only, hot reload off.
  These package checks do not establish signed release execution or upgrade acceptance.
- Closed AVD fixtures pass 37 standalone and 38 parasite cases, including six new
  cloud-playlist tests per flavor plus existing favorite/lyric/source identity checks
  and the parasite graph check. Both are rerun after the final picker recovery guard.
  The first parasite attempt failed the platform process
  attach timeout before any tests began; its PID crash buffer was empty. The recorded
  rerun passed all 38 cases; the attach failure's underlying cause is not determined.
- A debug-gated probe also passes inside the real TV process using a separate synthetic
  transport/session, never rebinding AppGraph or sending official writes. It verifies
  the production HostPlaylistTracksBackend, Body/Tag ownership, observed three-parameter
  versus two-annotation slot repair, audio ID encoding, recovery rejection and 502
  preservation. The normal probe-disabled module is rebuilt, installed and cold-started
  afterwards; its packaged APP flag is true and HOST/RUNTIME/WORK probe flags are false.
- Read-only screenshot checks on both actual debug runtimes show the existing player,
  more-action sheet and owned playlist picker. The TV cloud track reaches the TV
  account's picker; standalone displays only its different Cookie account's playlists.
  Neither Select, Create, Like, Download nor transport controls are activated. The
  sheets dismiss back to the original Home; no layout, resource or navigation change
  is made. This is picker/rendering evidence, not real cloud-write acceptance.
- Final AVD state is awake, portrait, authenticated TV MeiloX Home. TV's one-entry
  cloud queue remains PAUSED at 78,338 ms; standalone remains PAUSED at 60,347 ms with
  queue index 879. The official TV player is inactive STOPPED. Both current-PID crash
  buffers are empty and accepted TV media row 820 remains TV-owned, not pending and
  22,705,573 bytes. No remote account state, credentials, user-owned media, real playlist,
  download grant or social recipient is mutated; no screen-off/global orientation/scope
  change is performed.
- Real add/remove/duplicate acceptance, native privilege behavior, cloud playback-report
  IDs, native karaoke timing, account-switch cooperation, production standalone upgrade
  histories, independent startup and paired release/device regression remain required.
  The next bounded implementation work is cloud playback-report identity analysis;
  no complete D1/D2/D3/D4/D5/D6 acceptance is inferred from this checkpoint.

Local evidence: `/tmp/meilox-cloud-playlists-final-source-validation.log`,
`/tmp/meilox-cloud-playlists-standalone-device.log`,
`/tmp/meilox-cloud-playlists-parasite-device.log`,
`/tmp/meilox-cloud-playlists-parasite-device-rerun.log`,
`/tmp/meilox-cloud-playlists-final-standalone-device.log`,
`/tmp/meilox-cloud-playlists-final-parasite-device.log`,
`/tmp/meilox-cloud-playlists-host-probe-build.log`,
`/tmp/meilox-cloud-playlists-host-closed-probe.log`,
`/tmp/meilox-cloud-playlists-normal-module-build.log`,
`/tmp/meilox-cloud-playlists-standalone-picker.png`,
`/tmp/meilox-cloud-playlists-tv-cloud-player.png`,
`/tmp/meilox-cloud-playlists-tv-cloud-menu.png`,
`/tmp/meilox-cloud-playlists-tv-cloud-picker.png`,
`/tmp/meilox-cloud-playlists-final-standalone-ready.png` and
`/tmp/meilox-cloud-playlists-final-tv-home.png`. Generated artifacts, private payloads,
credentials, device logs and official source are not committed. No push or merge.

### D1/D4 Checkpoint: Source-Owned Cloud Playback Reports (2026-10-01)

This is progress on the existing dual-runtime goal, not complete cloud, native reporting,
server-statistics or release acceptance. API-025 records the identity/origin distinction.

- The pinned TV APK's actual DEX confirms the song event `id` comes from MusicInfo's
  `getFilterMusicId()`, whose remote match implementation uses the parsed audio ID.
  Neither the cloud UI/deletion entry nor the playback file-owner tuple belongs in
  this field. Native PlayExtraInfo origin/sourceId is a separate unfinished contract;
  source type 50's `cloudSong` label alone does not qualify its caller/context.
- Both shared player callbacks now feed the full source key to the existing active-time
  timer. Reporter start captures the typed source, validates entry/key/account ownership
  and keeps it through queued dispatch and completion matching. Both runtime sinks
  reject foreign sources and entry-ID bodies before emitting/transmitting. Wire `id`
  uses audio identity only; internal entry/file-owner/account fields never enter reports.
- Standalone retains its original Cookie weblog/NCBL contracts. NCBL context storage
  distinguishes different private files with equal audio IDs/start times, without
  changing their protocol or importing TV fields. Parasite retains the official SDK
  legacy/BI channels and their existing generation/duplicate ownership guards. No UI,
  layout, resource, navigation, database schema or playback-origin redesign is made.
- Paired validation passes: 731 standalone JVM tests in 89 suites and 791 parasite
  tests in 95 suites, zero failures/errors/skips. Both debug/instrumentation and
  unsigned minified release builds pass, including vital lint; both release APKs
  pass 16 KB ZIP alignment and retain production application IDs. Standalone has no
  Xposed metadata; parasite remains API 102, TV-only, hot reload off. Release execution,
  signing/upgrade and independent startup are not established by package checks.
- Closed AVD fixtures pass 42 standalone and 43 parasite tests, including five new
  Media3/source/timer/report tests per runtime and the previous cloud playlist,
  favorite, lyric and persistence fixtures. The normal parasite target is rerun after
  probe restoration. Synthetic sinks do not send listening-statistics or account writes.
- A debug-gated closed probe also passes inside the actual TV process. Its separate
  synthetic report bridge/session validates audio-ID bodies, full-key matching,
  account/recovery rejection and active seconds without rebinding AppGraph or invoking
  the official SDK. The normal module is rebuilt/installed/cold-started afterwards;
  packaged APP is true and HOST/RUNTIME/WORK probes are false. JVM suites are rerun
  with the normal flags before that installation.
- Actual official-account cloud reads display the user's uploaded
  `唯有追赶风的方向` in the unchanged cloud tab. Selecting the existing row starts
  its one-entry cloud queue from position zero. The 123,871 ms track reaches its
  natural repeat transition; both official legacy `play` and BI `_pld` process 124
  active seconds with the same captured start as their corresponding start events.
  The next repeat receives a distinct start timestamp and is paused at 31,423 ms.
  Existing loop mode is preserved, not toggled. This is actual playback/SDK-processing
  evidence, not HTTP/business acceptance or final server history/aggregation proof.
- AudioFlinger records the TV PID's active 48 kHz stereo PCM track with queued frames,
  no track underruns and neither port nor internal mute set. The original expanded
  player renders progress/transport state in screenshots. These engine/output-state
  observations do not establish audible sound from this AVD, native KRC timing or
  visualizer/all-device acceptance. No additional audible confirmation is requested.
- The final AVD remains awake, portrait, authenticated MeiloX Home with playback
  paused; the official TV player is inactive STOPPED. The accepted TV-owned media row
  820 remains 22,705,573 bytes and not pending. Cloud files/playlists/favorites, download
  quotas, uploads and social recipients are untouched; only real playback emits reports.
  No screen-off, global orientation, system scope or framework Hook change is made.
- Local history replay still loses cloud source metadata in its legacy Room mapping
  and requires a bounded persistence adaptation next. Native cloud origin/ancillary
  reporting fields, nonempty native KRC timing, real authorized library writes,
  account-switch cooperation, production upgrades, independent startup and paired
  release/device qualification remain gates. D1/D2/D3/D4/D5/D6 are not marked complete.

Local evidence: `/tmp/meilox-cloud-report-source-validation.log`,
`/tmp/meilox-cloud-report-normal-module-build.log`,
`/tmp/meilox-cloud-report-standalone-device.log`,
`/tmp/meilox-cloud-report-parasite-device.log`,
`/tmp/meilox-cloud-report-final-parasite-device.log`,
`/tmp/meilox-cloud-report-host-probe-build.log`,
`/tmp/meilox-cloud-report-host-closed-probe.log`,
`/tmp/meilox-cloud-report-real-playback-start.log`,
`/tmp/meilox-cloud-report-real-playback-final.log`,
`/tmp/meilox-cloud-report-real-sdk-start.log`,
`/tmp/meilox-cloud-report-real-sdk-final.log`,
`/tmp/meilox-cloud-report-audio-flinger.log`,
`/tmp/meilox-cloud-report-standalone-ready.png`,
`/tmp/meilox-cloud-report-tv-cloud-read.png`,
`/tmp/meilox-cloud-report-tv-playing-a.png`,
`/tmp/meilox-cloud-report-tv-playing-b-paused.png` and
`/tmp/meilox-cloud-report-final-tv-home.png`. Generated artifacts, official source,
private payloads, device logs and credentials are not committed. No push or merge.

### D1/D4 Checkpoint: Source-Owned Local Cloud History Replay (2026-10-01)

API-026 records the local/remote history distinction. This checkpoint advances the
existing dual-runtime goal; it does not complete cloud or release acceptance.

- New local cloud playback records use the existing canonical source string as their
  Room primary key, preserving entry/audio/file-owner/account affinity and exact
  millisecond duration. Ordinary/local mapping, schema version 21, original data and
  completed download rows remain unchanged. No legacy row is guessed or rewritten.
- Playback captures ownership before asynchronous persistence. Cancellation/current
  session guards surround the existing transaction, including a post-insert check;
  failure rolls back replaced metadata and cascading history. Cloud writes do not
  take the ordinary split-write constraint fallback. This is device-local recording,
  not a new backend report route or a remote account mutation.
- Both unchanged history click paths provide full cloud MediaItems to the existing
  queue while ordinary/local entries retain baseline hydration. Publication and
  clicks enforce current account ownership, account/recovery transitions reject old
  remote results, and merge identity distinguishes different private files with equal
  entry IDs. Recovery retains owned device-local metadata for the established offline
  policy but does not bypass remote source authorization. Foreign/malformed rows are
  preserved on disk rather than silently deleted or converted into catalog songs.
- Paired JVM validation passes 741 standalone tests in 90 suites and 801 parasite
  tests in 96 suites, zero failures/errors/skips. Both debug/instrumentation and
  unsigned minified release builds pass, including vital lint. Release APKs keep
  production IDs and pass 16 KB ZIP alignment; standalone has no Xposed metadata and
  parasite remains API 102, TV-only, hot reload off. Signing, release execution,
  production upgrade and independent startup are not established by these checks.
- Closed AVD fixtures pass 54 standalone and 55 parasite tests, including twelve new
  source/Room/transaction/reopen/history-queue cases per runtime and the previous
  cloud report, source, lyric, favorite and playlist cases. The initial history
  fixture exposed a non-void Kotlin-inferred test signature, corrected to Unit before
  acceptance. A post-restoration parasite attempt failed the platform process attach
  timeout before tests began; its PID crash buffer was empty and the recorded rerun
  passes all 55 cases. The attach failure's cause remains undetermined.
- A separate in-memory Room/session probe inside the actual TV process passes source
  roundtrip, exact duration, changed-account transaction rollback and ordinary row
  preservation without rebinding AppGraph or invoking SDKs. Actual selection of the
  user's uploaded `唯有追赶风的方向` creates a source-owned local record. Clicking
  that record in the existing History tab builds its 48-entry queue and progresses
  from zero through real playback. A read-only probe after process recreation verifies
  selected index 0 still has the full source and exact 123,871 ms duration.
- The ordinary module is rebuilt/installed/cold-started with APP true and all
  HOST/RUNTIME/WORK probes false. Its restored history queue is paused without autoplay;
  a further normal-module history click starts from zero and reaches 20,566 ms before
  pausing at 20,784 ms. Existing expanded-player/list screenshots confirm the original
  layout and transport state. Standalone's different Cookie account displays its own
  history and retains its 1,517-entry queue/index 879, paused at 60,347 ms. No audible
  output or native KRC/animation-wide acceptance is inferred from these observations.
- Legacy numeric cloud-looking rows remain ordinary because their source cannot be
  reconstructed safely. A same-title older row remains visible beside the new owned
  record. Native private/unmatched remote recent-history source decoding is a separate
  unfinished mapping; title matching or hiding the older row would not qualify it.
- Final AVD state is awake, portrait, authenticated TV MeiloX Home, playback paused.
  The official TV player is inactive STOPPED; both current-PID crash buffers are empty.
  Accepted TV media row 820 is still TV-owned, not pending and 22,705,573 bytes. A newly
  attached physical device is not operated: all subsequent ADB commands explicitly
  select emulator-5554. No real favorite/playlist/social write, upload/delete, quota
  download, account mutation, screen-off, global rotation or LSPosed scope change is
  performed. Real playback alone may emit existing official SDK listening reports.
- Remote private-history parity, native cloud origin/ancillary reporting fields,
  nonempty native KRC timing, cloud upload/provider lifecycle, real authorized library
  writes/account switching, TV microphone/PiP capability, production upgrades,
  independent startup, paired signed release regression and server final aggregation
  remain gates. D1/D2/D3/D4/D5/D6 are not marked complete.

Local evidence: `/tmp/meilox-cloud-history-source-validation.log`,
`/tmp/meilox-cloud-history-device-fixture-rebuild.log`,
`/tmp/meilox-cloud-history-final-source-validation.log`,
`/tmp/meilox-cloud-history-standalone-device.txt`,
`/tmp/meilox-cloud-history-standalone-device-rerun.txt`,
`/tmp/meilox-cloud-history-parasite-device-rerun.txt`,
`/tmp/meilox-cloud-history-final-parasite-device.txt`,
`/tmp/meilox-cloud-history-final-parasite-device-rerun.txt`,
`/tmp/meilox-cloud-history-host-probe-build.log`,
`/tmp/meilox-cloud-history-host-selected-probe-build.log`,
`/tmp/meilox-cloud-history-host-closed-probe.log`,
`/tmp/meilox-cloud-history-host-read-before-replay.log`,
`/tmp/meilox-cloud-history-host-read-after-cold.log`,
`/tmp/meilox-cloud-history-cold-restored.log`,
`/tmp/meilox-cloud-history-normal-cold.log`,
`/tmp/meilox-cloud-history-normal-replay-progress.log`,
`/tmp/meilox-cloud-history-normal-paused.log`,
`/tmp/meilox-cloud-history-tv-history.png`,
`/tmp/meilox-cloud-history-tv-replay-playing.png`,
`/tmp/meilox-cloud-history-standalone-history.png`,
`/tmp/meilox-cloud-history-normal-player-paused.png` and
`/tmp/meilox-cloud-history-final-tv-home.png`. Generated artifacts, private payloads,
device logs, credentials and official source are not committed. No push or merge.

### D1/D4 Checkpoint: Session-Owned Cloud Provider Cancellation (2026-10-01)

API-027 records provider and native SDK cancellation boundaries. This increment
keeps the shared upload flow, original frontend, snapshot metadata and publication
ordering; it does not qualify successful real cloud transfer or complete D4.

- Shared file preparation now forwards the preparation job's CancellationSignal
  to ContentResolver query/asset opening and closes its active descriptor/stream
  on cancellation. Current job/account/recovery checks follow blocking operations,
  copying, local metadata and MIME lookup. Prompt cancellation, provider denial,
  copy failure and late-account rejection remove only the private snapshot, never
  the selected source. Asset offset/length and existing tag/name/MIME fallbacks
  remain intact; authorization and transfer use the same copied bytes and digest.
- Recovery is checked independently of generation at coordinator business/progress
  boundaries and both binary adapters. Standalone also observes recovery while
  its OkHttp call is blocked and cancels that call. Parasite uses the official SDK's
  existing cancellation/progress callbacks, without a standalone transport fallback,
  retry or borrowed token. JVM substitutes cover recovery before dispatch, after
  every business response, during transfer and while standalone response I/O blocks.
- A test-APK-only platform Java provider generates one second of silent PCM WAV
  audio in UUID-owned cache files. Actual ContentResolver IPC passes fifteen new
  cases per runtime: byte/digest consistency, descriptor slices, denial/missing/empty/
  broken sources, cooperative query/open cancellation, changed account and recovery,
  late MIME results, source mutation after copying, business/transfer rejection,
  interrupted transfer, missing metadata, denied MIME, uncooperative cancellation
  after return and scoped interrupted-snapshot cleanup. Business and binary sinks
  remain closed substitutes; no official authorization or real account write occurs.
- The first Kotlin provider could not resolve Kotlin runtime classes in its separate
  test-APK process. It was replaced with platform-only Java rather than adding a
  production dependency. The first Java run exposed two test assumptions: read-only
  ContentResolver asset opening uses the typed-asset overload, and a denied getType
  can become null. The fixture now explicitly forwards that overload's signal, and
  tests preserve the original audio/mpeg fallback. These initial failures do not
  qualify production behavior; the corrected paired runs below are authoritative.
- The pinned TV APK's actual uploader DEX checks digest cancellation before LBS or
  source allocation/transfer. A temporary WORK-probe build inside the real TV process
  invokes a fresh ServiceFacade-backed adapter without rebinding AppGraph: zero-byte
  input returns -1; three synthetic bytes with an SDK digest cancellation callback
  return -2, with zero progress callbacks. The recorded pass establishes reflection,
  initialized uploader and pre-transfer cancellation, not successful upload. Inputs
  use an invalid fixture token; no token allocation, LBS request, transfer or publish
  is reached. The owned fixture file is deleted in finally.
- Final source validation passes 746 standalone JVM tests in 90 suites and 805
  parasite tests in 96 suites, zero failures/errors/skips. Both debug/instrumentation
  and unsigned minified release builds pass, including vital lint. Both release APKs
  pass 16 KB ZIP alignment and keep production IDs; the test provider is absent from
  both release manifests. Standalone has no Xposed metadata; parasite is API 102,
  TV-only, static scope, hot reload off. Signing/runtime/upgrade acceptance is not
  established by these package checks.
- Closed AVD regression passes 69 standalone and 70 parasite cases, combining the
  provider cases with existing cloud source, history, reporting, lyric, favorite and
  playlist tests. A second parasite run after restoring the ordinary module passes
  all 70 again. The ordinary installed APK has APP true and HOST/RUNTIME/WORK false;
  TV cold-starts into authenticated portrait MeiloX Home, with no autoplay. Its
  48-entry queue/index 0 remains paused at 20,790 ms; standalone's different Cookie
  account retains its 1,517-entry queue/index 879, paused at 60,347 ms. Home screenshots
  and current-PID empty crash buffers support this limited restoration check, not
  full visual/motion/audio acceptance. The official TV player is inactive STOPPED.
- Accepted TV MediaStore row 820 remains TV-owned, not pending and 22,705,573 bytes.
  No real upload/delete, favorite/playlist/social write, quota download, account
  mutation, screen-off, physical-device operation, global orientation change or
  LSPosed scope change is performed. Every ADB command selects emulator-5554.
- CancellationSignal depends on provider/platform cooperation. MIME lookup has no
  signal argument, and uncooperative provider reads or host SDK I/O may delay return;
  post-return ownership checks are not a universal immediate interruption guarantee.
  Real external provider grants/reboot/lifecycle, successful official transfer/progress,
  expired NOS tokens and publication reconciliation remain open, as do private remote
  history, native KRC/report metadata, authorized library/account mutations, TV
  microphone/PiP capability, production upgrades, independent startup, paired signed
  release regression and server aggregation. D1/D2/D3/D4/D5/D6 remain incomplete.

Local evidence: `/tmp/meilox-cloud-provider-final-validation.log`,
`/tmp/meilox-cloud-provider-standalone-final-device.log`,
`/tmp/meilox-cloud-provider-parasite-final-device.log`,
`/tmp/meilox-cloud-provider-sdk-probe-final-build.log`,
`/tmp/meilox-cloud-provider-sdk-probe-current-pid.log`,
`/tmp/meilox-cloud-provider-host-upload-dex.txt`,
`/tmp/meilox-cloud-provider-host-digest-dex.txt`,
`/tmp/meilox-cloud-provider-normal-module-final-build.log`,
`/tmp/meilox-cloud-provider-normal-parasite-final-device.log`,
`/tmp/meilox-cloud-provider-standalone-release-manifest.xml`,
`/tmp/meilox-cloud-provider-parasite-release-manifest.xml`,
`/tmp/meilox-cloud-provider-parasite-test-manifest.xml`,
`/tmp/meilox-cloud-provider-standalone-final-media.txt`,
`/tmp/meilox-cloud-provider-tv-final-media.txt`,
`/tmp/meilox-cloud-provider-final-normal-tv.log`,
`/tmp/meilox-cloud-provider-final-standalone-home.png` and
`/tmp/meilox-cloud-provider-final-tv-home.png`. Initial fixture failures are preserved
in the corresponding `fixture-crash`, `standalone-platform-fixture` and
`parasite-platform-fixture` logs. Generated files, private payloads, credentials,
official source and device logs are not committed. No push or merge.

### D5/D6 Checkpoint: Minified Parasite Runtime Smoke (2026-10-01)

- Executed the existing R8 parasite release from `708c938c`, locally signed with the
  installed module's development key. Signature verification and 16 KB ZIP alignment
  pass. API 102, TV-only static scope and disabled hot reload remain in the signed APK.
  This is local release-runtime evidence, not production signing or hosted CI proof.
- A data-preserving module replacement and TV cold start restore authenticated portrait
  MeiloX Home, artwork and the existing paused queue. The original expanded player
  renders, starts real playback and continues after Home. AudioFlinger reports an
  active, unmuted TV-owned track and increasing output frames while backgrounded.
  These observations establish decoding/output activity, not audible acceptance.
- The actual media notification returns to the same portrait MeiloX player in TV's
  LoadingActivity. Playback is explicitly paused afterward: 48 queue entries, index 2,
  position 22,307 ms, no session error. The official TV player remains inactive STOPPED;
  the isolated standalone retains 1,517 entries/index 879, paused at 60,347 ms. The
  current TV PID crash buffer is empty. Natural queue advancement during observation
  is not a controlled consecutive-track or full playback/reporting regression.
- No source/frontend change, new cloud test, quota download, account/library/social
  mutation, original standalone update, device restart, screen-off or LSPosed scope
  change is performed. All ADB commands select emulator-5554, never the physical device.
  Leave the validated local-signed R8 module installed and playback paused.
- Installed original standalone and current debug APKs share a development certificate;
  the repository's older release APK has a different certificate. A future consented
  AVD upgrade with the development key cannot qualify production-release compatibility.
  Framework-free startup, real data-preserving upgrade, standalone R8 execution and
  remaining paired business/lifecycle acceptance stay open. No milestone is completed
  by this limited smoke test; existing paired tests/builds are not rerun for this
  documentation-only checkpoint.

Local evidence: `/tmp/meilox-minified-parasite-first-launch-2026-10-01.png`,
`/tmp/meilox-minified-parasite-expanded-player-2026-10-01.png`,
`/tmp/meilox-minified-parasite-background-audioflinger-2026-10-01.txt`,
`/tmp/meilox-minified-parasite-notification-return-settled-2026-10-01.png` and
`/tmp/meilox-minified-parasite-final-paused-2026-10-01.txt`. Generated APKs, recordings,
logs and private data are not committed. No push, merge or remote release.

### D1/D3/D6 Checkpoint: Standalone Session Settings and Boundary Audit (2026-10-01)

- ABI-012 records a dual-runtime restoration omission, not a new frontend redesign:
  standalone regains its original General settings Account/MUSIC_U row and WebView
  waiting copy. Parasite retains QR/host copy and has no credential-export section.
  Flavor-owned leaf content keeps the existing shared page, layout and resource IDs.
- Current shared production sources have no concrete parasite/standalone implementation
  imports, libxposed imports or parasite build flags. Runtime-owned graph/bootstrap,
  session/reporting, component and named transport bindings select each backend.
  Paired contract tests pass: 746 standalone/90 suites and 805 parasite/96 suites,
  zero failures, errors or skips. D1's backend-neutral dependency-boundary exit
  condition is now met; business/session/device parity remains D3-D5 work.
- Both debug/instrumentation and unsigned minified releases build successfully with
  vital lint. Release alignment passes. The paired workflow suite passes its 26 local
  cases and real SDK signing/identity/version/alignment fixtures for the rebuilt APKs,
  including swapped/unsigned rejection and temporary-key cleanup. No production key,
  remote CI, tag, upload, release or original standalone installation is touched.
- Matched debug device package tests pass 5 standalone and 4 parasite cases, including
  flavor-specific actual resource resolution. Standalone's unchanged General screen
  renders the export row; actual local-signed parasite R8 General has no MUSIC_U export.
  No credential export is clicked. The failed debug-test/R8-target attempt and its
  test-runner Intrinsics resolution error are preserved separately, not counted as
  passed release tests. No application keep rules are changed to accommodate it.
- The previous R8 host package additionally renders search suggestions, artist results,
  artist detail/artwork/hot songs, album results and a 15-track album detail through
  existing UI. After installing the rebuilt R8 module, TV cold start again restores
  authenticated portrait Home and its 48-entry/index-2 queue, paused at 22,312 ms.
  Standalone retains 1,517 entries/index 879 at 60,347 ms; the official TV player is
  inactive STOPPED. Current TV PID has no crash-buffer entries; accepted media row 820
  remains TV-owned, published and 22,705,573 bytes. These are bounded smoke checks,
  not full catalog, motion, audio, reporting, upgrade or paired release acceptance.
- `main` remains `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`. The bounded integration
  review identified historical UI-only commit `f0c55cac`; the user explicitly permits
  retaining that glass-toggle fix. It is left unchanged as an approved exception,
  with no further frontend fixes. The complete integration review remains open.

Local evidence: `/tmp/meilox-session-settings-restoration-build-2026-10-01.log`,
`/tmp/meilox-session-settings-standalone-package-tests-2026-10-01.log`,
`/tmp/meilox-session-settings-parasite-matched-debug-package-tests-2026-10-01.log`,
`/tmp/meilox-session-settings-release-instrumentation-crash-2026-10-01.txt`,
`/tmp/meilox-session-settings-paired-signed-fixtures-2026-10-01.log`,
`/tmp/meilox-session-settings-standalone-general-settled-2026-10-01.png`,
`/tmp/meilox-session-settings-parasite-release-general-2026-10-01.png` and
`/tmp/meilox-session-settings-final-tv-home-2026-10-01.png`. All remain local; no push
or merge. This checkpoint does not reopen cloud investigation.

### D4/D5/D6 Checkpoint: Social Session Ownership (2026-10-01)

- The bounded merge audit found a concrete migration omission: shared private-message
  pages still consumed standalone `UserIdKey`, which parasite does not store. API-028
  records the correction and unchanged business routes. Conversation lists/history,
  contacts, text/resource sending and timeline sharing now take a captured common
  session; both flavor transports still own authentication and signing.
- Existing social pages and share modes remain shared and retain their layout/routes.
  No frontend problem from `main` is repaired. Public identity supplies participant
  selection and outgoing-message classification; private state, drafts and selected
  recipients clear on session replacement. Late results, stale callbacks and queued
  writes cannot silently inherit the replacement authorization. Backend-specific
  credentials never become common state.
- Full paired JVM suites pass: 765 standalone/91 suites and 824 parasite/97 suites,
  1,589 tests with zero failures/errors/skips. The 19 new social cases run in both.
  On emulator-5554, each matched debug variant passes nine real-Repository/platform
  JSON tests with substitute transports only. They verify request tags, preserved
  bodies/routes, multi-page ownership, rejection and cancellation. No real contact
  message, timeline publication, room creation, upload or download grant is attempted.
- Both debug/instrumentation and minified releases build successfully with vital lint.
  Both release packages pass 16 KB alignment. The paired signing workflow's 26 local
  cases and rebuilt-APK real-SDK signing/identity/version/alignment/rejection fixtures
  pass; temporary fixture keys/APKs are removed. No production key or remote workflow
  is used, and no original standalone installation is modified.
- Standalone debug cold startup (2,919 ms) restores its own account and paused queue;
  the original private-message and contact lists load through Cookie transport. The
  latest locally development-signed parasite R8 replaces only the module, then a
  2,482 ms TV cold start restores authenticated portrait Home. Its same social lists
  load through the official session without a Cookie preference dependency. Screenshots
  confirm the unchanged page structure and visible results. Neither app opens a real
  conversation or invokes a send/publish action; write paths are qualified only by
  substitutes, not server delivery or cooperating-account acceptance.
- Both players stay paused: standalone 1,517 entries/index 879 at 60,347 ms; parasite
  48 entries/index 2 at 22,312 ms. The official TV session stays inactive STOPPED and
  the current TV PID crash buffer is empty. Accepted TV MediaStore row 820 remains
  published, TV-owned and 22,705,573 bytes. No emulator restart, screen-off, physical
  device operation, scope change or cloud investigation is performed.
- `main` still resolves to `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`; historical
  glass-toggle exception remains untouched. This is an additional bounded integration
  review, not full merge approval. The remaining legacy identity read in
  `ListenTogetherStore.establish` can misclassify an official-room participant as its
  creator, and its unstamped multi-request/player sequence needs a separate bounded
  migration and substitute verification. Do not mark together-listening complete from
  the social tests. Framework-free startup, original data-preserving upgrade, paired
  full release execution and the existing remaining business/capability gates stay open.

Local-only evidence: `/tmp/meilox-social-session-paired-build-2026-10-01.log`,
`/tmp/meilox-social-session-standalone-repository-fixtures-2026-10-01.log`,
`/tmp/meilox-social-session-parasite-repository-fixtures-2026-10-01.log`,
`/tmp/meilox-social-session-paired-signing-fixtures-2026-10-01.log`,
`/tmp/meilox-social-session-standalone-conversations-settled-2026-10-01.png`,
`/tmp/meilox-social-session-standalone-contacts-settled-2026-10-01.png`,
`/tmp/meilox-social-session-parasite-release-conversations-2026-10-01.png`,
`/tmp/meilox-social-session-parasite-release-contacts-2026-10-01.png` and
`/tmp/meilox-social-session-final-media-2026-10-01.txt`. Private UI content, logs,
credentials, generated packages and official source are not committed. No push or merge.

### D4/D5/D6 Checkpoint: Together Room Session Ownership (2026-10-01)

- API-029 records the bounded migration found by the preceding integration audit.
  `ListenTogetherStore` no longer reads standalone `UserIdKey` or substitutes the
  creator as the local user. Public runtime identity determines participant/creator
  role, invitation inviter and playlist-version ownership. Nine Repository operations
  and supplementary song-detail pages carry the same captured authorization.
- Standalone retains the original business routes, WeAPI/EAPI selection and room
  payloads; parasite uses its official session/transport for those supplemental routes.
  There is no copied Cookie, standalone fallback or new native TV room-controller
  assumption. No original page, entry, glass control or queue/shuffle mapping is removed.
  Existing actions capture their visible session/room; manually edited invitation state
  follows the session while incoming navigation invitations remain available.
- Each room action, monitor and queued player report belongs to a session generation,
  room-work generation and player attachment. Invalidation clears room state
  synchronously and cancels the entire old job tree. Late check/accept/create/playback
  results, detail-page continuations, delayed reports, detached listeners and old end
  callbacks cannot continue under the replacement owner. Short player publications
  also guard reentrant invalidation between queue replacement, prepare, seek and play.
  A retiring monitor's network error cannot publish into or crash the new session.
- Latest full paired JVM suites pass: standalone 765/91 suites and parasite 824/97
  suites, 1,589 tests with zero failures/errors/skips. Each matched debug runtime on
  emulator-5554 passes 23 new Store/platform cases, nine new real-Repository/platform
  JSON cases and nine existing social Repository regression cases: 41 per flavor.
  The fixture-only Android coroutine-test dependency supplies deterministic timing;
  production dependencies are unchanged. Rooms, API transports and players are
  synthetic; no actual creation, invitation acceptance, heartbeat or command is sent.
- Both latest debug/instrumentation APK pairs and minified releases build successfully
  with vital lint (5m 52s). Both release APKs pass 16 KB alignment. Signing fixtures,
  workflow wiring and real-SDK rebuilt-APK identity/version/signature/alignment checks
  pass, including swapped/unsigned-pair rejection and temporary fixture-key cleanup.
  The locally development-signed parasite R8 package retains API 102, TV-only static
  scope and disabled hot reload; standalone contains no modern module declaration.
  No production signing credential, original standalone upgrade or remote CI is used.
- Standalone debug cold startup (5,040 ms) restores its separate Cookie account and
  paused queue. The locally signed latest parasite R8 replaces only the module;
  TV cold startup (2,634 ms) restores authenticated portrait MeiloX Home. Original
  Together pages render in both with create/input/paste/join controls and no room.
  Screenshots qualify this unchanged read-only page structure, not real cooperative
  room behavior or startup performance. No create/join/end button is invoked.
- Both queues remain paused and unchanged: standalone 1,517 entries/index 879 at
  60,347 ms; parasite 48 entries/index 2 at 22,312 ms. The official TV player remains
  inactive STOPPED with an empty queue. Current TV PID 7122 has an empty crash buffer.
  Accepted TV MediaStore row 820 remains published, TV-owned and 22,705,573 bytes.
  The AVD is left on portrait TV MeiloX Home with the R8 module installed. Only the
  task-owned temporary UI XML is removed; no emulator restart, screen-off, physical
  device operation, orientation override change or LSPosed scope change is performed.
- `main` remains `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`. The user explicitly
  permits retaining historical glass-toggle fix `f0c55cac`; its four files remain
  untouched. This checkpoint closes the identified Together ownership omission, not
  Together server/cooperating-account acceptance or full D4-D6 qualification. The
  existing framework-free startup, original data-preserving upgrade, paired full
  release execution, capability and remaining business/lifecycle gates stay open.
  Do not restart cloud investigation as a prerequisite for these next gates.

Local-only evidence: `/tmp/meilox-listen-session-final-paired-build-2026-10-01.log`,
`/tmp/meilox-listen-session-standalone-final-fixtures-2026-10-01.log`,
`/tmp/meilox-listen-session-parasite-final-fixtures-2026-10-01.log`,
`/tmp/meilox-listen-session-paired-signing-fixtures-2026-10-01.log`,
`/tmp/meilox-listen-session-standalone-page-2026-10-01.png`,
`/tmp/meilox-listen-session-parasite-release-page-2026-10-01.png`,
`/tmp/meilox-listen-session-final-tv-home-2026-10-01.png` and
`/tmp/meilox-listen-session-final-media-2026-10-01.txt`. Private UI content, logs,
credentials, generated APKs and official source are not committed. No push or merge.

### D3/D5/D6 Checkpoint: Standalone R8 Execution and Stable Checkpoints (2026-10-01)

- A local-only Gradle init script redirects build output to `/tmp` and changes only
  the validation `standaloneRelease` application ID to the existing isolated debug
  package. Normal R8/resource shrinking and DEBUG=false remain enabled. The artifact
  uses the existing development certificate and updates only that test installation
  with `-r`, preserving UID 10254. No permanent flavor, production ID, signing policy
  or build configuration is changed; the original standalone app remains untouched.
- Before the checkpoint-only fix, actual R8 cold startup (568 ms) restores account,
  artwork and a 1,517-entry queue. Original play controls resume decoding; MediaSession
  is PLAYING with no error and AudioFlinger shows an active, unmuted 44.1 kHz output.
  Output frame count advances by 12,510,912. Home-key background playback continues,
  and the system media notification returns to the original expanded player. A six-
  second capture sampled at 2 fps shows moving fluid background and advancing time,
  not just a static screenshot. Natural shuffle advancement is observed; this is not
  a controlled complete-track-from-zero or all-effects/performance qualification.
- Standalone's original weblog and NCBL report paths receive successful server
  responses, including NCBL start/end receipts. These prove request acceptance only,
  not final listening statistics or audible output. The user cannot hear this AVD;
  no new listening-confirmation request or audible-acceptance claim is made. The first
  post-install WARM start and a notification UI-dump idle timeout are excluded from
  cold-start and app-crash conclusions respectively.
- Returning from R8 to debug exposed the real persistence omission recorded as
  ABI-013. Only the shared checkpoint's seven JSON field names and verified legacy
  aliases are stabilized; no UI, page architecture, queue policy or frontend bug is
  changed. The fixed isolated R8 cold start (803 ms) retains paused metadata, then
  brief play/pause writes canonical disk keys. Reinstalling latest ordinary debug and
  cold starting (1,164 ms) preserves 1,517 entries/index 548 at 109,186 ms with no
  auto-play. MediaSession NONE/speed 0 is the restored unprepared state, not PAUSED.
- Nine new wire-contract tests pass per flavor. Latest full paired JVM suites pass
  774 standalone tests/92 suites and 833 parasite tests/98 suites: 1,607 total with
  zero failures/errors/skips. Both debug/instrumentation pairs and production R8
  releases build with vital lint (5m 37s); fixed local-ID R8 also builds (3m 21s).
  Both actual production DEX files retain canonical checkpoint fields and runtime
  annotations. Paired real-SDK signing fixtures and 16 KB ZIP alignment checks pass.
- Latest development-signed parasite R8 is reinstalled; TV cold start (1,473 ms)
  displays portrait authenticated MeiloX Home. Its 48-entry queue/index 2 remains
  paused at 22,312 ms; official TV playback remains STOPPED with an empty queue.
  Current TV PID 14006 and standalone debug PID 13704 have empty crash buffers.
  Accepted MediaStore row 820 stays published, TV-owned and 22,705,573 bytes. The
  ordinary standalone debug build is restored and the AVD is left on TV MeiloX Home.
- This adds minified standalone execution and cross-build persistence evidence, not
  production-ID execution, production signing/upgrade, framework-free startup or full
  paired release acceptance. The two pending device/upgrade permission requests are
  not bypassed. Remaining capability, business and lifecycle gates stay open. No
  cloud investigation, quota download, upload, real social action, emulator restart,
  screen-off, physical-device operation or scope/orientation change is performed.
  The user-approved historical `f0c55cac` frontend fix remains untouched.

Local-only evidence: `/tmp/meilox-standalone-r8-validation-2026-10-01.init.gradle`,
`/tmp/meilox-standalone-r8-validation-playback-2026-10-01.mp4`,
`/tmp/meilox-checkpoint-wire-paired-build-2026-10-01.log`,
`/tmp/meilox-checkpoint-wire-paired-signing-fixtures-2026-10-01.log`,
`/tmp/meilox-checkpoint-wire-standalone-dex-2026-10-01.txt`,
`/tmp/meilox-checkpoint-wire-parasite-dex-2026-10-01.txt`,
`/tmp/meilox-checkpoint-wire-fixed-r8-progress-2026-10-01.txt`,
`/tmp/meilox-checkpoint-wire-restored-debug-media-2026-10-01.txt` and
`/tmp/meilox-checkpoint-wire-parasite-home-2026-10-01.png`. Private evidence, APKs,
official source and credentials are not committed. No push or merge.

### D5/D6 Checkpoint: Actual Playing-Task Removal and Notification Return (2026-10-01)

- The unchanged source/artifacts from the preceding checkpoint are exercised on
  emulator-5554: development-signed parasite R8 and the same isolated debug-ID
  standalone R8 fixture. Actual identified Recents cards are swiped away while each
  player is running; force-stop is not substituted for task removal. Settled Activity
  stacks establish removal, excluding transitional snapshots with exiting windows.
- Both foreground services survive with PLAYING MediaSessions and advancing progress.
  TV uses the registered `LocalMusicMatchService` carrier; standalone uses its own
  `MusicService`. Tapping the actual system media notification recreates portrait
  MeiloX Home in a new task. The official TV session remains inactive STOPPED/empty.
  ABI-003 records this bounded extension; no component or control code is changed.
- Natural advancement occurs during playback, so unchanged indices are not claimed.
  Both players are paused afterward. Ordinary standalone debug is restored with `-r`;
  its cold start retains 1,517 entries/index 366 at 38,715 ms without autoplay (NONE,
  speed 0). TV retains 48 entries/index 3 paused at 10,237 ms. Current debug PID 17275
  and TV PID 14006 have empty crash buffers; MediaStore row 820 remains published,
  TV-owned and 22,705,573 bytes. The rooted AVD remains awake on portrait TV MeiloX Home.
- This qualifies the named task-removal/notification path only, not complete paired
  release regression, standalone production signing/upgrade, process-death playback,
  screen-off or audible acceptance. No new build is necessary for this evidence-only
  increment: source and previously tested artifacts are unchanged. No original app
  data, scope, orientation override, permission or real library/social state is altered.
- Reinspection of the supplied TV manifest confirms no RECORD_AUDIO declaration and
  zero PiP carriers. A scoped module-process recording/PiP helper is now explicitly
  awaiting the user's decision; it is not implemented or treated as approved. The
  framework-free AVD and original-install upgrade requests also remain pending.

Local-only evidence: `/tmp/meilox-task-lifecycle-*2026-10-01*` contains identified
Recents/notification screenshots, before/after Activity/service/MediaSession snapshots,
manifest XML and final preservation checks. Private device evidence is not committed.
Only acceptance documents change; no push, merge, emulator restart or physical-device
operation is performed. The full goal and remaining milestone gates stay open.

### D4/D5/D6 Checkpoint: Session-Owned Intelligence Playback (2026-10-01)

- The bounded integration review finds the original heart-mode seed/list sequence
  unstamped. API-030 records the fix: one captured authorization for both requests,
  before/after Repository checks, business-code validation and a session-owned one-shot
  result. Invalidation clears it synchronously; cancelled or late work cannot publish,
  start a second request under a new owner or consume a retired visible result.
- Original Home card/navigation/layout, seed-first assembly and same-owner seed-failure
  fallback are unchanged. The playback owner survives deferred PlayerConnection and
  queue-manager work. Publications validate between queue replacement, preparation
  and play, including reentrant invalidation. Existing unowned ordinary queue callers
  preserve their default path. No new page, hidden feature or frontend repair is added.
- Latest paired JVM suites pass 793 standalone tests/94 suites and 852 parasite
  tests/100 suites: 1,645 total with zero failures/errors/skips. New coverage is 12 state
  and seven Repository cases per flavor. Each matched debug runtime on emulator-5554
  passes six new actual queue-manager/platform-item cases using only synthetic data,
  players and transports. No fixture logs in/out or contacts the official service.
- Both debug/instrumentation pairs and production R8 releases build with vital lint
  (4m 40s). Paired real-SDK signing/metadata/rejection fixtures and both 16 KB alignment
  checks pass. Actual production DEXes retain the new Body/Tag slots and heart POST
  route. No production signing credential, remote CI or original standalone upgrade is
  used. Probe configuration and API 102 TV-only module boundaries are unchanged.
- Latest development-signed parasite R8 cold start (2,965 ms) displays the original
  authenticated portrait Home. Its original heart card creates a 149-entry queue and
  starts its seed with advancing progress/no MediaSession error. Standalone debug cold
  start (1,276 ms) restores its separate Cookie account; the same original card creates
  a 150-entry queue and starts its own seed. Each has an active unmuted AudioFlinger
  track. Queue content differs by account; identical recommendation content is not
  asserted. The official TV player remains inactive STOPPED with an empty queue.
- Latest isolated debug-ID standalone R8 builds (1m 54s), verifies its development
  signature/16 KB alignment and retains UID 10254 with DEBUGGABLE absent. Cold start
  (699 ms) restores the paused queue; its actual original heart card again produces
  150 entries and starts the seed, with advancing progress and an active unmuted
  AudioFlinger track. R8 PID 11467 has an empty crash buffer. This fixture retains the
  previously documented temporary ID override, not a production signing/upgrade claim.
- Both players are paused after testing. Ordinary standalone debug is restored; the
  first post-update UNKNOWN/0 start is excluded from cold-start evidence. A subsequent
  actual cold start (1,471 ms) preserves 150 entries/index 0 at 22,917 ms without
  autoplay (NONE/speed 0). TV preserves 149 entries/index 0 paused at 33,182 ms.
  Final debug PID 12143 and TV PID 10342 have empty crash buffers. Accepted MediaStore
  row 820 remains published, TV-owned and 22,705,573 bytes. The rooted AVD is left
  awake on portrait TV MeiloX Home with latest parasite R8 installed.
- Real click/transport/playback evidence complements, but does not replace, synthetic
  account-change and deferred-publication tests. It does not prove controlled real
  reauthorization, complete-track listening statistics, audible output or all FM/shuffle
  paths. No account/library mutation, quota download/upload, social write, cloud
  investigation, emulator restart/screen-off, scope change or physical-device operation
  occurs. Framework-free startup, original-install upgrade and recording/PiP decisions
  remain pending; no full milestone or the goal is marked complete.

Local-only evidence: `/tmp/meilox-intelligence-session-paired-build-2026-10-01.log`,
`/tmp/meilox-intelligence-session-standalone-queue-fixtures-2026-10-01.log`,
`/tmp/meilox-intelligence-session-parasite-queue-fixtures-2026-10-01.log`,
`/tmp/meilox-intelligence-session-paired-signing-fixtures-2026-10-01.log`, both
`/tmp/meilox-intelligence-session-*-api-dex-2026-10-01.txt` and actual Home/player/
MediaSession/AudioFlinger captures with the same prefix. No private evidence, APKs,
official source or credentials are committed. No push or merge.

### D5/D6 Checkpoint: Paired R8 Sleep-Timer and Notification Paths (2026-10-01)

This is bounded execution evidence for the existing timer, not a frontend change or
complete D5/D6 acceptance. ABI-014 records the runtime component differences.

- Reused the current development-signed parasite R8 and the temporary isolated
  debug-ID standalone R8 fixture from the preceding checkpoint. Standalone signature
  verification passes; its package retains UID 10254 with DEBUGGABLE absent. Its
  separate actual cold start takes 720 ms. The original production app is not updated.
- From the original full-player More Actions entry, each runtime starts five minutes,
  continues playback in the background and returns through its actual timer notification.
  The TV sheet decreases from 4:58 to 0:46; standalone permission recovery/re-entry
  shows 3:14 then 1:56 against the same cutoff. Neither path restarts its deadline.
  Captured active unmuted 48 kHz AudioFlinger tracks belong to the tested runtime.
- At expiry, TV is PAUSED at 29,191 ms/index 1 with 149 items; standalone is PAUSED
  at 128,687 ms/index 25 with 150 items. Natural advancement/shuffle occurred, so an
  unchanged track/index is not asserted. Both remove notification 1002 and render
  the original sheet's Off state. Official TV playback remains inactive STOPPED,
  with an empty queue throughout.
- TV's actual notification cancel action reaches its playback carrier, clears the
  timer and leaves playback running. Standalone's action is qualified separately
  while paused: an end-of-track timer posts, the system action reaches its own
  MusicService, notification 1002 disappears and the sheet shows Off. Natural
  end-of-track completion and standalone cancel-while-playing remain unaccepted.
- Standalone originally lacked POST_NOTIFICATIONS. The timer runs while its original
  permission dialog is unanswered; allowing that dialog posts only the remaining
  countdown. After testing, pm revoke and clear-permission-flags restore ungranted
  permission with the original USER_SENSITIVE flags. Normal debug is restored via a
  data-preserving update; separate cold start (1,603 ms) restores 150 items/index 128
  at 216,702 ms, NONE/speed 0 without autoplay. TV is paused at 134,084 ms/index 1.
- Keep a distinct failure: resuming standalone near the second track's end after
  expiry stalls at 129,910 ms and reports Media3's 30-second no-progress error.
  Next/play recovers, but this does not qualify seamless pause/resume. The retained
  log's R8 map ID matches the fixture; AudioPlayer, StableDeckPlayer and SleepTimer
  have no diff against current main, with Media3 1.10.1 unchanged. Baseline reproduction
  and attribution remain open; do not silently repair unrelated engine behavior or
  count the handled ERROR as an empty-crash-buffer success.
- Focused existing SleepTimer JVM cases pass eight per flavor. All four debug/release
  APK targets build (4m 51s); no application source, UI, manifest or dependency changes
  are made. Final standalone R8/debug and TV crash buffers are empty. MediaStore row
  820 remains TV-owned, published and 22,705,573 bytes. Root AVD stays awake with the
  parasite R8 installed; no restart, system scope/rotation change, quota grant, account
  mutation, upload or social write is performed. D2-D6 and the full goal remain open.

Local-only evidence: `/tmp/meilox-paired-timer-unit-2026-10-01.log`,
`/tmp/meilox-paired-timer-build-2026-10-01.log`, and same-prefix UI/MediaSession/
AudioFlinger/notification/PendingIntent/package captures. The handled error is retained
in `/tmp/meilox-paired-timer-standalone-r8-runtime-log-2026-10-01.txt`; this private log,
all screenshots/APKs and credentials remain outside Git. No push or merge.

### D4/D5/D6 Checkpoint: Session-Owned Personal FM Queues (2026-10-01)

- API-031 fixes a bounded backend continuation issue, not a frontend or playback-engine
  repair. PlayerConnection captures the click's current session before scheduling.
  QueueManager retains that owner across optional seed detail, FM activation, refill,
  metadata hydration and queue/player publications. Recovery, account changes and
  same-account reauthorization reject old work, including between insertion and play.
  The current-generation refill owns its lazy Job reservation; an old finally cannot
  clear a newer reservation. Queue replacement/release retire non-cooperative work.
- The same SessionStore used by the service is passed into QueueManager. FM restore
  captures its startup authorization before disk loading, without binding an old
  continuation to a later account. Duplicate service/listener callbacks capture the
  queue before dispatch and share one active refill. No backend reader executes under
  the session publication monitor; device fixtures assert this boundary.
- Original Home/library cards, pages, navigation, seed-first/no-seed rules, repeat and
  shuffle rules, current-seed exclusion and refill thresholds are retained. Local trash
  remains local only, now owned by its triggering queue/session; no native FM feedback
  context, library mutation, UI repair, new controls or audio-engine change is introduced.
- Latest paired JVM suites pass 799 standalone cases/95 suites and 858 parasite
  cases/101 suites: 1,657 total with zero failures/errors/skips, including six new
  Repository cases per flavor. Each latest matched debug runtime passes 14 new FM
  and six existing heart queue-handoff cases on emulator-5554 (20 each). Synthetic
  tests cover deferred start/refill, late seed/result, same-user reauthorization,
  recovery/guest rejection, duplicate jobs, old-finally ownership, normal queue
  replacement, reentrant insertion, restored ownership, local trash, rejection/release
  and backend-reader lock boundaries. They do not exercise real account changes.
- First post-update parasite instrumentation invocations fail to attach with no tests
  executed. The bounded retries pass on the same matching latest APKs. Retain those
  failed-attempt logs separately; do not count them as successes or infer startup
  reliability from the retry. No app/frontend repair is made for this tooling result.
- All paired debug/instrumentation/release targets build with vital lint (5m 17s).
  Actual production R8 DEXes preserve Radio, Body/Tag slots and the FM POST route.
  Paired real-SDK signature/metadata/16 KB checks and swapped/unsigned rejection
  fixtures pass. Development-signed parasite R8 verifies v3/16 KB alignment and its
  actual cold launch takes 3,962 ms; settled original authenticated portrait Home
  displays, without an official playback queue. No production signing or upgrade is used.
- Standalone debug cold launch takes 3,031 ms and its original private-roaming/FM card
  starts three entries with advancing playback and an active unmuted 48 kHz track.
  Parasite R8's corresponding card starts six entries; system next reaches index 1
  with progressing playback/no MediaSession error and an active unmuted 48 kHz track.
  The official TV session stays inactive STOPPED with zero entries.
- Latest isolated debug-ID standalone R8 builds separately (4m 22s), retaining the
  existing temporary external build-directory/ID override. Its v3 signature and
  16 KB alignment pass, UID 10254 is unchanged and DEBUGGABLE is absent. Actual
  652 ms cold launch restores three paused entries at 94,123 ms without autoplay.
  Original Home FM starts four entries; system next reaches index 1 and triggers
  refill to seven entries, with advancing playback/no error and an active unmuted
  48 kHz track. This is isolated fixture evidence, not production-ID upgrade or
  framework-free execution. Existing notification/recording permissions are unchanged.
- Players are paused after testing and ordinary standalone debug is restored with `-r`.
  Post-update UNKNOWN/0 starts are excluded from cold-start evidence. Final preservation
  checks show seven standalone entries/index 1 at 145,670 ms, PAUSED/speed 0 without
  autoplay after a separate 1,261 ms cold launch. TV retains six entries/index 1 at
  55,536 ms paused. Final debug PID 29095 and TV PID 27271 have empty crash buffers;
  UID 10254, DEBUGGABLE and original ungranted notification/recording permissions are
  restored/preserved. Accepted MediaStore row 820 remains published, TV-owned and
  22,705,573 bytes. Root AVD remains awake on portrait TV MeiloX Home; no
  reboot/screen-off, physical-device operation, scope/orientation change, Cookie read,
  quota download, upload, social action or original production-app replacement occurs.
- This qualifies the stated FM continuation/read/play/next paths, not native FM
  feedback, real login/logout/expiry, final listening statistics, audible output,
  every placeholder/server-shuffle path or complete D4-D6 acceptance. Framework-free
  startup, original-install upgrade and recording/PiP decisions remain pending. The
  earlier standalone near-end stall remains independently open; f0c55cac is retained.

Local-only evidence: `/tmp/meilox-fm-session-final-paired-build-2026-10-01.log`,
`/tmp/meilox-fm-session-final-*-queue-fixtures-2026-10-01.log`,
`/tmp/meilox-fm-session-paired-signing-fixtures-2026-10-01.log`,
`/tmp/meilox-fm-session-standalone-r8-fixture-build-2026-10-01.log` and same-prefix
DEX/UI/MediaSession/AudioFlinger/package/preservation captures. Private evidence,
APKs, official source and credentials are not committed. No push or merge.

### D4/D5/D6 Checkpoint: Paired Minified Search and Detail Navigation (2026-10-02)

This is bounded read-only acceptance using the original shared pages, not complete
search relevance, catalog pagination or D4-D6 qualification. No application source,
screen tree, layout, navigation architecture or dependency is changed.

- Reuse the paired artifacts from a0ea813d without rebuilding unchanged source.
  Parasite R8 SHA-256 is
  `d9ed15a9928fb34a7f562eba787251be5d949764db26557a453ce433ffda7008`.
  The isolated debug-ID standalone R8 fixture SHA-256 is
  `8a9bb58455c07cf2cfdb8d10b449aa2cf09a9f96316575feba261ea82975d626`;
  v3 signature verification passes with the existing development certificate and
  DEBUGGABLE is absent. This is not production signing or original-install upgrade
  proof. Only the isolated standalone package is updated with `-r`.
- Search discovery renders actual recommendation artwork and category entries in
  both minified runtimes. Both display song/artist/album suggestions and accept the
  committed `HOYO` query through the existing IME action. Standalone additionally
  selects the HOYO-MiX artist suggestion and opens the matching Artist result tab.
  Initial composing/transitional input is excluded; no keyboard settings are changed.
- Both minified song searches expose 47 distinct fully visible title/artist pairs
  across the initial snapshot and four bounded scrolls. Structured UI XML counting
  excludes the header, mini-player and clipped rows; 47 exceeds PAGE_SIZE 30. Covers,
  subtitles and the existing progressive header blur render. This does not qualify
  pagination in every search category or the artist's entire album catalog.
- All five result categories render in both runtimes. Artist, album, playlist and
  podcast title rows enter their original detail pages; Back retains the originating
  result category. Artist detail displays the existing biography, artwork and
  catalog counts; the same selected album displays 102 songs and the selected
  playlist displays 79 songs in both runtimes. Follow, collection, play and menu
  write actions remain untouched. Settled screenshots exclude initial image
  placeholders and one TV tab tap consumed during a return transition.
- Podcast detail routing, artwork and program lists render, but search relevance is
  not qualified: TV's `HOYO` results start with matching HOYO-MiX programs while
  standalone starts with a broad hot-song radio and other weakly related entries.
  Restored ordinary standalone debug reproduces the latter with confirmed `HOYO`
  input. API-032 records the observation, unchanged DTO/route and attribution limits;
  no endpoint substitution, client-side filtering or UI repair is introduced.
- Rerun SearchSessionTest, AlbumSessionTest, ArtistSessionTest, PodcastSessionTest
  and PodcastPagingTest for both debug flavors: five suites and 68 cases each,
  136 total, zero failures/errors/skips; Gradle succeeds in four seconds. These
  focused results do not replace the preceding full paired 1,657-case run, paired
  build/vital-lint result, real-SDK signing fixtures or unexecuted acceptance gates.
- Standalone R8 launches COLD in 686 ms and retains seven items/index 1 at
  145,670 ms, state NONE/speed 0 without autoplay. Ordinary debug is restored with
  `-r`; its initial UNKNOWN/0 start is excluded and a separate COLD launch takes
  1,185 ms. Final debug PID 6214 has seven items/index 1 at 145,670 ms PAUSED;
  TV PID 27271 retains six/index 1 at 55,536 ms PAUSED. Official TV playback remains
  inactive STOPPED/empty. UID 10254, DEBUGGABLE and the original ungranted
  notification/recording permissions are preserved/restored. Current app-PID crash
  buffers are empty; the global buffer retains earlier automation/instrumentation
  failures, which are not erased or misreported as current app crashes.
- MediaStore row 820 remains TV-owned, published and 22,705,573 bytes. Root AVD
  stays awake on portrait TV MeiloX Home. No production-app replacement, Cookie
  read, data transfer, account mutation, quota download, upload, social action,
  physical-device operation, reboot, screen-off or scope/orientation change occurs.
  Framework-free startup, original-install upgrade, recording/PiP decisions, real
  session transitions and the independently open near-end stall remain unresolved.

Local-only evidence: `/tmp/meilox-paired-search-session-unit-2026-10-02.log`,
`/tmp/meilox-paired-search-song-row-counts-2026-10-02.txt`, and same-prefix
UI/signature/package/launch/MediaSession/crash/preservation captures. Private
screenshots, logs, APKs and credentials are not committed. No push or merge.

### D6 Checkpoint: Release Runtime Declaration Gate (2026-10-02)

- The existing signed-pair preparation step now inspects the actual APKs before
  either export. Standalone rejects modern Xposed declarations; both variants reject
  legacy `assets/xposed_init`. Parasite requires the canonical API 102 properties,
  `staticScope=true`, `autoHotReload=false`, the exact TV-only scope and module entry.
  SDK DEX inspection must also resolve that declared module class. Missing files,
  failed inspection or invalid contents stop the pair without partial export.
- Extend the existing workflow fixture suite, not a new release tool. It passes one
  wiring check, 13 metadata cases, 23 signed-preparation cases and four signing/key
  cleanup cases. The 14 added negative preparation cases cover declaration leaks,
  missing entries, wrong API/entry/scope, hot reload, absent DEX entry and failed
  inspection. Existing production secret bindings, signing identities, permissions,
  artifact names and manual-only publication gate remain unchanged.
- Run the same shell blocks with real SDK 37 tools against the built production-ID
  release APKs and a temporary fixture certificate. Both packages pass signature,
  version, 16 KB ZIP alignment and runtime-declaration checks. An altered copy of
  parasite has only its scope entry removed, is realigned and freshly signed with
  that fixture key; its valid signature does not bypass the declaration gate. The
  actual swapped, unsigned and missing-scope pairs are rejected without exports.
  Temporary keys/APKs are removed; no production credentials are accessed.
- Both full JVM targets and all four debug/release assemble targets pass in
  3m 48s, including paired release vital lint. Structured test XML records 799
  standalone cases/95 suites and 858 parasite cases/101 suites, 1,657 total with
  zero failures/errors/skips. Ruby syntax, YAML loading, workflow shell syntax and
  `git diff --check` pass. Actionlint is unavailable locally in this turn; the older
  actionlint result is not represented as a fresh check of these shell additions.
- Focused pre-merge source review rechecks local main at
  `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`, which is an ancestor of f02acb59.
  Only the intentional login/account-settings frontend files exist in the two flavor
  UI trees; no general screen copy is introduced. The standalone search route/input
  DTO still match main, and its signer retains the original payload/crypto logic
  with captured credentials replacing global Cookie reads. This does not resolve
  API-032's live relevance difference or complete the 405-file branch review.
- This increment changes CI/tests and acceptance records only. No app-source/UI,
  installation, AVD, session, orientation, scope or media operation occurs. No remote
  workflow, push, tag, release or merge is triggered. Production signing/upgrade,
  framework-free execution, recording/PiP and complete device/business regression
  remain gates; D6 and the overall goal are not complete.

Local-only evidence: `/tmp/meilox-release-runtime-paired-build-2026-10-02.log`,
`/tmp/meilox-release-runtime-unit-counts-2026-10-02.txt`,
`/tmp/meilox-release-runtime-fixtures-2026-10-02.log` and
`/tmp/meilox-release-runtime-real-sdk-fixtures-2026-10-02.log`. Run the existing
workflow reproduction commands above; the real-APK case requires `zip` and SDK 37.

### D4/D5 Checkpoint: Session-Owned Recognition and Song Wiki Reads (2026-10-02)

This increment closes two request/state ownership gaps, not the TV microphone/PiP
gate or complete D4-D6 acceptance. API-033 records the wire and runtime distinctions.

- Recognition captures one SessionStamp before recording and carries it through
  fingerprint generation, Retrofit Tag and result publication. Account invalidation
  clears results immediately and cancels work; recovery/binding never starts recording
  automatically. Same-account stop/restart retains results without allowing the old
  job's cancellation cleanup to overwrite a replacement job.
- Song wiki requires the caller's owner for its unchanged EAPI body/route. Personalized
  memories are cached only for that session/song. Account changes clear the old page
  state before the new session reloads; song changes and cancellation reject late
  responses. Anonymous reads and original same-session cache behavior are preserved.
- Shared Compose page bodies, navigation, recorder/fingerprint implementation, assets,
  player and source-set boundaries are unchanged. No frontend tree is copied or redesigned.
- Tests cover the actual Retrofit query/Tag, original candidate conversion, each
  preprocessing stage, account generations, recovery, anonymous/unready sessions,
  late non-cooperative work, stop/restart, continuous merge/cap, retry and disposal.
  Two additional identity-reader-failure tests first reproduce retained Matching/owner
  state and uncaught IOException; publication now fails closed and permits explicit retry.
- Paired matched-debug AVD instrumentation executes the real wiki Repository with
  synthetic transports/platform parsing: seven tests per flavor pass. It checks the
  exact EAPI payload, FIRST_LISTEN/TOTAL_PLAY conversion, stale owners, anonymous reads,
  recovery, business rejection and non-cooperative cancellation. No official account
  request, recording, upload, download grant or social mutation is sent by these fixtures.
- AVD read-only smoke before the final reader-failure repair: development-signed TV R8
  cold start is 2748 ms; isolated standalone debug cold start is 2135 ms. Both retain
  their separate existing accounts. Original More Actions -> Song Wiki navigation
  renders TV's populated `Écoute Chérie` wiki and standalone's empty `Full Moon Serenade`
  page on repeated activation. First activations return to existing root pages; the
  final standalone retry also returns to Settings. Cause/timing is not established,
  and first-entry reliability is not qualified by repeat-entry success. No navigation
  or frontend repair is made; API-033 retains this observation separately from reads.
  Both Settings -> Recognition pages render Ready/default six seconds with unchanged
  choices and controls; Start, similar-song playback and contribution links are untouched.
- Paused checkpoints remain TV queue 6/index 1/55536 ms and standalone queue 7/index
  1/145670 ms (NONE/speed 0 after this cold restore); no autoplay or new playback occurs.
  Original TV player remains STOPPED with an empty queue. Current TV/standalone PID
  crash buffers are empty; historical automation/instrumentation crashes are retained.
  Standalone UID 10254 and ungranted POST_NOTIFICATIONS/RECORD_AUDIO flags are unchanged;
  accepted MediaStore row 820 is still TV-owned, published and 22,705,573 bytes.
- Final source validation after the reader-failure repair: full JVM suites pass 835
  cases in 98 standalone suites and 894 cases in 104 parasite suites (1729 total),
  with zero failures/errors/skips. Three new suites contribute 36 cases per flavor.
  Paired debug/release/AndroidTest builds and release vital lint pass in 5m 13s;
  the release-contract checker also passes its real-SDK signature, identity, version,
  16 KB alignment, modern declaration and swapped/unsigned/missing-scope rejection
  fixtures. Both current release DEX files retain all six audio Query fields, the
  required local owner Tag and the original GET route. `git diff --check` passes.
- Final matched-debug wiki device fixtures are rerun on both freshly installed debug
  targets: seven cases each pass (0.028/0.034 s test execution, not application startup).
  The parasite is then restored to the latest development-signed R8 artifact, v3-signed
  with certificate SHA-256
  `2a02b8d6f6f25067a95b685c9f9cf79d97cba2d4999d1a550a720931c8310243`
  and verified for 16 KB alignment. APK SHA-256:
  `b326a9c21334e19b35f169b8489295b65c6ccbb7d191e10e1858be023fa1caff`.
  This is a local development artifact, not production signing/upgrade qualification.
- Final actual COLD startup is 2863 ms for TV and 2137 ms for isolated standalone debug.
  Both recognition pages again render Ready/default six seconds after the repair;
  settled screenshots are inspected without starting recording. Both queues/checkpoints
  remain intact and ultimately PAUSED/speed 0; no autoplay occurs. TV PID 13251 and
  standalone PID 13506 crash buffers are empty. UID/permission flags and media row 820
  are rechecked unchanged. The rooted AVD stays running, awake and on the TV MeiloX UI.
- Live recording/match success, real account transitions/personalized memories,
  framework-free execution, original-install upgrade, remaining business/release
  regression and merge review remain gates. The standalone near-end stall and API-032
  search relevance are not repaired or qualified by this checkpoint.

Local-only evidence: `/tmp/meilox-recognition-wiki-*-2026-10-02*`,
`/tmp/meilox-wiki-*-2026-10-02*` and `/tmp/meilox-recognition-*-2026-10-02*`.
No raw device logs, screenshots, account responses or APKs are committed.

### D4/D5/D6 Checkpoint: Paired Minified Comment Reads (2026-10-02)

This read-only checkpoint extends API-018 beyond its original debug page carrier;
it does not complete the comment failure/account matrix, D4-D6 or the overall goal.
Application source, shared Compose pages, navigation, player, manifests, CI and
probes are unchanged. No unrelated frontend repair or separate screen tree is added.

- The current TV development-signed R8 module reads comments from the ordinary player
  menu in the existing host process. Isolated standalone R8 reads through restored
  Cookie transport. Tracks/accounts are deliberately preserved rather than replaced
  to create a matching fixture: TV's `Écoute Chérie` shows 23,338 comments and
  standalone's `Full Moon Serenade` shows 421 at observation time.
- Original recommend/hot/time sorts all render and continue beyond the configured
  initial 20 rows. Across the first viewport and four scrolls (five for time), structured
  XML yields 31/23/43 distinct fully visible nickname/content pairs on TV and 28/21/43
  on standalone. SHA-256 deduplication excludes clipped or mini-player-obscured pairs;
  settled screenshots are inspected. These counts are UI continuation evidence, not
  captured server cursors, precise request counts or identical account/server results.
- TV displays a complete single-reply thread and 45 distinct visible reply pairs from
  a thread labelled 98 replies; standalone displays all six replies of a six-reply
  thread. Both existing collapse controls remove reply text nodes, without adding
  retry controls or changing the original layout. Back returns to underlying Settings.
- TV enters comments on two ordinary-menu activations in its existing process.
  Standalone R8 and restored ordinary debug first return to Settings, then enter on
  their second activation. This independently retains the ordinary-entry limitation:
  first-entry reliability is not accepted, the cause is unestablished and repeat-entry
  data success does not qualify it. No frontend/navigation workaround is introduced.
- Fresh isolated standalone R8 builds in 5m 19s with the existing local-only init
  fixture, preserving production output identities. Its test-ID APK passes v3 signature
  verification with the existing development certificate and 16 KB alignment; SHA-256
  is `ea808a35d0b28033cadd40521b1338ee34c7f7b6d2b71c19b9e6efe98fc6556f`.
  Actual isolated R8 COLD startup is 706 ms. This does not qualify production signing,
  original-install upgrade or framework-free execution on the rooted AVD.
- Paired focused JVM comment model/repository/session/paging suites execute 23 cases
  per flavor (46 total), with zero failures/errors/skips. `assembleStandaloneDebug`
  and `assembleParasiteRelease` pass in the same seven-second run; these results are
  distinct from the earlier full 1729-case suite. `git diff --check` passes.
- Ordinary standalone debug is restored with `install -r`, without clearing storage;
  actual COLD startup is 1308 ms, the existing account remains and repeated-menu
  comments show 421. UID 10254 and ungranted notification/microphone permission flags
  remain unchanged. TV stays PAUSED at 55536 ms with queue 6/index 1; standalone
  restores NONE/speed 0 at 145670 ms with queue 7/index 1. Original TV playback stays
  STOPPED/queue 0. No playback or media mutation occurs; TV-owned published MediaStore
  row 820 remains 22,705,573 bytes. TV PID 13251, standalone R8 PID 16822 and restored
  debug PID 19044 crash buffers are empty; historical buffers are not cleared.
- The rooted AVD stays running and awake on TV MeiloX Home. No Cookie file is read,
  credential/data transfer, recording, social write, upload or quota-consuming download
  is performed. Live failure/account transitions, first-entry reliability, remaining
  business/release regression, capability decisions and merge/upgrade gates stay open.

Local-only evidence: `/tmp/meilox-paired-comments-*-2026-10-02*`, including structured
UI snapshots, inspected PNGs, hashed pair counts, startup/media/package/crash checks,
the isolated build log and focused build log. Raw evidence and APKs are not committed.

### D3/D6 Checkpoint: Original Standalone Transport Budgets (2026-10-02)

This bounded comparison with current `main` (`1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`)
repairs a standalone restoration regression recorded under ABI-008. It does not
complete the full merge review, production upgrade, D3-D6 or the overall goal.

- Business connect/read/write and audio-match connect/read return from the migration's
  accidental 15 seconds to the original 30 seconds. Audio-match write remains the
  original default 10 seconds, with no total-call deadline on either client. NCBL's
  original 15-second limits and no-retry/no-redirect policy remain unchanged. The same
  client factories are used by production transport and the new policy tests; session
  guards, captured credentials, TLS/redirect restrictions and official TV code are
  unchanged. No shared frontend, navigation, player, manifest or CI edit is made.
- The focused three-case suite first fails the two 30-second expectations against
  the old 15-second configuration; NCBL passes unchanged. After repair, full paired
  JVM suites pass: standalone 838 tests/99 suites and parasite 894 tests/104 suites,
  with zero failures/errors/skips. Both debug and minified release builds plus release
  vital lint pass in 2m 56s. Production-ID R8 constructor/builder DEX retains the
  intended 30/15-second policies. Release preparation passes 41 fixtures and real-SDK
  signature, identity, version, alignment and runtime-declaration checks, including
  rejection of swapped, unsigned and missing-scope pairs; no remote CI is invoked.
- Fresh isolated standalone R8 builds in 2m 13s using the existing local-only init
  fixture. Its unchanged test ID, development v3 certificate and 16 KB alignment are
  verified; SHA-256 is `c293dc8020e5ed28575a03a16425460522f010b381f87858f565cd0ed8043b29`.
  Data-preserving installation cold-starts in 696 ms, restores the existing account
  and displays live `HOYO` suggestions and `HOYO-MiX` artist results through ordinary
  UI controls. Settled screenshots are inspected; no follow or playback action occurs.
- Ordinary standalone debug is restored with the same test ID/certificate and no
  storage reset; actual COLD startup is 1688 ms and the same account remains. UID
  10254 and ungranted notification/microphone flags are preserved. Final queues stay
  TV 6/index 1 at 55536 ms and standalone 7/index 1 at 145670 ms, both PAUSED/speed 0;
  original TV playback stays STOPPED/queue 0. Standalone R8 PID 20871 and restored
  debug PID 21541 crash buffers are empty without clearing historical buffers.
  Published TV-owned MediaStore row 820 remains 22,705,573 bytes. The rooted AVD stays
  awake on TV MeiloX Home; no original standalone installation or Cookie file is read.
- No real recording, account mutation, upload, quota-consuming download or social
  write occurs. Client-configuration/DEX proof is not slow-server timing evidence;
  this repair does not qualify microphone recognition, server statistics, framework-
  free startup, original-install upgrades or the separately observed playback stall.
  Capability decisions, remaining business/release regression and full merge review
  remain open. `git diff --check` passes before the scoped local commit.

Local-only evidence: `/tmp/meilox-standalone-transport-policy-*-2026-10-02*` and
`/tmp/meilox-timeout-*-2026-10-02*`. Device logs, credentials and APKs are not committed.

### D4/D5/D6 Checkpoint: Session-Owned Home Requests and Recovery (2026-10-02)

This bounded backend/session repair is recorded under API-034. It does not change
the homepage layout, navigation, player, original request body or cache policy, and
does not complete real account-transition, full business or release acceptance.

- Home Repository fetches now pass their triggering SessionStamp through a required
  local Retrofit Tag. A replacement account or same-account reauthorization cannot
  be borrowed at route creation. Recovery-required sessions reject cached/fresh reads
  and cache publication; the ViewModel clears personalized success state and cancels
  its request even if recovery changes without a generation transition. A valid
  same-account cache remains reusable after recovery; explicit refresh still fetches.
- Five new shared JVM cases cover the required route owner, initial/refresh/anonymous
  owners and recovery behavior. The Tag assertion and three recovery cases fail before
  their respective repairs. Final full suites pass 843 standalone cases/99 suites and
  899 parasite cases/104 suites, with zero failures/errors/skips. Seven Android fixtures
  per flavor also pass against the production Repository/body builder/Retrofit using
  synthetic sessions, callbacks and isolated disposable cache directories. They cover
  HTTP/business rejection, retry, cancellation and late-owner/recovery responses;
  they make no server request or real account change. Parasite instrumentation needed
  one retry after a system attach failure; its cause is not established.
- The final paired debug/release/test-APK build and release vital lint pass in 1m 3s
  (267 tasks). An earlier client-disconnected build was cancelled and is not counted
  as successful. Both production R8 DEX files retain the required Tag and unchanged
  POST route. Release preparation passes 41 fixtures plus real-SDK identity/signature,
  version, 16 KB alignment and runtime-declaration checks; no remote CI is invoked.
- Fresh development-signed parasite R8 SHA-256 is
  `a0755c840e4b6dc014c649474820a31d11240dc5cfa0198c0ba1e4118c31c1eb`.
  The existing isolated standalone R8 fixture builds in 2m 56s; its SHA-256 is
  `ef71b07bb483405007fd29def5899e63874036749bdc2bffa6d4a9319032d05a`.
  Both keep their verified package identities, existing development certificate and
  16 KB alignment. Data-preserving installations restore the two existing accounts;
  ordinary homepage pull-to-refresh updates displayed recommendations in both.
  Settled screenshots are inspected. TV COLD startup is 10001 ms, standalone 1257 ms;
  the TV result is not a startup-performance acceptance claim. No autoplay occurs.
- Ordinary standalone debug is restored with the same identity/certificate, COLD
  startup 1463 ms and unchanged account/UID 10254. Notification/microphone remain
  ungranted. Final queues stay TV 6/index 1 at 55536 ms and standalone 7/index 1 at
  145670 ms, both PAUSED/speed 0; original TV playback is STOPPED/queue 0. Both tested
  R8 crash buffers and restored debug PID 25674 buffer are empty without clearing
  historical logs. TV-owned published MediaStore row 820 remains 22,705,573 bytes.
  The rooted AVD stays awake on TV MeiloX Home; original standalone data is untouched.
- Real session expiry/recovery/account-switch cooperation, server-side cancellation,
  recording/PiP, uploads/writes/quota use, framework-free startup, original-install
  upgrade, playback-stall attribution and full merge review remain open. No credential
  file, real account mutation or production-signing configuration is accessed.

Local-only evidence: `/tmp/meilox-home-*-2026-10-02*`. Device logs, credentials and
APKs are not committed. `git diff --check` passes before the scoped local commit.

### D3/D4/D5/D6 Checkpoint: Account and Library Read Ownership (2026-10-02)

- Profile primary/secondary reads retain one captured owner. Album pagination and
  photos now carry required local SessionStamp tags, derived from the triggering
  account rather than a later snapshot. Recovery without a generation change stops
  network reads and late publication. A validated public identity remains available
  for existing owned offline history, without granting online authorization.
- No layout, navigation, player, database schema or shared DTO transport fields change.
  Eight shared JVM cases were added. Four assertions fail before repair; final suites
  pass 851 standalone and 907 parasite cases with no failures/errors/skips. An initial
  offline-history regression was repaired in AccountStore, not hidden by changing the
  existing History test. See API-035 for the exact ownership contract.
- Twelve Android substitute cases pass per flavor (24 total), using production
  repositories/Retrofit with synthetic responses and no socket/database/account
  mutation. The photo cursor assertion was corrected against original main; its
  nullable cursor is omitted, not an empty string. These are not real account-change
  or server-expiry acceptance tests.
- Paired debug/release/test builds pass in 5m44s; the isolated standalone R8 fixture
  builds in 3m31s. The final test-APK rebuild passes in 13s. The release declaration
  gate passes 41 fixtures and real SDK signing/identity/16KB checks. Required album
  and photo Retrofit tags survive both production R8 DEX files.
- Development-signed parasite R8 SHA256:
  `e09fd5ad91b0111cb2139872cfe3cf66dd4dc4c9ac55ad5233ed948076a4005b`.
  Isolated standalone R8 SHA256:
  `ee1f02cf0734f1583596751e53b05dd857ebb5ab42d99fc6e4aa1706ea6c080c`.
  Actual COLD starts take 4640ms (TV) and 728ms (isolated standalone). Screenshots
  show restored account avatars, settled liked songs and existing library playlists.
  This does not prove every photo/album wire route or full release acceptance.
- The ordinary standalone debug APK is restored with install-r. A later settled COLD
  start takes 1824ms (PID 29845); the immediate post-install UNKNOWN launch is not used
  as cold-start evidence. UID 10254 and ungranted notification/microphone flags remain
  unchanged. Queues retain TV 6 items at 55536ms (PAUSED) and standalone 7 at 145670ms
  (NONE after cold restore, speed 0). Scoped crash buffers are empty. MediaStore row
  820 retains 22705573 bytes, TV ownership and pending=0. The rooted AVD stays awake
  on TV's portrait MeiloX Home; no emulator restart, new playback or quota use occurs.
- Local evidence: `/tmp/meilox-account-read-*-2026-10-02*`. No APKs, logs, credentials
  or official sources are committed. ABI-015 (dynamic signing/retry restoration) and
  API-036 (standalone photo metadata) are confirmed open fidelity gaps for the next
  backend-only repair. Original-install upgrade, framework-free startup, microphone/
  PiP, real cooperation and full release/merge gates remain open.

### D3/D4/D5/D6 Checkpoint: Dynamic Standalone Signing Restoration (2026-10-02)

- ABI-015's mode gap is repaired at the flavor-owned dynamic service boundary. The
  standalone EAPI/WeAPI qualifiers rewrite logical paths to their original prefixes;
  host qualifiers remain unwrapped official-SDK services. Typed APIs and raw playback
  history retain their previous profiles. No frontend/resource/component changes occur.
- The generic Repository helper restores standalone's original WeAPI-to-EAPI retry,
  retaining the captured owner and rejecting cancellation, changed generation and
  recovery before/after either attempt. Explicit account calls and the parasite helper
  do not acquire generic retries. API-036 restores original photo form defaults only
  in the standalone interceptor, not in the shared DTO or official signing metadata.
- Three production-provider/interceptor assertions fail before repair. Final paired
  JVM suites pass 862 standalone and 913 parasite cases, with zero failures/errors/
  skips. Paired debug/release/test builds pass in 7m05s (255 tasks); the isolated
  standalone R8 build passes in 4m26s. The release gate passes 41 fixtures plus real
  SDK signing, identity, version, 16KB, declaration and malformed-pair checks.
- Forty-six Android substitute cases pass per flavor (92 total), using actual
  repositories for dynamic retry, account/library, social, Together and wiki reads.
  No socket/contact/account/upload mutation occurs. One initial expected Map inferred
  Long pagination literals; only the fixture was corrected to original Int types.
  Final paired test-APK/unit rebuild passes in 13s. This is not live session-expiry or
  real social/cooperating-account acceptance.
- Final review makes the noncooperating cancellation case join its canceled child
  before asserting no fallback. The revised test APKs build in 12s; both full 46-case
  device suites pass again, including this stronger terminal-state assertion.
- Development-signed parasite R8 SHA256:
  `1dd63fa0bd2070897145dee387567345a6b8431afa46bf2c8d3b226298891704`.
  Isolated standalone R8 SHA256:
  `7789e8a0a2602ea5598f5bc7b2a2aafe4052adad686b1d61b0933f9989725fe0`.
  Both verify with the existing development certificate and 16KB alignment. Actual
  COLD starts take 3495ms (TV/PID 32123) and 2447ms (isolated standalone/PID 565).
  Screenshots show their distinct restored account avatars and subscribed podcasts.
  Production standalone R8 DEX retains both dynamic path rewrites. No forced live
  server failure, individual photo wire success or full release playback is claimed.
- Ordinary standalone debug is restored with install-r. After the final fixtures,
  it COLD starts in 2634ms (PID 3169); the restored parasite R8 COLD starts in 1528ms
  (TV PID 3331). UID 10254, ungranted microphone/notification flags, TV queue 6/55536ms
  and standalone queue 7/145670ms are retained, with speed 0. Scoped crash buffers
  are empty. MediaStore row 820 remains TV-owned, 22705573 bytes and pending=0. AVD
  remains awake on TV's MeiloX Home; the emulator itself is not restarted or powered off.
- Evidence stays in `/tmp/meilox-dynamic-transport-*-2026-10-02*`; no credentials,
  APKs, logs or official sources enter Git. ABI-015 records the remaining original
  retry behavior in two extracted cloud adapter boundaries, to repair with substitutes
  without repeating upload experiments. Full D3-D6/merge acceptance remains open.

### D3/D4/D5/D6 Checkpoint: Extracted Standalone Retry Restoration (2026-10-02)

- ABI-015's two remaining extracted retry boundaries are repaired using the existing
  flavor policy: cloud list/delete and binary NOS token allocation. Standalone retains
  original WeAPI-to-EAPI behavior under one captured owner; parasite remains on one
  official request with its original failure. Explicit EAPI upload phases and binary
  transfer do not gain retries, and only accepted publication marks completion.
  Parsing, bodies, database, shared UI/navigation and playback remain unchanged.
- Four targeted assertions fail before repair. Final paired JVM suites pass 873
  standalone and 922 parasite cases, zero failures/errors/skips. Production Retrofit/
  signing fixtures validate business 403 and HTTP 503 alternatives without sockets,
  preserving payload/owner and performing one synthetic binary transfer. Nine shared
  and two standalone cases are added. Six device fixtures and three opt-in live-read
  constructors are adapted; the live fixtures are not executed. A host SDK IOException
  expectation is corrected in the test only; production host failures stay untouched.
- Sixty-one Android substitute cases pass per flavor (122 total), including real
  ContentResolver IPC with synthetic authorization/files. Paired debug/release/test
  APK builds pass in 5m12s (255 tasks); isolated standalone R8 builds in 3m53s. The
  release gate passes 41 fixtures plus actual SDK signing/identity/version/16KB/
  declaration checks and rejects swapped, unsigned and missing-scope pairs.
- Development-signed parasite R8 SHA256:
  `ca305f68a7d6b31911992422549b3de914f025372f833f3d9d4767896d482a9f`.
  Isolated standalone R8 SHA256:
  `6944c8f9c7cb9d2a08898d13ae206af2c3b5d8e18a9d3e7c4e5a696a978fdfbe`.
  Existing development certificate and 16KB alignment verify. Actual COLD starts take
  6451ms (TV/PID 6218) and 849ms (isolated standalone/PID 7253). The first standalone
  frame reports recovery required; a later screenshot shows restored account/feed and
  the existing mini-player without intervention. Startup timing is not feed-load timing.
- Ordinary standalone debug is restored with install-r and COLD starts in 2625ms
  (PID 7711), displaying its existing account/feed/mini-player. UID 10254 and ungranted
  microphone/notification flags remain unchanged. Scoped crash buffers are empty;
  TV's module queue remains six tracks, PAUSED at 55536ms/speed 0. MediaStore row 820
  retains TV ownership, 22705573 bytes and pending=0. No new playback, upload, delete,
  download or social write occurs. The rooted AVD is not restarted or powered off and
  returns awake to TV's portrait MeiloX Home.
- Evidence stays in `/tmp/meilox-extracted-retry-*-2026-10-02*`; no APKs, credentials,
  logs or official sources enter Git. These bounded source/wire repairs close the
  extracted retry gap, not D3-D6 or merge acceptance. Framework-free startup, original
  installation upgrade, capability decisions and full paired/server regression stay open.

### D3/D5/D6 Checkpoint: Candidate Verification and Shared-Resource Propagation (2026-10-02)

- Rechecked main at `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`. The inventory at
  starting HEAD `abda09ad` contains 419 changed paths. This bounded review covers
  Application/Activity bootstrap, ViewModel factory/navigation, typed request labels,
  extracted collection bindings, interceptor selection and standalone login. It finds
  the private verifier's missing original account alternative (ABI-008), now repaired.
  It is not a complete review of all changed feature, persistence or playback paths.
- Candidate verification restores EAPI `/api/w/nuser/account/get` then one EAPI
  `/api/nuser/account/get`, retaining `{}`, candidate credentials and the captured
  stamp until publication. Original success-code/default-profile handling is restored;
  invalid accepted profiles do not cause another request. Recovery already pending
  at verification start is allowed, while changed recovery/account/cancellation fences
  both dispatch and commit. Official login/session code and UI/layout are unchanged.
- Three production-verifier assertions fail before repair. Ten new standalone wire/
  controller cases pass without sockets or real authorization changes. Final paired
  JVM totals are 883 standalone/102 suites and 922 parasite/105 suites, zero failures/
  errors/skips. Paired debug/release/instrumentation builds pass in 4m14s; isolated
  standalone R8 builds in 9m31s. Final paired rebuild, including stronger session-error
  assertions and final imports, passes in 6m48s (267 tasks). The release gate passes
  41 fixtures plus actual SDK signing/identity/version/declaration/16KB checks.
- Five standalone and four parasite matched-debug package/device tests pass. An
  initial debug-runner/R8-target mismatch fails in AndroidX's application factory on
  missing `kotlin.jvm.internal.Intrinsics` before executing tests; the first matched
  post-install attempt fails to attach. Both are retained as unqualified harness
  attempts. With settled matching debug targets, both suites pass; no app/R8 rule
  change is made to suppress the failures. The original parasite R8 is restored.
- Flavor-owned `ui/` sources contain only the intentional login/account-settings split;
  common navigation and the image-loader implementation are retained. Actual package
  resource reads compare the new shared `netease_logout_error` and
  `account_profile_unavailable` values against their single `src/main` XML source in
  default/zh/zh-rTW across both debug and R8 artifacts: 24 checks pass, including after
  the final rebuild. No temporary or permanent frontend edit/copy is needed. This
  qualifies shared-resource propagation, not complete pixel or interaction parity.
- Isolated development-signed standalone R8 SHA256:
  `e4f27acaac1ed5d2f73303a0663ea451b377297fb96a266f92dc6f2bff1106ba`.
  Its package remains the isolated debug identity, with the existing development
  certificate and 16KB alignment. COLD startup takes 5124ms (PID 9757), restoring
  the existing account/feed/mini-player. Ordinary debug is restored with install-r
  and COLD starts in 1498ms (PID 10931). Restored unchanged parasite R8 cold-starts
  TV in 3026ms (PID 11165); screenshots show portrait Home and distinct account avatars.
- UID 10254 and ungranted microphone/notification flags are retained. Standalone's
  seven-track queue is NONE at 145670ms/speed 0; the module's six-track TV queue is
  PAUSED at 55536ms/speed 0. Official TV playback is separately STOPPED with no queue.
  Current standalone/TV crash buffers are empty; the harness failures above are not
  erased. MediaStore row 820 remains TV-owned, 22705573 bytes and pending=0. No new
  playback, upload, deletion, download/quota or social action occurs. The rooted AVD
  is never stopped/restarted/screened off and is left awake on TV's MeiloX Home.
- Evidence stays in `/tmp/meilox-login-fallback-*-2026-10-02*`,
  `/tmp/meilox-shared-resource-pair*-2026-10-02.log` and the local merge inventory.
  No credentials, APKs, device logs or official sources enter Git. The exit-gate
  summary above separates established evidence from remaining D2-D6 requirements;
  this checkpoint does not complete production upgrade, framework-free execution,
  real server-induced fallback, full release/merge review or the overall goal.

### D3/D6 Checkpoint: Legacy Library Preference Affinity (2026-10-02)

- Rechecked main at `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`; it is also the
  merge base with starting HEAD `d267ac50`. This bounded continuation reviews the
  public session/account/call wrappers, Room 17-to-21 models/migrations/DAOs, standalone
  preference and legacy-work adapters, queue persistence/restore, download ownership/
  publication, cache/source acquisition and selected library/player/lyric consumers.
  It is not complete review of every changed feature, test, host hook or build file.
- The review finds a concrete upgrade-consumption gap: original `UserPhotoKey` survives
  disk writes but is no longer read by the shared per-account library. Standalone's
  production account persistence now freezes original public affinity before the first
  account replacement/removal and restores the photo during verified same-account
  publication only. Existing scoped selections win; the old key is retained. Missing
  identity, repeated replacement logins, logout and reopen cannot reassign that photo.
  The affinity is not an authorization claim. No screen/layout, official session,
  credential boundary, signing, playback engine or server endpoint changes.
- Eight new real disk-backed JVM fixtures cover preference preservation and the
  production persistence/controller boundary; five assertions fail before repair.
  All eight pass after repair. Paired totals are 891 standalone/103 suites and 922
  parasite/105 suites, zero failures/errors/skips. Shared cases are counted per variant.
  Both debug/release/instrumentation builds pass in 3m19s (267 tasks); the local real-SDK
  release gate passes identity/version/signature/declaration/16KB and invalid-pair checks.
- Matched standalone debug target/test APKs pass all 16 selected AVD cases: five package/
  graph, three frozen v17 database migration and eight legacy WorkManager recovery
  fixtures. The latter retire only UUID-tagged synthetic work and clean their private
  Room/WorkSpec/DataStore/media fixtures; no real source request or download is issued.
  The original installation is not replaced. Ordinary isolated debug COLD startup takes
  1545ms and its screenshot shows the existing account, Home feed and paused mini-player.
- Isolated standalone R8 builds in 3m6s (55 tasks), retaining normal minification and
  DEBUG=false while the previously documented local init script changes only its
  validation package/build directory. Development-signed SHA256:
  `49505da1a005dc689fcb958713ca1dd2261c800c4d32493dbe4b30198c0be8e5`.
  SDK signature and 16KB alignment checks pass. An update-restored top Activity is not
  counted as cold startup; the subsequent app-only force-stop/start is COLD at 949ms
  (PID 13689). Its immediate capture shows recovery-required content, then a settled
  capture restores the account/feed/seven-track queue without interaction. Both
  captures are retained. Activity timing is not account/content readiness or a repair
  of that transient UI. The scoped crash buffer is empty.
- Ordinary debug is restored with install-r and COLD starts in 1435ms (PID 14185),
  restoring its existing Home/account/queue. UID 10254 and ungranted notification/
  microphone flags are unchanged. Final standalone state is PAUSED at 145670ms,
  seven tracks; TV's module stays PAUSED at 55536ms, six tracks; both have speed 0
  and no error. The official TV player stays STOPPED with an empty queue. Its
  unchanged R8 host returns WARM in 352ms (PID 11165), showing portrait MeiloX Home.
  Final scoped crash buffers are empty. MediaStore row 820 is still TV-owned,
  22705573 bytes, pending=0. The rooted AVD stays awake and is not restarted or
  screened off; no new play command, download grant, upload/delete or social action
  is issued. Evidence stays under `/tmp/meilox-legacy-photo-*-2026-10-02*`, not Git.
- This closes the confirmed original selection adapter gap under ABI-012, not the
  actual original-install upgrade, URI grant retention, full cache/queue compatibility,
  real account-switch matrix or full release/merge qualification. The framework-free
  device and original-install upgrade permissions and TV capability decisions remain
  pending; no cloud flow is reopened to substitute for those gates.

### D3/D6 Checkpoint: Authorized Legacy Playback Cache Reuse (2026-10-02)

- The bounded main-upgrade review finds that original `meilox-media-v3` spans remain
  on disk but cannot be consumed by the new account-scoped lookup. Standalone now
  records a per-public-owner metadata receipt after current full-source authorization
  matches catalog song, effective quality, 32-hex MD5 and positive content length.
  It reuses original files rather than duplicating/re-keying them; missing MD5s,
  unowned lookup, cloud identities and a different account cannot inherit old bytes.
  Session guards reject stale/recovery/transition publication. Recovery retires only
  that owner's receipt and retains a rejection marker plus the original/other-owner
  spans. Parasite's policy still rejects legacy adoption. See ABI-016 in the ledger.
- Fourteen focused standalone JVM cases cover the adapter, including guarded
  retirement after same-account reauthorization; resolver assertions also retain
  source metadata through actual-quality fallback. Paired result totals follow
  the final rebuild, not the earlier pre-retirement-guard artifacts:
  905 standalone/104 suites and 922 parasite/105 suites,
  zero failures/errors/skips. The first run had one incorrect fixture expectation:
  MD5-based keys do not encode size; the key/size contradiction case now correctly
  uses a size-only key. Production policy was not relaxed to satisfy that assertion.
- Matched final debug AVD target/test APKs pass nine standalone cases (five package/
  graph and four cache upgrades, 1.369s) and six parasite cases (four package and two
  ownership/local-source cases, 0.190s). The private standalone cache fixtures read
  all original bytes with null upstream, preserve one physical cache namespace,
  continue a 37-byte span using only the missing 219-byte range, and reopen with
  persisted ownership/rejection metadata. A 500 ms silent WAV is decoded from the
  authorized original cache to ExoPlayer STATE_ENDED, with a 48 kHz mono format and
  positive rendered output-buffer count; its data-source factory has no upstream.
  This is synthetic decoder/output-pipeline evidence, not audible AVD acceptance or
  real-song/minified MusicService completion. Parasite also rejects a legacy 32-hex
  fingerprint after current source authorization. UUID fixtures clean only their
  own cache directories and Media3 index tables. No real URL/grant/download/upload,
  social mutation, original standalone replacement or copied account data is used.
- Final paired debug/instrumentation/R8 builds pass in 2m39s (267 tasks, 33 executed).
  The actual SDK release gate passes signed identity/version/declaration/16KB checks
  and rejects swapped, unsigned and missing-scope pairs. The final development-signed
  parasite R8 APK SHA256 is
  `9c231a88639bf80db67e979615afe05d19d9b821842ee86f40b43425a40a63d5`.
  Streamed install-r and app-only TV cold start succeed in 6534ms (PID 18932).
  The inspected screenshot shows portrait MeiloX Home, the existing account/feed
  and paused mini-player; the scoped crash buffer is empty. Module queue six remains
  PAUSED at 55536ms, speed 0, error=null; official TV remains STOPPED/queue zero.
  These timings are observations, not account/content readiness or performance
  acceptance. The initial pre-guard incremental-install capture and its later
  settled/streamed captures are retained separately and are not final-artifact proof.
- Isolated standalone R8 rebuild passes in 6m29s (55 tasks) with normal minification,
  DEBUG=false and only the existing local init-script package/build-directory override.
  Development-signed SHA256:
  `674bd81d6a1eae960b74e3565787bb8ff46f80faa2057d7033d8e823c0f1c796`.
  SDK signature/16KB checks pass. Preserving install-r and app-only COLD startup take
  1125ms (PID 19907); the inspected Home screenshot shows its distinct existing
  account/feed and paused mini-player. Its seven-item MediaSession is NONE/speed 0
  at 145670ms, error=null, not a prepared PAUSED session. Ordinary isolated debug is
  restored with install-r and COLD startup at 1918ms (PID 20323), retaining that same
  queue/state/position and Home account. UID 10254 and ungranted notification/microphone
  permission flags are unchanged; both scoped crash buffers are empty.
- Final TV return is HOT at 1220ms with PID 18932 unchanged and an inspected portrait
  Home screenshot. Existing MediaStore row 820 stays TV-owned, 22705573 bytes, pending=0.
  The rooted AVD stays awake and is never stopped/restarted/screened off. No new real
  track play command, download grant, upload/delete or social action is issued. Evidence
  remains under `/tmp/meilox-legacy-cache-*-2026-10-02*`, not Git. No UI architecture,
  screen, DSP/AutoMix logic, production identity or original installed data is changed.
- This is a verified cache-adapter increment, not completion of original-install
  upgrade, first offline Cookie recovery, production signing, full release playback
  or merge review. Broader D2-D6 gates and pending device/capability decisions remain.

### D3/D6 Checkpoint: Target-Only R8 Reporting Codec Qualification (2026-10-02)

- The merge-review base remains main `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`
  against `48a98999`: 425 changed paths, including 135 app test files. Inventory is
  not semantic review. This pass reviews flavor/manifest/dependency/R8 declarations,
  shared and flavor network providers, signing restoration, download-source selection,
  and the host identity/call/request boundaries; the complete branch review is open.
- Original standalone NcblCodec, NcblPayload and RSA implementations match main.
  The interceptor retains original signing behavior with captured standalone
  credentials; shared non-session artwork/device helpers remain shared. Standalone
  selects the player URL for downloads; parasite selects the official download grant.
  Host qualifiers have no standalone signing/retry fallback or credential export.
- The removed blanket Zstd keep rule is investigated, not presumed defective. The
  pinned AAR has no consumer rules; generic native-method rules preserve JNI names,
  and R8 rewrites AutoCloseBase's field updater. An Android-platform test runner now
  loads only the fingerprint-checked target APK and its packaged arm64 native library.
  It bypasses application initialization and uses mapping-derived codec/member names,
  so debug AndroidX/test implementations cannot substitute for minified code.
- Actual isolated development-signed R8 APK SHA256
  `674bd81d6a1eae960b74e3565787bb8ff46f80faa2057d7033d8e823c0f1c796`
  passes 32 native Zstd compressions and 32 NCBL v3 encodings, checking frame/header
  accounting and fresh UUIDs. A wrong SHA256 is rejected before encoding. No extra
  production keep rule or business-code change is necessary for this verified path.
- The probe is opt-in only: build the standalone instrumentation APK with
  `-PstandaloneR8CodecProbe=true`, install it over its test package, then use
  `am instrument -w -r` with `apkSha256`, `codecClass`, `codecInstance`, `codecEncode`
  and `compressMethod` from the exact tested APK/mapping. Only the isolated standalone
  package is accepted. The runner is
  `com.ljyh.mei.standalone.StandaloneR8NcblInstrumentation`; normal builds retain
  AndroidJUnitRunner. Restore the ordinary test/debug APKs afterward. No production
  package, test runner or application identity is replaced to execute this probe.
- Default runner restoration passes six device cases: native compression round trip,
  NCBL encoding, graph/bootstrap, WorkManager factory, resources and package isolation.
  Paired JVM results remain 905 standalone and 922 parasite cases, with zero failures,
  errors or skips; unchanged unit-test tasks are reused, not represented as fresh runs.
- Both production R8 releases build with vital lint in 8m32s. Forty-one release
  workflow fixtures and the actual SDK signature/identity/version/declaration/16KB
  gate pass, including swapped/unsigned/missing-scope rejection and fixture-key cleanup.
  Both production APKs contain neither test Instrumentation nor the new probe class;
  the ordinary standalone test APK registers AndroidJUnitRunner again.
- Ordinary standalone debug is restored with preserving install-r. Its inspected
  portrait Home retains the distinct existing account/feed and mini-player; the
  MediaSession is PAUSED at 145670ms, speed 0, error=null. TV returns HOT to inspected
  portrait MeiloX Home with PID 18932 unchanged, module playback PAUSED at 55536ms
  and official playback STOPPED. Standalone PID 22502's crash buffer is empty.
  Existing TV-owned MediaStore row 820 stays 22705573 bytes, pending=0. The rooted
  AVD remains running and awake; no global rotation or framework hook is introduced.
- This qualifies the packaged minified encoder on the existing rooted 16KB AVD,
  not full reporting dispatch, server acceptance/statistics, audible playback,
  framework-free startup, production signing/upgrade or complete D3/D6 acceptance.
  No real playback/download/upload/social request, credential read/copy, frontend
  change or host/system-scope change is part of the probe. Evidence remains local in
  `/tmp/meilox-r8-ncbl-*-2026-10-02*` and `/tmp/meilox-merge-audit-*-2026-10-02*`.

### D3/D5/D6 Checkpoint: Search Recovery Independent of Session Generation (2026-10-02)

- Standalone initialization can end its transition with an anonymous public stamp
  while a saved Cookie still awaits verification. Unlike the host login fence, this
  recovery state does not require snapshot() to fail or a new generation. Search
  results/suggestions/discovery incorrectly relied on the stamp alone. Three added
  JVM cases fail before repair, including late non-cooperative responses. ABI-018
  records the runtime distinction; no endpoint/signing/pagination body changes.
- Both shared search ViewModels now retire results/cache/jobs while recovery is
  required, reject explicit retries and check recovery before dispatch/publication.
  The latest query/type/input is retained and resumes after recovery, even with the
  same stamp. Ready guest search, debounce, existing pages/layouts and navigation
  remain unchanged; candidate Cookie verification is not blocked or modified.
- Fresh paired JVM runs pass 908 standalone and 925 parasite cases (1833 total),
  including 19 search cases per variant. Both debug/test APK pairs build. Two new
  Android substitute cases pass per variant on the existing rooted AVD (0.175s and
  0.123s): pending anonymous recovery and non-cooperative result/suggestion/discovery
  retirement. These instantiate actual Android ViewModels with synthetic sessions;
  they do not use credentials, real accounts, sockets or authorization changes.
- Both production R8 artifacts build with vital lint in 3m53s. All 41 release workflow
  fixtures and the actual-SDK built-pair signature/identity/version/declaration/16KB
  gate pass. The isolated-ID standalone R8 validation build passes in 3m18s; it is
  not production-ID or production-signing execution.
- Development-signed minified artifacts are preserving-installed in their isolated
  module/test packages. Both existing search UIs show suggestions and Coldplay artist
  results from their separate persisted accounts. No follow action is taken. Runtime
  SHA-256: parasite `663ff9f0cf8a088c0fe727792089c0e49e5caa3e54530bcad460bb96e618823c`;
  isolated standalone `17aa904f91350a24e98d895b767eabeaccffa98442855de89263bc7c55cb857c`.
  This qualifies bounded ready-state R8 search consumption, not real recovery/expiry
  or the complete release lifecycle matrix.
- The ordinary standalone debug APK is restored with a preserving install. Both
  portrait Home screens are checked; the foreground is TV-hosted MeiloX Home. Their
  queues remain paused at 145670ms (standalone, seven entries) and 55536ms (parasite,
  six entries), with null session errors. Original TV playback is STOPPED/empty.
  Current app crash buffers are empty. MediaStore row 820 retains its TV owner,
  22705573-byte size and pending=0; notification/microphone permissions are unchanged.
  The rooted AVD stays running/awake; original standalone installation/data is untouched.
- Framework-free startup,
  original-install upgrade, real expiry/account switching, full release acceptance
  and the remaining merge groups are not qualified by these fixtures. The current
  repair neither reopens cloud/download investigation nor changes frontend behavior
  unrelated to session recovery. Evidence stays under
  `/tmp/meilox-search-recovery-*-2026-10-02*`, not Git.

### D3/D5/D6 Checkpoint: Album Recovery and Captured Actions (2026-10-02)

- The same readable-stamp/pending-Cookie difference recorded in ABI-018 affects the
  shared album consumer. Four new JVM cases fail before repair: pending anonymous
  reads, immediate actions/loaded content, non-cooperative detail/collection reads,
  and a late collection write/library notification. These are synthetic consumer
  reproductions, not evidence that the guarded real transport sends pending requests.
- AlbumDetailViewModel now treats pending recovery as unavailable, cancels/clears
  reads and mutations, and gates reservation/dispatch/publication and captured
  playback/download actions. The latest requested album resumes under the same stamp
  after recovery. Existing guest reads, collection semantics, endpoints, candidate
  verification, page layout and navigation are unchanged.
- Fresh paired JVM suites pass 912 standalone and 929 parasite cases (1841 total),
  including 19 album cases per flavor. Paired debug/test APKs build in 50s. Two new
  actual-Android ViewModel substitutes pass per flavor (0.177s standalone retry,
  0.274s parasite), with private sessions and closed synthetic sources: no real
  collection writes, download grants, credentials or sockets.
- The first standalone instrumentation attempt terminates before the runner with
  `failed to attach`; its event buffer records a process-start timeout and no Java
  crash. An app-only force-stop and one bounded retry succeed. This is not a failed
  test assertion or an attributed application regression; the AVD is not restarted.
- Both production R8 artifacts and vital lint pass in 8m2s. All 41 release workflow
  fixtures and the actual-SDK built-pair gate pass. The development-signed parasite
  R8 APK passes signature/16KB alignment checks, is preserving-installed, and cold
  launches portrait MeiloX Home through the original TV icon in 2692ms. SHA-256:
  `b426c4157cad3011b78434cad3a14024232bf9735e8f13e2a6703cf4ead09bf8`.
  This is module bootstrap evidence, not a real pending-recovery/album-write test or
  complete paired minified execution of this increment.
- Current standalone debug and TV-hosted Home screenshots are checked. Existing
  queues remain paused at 145670ms/seven entries and 55536ms/six entries, with null
  errors; original TV playback is STOPPED/empty. Current app crash buffers are empty,
  accepted TV media row 820 and standalone notification/microphone permissions are
  unchanged. The rooted AVD stays awake; original standalone data is untouched.
- Full release/device acceptance, real expiry/account switching, production upgrade,
  framework-free execution and the remaining merge review are still open. Local
  evidence is `/tmp/meilox-album-recovery-*-2026-10-02*`; no device logs are committed.

### D3/D5/D6 Checkpoint: Playlist and Podcast Recovery (2026-10-02)

- ABI-018's readable-stamp/pending-Cookie distinction also affects explicit playlist
  retries/captured actions and both podcast consumers. All 12 new JVM reproductions
  fail before repair. Two shared source files now guard reservation, dispatch,
  publication and captured cache/actions while retaining latest playlist/detail and
  daily intent for same-stamp recovery. No endpoint, signing, download semantics,
  pagination cursor, page architecture, layout or player-engine change is included.
- Fresh paired suites pass 924 standalone and 941 parasite cases (1865 total),
  including 24 playlist and 26 podcast cases per flavor. Both debug/test pairs build
  in 10s. LibraryRecoveryDeviceTest passes three actual-Android ViewModel substitutes
  per flavor (0.146s standalone, 1.160s parasite retry), using private sessions,
  closed dependencies and synthetic sources: no credentials, sockets, real account
  changes, collection writes or download grants. This does not establish a real
  transport escape or qualify the real expiry/account/server matrix.
- The first parasite instrumentation attempt ends before the runner with
  `failed to attach` (PID 31456); its scoped Java crash buffer is empty. An app-only
  module force-stop and one bounded retry succeed. No application/AVD cause is
  attributed, and the rooted AVD is not restarted or powered off.
- Both production R8 artifacts and vital lint pass in 9m25s; all 41 release workflow
  fixtures and the real-SDK built-pair gate pass. The current development-signed
  parasite R8 APK passes signature/16KB alignment checks, is preserving-installed,
  and cold-launches portrait TV-hosted MeiloX Home through LoadingActivity in
  10072ms. SHA-256:
  `fe2e45ab52d5c2fd60f8db511836532e991ee1d42668e6681db92e24d2946abc`.
  This qualifies bootstrap only, not full paired minified execution or a real
  pending-Cookie/official-recovery/collection/download test of this increment.
- Ordinary standalone debug and current TV-hosted Home screenshots are checked.
  Existing queues remain paused at 145670ms/seven entries and 55536ms/six entries,
  with null errors; the original TV player is STOPPED/empty. Current PID-scoped
  Java crash buffers are empty, media row 820 retains its size/owner/published state,
  and standalone notification/microphone permissions remain ungranted. The rooted
  AVD remains awake; original standalone installation/data are untouched.
- Remaining original-upgrade, framework-free execution, paired runtime/account
  matrices and full semantic merge review stay open. Local evidence is
  `/tmp/meilox-library-recovery-*-2026-10-02*`; no device logs are committed.

### D4/D5/D6 Checkpoint: High-Quality Discovery Ownership (2026-10-02)

- The remaining discovery consumer had no session-bound cache/retirement, and
  high-quality requests omitted the originating SessionStamp. A closed typed source
  exposes the old loading behavior without adding a fence: seven consumer cases and
  four repository/API cases fail before repair. They reproduce stale cached content,
  queued pending reads, non-cooperative results, lifecycle retirement, missing tags
  and unvalidated business/missing-row responses. These substitutes do not establish
  real requests escaping the already guarded transport.
- FindMusicViewModel now binds reservations, category cache, dispatch and publication
  to the originating session, retires work/content on invalidation/recovery and resumes
  the latest category/limit after readiness. PlaylistRepository passes a required
  Retrofit session tag and checks ownership/readiness/cancellation and business/rows
  before accepting the response. Ready public/guest discovery remains allowed.
- Original `/api/playlist/highquality/list`, request fields, runtime-owned crypto,
  default limit 30, explicit limits, category-only ready-session cache, forced refresh
  and the existing ranking alias are retained. FindMusicScreen is untouched in this
  increment; against main it differs only by the previously approved ViewModel factory
  import/default. No layout, page architecture, player or main-only frontend repair
  is added. ABI-018 records the shared-consumer/runtime distinction.
- Fresh paired suites pass 939 standalone and 956 parasite cases (1895 total),
  including 11 discovery consumer cases per flavor; additional cases cover unbound
  startup and a held same-account reauthorization. Both debug/test pairs build in
  2m14s. Two FindMusicSessionDeviceTest substitutes pass per flavor (0.118s standalone,
  0.075s parasite), with actual Android ViewModels, private sessions and a socket-free
  source. No credentials or real account changes/writes/grants are used by those cases.
- Current standalone debug displays loaded All and Western discovery using its
  persisted account through the unchanged frontend; screenshots are checked. The
  initial Home recovery-required frame resolves automatically before discovery is
  entered. This ready-state consumption is not a controlled expiry/account-switch
  test.
- Both production R8 artifacts and vital lint pass in 7m55s. The local release
  workflow passes 41 fixtures and one real built-pair SDK gate, covering identity,
  version, signatures, runtime declarations and 16KB alignment. No production keys,
  remote CI, upload or original-install replacement is used.
- Fresh development-signed minified APKs display Home, All and Western discovery
  under each runtime's persisted account; screenshots are inspected. The parasite
  cold-starts from the original TV launcher in 6996ms and remains portrait. A local
  init script builds standalone release in 4m1s under the isolated debug package ID;
  its app-only cold start takes 808ms. This qualifies bounded minified ready-state
  consumption, not production-ID signing/upgrade or complete release/session parity.
  The signed SHA-256 values are
  `cab598d8de5b24d3c0490bf41cfe215759cadd91454a5a4a7f7e7509f8a7c073`
  (parasite) and
  `6c9ec23445e13c8d145215f9f2024f13a5ba172f5176bb782c115e20ca6a6e42`
  (isolated standalone); both pass signer and 16KB zip-alignment checks.
- Ordinary standalone debug is preserving-reinstalled and its loaded Home checked;
  the TV-hosted Home is restored to the foreground. Queues remain paused at
  145670ms/seven entries and 55536ms/six entries, with null errors; the original TV
  player remains STOPPED/empty. Media row 820 retains size 22705573, TV ownership and
  published state. Standalone notification/microphone grants remain false, current
  PID-scoped Java crash buffers are empty, and the rooted AVD stays awake. No original
  standalone data or credentials are copied, and no real write/download is performed.
- Framework-free startup, original-install upgrade, complete paired release/device
  qualification, real failure/account/server matrices and remaining semantic merge
  review stay open. Local evidence is `/tmp/meilox-discovery-session-*-2026-10-02*`;
  no APKs, credentials or device logs are committed.

### D5/D6 Checkpoint: Runtime-Owned Log Share URIs (2026-10-02)

- Review finds a concrete provider-root mismatch in the migrated LogViewModel, not a
  main-only frontend bug. ABI-019 records the original standalone/private-files
  provider and TV/cache-apk provider distinction. The existing ComponentRuntime now
  supplies share URIs and export cleanup. Standalone keeps direct private-file URIs;
  parasite stages only the selected module log under a UUID-scoped host-cache path
  and generates the URI using the raw host context. Page/layout/navigation and the
  chooser's MIME/stream/read-grant behavior remain unchanged.
- Six JVM cases cover preservation, uniqueness, rejected foreign/nested/missing/link
  sources, destination escapes, age pruning and explicit cleanup. The first run has
  one fixture assertion failure caused by macOS's canonical temporary-directory path;
  correcting the expected path yields fresh paired suites of 939 standalone and
  962 parasite cases (1901 total), with no failures/errors/skips. Both ordinary debug
  and test artifact pairs build. Two standalone Android cases pass in 0.025s; four
  host-runtime Android cases pass in 0.061s. The standalone chooser is captured, not
  sent, and all content is synthetic.
- The warmed TV process rejects the legacy private-file path and serves two staged
  synthetic logs through its actual registered provider. A separate ordinary module
  UID reads only the granted URIs, rejects an ungranted child URI, then rejects the
  selected URIs after revocation. Five host/runtime Android cases pass in 2.298s and
  TV independently reports cross_uid_readback=true and fixture cleanup. No root or
  shell permission identity is adopted by the reader; ADB only triggers the explicit
  debug fixture. No real share target, logs or credential-bearing files are used.
- Fixture development first encounters package-visibility/command-trigger failures,
  then an ordered-broadcast acknowledgment deadlock. An earlier recipient-only pass
  was insufficient while TV reported failure; it is not acceptance evidence. The
  trigger now finishes before waiting for a reply, and the recipient requires both
  its read assertions and TV's successful cleanup acknowledgment. A cold fixture
  also times out during bootstrap; final cross-UID evidence is explicitly warm-host
  evidence, not qualification of cold delivery. Debug TV startup after updates has
  pre-Application attach timeouts; system termination is checked before app-only
  retries. No AVD restart or unrelated UI/startup repair is performed.
- The legacy UserRepository convenience reads and legacy ShareViewModel have no
  shared frontend call sites; the factory binding alone does not execute them. The
  active NeteaseShareViewModel uses the separate owned social source. No unused
  baseline API or feature entry is removed merely to reduce review scope.
- The final ordinary debug/test pair, paired JVM suites and production R8 pair build
  successfully in 3m32s (267 tasks). The real-SDK release gate passes all 42 checks,
  including identity/signature/version/16KB/runtime declarations and negative pairs.
  The final parasite R8 artifact is signed only with the existing development key
  and installed preserving data; PARASITE_WORK_PROBE is false. Its SHA-256 is
  `190915745f4ae0a21bc52a2c2d5781cdb8e8b5c533aa42c7476ade5451912bfd`.
- The restored R8 TV cold launch succeeds in 4103ms, with portrait MeiloX Home
  visually inspected and no entry in its PID-scoped Java crash buffer. The ordinary
  standalone debug cold launch succeeds in 3275ms and its Home is also inspected.
  Paused queues retain 55536ms/6 entries for parasite and 145670ms/7 entries for
  standalone, both error-null; the original TV session remains stopped/empty.
  MediaStore row 820 retains TV ownership, size 22705573 and pending=0. Standalone
  notification/microphone permissions remain denied, the AVD stays awake, and
  scoped fixture checks find no synthetic logs. These are bounded restoration
  checks, not original-production upgrade, audible playback or full UI acceptance.
- Framework-free startup, production signing/original-install upgrade, complete
  paired account/lifecycle/release qualification and remaining semantic merge groups
  stay open. Local evidence is `/tmp/meilox-log-share-*-2026-10-02*`; no device logs,
  credentials, official sources or APKs are committed.

### D3/D4/D5/D6 Checkpoint: Account Detail and Record Request Ownership (2026-10-02)

- Semantic merge review finds omitted owners in active account-detail, account-playlist,
  listening-rank and recent-history calls. The Repository now requires their triggering
  stamp, including both original detail routes. AccountHome and History pass the stamp
  they already use to publish results. Dynamic request helpers cannot silently take a
  later snapshot. API-035 is extended in place with this boundary and verification.
- ListeningRankViewModel follows the existing session-owned consumer pattern: request,
  target user, period, cache and click callbacks are generation-owned; invalidation
  clears cached rows synchronously and recovery defers the remembered read. Retired
  jobs cannot publish late results. Guest/public reads, same-owner caching, refresh,
  both time periods and the original queue builder remain available. No layout,
  navigation, resources, player/AutoMix/effects or unrelated main-only UI bug is changed.
- Eleven ranking JVM cases and one additional history-owner case pass per variant.
  The full pair passes 951 standalone and 974 parasite tests (1925), with zero
  failures/errors/skips. The 19 account and nine generic dynamic-request Android
  substitutes pass in both variants: 28 cases in 0.758s standalone and 0.717s parasite.
  They use production Repository/Retrofit/provider/session guards but terminal fake
  transports and synthetic identities, with no socket, database or account writes.
- The first standalone instrumentation attempt fails to attach before Application;
  system termination and an empty PID-scoped Java crash buffer are checked before
  app-only retry. The retry runs all 28 cases successfully. No AVD restart, power,
  timeout, global orientation or permission configuration is changed.
- The final paired JVM/debug/AndroidTest/production R8 build succeeds in 4m39s
  (267 tasks); all 42 real-SDK release checks pass. The ordinary probe-disabled
  parasite R8 is development-signed and restored preserving data, with SHA-256
  `28ce8eb86903a14b0381f839e0325022464109ffd7d762e25600e7fd6ebc0198`.
  TV cold startup succeeds in 2756ms and its portrait MeiloX Home is inspected;
  its PID-scoped Java crash buffer is empty. Its ordinary shared MainActivity
  account page consumes real detail/playlist reads, and weekly/all-time rankings
  load and switch through the original navigation, not a probe activity.
  Standalone debug cold startup succeeds in 5872ms and consumes those same read
  views through its unchanged UI. Neither flow starts playback. These show
  pre-existing account records, not acceptance of this task's listening reports.
  This qualifies the bounded TV R8 account/rank reads, not updated standalone R8,
  production-ID execution or the complete paired account/history matrix.
- The existing paused queues retain 55536ms/6 entries for parasite and 145670ms/7
  entries for standalone, both error-null. Original TV playback remains stopped.
  MediaStore row 820 retains size 22705573, TV ownership and pending=0. Standalone
  notification/microphone permissions remain denied and the rooted AVD stays awake.
- This closes a concrete source/contract gap, not the remaining real login/expiry,
  cooperating social/Together, production upgrade/signing, framework-free startup,
  full lifecycle/release and semantic merge gates. Evidence remains local under
  `/tmp/meilox-account-read-*-2026-10-02*`; no credentials, device logs, official source
  or artifacts enter Git.

### D5/D6 Checkpoint: Production Components and Current-Schema Diagnostics (2026-10-02)

- Production entry review checks the identity-pinned module bootstrap, runtime Activity/
  service selection and work receiver/worker opt-in guards. Ordinary release constants
  force all three probes off and the shared app on. The actual R8 work receiver contains
  only parameter null checks and return; R8 removes ModuleStorageProbe, capability,
  Retrofit and work-worker diagnostics. Blanket component keep rules retain the prototype
  Activity/service classes, so their class presence is not claimed absent. They are not
  registered in the module manifest and ordinary carrier selection instantiates the
  shared MainActivity/MusicService, not those prototypes.
- The opt-in Room diagnostic was obsolete: it fabricated version 17 from a current
  schema, registered only 17-to-18 and asserted version 18. A new actual Android case
  first fails with `A migration from 17 to 21 was required but not found`. The diagnostic
  now creates a UUID-named current-schema database, validates reopen and retains its
  existing account-library/rollback checks. It no longer emits a historical-migration
  success marker. Dedicated versioned migration fixtures remain the upgrade evidence;
  the production migration policy, real databases and frontend are unchanged.
- BackendGraphDeviceTest passes both cases in 0.269s, including two diagnostic runs,
  complete fixture cleanup and an unrelated synthetic sentinel database/row preserved.
  The fixture runs in the module test package, not the TV storage wrapper, and does not
  qualify the full opt-in storage probe, real upgrade or server authorization. The
  current paired JVM suites pass 951 standalone and 974 parasite cases (1925), zero
  failures/errors/skips. Paired debug/production R8 and parasite AndroidTest builds
  pass in 2m49s (234 tasks).
- The signed release workflow previously accepted a standalone fixture with no launcher.
  A new negative case reproduces that missing gate. It now parses the actual APK manifest
  with the existing Ruby toolchain/REXML: require the shared Application, usable original
  standalone launcher/playback service, no host component in standalone, and no independent
  launcher or module-owned component registration in parasite. Production instrumentation
  and failed/malformed manifest inspection are rejected. Activity aliases are checked,
  not just Activity names. APK manifests themselves are not changed.
- All 56 local release checks pass, including fourteen added parsed-manifest negatives
  and the real-SDK signed current R8 pair. The existing actual swapped/unsigned/missing-
  scope APK negatives remain covered. New malformed/component-negative manifests use
  synthetic SDK fixtures, not rewritten official APKs. Original CI triggers, permissions,
  signing bindings, paired artifact names and manual-only publication are retained;
  temporary test keys/artifacts are cleaned up and no remote run/upload is triggered.
- The current ordinary parasite R8 is development-signed, signature/16KB alignment
  checked and restored with a preserving install. SHA-256:
  `42871f26ec42df2f4d13f3a08ca69e0f8b134a8ce81b6e5bc16d3267fa795168`.
  App-only TV cold startup succeeds in 3479ms; its portrait shared Home is visually
  inspected and PID 17985 has an empty Java crash buffer. Both retained playback positions
  remain paused (parasite 55536ms, standalone 145670ms); original TV playback stays stopped.
  MediaStore row 820 remains TV-owned, size 22705573, pending=0. No AVD restart, global
  setting, permission, original standalone installation or credential/media copy occurs.
- ABI-007 is extended in place with the package-registration gate. These checks do not
  complete the remaining production-signing/original-upgrade, framework-free startup,
  paired lifecycle/account/server, capability or complete semantic merge-review gates.
  Local evidence: `/tmp/meilox-storage-probe-*-2026-10-02*` and
  `/tmp/meilox-component-gate-*-2026-10-02*`; logs, APKs and credentials stay out of Git.

### D3/D4/D5/D6 Checkpoint: Captured Catalog Clicks and Queue Hydration (2026-10-02)

- The previous immediate page guards did not own asynchronous PlayerConnection work.
  Daily recommendations, playlist/album, both artist pages and listening rank now
  forward the displayed session through new queues and existing-item clicks. Their
  composed session/content are value snapshots, not delegated state read again after
  account replacement. Page/revision validation ends before player publication; it
  does not make backend identity readers run under the page's session monitor.
- PlayerConnection rejects retired/recovering synchronous title publication without
  returning SessionChangedException to the click callback. Existing-item seek and play
  are separately owner-checked, including reentrant invalidation during seek. New-track
  assembly retains its owner after the UI callback returns and through queue commit.
  The existing default paths remain recovery-independent for local/offline callers;
  no blanket online-session requirement is introduced for them.
- Selected placeholder metadata now carries that owner through the actual supplemental
  song-detail request and response check. Public catalog guests remain valid; FM keeps
  its authenticated-owner policy. Neither backend transport/signing, ListQueue ordering,
  playlist source, shuffle/start index, original frontend tree nor playback engine is
  replaced. Other consumer callback integration still needs its own audit;
  the selected pages do not qualify every retained playback action.
- Before repair, six actual PlayerConnection device cases reproduce two uncaught
  title-publication exceptions. Current matched debug packages each pass sixteen new
  connection cases plus six intelligence and fourteen FM queue-manager regressions:
  36 parasite cases in 0.368s, 36 standalone cases in 1.025s. Metadata tests prove the
  captured tag, successful guest hydration and rejection of a retired successful reply,
  not just an empty-response/no-op. Existing queue seek and default recovery paths are
  also checked. Fixtures use real binder/connection/manager/StableDeckPlayer and muted
  silence-source decks, closed API substitutes and an in-memory database. They do not
  start registered service lifecycle, alter persistent queues or perform real writes.
- Current paired JVM results remain 951 standalone and 974 parasite cases (1925), zero
  failures/errors/skips. Both current debug and AndroidTest packages build in 1m02s
  (160 tasks). Preserving installs target only the module and isolated standalone
  debug IDs on emulator-5554; the original production standalone is not replaced.
- Both current production R8 packages build in 8m38s (107 tasks). All 56 local release
  checks pass, including the actual signed SDK pair and swapped/unsigned/missing-scope
  negatives; no remote workflow or upload runs. The ordinary parasite release is
  development-signed with the existing compatible key, signature/16KB alignment checked
  and preserving-installed. SHA-256:
  `7029fb5ba73aceb66eff4ac7f26c8199616e8fb2d4cfb166d9a3d571c61d740b`.
  App-only TV cold launch succeeds in 7574ms; a screenshot shows the original portrait
  shared Home and paused mini-player. PID 21780 has an empty Java crash buffer. Its
  session remains paused at 55536ms, official TV playback remains STOPPED, and MediaStore
  row 820 retains size 22705573, TV ownership and pending=0. This is bounded restore/
  startup evidence, not new catalog UI interaction, motion, audio or full R8 acceptance.
- API-015 is extended in place. These checks do not complete framework-free startup,
  production upgrade/signing, real account changes, capability decisions, full lifecycle/
  business/server coverage or the remaining merge audit. Logs stay out of Git at
  `/tmp/meilox-queue-handoff-*-2026-10-02.log`.
- Remaining handoff audit is concrete: Home private/similar/podcast, heart seeds and FM,
  Library liked/FM, SearchResultScreen, PodcastDetailContent and recognition results
  still call default playback entry points without their displayed owner. Cloud page
  and History guards also end before the asynchronous default queue build; review those
  continuations with substitutes, not repeated live cloud experiments. History/local
  downloads must retain their existing generation/source affinity and offline-recovery
  policy rather than adopting the recovered-online-only policy of these catalog pages.
  Home intelligence's consume callback also needs a separate publication-lock review.
  This enumerated handoff review is addressed by the following checkpoint, not by the
  earlier sixteen connection cases alone.

### D3/D4/D5/D6 Checkpoint: Remaining Consumer Playback Handoff (2026-10-02)

- Home private/similar/podcast, heart seeds and FM, Library liked/FM, search, podcast
  detail and recognition now pass their displayed owner into PlayerConnection. Wiki
  and private-message song cards use the same captured-owner path. Composed result
  state is a value snapshot; no callback rereads a replacement session via a delegate.
  Home validates the exact displayed feed result, including refresh, recovery,
  invalidation, guest catalog and disposal. Original page trees and queue builders,
  selection/order/shuffle, player/AutoMix/DSP/glass and backend wire contracts remain.
- Cloud/History short validation ends before player invocation, removing nested backend
  identity reads under their publication monitor. Deferred queue commits retain the
  owner. Hydrated owned history queues play during recovery but reject retired generations;
  this exception does not grant online source authorization. Local/download default
  paths remain unchanged. No live cloud request or quota download is repeated.
- A new real connection fixture fails before repair because heart consumption calls
  the player under SessionStore's monitor. The state now claims/clears once under the
  locks and hands off after ownership/recovery/revision checks outside them. FM seed
  startup accepts the displayed owner instead of acquiring a later one; Home toggle
  checks ownership separately before prepare and play/pause.
- Current paired JVM suites pass 957 standalone and 980 parasite cases (1937), with
  zero failures/errors/skips. Six new JVM cases cover Home's result owner and heart
  seed/consume behavior. Both final debug/AndroidTest pairs build in 17s (160 tasks)
  after the first successful 54s build. Final AndroidTest packages rebuild in 5s after
  adding the online-placeholder recovery check. Each matched debug pair passes 27 actual
  connection plus six intelligence and fourteen FM cases on emulator-5554: 47 parasite
  cases in 0.588s and 47 standalone cases in 0.463s. That last fixture first fails in
  both runtimes on premature observation of a still-null manager Job; it now waits
  through the connection handoff for the actual terminal queue state. Production code
  does not change for that fixture correction. The fixtures use real connection/
  binder/manager/StableDeckPlayer, muted silence-source decks, closed substitutes and
  in-memory databases; they do not start registered service lifecycle, switch accounts,
  copy credentials or touch persistent queues/media. The production standalone is not
  replaced. Current build/device logs remain local at
  `/tmp/meilox-consumer-handoff-*-2026-10-02.log`.
- Both current production R8 artifacts build in 3m30s (107 tasks). All 56 local release
  checks pass, including real SDK pair signing/alignment/declarations and rejected
  swapped/unsigned/missing-scope packages. Ordinary parasite R8, with probes disabled,
  is development-signed using the existing compatible key, signature/16KB checked and
  preserving-installed. SHA-256:
  `fe72c74145f44f8623d4eec58c18812c5e5d0bc0cb24ce9982d244a8ec5d036a`.
  App-only TV cold startup succeeds in 3178ms. The inspected screenshot shows the
  original portrait shared Home, recommendation cards, glass navigation and paused
  mini-player. PID 6720 has an empty Java crash buffer; MeiloX restores six entries,
  paused at 55536ms with null error, and official TV playback stays STOPPED/empty.
  MediaStore row 820 still has size 22705573, TV ownership and pending=0. This is
  restore/startup evidence, not fresh interaction on every changed callback, glass
  motion, audible output or full paired minified runtime acceptance. No AVD restart,
  screen power/rotation configuration, original production app upgrade or upload occurs.
- API-015 and API-030 are extended in place. This closes the enumerated source handoff
  gap, not actual screen interaction or full paired lifecycle/release qualification.
  Framework-free startup, original-install upgrade/signing, real account/expiry/server
  matrices, cooperating social/Together tests, capability decisions and the remaining
  semantic merge review are still open.

### D3/D4/D5/D6 Checkpoint: Account Retry and Rendered Share Ownership (2026-10-02)

- The original shared share sheet now captures its rendered state as a value rather
  than rereading a replacement owner through a delegate. Three obsolete callbacks
  fail before repair; six actual Compose sheet cases pass per debug flavor afterwards,
  including current private/timeline sends and newly rendered replacement actions.
  API 37 Espresso fails before behavior due to its removed InputManager method; the
  fixture instead uses ActivityScenario, native Compose roots and the rendered debug
  ClickableElement callback. Semantics' mutable-node forwarding is not treated as a
  captured callback. Accounts/contacts/sends are in-memory; no real social write runs.
  Page architecture, controls, glass and draft/recipient lifetimes remain unchanged.
- Account-owned private conversation/history, contacts and Together status reads
  reuse the original standalone WeAPI-to-EAPI retry policy. The migration wrapper
  previously bypassed it. Owner/authentication/recovery/cancellation guards and the
  original bodies remain; parasite still uses one official pipeline. Ten paired
  Repository cases cover success, transport/business failures, terminal alternative,
  stale/reauthorized/recovery/canceled requests and single-attempt EAPI writes. The
  previous Together no-retry assertion is corrected to match main; wiki EAPI
  single-attempt coverage is retained.
- Both opt-in diagnostic helpers pin their original stamp through dispatch and
  cancellation. Only account/subcount typed declarations gain optional non-wire tags
  preserving their legacy calls. Nine native parasite cases use production Retrofit/
  HostCallFactory/HostRequestBridge and synthetic backends, covering full sequences,
  first-call/between-report races and cancellation. Ordinary probe flags remain off.
- Shared parser/report helper comparison retains main's bodies except the documented
  official-json diagnostic envelope; no new live cloud test is performed. Named
  business/diagnostic source review is classified in the table above. Remaining page,
  runtime-carrier and test-source coverage is still bounded, not a complete merge audit.
- Current paired JVM suites pass 957 standalone and 980 parasite cases (1937), zero
  failures/errors/skips. The final paired debug/AndroidTest/JVM build succeeds in 5s
  (160 tasks), after successful 31s and 24s builds. Matched debug pairs pass 43
  standalone native cases in 23.470s and 52 parasite cases in 17.465s on emulator-5554:
  account retry, dynamic/social/Together regressions, original share sheet and the
  parasite-only probe fixtures. The original production standalone is not replaced.
  Logs stay local at `/tmp/meilox-business-owner-*-2026-10-02.log`; the initial
  share-only fixture/build evidence remains at `/tmp/meilox-share-owner-*`.
- Both production R8 artifacts build in 4m31s (107 tasks); all 56 local release gates
  pass, including real SDK signing/alignment/declarations and swapped/unsigned/
  missing-scope negatives. Ordinary parasite R8 is development-signed with the existing
  compatible key, signature/16KB checked and preserving-installed. SHA-256:
  `191d918afa0a334300c5ce360171637517f181cd715dd6f583141edee3519e8f`.
  The first TV cold launch returns UNKNOWN and its process exits with INITIALIZATION
  FAILURE / start timeout; the crash buffer and process log are empty. This is a
  failed startup, not qualified success or an attributed code regression. Logs/exit
  information and the launcher screenshot are preserved before retry. One app-only
  retry cold-starts in 2281ms; the inspected screenshot shows the original portrait
  shared Home, glass navigation and paused mini-player. PID 13843 remains alive with
  an empty Java crash buffer. The MeiloX session has six queue entries, paused at
  55536ms with null error; native TV playback is STOPPED with an empty queue.
  MediaStore row 820 retains size 22705573, TV ownership and pending=0. No emulator
  restart, global rotation/permission change, original standalone upgrade, opt-in live
  cloud/social test or quota-consuming download occurs. Initial startup timeout
  attribution and full minified interaction/lifecycle qualification remain open.
- API-001, API-028 and ABI-015 are extended in place. Native substitutes do not prove
  real server writes, later statistics, minified sheet interaction or audible output.
  Framework-free standalone startup, preserving production upgrade/signing, real
  authorization/business/capability/lifecycle matrices and full merge acceptance
  remain open; this checkpoint does not mark D2-D6 complete.

### D3/D4/D5/D6 Checkpoint: Retained Account Actions and Offline Device Fixtures (2026-10-02)

- Album, playlist and podcast collection controls pass their rendered context rather
  than recapturing a new account. Reservation validates the exact state/detail and
  stamp, including reloads after same-stamp recovery. The original Together page
  captures the rendered state value for create/join/end. Playlist menu/picker/create
  transitions retain one operation owner until dismissal; reopening can capture the
  current account. No page, layout, control, endpoint or download policy is replaced.
- Settings passes the displayed owner into both logout adapters. The transition
  compares that owner before any invalidation/cleanup; standalone also checks it
  after waiting for its mutation mutex. Closed tests do not log out a real account.
- The opt-in media prototype retains its resolving owner and rejects stale play or
  resume; ordinary production playback still uses MusicService with probes disabled.
  Listener publication avoids reading credential identity under the session monitor.
- Default standalone AndroidTest startup is now offline. Explicit live-read opt-in
  uses a private, in-memory verified session; recovery fixtures own an unscheduled
  in-memory WorkDatabase and do not initialize global production scheduling. Earlier
  source substitutes did not prove that the old target Application avoided background
  account/download bootstrap. ABI-020 records this correction and keeps ordinary
  production launch and real recovery acceptance separate from fixture startup.
- Paired debug/AndroidTest/JVM builds pass in 21s (160 tasks). Fresh JVM suites pass
  972 standalone and 1005 parasite cases (1977), zero failures/errors/skips. The
  offline standalone native run passes 59 cases in 170.042s: original Together/menu/
  share callbacks, Store regressions, offline package/image boundaries and private
  database/download recovery. The first instrumentation attempt fails to attach
  before any test runs (empty Java crash buffer); a subsequent run identifies seven
  fixture-only missing PlayerConnection providers. Supplying null only in that
  fixture fixes all seven. These failed runs remain recorded, not counted as success.
- Both production R8 artifacts build in 3m27s (107 tasks), and all 56 real-SDK local
  release gates pass. Release standalone DEX contains neither the fixture runner/
  Application nor live-read/package test classes. The ordinary parasite R8 is signed
  with the existing compatible development key; signature and 16KB alignment pass.
  Its SHA-256 is `d0a11111957aa2b65c125c046dc42d0e4f1f5017323d0fcac0e65c3b99ffa9a4`.
- Parasite passes 47 matched native cases in 137.079s (106 with the standalone run).
  A final standalone safety recheck passes 14 package/recovery cases and skips all
  seven live-read cases without opt-in; those skips are not server acceptance.
  Ordinary probe-disabled parasite R8 is preserving-installed and cold-starts TV in
  7805ms. PID 17871 has an empty Java crash buffer; the inspected screenshot shows
  original portrait Home, glass navigation and paused mini-player. MeiloX remains
  paused at 55536ms with null error, native TV is STOPPED, and MediaStore row 820
  retains size 22705573, TV ownership and pending=0. This is a bounded startup/state
  check, not full interaction, audio, cold-start reliability or real account acceptance.
- The current exit-gate summary remains authoritative; API-001/API-003/API-029/
  ABI-018 are extended in place. No cloud flow is reopened, no original standalone
  data is replaced, and no social/quota write is authorized by these changes. No
  AVD restart or screen-off, framework hook, global rotation change or remote action
  occurs. Local logs and artifacts stay under `/tmp/meilox-final-action-owner-*`,
  outside Git; D2-D6 and the full goal remain open.

### D3/D4/D5/D6 Checkpoint: Rendered Social and Library Navigation Owners (2026-10-02)

- A retained conversation/contact row or Library playlist/album row could navigate
  from an old account's rendered content, after which the destination would load
  under the current account. Social now captures Compose state as a rendered value;
  both existing consumers gate the exact state identity, session generation and
  recovery status while dispatching the unchanged route. Refreshed/removed resources
  are rejected even without an account-generation change. No layout, resource,
  control, endpoint, player or page architecture is changed.
- Paired debug/AndroidTest builds and focused JVM suites pass in 38s (160 tasks):
  SocialSessionTest has 23 cases and LibraryViewModelTest 19 per flavor, 84 total,
  with zero failures/errors/skips. Eight added cases per flavor cover replacement
  accounts, same-user reauthorization, same-stamp recovery and refreshed/removed rows.
  This is a focused rerun, not a fresh aggregate run of all 1977 earlier cases.
- SocialNavigationOwnerDeviceTest passes six native cases in standalone (6.326s)
  and six in parasite (20.526s). It renders the original conversation/contact rows
  and retains their actual debug ClickableElement callbacks across replacement,
  reauthorization and recovery, then verifies fresh callbacks can navigate. Sessions,
  contacts and accounts are in memory; history/send/share operations cannot dispatch.
  This is callback ownership, not pointer hit-testing, minified UI, real private reads
  or server delivery. Library navigation has focused JVM/source evidence here, not
  a new full-Library native fixture. The first parasite instrumentation attempt fails
  to attach before tests; no later AndroidRuntime stack is found. Its retry success
  does not erase that separate startup observation.
- Both production R8 artifacts build in 3m22s (107 tasks); all 56 real-SDK local
  release gates pass, including identities, signatures, versions, declarations and
  16KB alignment. Unsigned standalone SHA-256 is
  `7782d82aecf995d7c3dea12f066b8bce8623edecd3e1d4ae081ba206f97fa305`;
  parasite is `0f0a2e5c6ea9c79a74eecff97b8e4b02d44ebd141e4b03d6bed124b5e6952ca8`.
  These contain `de14777e` plus this scoped navigation repair, not the old playback
  artifacts described next. Development-signed parasite R8 is non-debuggable, passes
  signature/16KB checks and has SHA-256
  `a7b4b900baad2f0896c9fa6d7cf0f97031c4e2e267dfc2d0b5645f9554d30348`.
- Current parasite R8 is preserving-installed into the module package; the official
  TV APK is not replaced. App-only TV cold start succeeds in 2827ms (PID 27407),
  restoring Prelude PAUSED at 178897ms, null error and queue size 11;
  native TV remains STOPPED with no queue. The inspected settled screenshot shows
  original portrait MeiloX Home, glass mini-player/navigation and its paused control.
  This is not a cold-frame sequence, rotation or repeated-start reliability claim.
  MediaStore row 820 remains TV-owned, pending=0 and 22705573 bytes; the original
  production standalone package/data is untouched. API-028 and ABI-018 record the
  session-consumer distinction. At this checkpoint six other navigation families
  remained open; their later scoped repair is recorded below.
- Current ordinary standalone debug cold-starts after its offline fixture in 11603ms,
  restoring Full Moon Serenade PAUSED at 143811ms, speed 0, null error and seven
  queue entries. The inspected portrait Home retains original glass controls; TV
  subsequently returns hot in 183ms with the same PID and paused state. The slow
  standalone start is recorded, not generalized into startup reliability. No fresh
  Cookie is imported, and isolated-ID execution is not production-ID or upgrade
  acceptance. Neither cold start validates current minified playback or callback
  integration; the native callback cases above use matched debug targets.
- Local-only evidence is under `/tmp/meilox-retained-navigation-*` and
  `/tmp/meilox-retained-navigation-owner-*`. No AVD restart, screen-off, framework
  scope/global rotation change, credential copy, cloud rerun or social/quota write
  occurs. This checkpoint does not close any remaining D2-D6 exit gate.

### D5/D6 Checkpoint: Bounded R8 Track Completion and Tail Resume (2026-10-02)

- These playback cases use the prior `de14777e` artifacts, before the navigation
  repair above. TV's installed module SHA-256 is
  `d0a11111957aa2b65c125c046dc42d0e4f1f5017323d0fcac0e65c3b99ffa9a4`.
  A local-only build-directory/application-ID override produces normal standalone
  R8 under `com.neoruaa.meilox.standalone.debug`, SHA-256
  `3e215580f6f436a7da01bdf645c8738991789c29641bf7ecfecd223a201636ec`.
  Both are non-debuggable and use the compatible existing development key; production
  standalone is neither replaced nor given the validation ID. AutoMix stays off;
  the existing qualities, modes, queues and controls are not redesigned.
- Standalone Full Moon Serenade decodes a duration of 224888ms. A 243s capture starts
  at 2612ms, advances through its end and naturally reaches Whispers Woven in Mist,
  whose progress also advances. Primary AudioFlinger frames increase by 11663360,
  with an active unmuted isolated-runtime track and nonzero signal power. A separate
  original-slider tail case pauses at 209462ms, resumes through the platform media
  session and naturally reaches the successor. No Media3 ERROR/stuck exception is
  recorded in either scoped engine capture. The first attempted tail seek happened
  while the queue was open and did not seek; it is not counted as tail evidence.
- The original EndOfTrack timer naturally transitions from Whispers to the successor,
  then holds PAUSED/speed 0 at 36ms throughout a 41s capture. An explicit platform
  resume advances beyond 39s with no engine error. This is natural end-of-track and
  platform resume evidence, not a five-minute-expiry replay or UI-toggle equivalence.
  An unanswered system notification-permission dialog obscures direct Off-state
  inspection; it is canceled with Back, with the ungranted permission and original
  flags unchanged. No notification permission is silently granted.
- TV magnolia is confirmed at 0ms before continuous playback and naturally advances
  to Prelude; the successor reaches 101698ms at the 243s sample. Primary AudioFlinger
  frames increase by 11665536 with PID 17871 and nonzero signal. Native TV remains
  STOPPED with no queue throughout. The capture has AudioTrack device-stall time
  corrections but no Media3 ERROR/stuck exception; it is not described as an empty
  log. An earlier manually interrupted track capture is not full-track acceptance.
  The displayed queue window grows from six to eleven, so unchanged queue size is
  not claimed. No stream-provider attribution is inferred solely from metadata IDs.
- Engine/progress/output-state evidence does not establish audible AVD output,
  full quality/AutoMix coverage or final listening-statistics aggregation. Accepted
  standalone report acknowledgements are kept distinct from server statistics.
  The Oct1 handled no-progress ERROR at 129910ms remains open: its named local raw
  trace is no longer present at this audit, so the exact original song, duration and
  stack cannot be recovered. These different-song, AutoMix-off cases neither prove
  attribution nor erase that five-minute-expiry failure. No engine/UI fix is made.
- The isolated standalone is preserving-restored to ordinary debug after the R8
  cases, retaining its seven-entry queue without replacing production data. Local
  captures, APKs and R8 mappings remain outside Git under `/tmp/meilox-nearend-*`
  and `/tmp/meilox-standalone-r8-validation-*`. ABI-014 is extended in place; all
  production-upgrade, framework-free, real-account, capability and full-regression
  gates in the current exit summary remain open.

### D3/D4/D5/D6 Checkpoint: Remaining Catalog Navigation Owners (2026-10-02)

- This follows `d962a931` without changing the original page architecture. The six
  previously open families cover 13 existing entries: AccountHome rank/playlist,
  FindMusic playlist, SearchLanding recommendation, four non-song SearchResult
  kinds, three Podcast list sections and SongWiki playlist/contribution. Routes,
  URLs, endpoints, layout, controls, song playback and public guest browsing remain
  unchanged. Authentication is still required for account and subscription content.
- Search/discovery rows capture rendered values and gate exact state, session and
  recovery at dispatch; a copied cached search tab cannot revive an old callback.
  FindMusic exposes the existing load generation as a read-only session/generation
  pair, collected by the existing screen so same-category cache re-entry captures
  the new owner. Dispatch also matches resource identity, category and playlist ID.
- AccountHome keeps its original concurrent detail/playlist loads behind a narrow
  loader/error seam. A publication-only revision retires structurally equal renders;
  a private refresh flow also reloads after AccountStore's same-profile refresh is
  conflated. The existing refresh still calls AccountStore.refresh and immediately
  retires old callbacks. Success/error publication pins the exact loading state,
  rejecting a non-cooperative earlier reload. Navigation requires the displayed
  profile to match the current account by value, exact internal/rendered state,
  current authentication/session and readiness; it does not rely on profile pointers.
- Podcast navigation checks exact rendered state, selected tab and visible member;
  clearing the ViewModel retires presentation. SongWiki checks the current request's
  song, related playlist membership or exact contribution URL. These gates adapt
  session-owned consumer dispatch, not a second transport or a frontend cleanup.
- Paired debug/AndroidTest builds and fresh focused JVM suites pass in 16s (160 tasks).
  Per flavor: SearchSessionTest 27, AccountHomeViewModelTest 9, AccountHomeStateTest 4,
  FindMusicSessionTest 18, PodcastSessionTest 37, SongWikiSessionTest 19,
  SocialSessionTest 23 and LibraryViewModelTest 19: 156 each, 312 total, with zero
  failures/errors/skips. The repair adds 38 cases per flavor. An initial secondary-
  constructor qualifier compilation failure and two then one account test failures
  expose the refresh/profile-matching gaps; corrections precede the successful fresh
  run. They are not runtime flakiness or counted as successful attempts.
- FindMusicNavigationOwnerDeviceTest passes six cases in standalone (8.618s) and
  six in parasite (7.847s), both on their first attempt. The original screen's actual
  debug rendered callbacks
  reject old owners and accept new callbacks, including cached same-category re-entry
  where only the reactive owner changes. Public sessions/source and coverless rows
  are closed fixtures, without AppGraph, real accounts or network. This is callback
  ownership, not pointer hit-testing or minified stale-callback behavior. The paired
  AndroidTest build passes in 16s (114 tasks); this test-only addition leaves both
  production artifact hashes below unchanged.
- Both current production R8 artifacts build in 3m47s (107 tasks); all 56 local
  real-SDK release gates pass. Unsigned standalone SHA-256 is
  `e7b86cc28244f5c00e18135bd1dbebb2acde8b35ffb8e34ccf2b57d32e49cec5`;
  parasite is `b7e04631fd4131aa6fdcfda5cb557bfa777a468a6b14c810a940aa5686d3a071`.
  Development-signed parasite SHA-256 is
  `94fb482ed23917ab863573bfa130b2b6d9894bca1456ef4953641dbacd7fa66c`,
  with the unchanged compatible development key and passing 16KB alignment.
  Module-only preserving installation and app-only TV cold start succeed in 14679ms
  (PID 29938). The inspected settled screenshot shows portrait Home and paused
  Prelude; the original Settings -> AccountHome pointer entry loads the current
  profile and playlist rows through the new constructor, and its original rank
  button loads recent-week rows. This is ready-content/navigation, not an attributed
  listening-count increment: no pre-play ranking baseline is captured. It is not
  repeated-start reliability, obsolete callback behavior or full current paired R8
  execution; standalone R8 is not updated for this pass. The target is non-debuggable
  and the scoped PID has no AndroidRuntime stack; that does not qualify every failure.
- After native fixtures, current signed parasite R8 and ordinary standalone debug
  are preserving-restored. Standalone cold start succeeds in 11996ms, retaining
  Full Moon Serenade at 143811ms and seven entries, NONE/unprepared, speed 0 and null
  error without autoplay; this is not a PAUSED/decoding claim. TV app-only cold start
  succeeds in 16389ms (PID 32457), retaining Prelude PAUSED at 178897ms, null error
  and eleven entries; native TV stays STOPPED with no queue. The settled screenshot
  shows original portrait Home/glass mini-player/navigation. Both slow starts remain
  unqualified for reliability. The original production standalone package path is
  unchanged; MediaStore row 820 remains TV-owned, pending=0 and 22705573 bytes. No
  AVD restart, second-device interaction, framework hook/global rotation change,
  new authorization/Cookie import, permission grant or social/quota write occurs.
- FindMusic's native cases do not qualify retained original-row callbacks for the
  other five families; AccountHome's current R8 positive entries remain a separate
  ready-state check. Real account/expiry, failure/page integration, minified lifecycle,
  original-install upgrade, framework-free execution, recording/PiP and cooperating-
  account gates remain open. ABI-018 is extended in place; no cloud/download rerun
  or real write fills these gates. Native logs stay outside Git at
  `/tmp/meilox-public-navigation-{standalone,parasite}-native-2026-10-02.log`.

### D3/D5/D6 Checkpoint: Same-Artifact Startup Timing Boundaries (2026-10-02)

- The installed ordinary parasite R8 is unchanged from the `d55c` source checkpoint,
  SHA-256 `94fb482ed23917ab863573bfa130b2b6d9894bca1456ef4953641dbacd7fa66c`.
  An app-only TV force-stop/start on the awake existing rooted AVD gives COLD
  TotalTime 3295ms and WaitTime 3299ms (PID 4492). The matching platform launch event
  at 20:22:06.800 reports 3295ms. Settled original portrait Home/glass retains Prelude
  PAUSED at 178897ms, eleven entries and null error; native TV stays STOPPED with no
  queue. Home-return keeps PID 4492 and reports UNKNOWN(0), task brought to front,
  WaitTime 94ms. That is not a WARM launch or a measured first-frame duration.
- Three same-artifact traces have these logged milestones; times are trace timestamps,
  not individually instrumented phase durations:

  | Milestone | PID 29938 | PID 32457 | PID 4492 |
  | --- | --- | --- | --- |
  | Process start | 19:15:40.847 | 19:42:38.294 | 20:22:03.720 |
  | Module loaded | 19:15:42.780 | 19:42:41.796 | 20:22:04.637 |
  | Verified | 19:15:47.872 | 19:42:45.635 | 20:22:05.847 |
  | Activity milestone | 19:15:48.823 | 19:42:48.268 | 20:22:06.076 |
  | Platform launch/display event | 19:15:54.697 | 19:42:54.232 | 20:22:06.800 |
  | Service instance milestone | 19:15:56.518 | 19:42:56.730 | 20:22:06.895 |
  | Service-created milestone | 19:15:57.486 | 19:42:58.085 | 20:22:06.995 |
  | Reported TotalTime | 14679ms | 16389ms | 3295ms |

- In the two slow launches, Activity-to-service-instance gaps are 7.695s and 8.462s,
  while service-instance-to-created intervals are 0.968s and 1.355s. This narrows the
  service initialization explanation; it does not explain the entire launch delay
  or identify class loading, network, cache, host, module or OS as its cause. Host
  onCreate precedes module initialization; loaded-to-verified mixes those lifetimes.
  The Activity milestone precedes setContent; service-created is not complete queue
  restoration. All three platform first-draw events precede service completion, so
  none is a complete shared-UI/playback readiness measurement or a startup fix.
- A separate latest `d55c` standalone R8 is preserving-installed and pulled back
  under the isolated debug ID, non-debuggable, with the unchanged development
  certificate and passing 16KB alignment. Its installed/pulled SHA-256 is
  `58f59b9356ec7773e77d68965e9c700923bc9f55d49effd83168df25bf03c22a`.
  Its app-only cold start has TotalTime
  515ms/WaitTime 518ms (PID 5163); process start at 20:25:03.997 and platform launch
  event at 20:25:04.490 correlate with the reported 515ms. Settled original Home
  restores existing account/content/glass and seven entries, Full Moon Serenade
  NONE/unprepared at 143811ms, speed 0 and null error without autoplay. Home-return
  keeps that PID, UNKNOWN(0), WaitTime 20ms, not a WARM launch or first-frame measure.
  This is isolated minified startup/state, not new playback/decoding, production-ID
  preserving upgrade, framework-free execution or full paired runtime acceptance.
- The isolated R8 original Settings account row opens AccountHome, and its original
  rank button opens settled recent-week content; both screenshots are inspected.
  These are read/navigation checks with the existing account, not a new playback
  session or an attributed statistics increment without a pre-play baseline.
  The original production standalone package path remains unchanged and MediaStore
  row 820 is again TV-owned, pending=0 and 22705573 bytes. At this capture, isolated
  R8 remains installed and TV is background-paused; ordinary-debug restoration is
  not yet claimed.
- Failed attach observations remain separate: TV PID 13579 at 16:20:09.722
  (10.166s after process start), standalone PID 15761 at 16:50:01.489 (10.004s),
  and module fixture PID 27061 at 18:38:49.247. Successful later launches do not erase
  them or establish a common cause. ABI-005 is extended in place; startup reliability,
  framework-free execution, production upgrade and the remaining D2-D6 gates stay open.
  Only emulator-5554 is used, screen-on, without an AVD restart. Whitelisted traces,
  screenshots and artifacts remain outside Git; no account/permission write is added.

#### Finite Platform Trace Analysis (2026-10-03)

- The previously collected 20.000700s TV trace parses without nonzero error/warning
  stats. PID 14025's platform launch slice is 2140.796ms, bindApplication 951.134ms,
  makeApplication 82.997ms and first post-resume frame 466.084ms. It is a different,
  fast launch, not the PID 4492 milestone log or evidence explaining the 14-16s cases.
- At source checkpoint `dde5c5a2`, one additional bounded capture uses the awake
  emulator-5554, app-only stops while both queues are paused, and sequential ordinary
  TV/isolated-standalone cold starts. No instrumentation fixture, rebuild, installation,
  AVD restart, playback action, permission or account write is introduced. The installed
  artifacts are independently hashed: parasite R8
  `bacec67217bee6422967eb5d241b208d4c5de593bbd1a7f5250314e2fd32633c`,
  isolated standalone ordinary debug
  `605a98fde598e40ce40104c81b18a8871c02edd348bfe383b0fb192fd3891992`.
  Different build types mean these samples are not a paired R8 performance comparison.
- `atrace -b 16384 -t 60` captures am/wm/gfx/view/dalvik/sched/binder_driver with only
  the two application cmdlines enabled. It completes normally; tracing_on returns to
  zero. Local official Perfetto Trace Processor v58.2 imports the 60.000844s trace
  without nonzero error/warning stats or ftrace-loss stats. No trace is uploaded.

  | Measured phase (ms) | TV PID 30232 | Isolated standalone PID 30567 |
  | --- | --- | --- |
  | `am start -W` COLD TotalTime | 2587 | 1656 |
  | Platform launching slice | 2580.369 | 1644.806 |
  | ActivityThreadMain | 410.058 | 12.090 |
  | bindApplication | 905.218 | 786.764 |
  | APK open slice within bindApplication | 378.626 | 677.100 |
  | makeApplication within bindApplication | 87.436 | 2.823 |
  | activityStart | 141.957 | 60.076 |
  | activityResume | 27.307 | 12.493 |
  | First post-resume Choreographer frame | 626.397 | 599.623 |

- Nested slices must not be added to their parents. The standalone's earlier 1.267ms
  frame precedes activityStart and is not the content frame. The first post-resume
  traversal contains Compose initialization (73.331/75.947ms) and measurement
  (360.433/296.748ms), with nested movable-content insertion (232.594/209.819ms).
  During the launching slices, main-thread Running totals are 1939.030/1355.368ms;
  sleep totals are 484.327/190.775ms. Neither sample contains the old multi-second
  gap. These fast samples do not attribute an intermittent slow launch or failed
  process attachment to host, module, framework, network or shared UI. Existing
  source does not separately trace official Application.onCreate and module graph
  initialization; makeApplication is not either one's exclusive duration.
- Settled screenshots show original portrait Home, covers and glass in both runtimes.
  TV returns to foreground with the same PID (HOT, TotalTime 1231ms), retains Prelude
  PAUSED at 178897ms and eleven entries; native TV stays inactive/STOPPED with no queue.
  Standalone retains Full Moon Serenade NONE/unprepared at 143811ms, speed zero and
  seven entries. Both have null playback error and no autoplay. Published MediaStore
  row 820 remains TV-owned, pending=0 and 22705573 bytes; the original production
  standalone package path is unchanged. No new decoding/audio acceptance is inferred.
- Raw trace (SHA-256
  `b70547a49024b6432271ab74c6e40a3ebca0796e5ceba680a8a7496d7da988ea`),
  SQLite export, phase/state summaries, start logs and screenshots stay under
  `/tmp/meilox-paired-cold-*2026-10-03*`, outside Git. This is a documentation-only
  qualification increment, not a startup fix. Slow-launch/attach attribution and
  all existing D2-D6 exit gates remain open; normal starts are not repeatedly run
  until a desired failure occurs. ABI-005 records the runtime evidence boundary.
- Validation: the local real-SDK `dual_runtime_release_test.rb --built-apks` gate
  has 56 PASS lines and exits zero; `git diff --check` passes. No app source changes,
  new unit/device fixture run or APK rebuild is needed for this documentation-only
  increment. Previously built APK checks are not substituted for startup acceptance.

### D3/D6 Checkpoint: Creation-Owned WebView Candidate Retry (2026-10-02)

- The standalone restoration's `lastAttemptedCookie` marker permanently skipped an
  unchanged Cookie after failed private verification. A transient verification failure
  could therefore prevent the existing WebView page from recovering. The extracted
  poller now retries a failed same value after five seconds, observes a changed value
  on the next tick, and reports success only when verification returns true. This
  corrects a dual-backend login/session adaptation, not a main frontend cleanup.
- Retries retain the poller/controller's first readable creation owner, never
  rebinding after retirement. Initial unreadable transitions wait at 500ms intervals
  without reading WebView Cookies until that first owner is available. A no-longer-current
  owner ends observation; failed verification remains retryable while ownership is
  current. Standalone controller/store validation uses atomic `withCurrent` before
  incrementing the attempt counter, so a stale observer cannot cancel or replace a
  newer login. The official QR logic, credential ownership, signing and page layout/
  controls remain unchanged; no official Cookie is copied into standalone.
- Opening the original manual sheet pauses/cancels automatic polling; mutual
  exclusion and manual priority apply before commit. They cannot revoke a durable
  commit already started. A held persistence case proves that the earlier valid A
  commit may complete, while manual B carrying its retired fixed owner fails closed
  without verifying/writing B. No transaction rollback or automatic owner rebasing
  is added, and all-stage manual priority is not claimed.
- A fresh closed run passes 63 cases: WebCookieLoginPollerTest 27,
  StandaloneSessionStoreTest 16, StandaloneAccountTransportTest 10 and shared
  SessionStoreTest 10. These use synthetic credentials/sessions and controlled
  coroutine/persistence/transport behavior, not real authorization. The initial
  14-case dedup repair missed three external-owner regressions; their failing tests
  lead to fencing. Two later initial-transition WebView/manual failures lead to
  one-time readiness capture. Intermediate passes/artifacts are not final evidence.
- Final source/test fingerprints remain unchanged across the build. Both production
  R8 artifacts build in 1m20s (107 tasks); all 56 real-SDK local release gates pass
  in 38.14s. Unsigned standalone SHA-256 is
  `cf12ad02d072cf6daa62dfe5891264452e6fc25273abaa50b355153cad4465c2`;
  parasite is `e747628d1392f4387f3b40a1d0d56aafa74e83039a9d5131b6ce850420fc6ba6`.
  The final local isolated standalone R8 builds in 2m15s (55 tasks), SHA-256
  `0bf241904c715747d513e807e631b79f1f40083ca69679248c3cbd0d8bc4a448`;
  ordinary-debug restoration builds in 9s (41 tasks), SHA-256
  `c934b45531c0efa67850077a87b375bc5d39874a0fa1c2e56d0447ba92df9b95`.
  Isolated R8 and ordinary debug use the unchanged compatible development certificate,
  pass 16KB alignment and keep probes disabled; R8 is v3-signed and debug v2-signed.
  No middle artifact is substituted for this final source. Existing shared SessionStore
  production code is unchanged; its ten contract tests are rerun, not a new store repair.
- Final isolated R8 is preserving-installed and its pulled SHA-256 matches the final
  isolated artifact above. Android's PackageUpdateActivity catches the first start:
  UNKNOWN(0)/WaitTime 4ms with the update screen, not cold-start proof. Observation
  confirms that process subsequently enters MainActivity; no timeout triggers an
  AVD restart. After the wrapper finishes, app-only cold start reports 537ms/539ms
  (TotalTime/WaitTime, PID 9506), retaining Full Moon Serenade NONE/unprepared at
  143811ms, seven entries, speed 0 and null error without autoplay. The intermediate
  wrapper process's PAUSED state is not substituted for this final cold state.
- Final signed/pulled parasite SHA-256 is
  `24895a8dac4f428b82954ffe057b90f200243a224552074ae9e83bcacbca0d4f`,
  non-debuggable/probe-disabled, with unchanged development certificate and passing
  16KB alignment. Module-only preserving install and TV app-only cold start report
  14923ms/14955ms (PID 9861). Logged milestones are process 21:37:50.444, loaded
  21:37:51.429, verified 21:37:54.010, Activity 21:37:54.920, platform launch
  21:38:01.853, service instance 21:38:04.847 and service-created 21:38:05.710.
  Activity-to-service-instance is 9.927s, versus 0.863s to service-created; these
  observations do not attribute the slow launch or establish reliability. Settled
  original portrait Home/glass retains Prelude PAUSED at 178897ms, eleven entries
  and null error, with native TV STOPPED and no queue. Current-PID AndroidRuntime
  error lines are zero, not proof of every failure path or complete release behavior.
- Ordinary standalone debug is then preserving-restored from the final debug artifact
  above; app-only cold start reports 1222ms/1227ms (PID 10705), retaining the same
  Full Moon Serenade NONE/unprepared position/queue without autoplay or player error.
  TV returns to front HOT at 480ms/481ms in the same PID 9861; this is not cold-start
  reliability. The final portrait Home/glass/paused state is inspected. Production
  standalone's original package path is unchanged; MediaStore row 820 remains
  TV-owned, pending=0 and 22705573 bytes. No AVD restart/screen-off, second device,
  framework/global rotation change, real Cookie-file access, authorization/logout,
  permission/quota/social write or new playback is performed. Home smoke does not
  exercise interactive WebView authentication. Logs/screenshots/APKs remain outside
  Git; all capture processes finish before the checkpoint is recorded.
- Original login pages may be inspected without authorizing a real transition;
  no WebView/QR login, logout, new account, Cookie-file import or real expiry matrix
  is accepted here. ABI-008 is extended in place, with API-003 retaining the official
  state-machine distinction. Existing D2-D6 upgrade/framework-free/capability/account/
  lifecycle and real-server gates remain open.

### D3/D5/D6 Checkpoint: Paired Original Search Navigation Callbacks (2026-10-02)

- This is a test-only follow-up to the catalog-navigation repair. The shared original
  SearchLandingScreen and SearchResultScreen are rendered with their existing Nav3
  navigator and unchanged controls. Five targets cover recommendation, artist, album,
  playlist and podcast navigation; there is no production source or page change.
- Each target tests ready guest navigation, replacement render with the same stamp,
  replacement account, same-account reauthorization and same-stamp recovery. The
  fixture retains the actual rendered ClickableElement callback, not a mutable
  semantics forwarding action or a direct ViewModel guard call. Retired callbacks
  cannot navigate; fresh callbacks reach the original route and ID. The same DTO
  object survives replacement, so changed row identity cannot satisfy the retirement
  assertion accidentally.
- Sessions, search/discovery sources and artwork are closed fixtures. The original
  result screen's inactive overlay dependencies use an owned ViewModelStore and
  fail-closed repository/DAO/client substitutes. Teardown rejects any unrelated
  dependency or fallback-model call. Standalone instrumentation substitutes its
  offline Application before bootstrap; parasite fixtures run in the module process,
  not the official host. No real account, socket, Cookie, transport or player is used.
- Root-reviewed source and paired debug/AndroidTest builds pass in 8s (160 tasks).
  Fresh SearchSessionTest runs pass 27 cases per flavor, zero failures/errors/skips.
  On emulator-5554, all 25 native cases pass first attempt in standalone (26.930s)
  and parasite (27.395s). This qualifies concrete callback ownership in debug, not
  pointer hit-testing, R8 callback execution, destination reads or real authorization.
- The test-only change leaves the production R8 hashes unchanged: standalone
  `cf12ad02d072cf6daa62dfe5891264452e6fc25273abaa50b355153cad4465c2`,
  parasite `e747628d1392f4387f3b40a1d0d56aafa74e83039a9d5131b6ce850420fc6ba6`.
  Logs remain outside Git at
  `/tmp/meilox-search-native-{standalone,parasite}-root-2026-10-02.log`.
- Ordinary standalone debug and the previously qualified parasite R8 APK are
  preserving-restored, with the installed module hash matching the recorded artifact.
  App-only cold starts report 1918ms for standalone and 2546ms for TV; this single
  fast sample does not resolve the earlier slow starts. The inspected final screenshot
  shows original portrait Home and paused Prelude. TV retains position 178897ms,
  eleven entries and null error; its native session stays STOPPED/empty. Standalone
  retains NONE/unprepared at 143811ms, seven entries, speed zero and null error.
  The production standalone package path and TV-owned MediaStore row 820 (22705573
  bytes, pending=0) are unchanged. No AVD restart/screen-off, second device, permission,
  authorization or write operation is used.
- ABI-018 is extended in place. AccountHome, Podcast list and SongWiki native
  retained-callback coverage and the existing D2-D6 production-upgrade, framework-free,
  real-account, capability, lifecycle and server gates remain open. No cloud/download
  flow is repeated and no real write substitutes for those gates.

### D3/D5/D6 Checkpoint: Observable Podcast and Remaining Catalog Callbacks (2026-10-02)

- Closed native tests render the original AccountHomeScreen, PodcastScreen and
  SongWikiScreen, retaining concrete ClickableElement callbacks through the original
  navigator. AccountHome covers rankings/playlist; Podcast covers recommendation,
  featured and subscription rows; Wiki covers related playlist/contribution. The
  contribution UriHandler only records fixture.invalid locally and never opens a
  browser. No real account, credential, transport, subscription mutation or player
  service is used. Explicit models and fail-closed fallback factories keep the
  production graph out of these fixtures.
- AccountHome adds 18 cases per flavor: ready authenticated and guest policy,
  replacement account, reauthorization, same-stamp recovery, identical resources,
  changed profile, removed playlist and pending refresh. Wiki adds 18 cases per
  flavor: ready authenticated/guest, replacement account, reauthorization, recovery,
  same-content song reentry, pending replacement song, removed target and clearing.
  These two page implementations are unchanged.
- Podcast initially fails nine of 24 standalone cases. Removing an inappropriate
  hidden-discovery readiness requirement and relocating lazy rows after tab reentry
  does not eliminate those failures. The corrected observation shows a present row
  and ready data but rendered-state reference mismatch. Three new JVM reproductions
  fail for structurally equal immediate discovery/subscription reloads and category
  reentry. The migration's exact-state dispatch guard therefore needs observable
  publication ownership; weakening the test or guard would leave visible callbacks
  retired after equal reads.
- PodcastUiState gains only a presentation revision. Every PodcastViewModel state
  publication advances it under the existing stateLock. Session ownership, request
  versions, recovery, membership checks, endpoints, business DTOs, routes, original controls
  and layout remain unchanged. Three immediate-equal-content native cases are added,
  producing 27 podcast cases per flavor; gated refresh, tab reentry, clearing and
  guest discovery/private-subscription policy remain covered. This is migration
  integration, not a main-only UI cleanup or playback-engine change.
- Fresh paired focused JVM suites pass 72 cases each (AccountHomeState 4,
  AccountHomeViewModel 9, PodcastSession 40, SongWikiSession 19), zero failures,
  errors or skips. Debug and AndroidTest builds pass in 18s (160 tasks). The new
  native suites pass 63 cases in standalone (105.530s) and 63 in parasite (99.794s),
  including 27 podcast cases each. Installed standalone test-APK hash matches the
  locally built artifact. Failed native attempts and the three failing-before-fix
  JVM cases are retained separately, not counted as accepted evidence.
- The paired AndroidTest/production R8 build passes in 7m40s (221 tasks), including
  vital lint. All 56 local real-SDK release/signing/identity/version/declaration/16KB
  gates pass. Unsigned standalone SHA-256 is
  `69ae0df48f966a2a7a7a7c44c9e436987ad1c8337e758b482ec37231e01b8d49`,
  parasite `86f8dc7fe210a88a22f9eeea7004676ea16acb38374180acff51ad37c111b0c7`.
  Both R8 mappings retain the presentation revision. Development-signed parasite
  SHA-256 is `d7e45aea486a5faee4198417c8a953ad18ed8a095fa4fa3e9d01710def907f0d`,
  with the existing compatible certificate and passing 16KB alignment. Its installed
  hash matches. Module-only preserving installation and TV app-only cold start succeed
  in 4416ms (PID 22069), displaying original portrait Home/glass and paused Prelude
  at 178897ms, eleven entries and null error; native TV stays STOPPED/empty. This
  single startup sample does not attribute or resolve the previous slow-start gap.
- With its existing official account, the minified TV runtime displays a real
  subscribed podcast in the original Library tab. A pointer click opens its original
  detail page with artwork, a 92-program total and visible program rows; no playback
  or subscription action is invoked. This is positive read/navigation evidence, not
  retired Library-row ownership or completion of all 92 program reads.
- A separate isolated-ID standalone R8 artifact builds in 3m48s (55 tasks), signed
  SHA-256 `4ae1761d0e90e0a519230ac76aae80a71edb52ad5f511bcbe163d8f78aec44e2`.
  It remains non-debuggable and 16KB-aligned with the compatible development key;
  installed hash matches. Its app-only cold start reports 846ms (PID 22966), retaining
  Full Moon Serenade NONE/unprepared at 143811ms, seven entries, speed zero and null
  error. The existing standalone account displays seven subscribed podcast rows;
  a pointer click opens an original detail page with artwork, a 2515-program total
  and visible rows. This does not qualify production-ID upgrade/signing, all 2515
  programs, actual playback or real account transitions. No Cookie-file import or
  official credential copy occurs.
- Ordinary standalone debug is preserving-restored, installed hash
  `6ccbc6be8e258d5e54e01ccffaec4c8663dc7c358f26bf5820d37bca6465474a`.
  The settled session is PAUSED/prepared at 143811ms, seven entries, speed zero and
  null error without a playback action. TV returns in the existing PID 22069 and
  retains its paused position/queue. Production standalone's original package path
  and TV-owned MediaStore row 820 (22705573 bytes, pending=0) remain unchanged.
  No AVD restart/screen-off, second-device access, permissions, real authorization,
  upload/download/social/subscription write or framework/global rotation change
  is performed. Logs, APKs and screenshots remain outside Git under
  `/tmp/meilox-catalog-*`; all execution handles finish before commit.
- This closes only the named debug callback-family test scopes, not pointer input,
  destination data, real authorization or full R8
  lifecycle. The LibraryMobileLayout libraryPodcastItems direct subscription-row
  dispatch is a separate source-review finding: it still lacks a rendered-owner
  guard and is not covered by the original PodcastScreen fixture. Captured-row
  reproduction and a scoped migration repair remain open; no original layout change
  is authorized by this observation. Existing D2-D6 real-account, upgrade, capability,
  framework-free, server and playback/startup-attribution gates remain open.

### D3/D5/D6 Checkpoint: Library Subscription Navigation Ownership (2026-10-03)

- The separate LibraryMobileLayout subscription row recorded above is reproduced
  using its original rendered ClickableElement callback and navigator, with synthetic
  identities and closed sources. Before production changes, a corrected two-case run
  passes ready-state navigation but fails account replacement: the retired callback
  enters `podcast/91` instead of remaining at the fixture root. An earlier fixture
  teardown failure is corrected by disposing content before clearing its default
  model store; it is not attributed to production behavior.
- Library requests subscriptions without selecting PodcastTab.Subscriptions. A
  tab-based guard would therefore break valid Library clicks. Its original row now
  calls PodcastViewModel.withCurrentSubscription, checking the exact rendered state,
  current authenticated session, recovery, loaded subscriptions and membership.
  Existing podcast-screen dispatch shares only the session/state gate and retains
  its tab/category membership policy. Routes, layout, controls, endpoints, DTOs,
  backend bindings and playback are unchanged. This repairs migration-owned account
  intent, not a main-only frontend issue.
- Five new focused JVM cases cover independent discovery/subscription membership,
  refresh/removal, same-stamp recovery/reauthorization, replacement/logout and clearing.
  PodcastSessionTest passes 45 cases per flavor, zero failures/errors/skips. The
  paired debug/test build passes in 37s (160 tasks).
- Library adds nine original-layout native cases per flavor: current/default-tab
  navigation, guest policy, replacement, reauthorization, recovery, equal immediate
  refresh, pending refresh, removal and clearing. The existing 27 original-podcast
  cases also pass after the shared gate extraction. Final serial execution passes
  36 cases in parasite (175.657s) and 36 in standalone (317.392s). A prior overlapping
  scheduling attempt and separate failed-attach/process-crashed startup attempts
  are excluded from acceptance. They remain separate logs, not silently counted as
  successful tests or attributed to this business change. The long test times do
  not qualify startup or rendering performance. Standalone fixture bootstrap is
  offline; parasite fixtures run in the module process, not the TV host. Inactive
  overlays use explicit models and fail-closed dependencies, with no production graph,
  player connection, credentials or real transport.
- Both production R8 artifacts build in 4m38s (107 tasks), including vital lint;
  all 56 local real-SDK release/signing/identity/version/declaration/16KB gates pass.
  Unsigned standalone SHA-256 is
  `bdd08b9503c310ade417508af5125039008c02be55cddfff289077bdc936f50a`,
  parasite `37539bce4ec3f80db6cf8392574bba145f03a0417d0044a0b564b2ce257e1633`.
  The compatible development-signed parasite is
  `bacec67217bee6422967eb5d241b208d4c5de593bbd1a7f5250314e2fd32633c`;
  signature/alignment and its installed hash match. API 102 metadata retains static
  TV-only scope and disabled hot reload. No framework hook is added.
- The ordinary isolated standalone debug installation remains current, SHA-256
  `605a98fde598e40ce40104c81b18a8871c02edd348bfe383b0fb192fd3891992`.
  Its app-only cold start reports 12318ms. With its existing account, the original
  Library shows seven subscribed rows; an actual pointer click opens the first
  detail with artwork, a 2515-program total and visible rows. This is current debug
  positive read/navigation evidence, not a newly installed standalone R8 artifact,
  all-program pagination, real account transitions or production-ID upgrade.
- The new parasite R8 module is preserving-installed and the TV app is restarted.
  Its cold start reports 4695ms and shows portrait original MeiloX Home/glass. An
  actual Library subscription-row click opens the original detail with artwork,
  a 92-program total and visible rows. These samples do not resolve intermittent
  startup failure or qualify retired callbacks in R8. Both players remain PAUSED at
  their prior positions (TV 178897ms; standalone 143811ms), speed zero and null error;
  native TV remains STOPPED/empty. No playback, subscription mutation, upload/download
  quota, social write, supplied Cookie-file access or account authorization is invoked.
- Original standalone's package path and TV-owned MediaStore row 820 (22705573 bytes,
  pending=0) remain unchanged. Only emulator-5554 is used; no AVD restart, second-device
  access, permission change, framework/global rotation change, merge or push occurs.
  Logs, screenshots and APKs remain outside Git under `/tmp/meilox-library-*`.
  Main is rechecked at `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`.
  TV is returned to the original portrait Home paused; all execution handles finish
  before the scoped commit.
- This closes the recorded Library subscription-row source/debug callback scope,
  not full Library action integration or the D2-D6 upgrade, capability, real-account,
  lifecycle, server and playback/startup-attribution exit gates. The full goal remains
  open; no milestone is marked complete by these bounded tests.

### D5/D6 Checkpoint: Isolated Host Lifecycle and Saved-Task Recreation (2026-10-03)

- A real same-APK parasite R8 failure is reproduced without resetting the system
  task: TV task 1386 stops with saved state (3980 bytes), its process is killed
  while backgrounded, and recreated PID 31849 crashes in platform Fragment restore
  with ClassNotFoundException for `androidx.lifecycle.ReportFragment`. PID 31966
  subsequently starts a service; its existence is not restored-Activity success.
- TV's exact APK DEX confirms `LifecycleDispatcher` invokes the host's static
  `ReportFragment.injectIfNeededIn(Activity)`. This Application callback inserts
  a host-owned Fragment into module MainActivity; the module-only loader cannot
  restore that name after death. Module R8 has its own `androidx.lifecycle.v`.
  This is a parasite classloader/lifecycle conflict, not a layout or backend
  endpoint change; ABI-021 records the runtime difference.
- The module skips only that host injection for the exact module MainActivity
  Class object in the verified TV package. Official/foreign activities keep their
  original behavior. A narrow platform Fragment restore adapter borrows the host
  loader only for that exact activity and legacy host Fragment name. It neither
  discards saved state nor adds a general host-loader fallback. The host's distinct
  API-29+ ProcessLifecycleOwner callbacks remain installed. API 102, TV-only scope,
  disabled hot reload, official Application and existing direction configuration
  are unchanged; no system-framework scope or global rotation change is made.
- Cross-artifact experiment is **not qualified**: task 1388 retains 3980-byte state
  from the old minified APK across module replacement. New PID 1681 gets past the
  platform Fragment phase but crashes at first Compose attachment while reading
  saved state: `IllegalStateException: Bad magic number for Bundle: 0x37`.
  Its cause is not conclusively attributed. This does not prove full legacy-task
  restoration or justify a shared frontend repair. Ordinary module updates retain
  the documented host force-stop/restart procedure; hot reload stays disabled.
- After that ordinary restart, the fixed APK creates TV task 1389 with the module's
  own `v` report Fragment. The original subscribed-podcast detail is opened, task
  state reaches STOPPED/saved (3956 bytes), and `am kill` removes PID 2331.
  Recreated PID 3450 restores the same task and original detail with artwork,
  92-program total and rows; settled before/after screenshots are inspected.
  LaunchState is COLD, TotalTime 2007ms, and the current-PID crash buffer is empty.
  MeiloX remains paused at 178897ms with 11 queue entries; native TV stays inactive,
  stopped with an empty queue. No playback or subscription write is triggered.
- The latest isolated standalone R8 also passes same-APK saved-task recreation:
  task 1390 is STOPPED/saved (3856 bytes), PID 4484 is killed, and new PID 5225
  restores that same task and the original 2515-program detail/artwork/rows.
  TotalTime is 1644ms; current-PID crash buffer is empty. Its seven-entry queue
  remains unprepared at 143811ms, speed zero and no error. Existing persisted
  session is reused; no Cookie file import or production-ID upgrade occurs.
- Builds pass: focused parasite unit tests plus assembleParasiteRelease (3m59s)
  and isolated standaloneRelease R8 (5m59s). All 19 focused mapping/storage/Work
  tests pass without failures/errors/skips, including foreign-package, wrong-class,
  same-name/different-loader and exact legacy-name rejection. The local real-SDK
  `dual_runtime_release_test.rb --built-apks` gate passes all 56 checks. Both
  validation APK signatures and 16KB zip alignment pass with existing development
  signing; this does not qualify production signing or the original-install upgrade.
- Signed validation SHA-256: parasite
  `e3309814300d5c0e455fead5266cf8ece9f6ec7a93a23f9ed4e0664011f0a7da`;
  isolated standalone R8
  `d9ca81fd4114ce057167993939486226035db91aed74276df0b7d540860ff100`.
  Installed hashes match. The unchanged production standalone unsigned artifact is
  `bdd08b9503c310ade417508af5125039008c02be55cddfff289077bdc936f50a`;
  fixed parasite unsigned is
  `275c21c3803e51433d864f87308890b04ac3f82f8f093e17ad9eff01204c4a74`.
- The isolated standalone is preserving-restored to its ordinary debug APK
  `605a98fde598e40ce40104c81b18a8871c02edd348bfe383b0fb192fd3891992`
  after testing, without autoplay. The original production standalone package path
  remains unchanged; MediaStore 820 remains TV-owned, 22705573 bytes, pending zero.
  TV is returned to original portrait Home paused. Logs, XML, screenshots, DEX
  output and validation artifacts stay under `/tmp/meilox-r8-lifecycle-*2026-10-03*`
  outside Git. Main remains `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`.
  This closes only the reproduced host Fragment conflict and named same-artifact
  recreation scenarios; cross-artifact restore and all other D2-D6 gates remain open.

#### Stable Parcelable Wire Identities (2026-10-03)

- Offline analysis of the preserved PID 1681 crash uses the matching R8 map and
  SDK retrace, locating Compose's saved-state Bundle-to-map read. Actual pre-rule
  APK DEX confirms alias `z99` changes from generic state (writeValue plus policy)
  to Long state (readLong); primitive aliases also shift. This is demonstrated
  Parcelable wire-identity incompatibility, not proof of the failed Bundle's exact
  contents or of a sole cause. ABI-021 records that distinction.
- Two parasite-only keepnames rules retain the four Compose mutable-state
  Parcelables and SnapshotStateList without disabling release minification or
  resource shrinking.
  Module ReportFragment remains its distinct `v`; its host isolation adapter is
  unchanged. No shared frontend, standalone R8 rule, dependency or manifest changes.
- The existing CI signed-pair preparation checks each of those five actual DEX
  classes, its Parcelable interface and platform CREATOR field. Seven negative
  fixtures cover each renamed class, a non-Parcelable type and a wrong CREATOR.
  The new actual-APK gate fails on the pre-rule artifact as expected and passes
  after the build. `ruby -c` and all 64 local release checks pass; no remote CI runs.
  ParasiteRelease builds in 3m44s; signature and 16KB alignment pass with the existing
  development certificate. Standalone's unsigned artifact remains unchanged at
  `bdd08b9503c310ade417508af5125039008c02be55cddfff289077bdc936f50a`.
- New parasite unsigned SHA-256:
  `e0408a11e931701bb417ebd67974e25dac6294715476ce44dee31af07c23c3df`;
  signed validation SHA-256:
  `d5c9667b2c1b3854ca39fafa9902d603cfbfeadb4e3588628db87fcb7cebce03`.
  Its installed hash matches. Preserving install followed by the documented host
  restart reaches original portrait Home in PID 7400 (COLD, 5107ms). Original
  subscribed detail is then saved in STOPPED task 1392 (4692 bytes); background
  `am kill` removes PID 7400 without an APK change. New PID 8142 restores the same
  task/detail/artwork/92-program rows (COLD, 1658ms); before/after screenshots are
  inspected and both current-PID crash buffers are empty. These launch samples are
  not a timing fix or attribution of earlier startup timeouts. MeiloX remains
  paused at 178897ms with 11 entries; native TV remains stopped/inactive and empty.
  No playback, quota-consuming test download, subscription/account mutation or
  Cookie import is initiated.
- TV is returned to original portrait Home paused. Evidence remains outside Git at
  `/tmp/meilox-r8-stable-parcelable-*2026-10-03*` and the preserved lifecycle trace
  paths above. No old alias migration or generic Bundle clearing is introduced;
  cold update restart remains required. Same-artifact recreation and future naming
  guards do not qualify full cross-artifact task restoration or the other D2-D6 gates.

#### Dynamic Transport Adapter Review (2026-10-03)

- Reviewed at `ae23efb0` against unchanged main
  `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`: full bodies of ApiService,
  WeApiService, EApiService, MeloXDirectService, both flavor MeloXRequestPolicy
  implementations, RuntimeBackendModule (parasite), HostRetrofitCompatibility,
  HostCallFactory, HostRequestBridge and TvHostRequestBackend. The RetrofitModule
  provider section and MeloXRepository's request/accountRequest/requestOwned/validate
  and raw-report envelope sections are reviewed separately, not claimed as full files.
- Logical prefixes normalize only at the host adapter; standalone dynamic qualifiers
  retain their original signing labels and finite fallback. Captured session tags
  remain transport metadata, ambiguous parameters/credential overrides are rejected,
  and cancellation or retired generations cannot retry under another account.
  The host's synthetic response preserves business codes and does not establish a
  real HTTP status. No new defect or source change is established in this scope.
- Focused Gradle regression passes in 13s: 52 parasite and 29 standalone cases,
  zero failures/errors/skips. This covers the named transport, bridge, annotation,
  provider, signing, session-call and retry suites. Production compile prerequisites
  are up to date; no APK is rebuilt, installed or exercised for this source review.
- Existing real-device evidence is retained without repeating cloud/download/startup
  tests. Full Repository/page integration, live authorization/failure/cooperation,
  original-install upgrade and remaining D2-D6 runtime gates are still open.

#### Source-Identity Recovery Repair (2026-10-03)

- A focused check of the unresolved near-end failure finds a separate, concrete
  migration defect: range-error scheduling uses full sourceKey, while the transition
  callback still uses visible mediaId. Private-source repeat transitions can cancel
  the pending recovery and reset its single-attempt budget. This is not evidence
  identifying the Oct1 no-progress failure; its missing raw trace and unproven
  original-song/expiry reproduction remain explicit.
- PlaybackSourceRecovery groups the existing source key, job and attempt state.
  Both service paths select the same full identity; a changed identity cancels the
  old job, and an unchanged source retains its budget. Invalid/conflicting metadata
  retires recovery without throwing from the callback. Session retirement clears
  even an unchanged public source. The original maximum remains one attempt.
- JVM tests cover repeat, file/account/audio/entry replacement, public/private
  collisions, null selection, retirement and finite budgets. Native fixtures invoke
  the actual MusicService transition callback with synthetic MediaItems and empty,
  unprepared/muted StableDeckPlayer decks. They do not start the registered service
  lifecycle, load credentials/media, resolve URLs or report listening statistics.
- Final-source JVM results are 39 cases per flavor with zero failures/errors/skips;
  eight cases per flavor are new recovery-state regression. Each matching debug
  device APK passes four service-callback cases (0.637s parasite, 0.643s standalone),
  including conflicting metadata. The earlier three-case device run predates that
  additional guard and is not substituted for these final four-case results.
- The isolated standalone is preserving-updated with its matching debug APK, then
  launched through the ordinary application, not the fixture Application. It COLD
  starts in 7706ms (PID 11246), displays its original Home/artwork/mini-player and
  restores seven queue items PAUSED at 143811ms, speed 0/error null. Its screenshot
  is inspected. This does not qualify R8 recovery, audible output or a timing fix.
- The final frozen source passes the paired filtered JVM/debug/test-APK/release
  Gradle command in 30m20s (267 tasks: 63 executed, 204 up-to-date), including both
  R8, resource shrinking and lintVital tasks. A previous in-progress build predates
  the conflicting-metadata guard and is explicitly terminated; its partial outputs
  are not substituted for this successful final command.
- Final unsigned release SHA256 values are parasite
  `7d8a5f39a19ffe357dbee24a07d7c7b459e44188865723430b73e4dc9f858ac7`
  and standalone
  `768d7cc2fa7180719d084e17975eb292d1026abdaf5ec3381d8cae1ec364e251`.
  The local `dual_runtime_release_test.rb --built-apks` gate exits 0 with 64 PASS
  records: actual SDK signatures/identities/versions, 16KB alignment, runtime
  declarations, stable parasite Parcelable names/platform CREATOR fields, and
  rejection of swapped, unsigned or missing-scope pairs. Its temporary fixture
  keys/APKs are removed; it does not install APKs or use production credentials.
  The parasite is development-signed, passes SDK signature and 16KB alignment
  checks, and is preserving-installed with device SHA256
  `b26fd83023549bf36b5e39e45472f23023aa82624b1cfada1dffc20a6737e727`.
- The final parasite R8 cold-starts through the ordinary TV launcher in 7011ms
  (PID 12918). The inspected screenshot shows the original portrait MeiloX Home,
  artwork, mini-player and glass navigation. Its session restores 11 queue items
  PAUSED at 178897ms, speed 0/error null; the native TV session remains inactive,
  STOPPED at 0 with an empty queue. Each restored application's PID-scoped crash
  buffer is empty. No playback action or audible-output qualification is made.
- The production standalone install path is unchanged. MediaStore row 820 remains
  22705573 bytes, is_pending=0, owned by com.netease.cloudmusic.tv. No credentials
  are read/imported, accounts switched, production data cleared or real writes
  exercised. Local logs/screenshots/APKs stay outside Git under
  `/tmp/meilox-source-recovery-*-2026-10-03.*`; no remote CI or push is requested.
- This changes backend source-recovery ownership only; it does not redesign pages,
  playback controls, DSP, AutoMix, source authorization or quality/cache policy.
  ABI/API evidence is recorded in API-015. Full D2-D6 acceptance remains open.

#### Executed Code Identity During Module Replacement (2026-10-03)

- A bounded update experiment uses the existing development-signed, stable-name
  parasite APK `d5c9667b2c1b3854ca39fafa9902d603cfbfeadb4e3588628db87fcb7cebce03`
  and source-recovery APK
  `b26fd83023549bf36b5e39e45472f23023aa82624b1cfada1dffc20a6737e727`.
  Both have package com.neoruaa.meilox.parasite/versionCode 11 and the same existing
  development certificate. No APK is repackaged or re-signed for this experiment.
- Original subscribed-podcast detail, artwork and 92-program rows are inspected in
  PID 13665. Task 1395 reaches STOPPED with 4692-byte saved state. `am kill` removes
  that background process; the task retains its state with app=null before and
  after preserving module installation. No post-update host force-stop occurs
  before attempting that task's restoration.
- Restoration fails: PID 14306 logs app_activity_created restored=true, then crashes
  in MainDispatcherLoader initialization with the missing-Main-dispatcher error;
  Compose subsequently encounters its failed class initializer. The later PID 14621
  is not UI recovery. The task disappears and the inspected screen is the launcher.
  `am start -W` reports UNKNOWN/WaitTime 2060ms, not a successful COLD timing sample.
- Installed APK SHA256 matches the newer source-recovery artifact, but the failure
  frames carry R8 map ID
  `f8b575b416e7f91c74df8a187c0b025e271fae6ac9ef9e7948d1bc988d2d8524`.
  SDK DEX inspection associates that ID with the older APK; the installed APK's
  dispatcher class instead carries
  `3637ef95a38e6b8d60242b938831b0eb6d770c450fca9d39df2100161e7fb19d`.
  Therefore this is not qualified execution of the intended newer DEX. APK presence
  alone does not prove that LSPosed has refreshed its executed module code.
- Both APKs contain the MainDispatcherFactory service entry, and the newer DEX
  contains the implementing Android dispatcher factory. The recorded error does
  not justify adding a dependency, changing global dispatcher properties or
  overriding coroutine/R8 rules. Its precise resource-loading cause remains open.
- The existing module-loaded log now reports the first stack frame's code_source.
  For the inspected R8 format this exposes its executing map ID; in debug it is
  only a source filename, not a revision identifier. This diagnostic does not
  authenticate the APK, identify all resources or alter eligibility/hooks/lifecycle.
  Future R8 runtime qualification must compare it with the tested APK's actual DEX
  metadata, alongside the existing installed-hash/signature/resource checks.
- Ordinary force-stop/restart of TV afterward displays the original portrait Home
  in PID 15083 (COLD, 5223ms), with an empty PID-scoped crash buffer. MeiloX retains
  11 queue entries PAUSED at 178897ms; native TV is inactive/STOPPED at zero/empty.
  Standalone remains PAUSED at 143811ms with seven entries. This is recovery to the
  usable test state, not a successful cross-APK restore or old failure attribution.
- Evidence stays outside Git under `/tmp/meilox-cross-r8-*-2026-10-03.*`. No playback,
  account/session mutation, quota download, subscription write, supplied Cookie
  access, permission/framework/global-rotation change or AVD restart is performed.
  Legacy pre-rule Bundle compatibility and the full D2-D6 exit gates remain open.
- The marker's final source passes 15 parasite mapping/storage/Work JVM cases with
  zero failures/errors/skips and ParasiteRelease in 3m48s (83 tasks: 21 executed,
  62 up-to-date), including R8 and lintVital. All 64 local real-SDK paired release
  checks pass; temporary fixture keys/APKs are removed, and no remote CI is invoked.
- Final unsigned parasite SHA256 is
  `9397f55a71bb55a901c608fd05574b42102ee1a459ae33ead27098ee7c22a709`.
  Its development-signed artifact/device SHA256 is
  `5ae79a5ea23305818a22ca6e470b04dc50bd01306c46090f4638b67d9489db85`,
  with successful signature/16KB alignment checks. Standalone's unsigned SHA256
  remains `768d7cc2fa7180719d084e17975eb292d1026abdaf5ec3381d8cae1ec364e251`.
- After the ordinary preserving update/host restart, PID 16061 reports code_source
  `r8-map-id-020077de3aa68755d9e29c79c66cfccf98fda510b24cae9b3267a885beb69979`.
  An exact comparison passes against both the final R8 mapping header and the SDK
  DEX source metadata of MeiloXModule. COLD startup is 5241ms; its inspected Home
  screenshot and empty PID-scoped crash buffer qualify this matching-code launch,
  not a timing fix or cross-APK task restore. The same paused queues and inactive
  native TV player remain; the original standalone install path and TV-owned
  MediaStore 820 (22705573 bytes/pending=0) are unchanged. Evidence stays under
  `/tmp/meilox-runtime-code-marker-*-2026-10-03.*`; no logs/APKs are committed.
  Main is rechecked at `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`.

#### Full Paired JVM Gate and AVD Upgrade Candidate (2026-10-03)

- Frozen production source is `8ff63f75`; main is still
  `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`. The unfiltered planned command
  `:app:testStandaloneDebugUnitTest :app:testParasiteDebugUnitTest` succeeds in
  1m06s (56 tasks: two executed, 54 up-to-date). XML totals are 1063 standalone
  cases/109 suites and 1070 parasite cases/111 suites, all with zero failures,
  errors or skips. These are the full current JVM suites, not a sum of earlier
  filtered runs. They do not substitute for device, upgrade or real-server gates.
- Read-only SDK inspection of the original AVD standalone APK confirms package
  com.neoruaa.meilox/versionCode 11 and development certificate SHA256
  `2a02b8d6f6f25067a95b685c9f9cf79d97cba2d4999d1a550a720931c8310243`.
  Its pulled APK hash matches the device:
  `7d0233bca7734e8e0141eee2e99c9db5820f2be599420ff91e7fb514b99067b9`.
  No app-private data or supplied Cookie is read. The original install path and
  device APK hash are rechecked unchanged after preparing the candidate.
- The current standalone unsigned R8 APK remains
  `768d7cc2fa7180719d084e17975eb292d1026abdaf5ec3381d8cae1ec364e251`.
  A local candidate is signed with that same AVD development certificate, keeps
  the original package/versionCode, is non-debuggable, and passes signature and
  16KB alignment checks. Candidate SHA256 is
  `c80c81722532a270390167e2a8d6ade6d573b1993dd6b869259d0070fad5c5c9`;
  path: `/tmp/meilox-production-id-upgrade-candidate-2026-10-03.apk`.
- This establishes a matching-signature test candidate, not a successful upgrade.
  The repository's older release APK instead has certificate SHA256
  `03bc4bbc1e9b3b2bfad4e8712ccbc964546a2648ec6992d697875270d2b1a737`.
  Development signing cannot qualify that production-release identity; no release
  secret is accessed. Original-install backup/upgrade permission is requested with
  the database-migration risk made explicit. No backup, installation, downgrade,
  uninstallation, data clearing or account authorization is performed while waiting.
- The existing workflow's exact prepare_apks shell block also succeeds locally on
  this real standalone candidate and the existing signed parasite R8 artifact.
  It checks signatures, identities, versions, 16KB alignment, runtime declarations
  and parasite Parcelable wire identities, then exports the actual two files to
  `/tmp/meilox-actual-validation-pair-2026-10-03-20261003-5896-od5i00/release-apks/`:
  MeiloX-standalone.apk has the candidate hash above; MeiloX-parasite.apk has
  `5ae79a5ea23305818a22ca6e470b04dc50bd01306c46090f4638b67d9489db85`.
  Output hashes match their inputs. These are development-signed local validation
  artifacts; no upload, tag, release, push, remote CI or user-app update is invoked.
- Logs and the read-only pulled original APK remain outside Git under
  `/tmp/meilox-full-paired-unit-8ff63f75-2026-10-03.log`,
  `/tmp/meilox-actual-validation-pair-preparation-2026-10-03.log` and
  `/tmp/meilox-original-readonly-signature-2026-10-03.apk`.
  No source/UI/CI policy is changed. Framework-free startup, original data-preserving
  upgrade, production signing, real authorization/cooperation and remaining D2-D6
  runtime/source acceptance remain open.

#### Latest Standalone R8 Account Read Qualification (2026-10-03)

- Frozen production source remains `8ff63f75`; no UI, backend or build-policy edit
  follows the full paired JVM gate above. The existing temporary init script builds
  StandaloneRelease with the isolated com.neoruaa.meilox.standalone.debug identity
  and a separate `/tmp` output directory. R8/resource shrinking remain enabled,
  the merged application is non-debuggable, and no module/probe/instrumentation
  declaration is added. The build passes in 4m55s (55 tasks: 36 executed, 19 up-to-date).
- The development-signed validation APK passes SDK signature/16KB alignment checks.
  Its local/device SHA256 is
  `b42c0b0e171719256ab9a7a2dd892ecc089f0cc5e578b9e1210c37dcf78775b9`;
  R8 map ID is `4bd85e771b698eed70dced6a49a113c047c4a8e50495dad9a45b3bc0670ad512`.
  Only the isolated test package is updated preserving data. A normal task launch
  reports COLD/1686ms in new PID 17933; installed APK identity/hash are checked.
- Actual shared UI navigation displays the existing account profile/detail and
  playlist list, account refresh, weekly and all-time listening ranks, all-time
  refresh, and account-playlist detail with artwork and visible song rows. Screenshots
  and structured UI trees are inspected. No rank/song/play control, subscription,
  social action, download or upload is invoked. This closes the latest standalone
  R8 account-read gap left by the earlier TV R8/standalone debug checkpoint, not
  complete paging, fresh authorization, account switching, failure coverage or
  subsequent server reporting/statistics. The PID-scoped crash buffer is empty.
- During this read-only R8 run, the restored standalone queue remains seven entries
  at 143811ms, speed zero/error null, with unprepared NONE state; it is not claimed
  as prepared playback acceptance. Preserving reinstall of the original test debug
  APK is checked at SHA256
  `175a7f9ee345be9ff62b3b1e5cd94e2159f13f2dc98d87cd12f1d344362063ac`.
  PID 18551 displays Home, has an empty crash buffer and reports the same seven-entry
  queue PAUSED at 143811ms. TV PID 16061 returns to inspected portrait Home, retaining
  11 entries PAUSED at 178897ms; native TV remains inactive/STOPPED with an empty queue.
- One final UI dump encounters transient ADB offline state. Its stale tree is not
  accepted; a fresh successful dump identifies the TV package, and the unchanged
  TV PID plus screenshot/media state are rechecked without restarting the AVD.
  The original standalone APK remains at the recorded install path/hash `7d0233bc...`;
  TV-owned MediaStore 820 remains 22705573 bytes/pending=0. Both production unsigned
  APK hashes above are unchanged. No original-install upgrade, Cookie access,
  permission/framework/global-orientation change or official API-contract change
  is performed. The pending D2-D6 exit gates remain open.
- Evidence stays outside Git under `/tmp/meilox-latest-r8-account-*-2026-10-03.*`,
  `/tmp/meilox-latest-r8-rank-*-2026-10-03.*` and
  `/tmp/meilox-latest-standalone-r8-read-build-2026-10-03.log`.
  No credentials, screenshots, raw logs or APKs are committed.

#### Catalog Page Consumer Review (2026-10-03)

Review checkout is `e4d1b124`, against unchanged main
`1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`. Production source still matches
`8ff63f75`; the intervening diff contains only this plan. Complete bodies of the
following seven files, not only their changed hunks, are read. Prefix:
`app/src/main/java/com/ljyh/mei/ui/screen/`.

```text
album/AlbumDetailScreen.kt
artist/ArtistScreen.kt
artist/ArtistSongsScreen.kt
playlist/PlaylistScreen.kt
playlist/CommonSongListScreen.kt
playlist/component/StandaloneTrackActionOverlay.kt
playlist/component/PlaylistActionOverlay.kt
```

- The four page diffs against main are also inspected. Shared field mapping,
  collection state/mutation consumption, queue session handoff, playlist detail
  identity, chunked song-detail loading, selection/download-dialog lifetimes,
  menu/picker ownership and retained original controls are traced through the
  common list/overlay. The artist helper/unavailable states already exist in main;
  nullable payload adaptation does not introduce a new page architecture here.
- Supporting reads are limited to the owner/publication helpers in AlbumDetail,
  Artist, ArtistSongs and Playlist ViewModels, PlaylistViewModel's action/detail
  invalidation, PlayerViewModel's download method and the closed
  PlaylistOverlayOwnerDeviceTest. They are not a new full-body audit of every
  ViewModel/repository, a native test run or proof of every possible UI interleaving.
  No new defect or source/UI/API-contract change is established in this scope.
- The unfiltered paired JVM command passes in 6s with all 56 tasks up to date.
  Current XML still contains 1063 standalone/1070 parasite cases, with zero
  failures/errors/skips; the six related factory/album/artist/playlist session,
  action and paging suites account for 98 cases per flavor. These reuse the
  previously executed unchanged-source results, not 196 new executions or full
  Compose integration coverage. The command log stays outside Git at
  `/tmp/meilox-catalog-consumer-source-review-jvm-2026-10-03.log`.
- This closes the named page-body source-review gap, not the full catalog audit
  or D2-D6 exit gates. No APK rebuild/install, device action, account mutation,
  quota grant, credential access, main merge or remote operation is performed.
  Other page integrations and real authorization/failure/cooperation acceptance
  remain separate; resume unreviewed scopes rather than re-reading these seven
  unchanged bodies as new qualification.

## HyperOS 4 Environment Transfer (2026-10-03)

- Source checkout is `b0dbbab`; production source remains `8ff63f75`. No application
  code, shared UI, page architecture, backend contract or APK version is changed.
  The final transferred applications are official TV `com.netease.cloudmusic.tv`,
  module `com.neoruaa.meilox.parasite`, original `com.neoruaa.meilox` and isolated
  `com.neoruaa.meilox.standalone.debug`. Installed APK SHA-256 values match the
  source backups byte for byte: TV `b0bba591...`, parasite `5ae79a5e...`, original
  `7d0233bc...`, isolated debug `175a7f9e...` (full values in private audit records).
- Private backups include credential-encrypted/device-protected application
  directories and existing app-owned external data/media. They stay under the permission-restricted
  local directory `~/.local/share/meilox-avd-migration/2026-10-03`, outside Git.
  Source applications were stopped for consistent snapshots, not tested again.
  Target application UIDs, cache groups and private-file SELinux categories are
  reassigned for HyperOS; no Android Keystore or system account database is copied.
- `com.netease.cloudmusic` was identified as a historical MeiloX 1.54.2 test APK,
  not an official mobile client. The user excluded it during transfer; its new
  target copy is uninstalled. Its original Pixel installation and private backup
  remain intact. It is not part of final migration or runtime acceptance.
- The migration enables MeiloX Parasite through the target LSPosed manager, with
  static TV-only scope. The shared LSPosed database is not imported; pre-existing KeiMi
  enabled state and all 19 scope entries are unchanged. The TV-only orientation
  compatibility changes `265464455` and `265452344` are restored; no global rotation
  setting or system-framework scope is added for MeiloX.
- Three existing downloaded audio files have matching SHA-256, size, ownership
  and non-pending status. Target MediaStore IDs are `40`, `41`, `42` rather than
  source `342`, `441`, `820`. Existing song URI references are remapped, with the
  published TV download receipt updated to the target provider version/generation.
  SQLite integrity passes; song counts stay 150/original, 23/isolated and 15/TV.
  TV's existing download row displays completed. No new download/grant is requested.
- Actual TV cold launch reaches portrait MeiloX Home (3288 ms); the executed
  API-102 module marker matches `r8-map-id-020077de3aa68755d9e29c79c66cfccf98fda510b24cae9b3267a885beb69979`.
  Original and isolated standalone cold launches also show their restored account
  identities and shared Home. PID-scoped crash buffers are empty for these launches.
  TV retains its 11-item queue paused at 178897 ms; isolated standalone retains
  seven items paused at 143811 ms. The native TV player remains stopped/inactive.
- After verification, `adb -s emulator-5554 emu kill` closes Pixel successfully.
  A subsequent device inventory contains only HyperOS `emulator-5574`. Pixel's
  AVD and original installations/data are retained, not deleted or upgraded.
- These checks qualify environment/data transfer only. They do not qualify audible
  playback, all interactions, account expiry, later statistics, a production-signed
  original-install upgrade, cross-R8 task restoration or the remaining D2-D6 gates.
  APKs are copied unchanged, so no rebuild or new unit-test execution is claimed.

## Preserving Original-ID Upgrade on HyperOS 4 (2026-10-03)

- The user authorized a private APK/CE/DE/external-data backup followed by
  `adb install -r`; no uninstall or data clear is performed. The restricted backup
  is under `~/.local/share/meilox-avd-migration/2026-10-03/original-preserving-upgrade`.
- Both APKs use the same development certificate (`2a02b8d6...`). The installed
  production-ID R8 candidate is `com.neoruaa.meilox`, SHA-256
  `c80c81722532a270390167e2a8d6ade6d573b1993dd6b869259d0070fad5c5c9`.
  Its installed file hash is verified independently after installation.
- The loaded database migrates from version 17 to 21 with `integrity_check=ok`.
  All 150 song rows and every old row in albums, artists, playlist membership,
  likes and download tasks remain unchanged in their original columns. All 22
  playlist IDs remain; refreshed timestamps and two cover/count values differ,
  so playlist row equality is not claimed. Account identity, Home, the paused
  queue track and both completed download rows are observed in the actual UI.
- PID-scoped crash capture contains no fatal exception. This qualifies the
  development-signed original-install upgrade, not compatibility with the separate
  production release certificate, audible playback or full lifecycle acceptance.
  Application source is unchanged; existing JVM results are not new executions.
- The user also authorized parasite-only microphone authorization/capture and
  PiP helper components. They must have no music launcher, host credentials or
  backend implementation; business work remains in the verified TV process.

## Module Microphone Helper and Paired Recognition (2026-10-03)

- Shared recognition layout, navigation, durations, DSP and result presentation stay
  single-source. Standalone keeps its own permission/AudioRecord path; parasite uses
  a module-owned authorization Activity and microphone foreground Service. Module
  startup does not initialize the host graph, session or business request adapters.
- Helper authorization checks the framework-reported activity caller and the pinned
  TV UID/version/current signer. Every recording/close transaction rechecks the UID.
  One-use grants, bounded reliable pipes, per-job leases, Binder death, cancellation
  and idle expiry handle resource ownership without transferring any credentials.
- Live recording exposed a host-WebView difference: `android_asset` resolves against
  the official APK. Three allowlisted fingerprint assets are now served from the
  selected runtime AssetManager at an intercepted local HTTPS origin; no network
  fetch or official APK modification is used. See API-037 in the differences ledger.
- Actual parasite R8 execution matches map ID
  `b04802b867c7e1b0bf41c7cf8919f0512503926d37978c5112cbd3846c14ffdb`.
  The signed APK SHA-256 is
  `51d808f49abab5548213bd2f211dc63892797b1438f3d1ed07815cea495cd7c4`.
  A 3-second window returns NoMatch; continuous recognition completes a 9-second
  window with a server candidate, then cancellation stops its second window.
  AudioFlinger identifies module UID 10311, microphone foreground type is 0x80,
  and the service/notification disappear after Stop. Candidate accuracy is not
  established against a known reference recording. Raw audio is not saved.
- The latest original-ID standalone R8 APK is installed preserving data, SHA-256
  `9e1f57cf343f520d23f8638599acab3532b95759e8871804b1780709d38acd8d`.
  Its account/paused queue remain; its own UID 10309 records 3 seconds and the original
  public match transport returns NoMatch. Both inspected pages retain the original
  layout; current host/standalone PID crash buffers contain no fatal exception.
- Full paired JVM execution passes 1069 standalone/1080 parasite cases. Both R8
  APKs and vital lint build successfully. Signed-pair fixtures/real-SDK tests pass,
  including a strict helper allowlist, permission/type isolation and numeric SDK
  manifest enum handling; no remote workflow is run. The native Java instrumentation
  passes installed-R8 manifest ownership/no-launcher/untrusted-Binder checks.
  The earlier debug AndroidX runner could not link R8-renamed Kotlin classes; that
  failed runner is not counted as application or test acceptance.
- TV-only LSPosed scope, official APK and rotation configuration remain unchanged.
  HyperOS app-to-app confirmation is respected, not bypassed. PiP implementation,
  broader permission/process-death acceptance and known-source recognition accuracy
  remain separate; this checkpoint does not close all D2-D6 gates.

## Module PiP Helper and Shared Lyrics Rendering (2026-10-03)

- The pinned TV manifest has no PiP-capable Activity. The authorized module helper
  owns only a native PiP window and private RemoteAction receiver; it has no launcher,
  playback service, account identity, Cookie or backend graph. TV still owns the
  player, lyric requests, settings, artwork loading and official session. API-038
  records this platform component boundary.
- Standalone retains its original MainActivity PiP path. Both windows call the same
  extracted content/backdrop components and text-selection function, without changing
  their layouts, colors, padding, sizes, preference semantics or player-menu entry.
  Future shared-renderer edits therefore apply to both APKs; no duplicate lyric
  screen tree or alternate music application is introduced.
- Across processes, only platform Bundles, bounded Bitmaps, a lifetime Binder and
  three integer commands are passed. The helper verifies the actual TV result caller
  and pinned signer/version; the host endpoint verifies the module UID. Session
  invalidation, Activity destruction, endpoint replacement, peer death and activation
  timeout release the source. Timed-out queued host operations are removed. Private
  immutable PendingIntents have per-window identities and stop acting after close.
- Paired suites pass 1074 standalone and 1085 parasite cases, with zero failures,
  errors or skips. Both production-ID R8 artifacts and vital lint pass. The local
  real-SDK signed-pair verifier reports 79 PASS lines, including eight new negative
  PiP-helper declarations; temporary fixture signing material is removed. No remote
  CI, release, merge or push is performed.
- Development-signed standalone SHA-256 is
  `3e698fac38622a8de856181e3ff75cb9bfd04c6f6d25ad9732bb9422108d0d26`;
  parasite is `00e45ab0ee38962c3e4eaed266858df4e22062b7b1c474aad168fb9f8837b2b5`.
  Both are preserving-installed and their installed hashes match. TV PIDs 21033
  and 25742 execute R8 map ID
  `3272c2faba8232e55095f21607584efae41547d51ec3132b0daed6315fde7095`,
  separately confirming intended code execution rather than only installed resources.
- Native Java/platform-only instrumentation passes helper capability/ownership,
  no-launcher and microphone caller checks against the installed minified APK.
  An optional normal-menu Activity monitor discovers the actual R8 PiP factory by
  its Android signature and sends its real immutable PendingIntents. External media
  snapshots verify PLAYING, item 2-to-3-to-2 changes, PAUSED, and no state change on
  closed-window replay. Native TV stays STOPPED/empty; original standalone stays
  paused. This proves the action channel, not a system-menu button click.
- In production execution, 24 consecutive frames show Nod-Krai advancing from
  approximately 3s to 38s with different primary/translated/next lines and a rendered
  cover. Killing host PID 21033 removes the helper's pinned Activity; restarting the
  official launcher retains the session and paused queue in PID 25742. Direct shell
  launch without the required TV result caller does not leave a helper window.
  The temporary TV dark-theme preference is restored to Follow System.
- Limits remain explicit: native PiP controls cannot be exposed by the tested taps
  in either standalone or helper windows on this HyperOS AVD. Expand/close/menu
  interaction therefore remains unqualified. Standalone also reproduces the original
  light-theme white-text/white-card readability problem; its shared styling is not
  repaired in this backend migration. The HyperOS app-launch confirmation is
  respected; one prompted launch leaves playback paused, with normal playback
  resumed for the consecutive-frame capture. Cause/all prompt paths are unqualified.
- An initial instrumented action probe fails while connecting/disconnecting native
  UiAutomation in test PID 22741. The final probe avoids UiAutomation/permission
  adoption and uses external state snapshots instead. This test-harness failure is
  kept separate from ordinary R8 helper execution and does not authorize production
  framework hooks or new permissions. Logs, APKs and screenshots remain outside Git
  under `/tmp/meilox-pip-*`; no audio or credentials are committed.

## HyperOS 4 Current-R8 Boundary Checks (2026-10-03)

- At `ec1dd5a3`, the two installed production-ID, development-signed R8 hashes match
  the PiP checkpoint above. TV PID 25742's actual API 102 `code_source` matches
  `r8-map-id-3272c2faba8232e55095f21607584efae41547d51ec3132b0daed6315fde7095`.
  This is separate execution evidence, not an assumption from the installed file.
- Existing playback is observed without play/pause/seek/next commands, an install,
  Activity launch, process kill, permission or volume changes. The native session
  monitor records The des Alizes advancing from 133591ms to 217949ms, followed by
  the next item at 14ms. No transition-reason or complete decoded-duration evidence
  is obtained; the item change alone is not counted as automatic/full-track completion.
- A subsequent seven-sample, 121-second capture holds the hosted player PLAYING,
  speed 1, error null, with six queue items. The successor advances from 9559ms to
  129816ms. Original standalone remains PAUSED at 72252ms with 1512 entries; the
  separate native TV session remains inactive, STOPPED at zero, with an empty queue.
- The primary AudioFlinger speaker output is unmuted, with an active 44100Hz track
  owned by TV UID 10312. Its frames increase from 46893888 to 52638528, a delta of
  5744640; signal-power samples are nonzero. The independently read system music
  volume is 7/15. These are decoder/output-state observations, not audible acceptance
  of the emulator or any physical device. The current-PID crash buffer is empty;
  this does not assert an empty engine/system log or erase the earlier near-end failure.
- PID-scoped official SDK markers process the prior item end (`play`/`_pld`,
  222 seconds) and successor start (`startplay`/`_plv`) under generation zero.
  SDK processing/enqueue, request acknowledgement and later server statistics remain
  distinct; this capture does not prove a network acknowledgement or final statistics.
- Inspected screenshots retain the original portrait General Settings page,
  Follow System theme, successor artwork and playing mini-player. No frontend repair
  is made. Captures and the read-only script remain outside Git under
  `/tmp/meilox-hyperos-passive-playback-2026-10-03-1790980130471` and
  `/tmp/meilox-hyperos-passive-playback-2026-10-03.mjs`. No original account,
  Cookie, download quota or social action is changed; all existing exit gates remain.
- The Java-only installed-R8 instrumentation now optionally verifies the module
  process's actual AppGraph instance before and after its untrusted microphone
  binding test. The matching map resolves AppGraph to `xx` and its static instance
  field to `b`; both reads are null. The real service rejects the module UID before
  recording. Optional PiP-action instrumentation checks this field while pinned and
  after destruction too, but that action mode is not rerun in this checkpoint.
- The test APK builds in 10 seconds and is installed independently, without
  `connectedAndroidTest`, module replacement, scope changes or host restart.
  `am instrument -w -r -e graph_class xx -e graph_field b` with runner
  `com.neoruaa.meilox.parasite.test/com.ljyh.mei.parasite.MicrophoneR8Instrumentation`
  returns PASS and instrumentation code -1. Replacing only the field argument with
  `missing_graph_probe_field` returns the expected NoSuchFieldException/FAIL and
  code zero, proving an invalid map argument cannot silently qualify the graph.
  These aliases belong only to the pinned mapping, not a stable future command.
- After both runs, TV PID 25742 is still PLAYING/error null at 305826ms on item two;
  native TV is still STOPPED/empty and original standalone remains PAUSED at 72252ms.
  This closes the named module-bootstrap/binding scenario, not every real helper
  permission/PiP lifecycle. Production sources and both installed R8 APKs are
  unchanged; prior paired JVM/release evidence is retained, not rerun or broadened.

## Production-Certificate Paired Artifacts (2026-10-03)

- The user supplied a local keystore and authorized reading the signing parameters
  from its project workflow. Passwords stay in child-process environment variables;
  no password, private key or signing configuration is copied into this repository.
  The original keystore's before/after SHA-256 is unchanged.
- Its public certificate SHA-256 is
  `03bc4bbc1e9b3b2bfad4e8712ccbc964546a2648ec6992d697875270d2b1a737`,
  matching the previously inspected MeiloX production release, not the AVD's
  development certificate. This closes the certificate-identity uncertainty only.
- At `265de285`, the paired unit-test/release Gradle command succeeds in 9 seconds
  (163 tasks: 10 executed, 153 up-to-date), including both vital lint gates. Unit
  tasks are up-to-date: the retained XML reports contain 1074 standalone and 1085
  parasite cases with zero failures/errors/skips; no fresh case execution is claimed.
- Both final production-ID R8 APKs keep version 1.54.6/code 11 and pass SDK v3
  signature verification. Standalone SHA-256 is
  `afe5af2b01d83a4b622c301ccadc6cfd6ee401dcf7709fc9188f991382a926bb`;
  parasite SHA-256 is
  `2b0a3c9a7e19ea0d3254461b655a94bccd5c0a87c3ba2504586894aa6fe0804b`.
  Signing preserves all original ZIP entry names/order and the concatenated
  uncompressed payload (266 standalone/269 parasite entries); only signature
  entries are added. No UI/backend source is changed.
- The existing workflow's exact `prepare_apks` block passes on this production
  pair: identity/version, usable standalone components, helper-only parasite
  components, API 102 TV-only declarations, saved-state Parcelable identities,
  signatures and 16KB alignment. Exported hashes equal their signed inputs.
  `ruby .github/tests/dual_runtime_release_test.rb --built-apks` also passes its
  metadata, signing, component-negative and actual-SDK package scenarios.
- The pair and credential-free verification manifest remain outside Git under
  `/tmp/meilox-production-pair-2026-10-03-20261003-55614-7vaoob`.
  No APK is installed, no account/data/LSPosed scope is changed, and no remote CI,
  push, merge, tag or release is invoked. The current AVD uses a different
  certificate and must not be overwritten with this pair. Production-signed
  preserving upgrade and runtime acceptance remain separate, still-open gates.

## Paired Server Records and Helper Boundary Review (2026-10-03)

- Current installed production-ID, development-signed R8 hashes still match
  `3e698fac38622a8de856181e3ff75cb9bfd04c6f6d25ad9732bb9422108d0d26`
  (standalone) and
  `00e45ab0ee38962c3e4eaed266858df4e22062b7b1c474aad168fb9f8837b2b5`
  (parasite). Existing processes are brought forward, not restarted or replaced.
- Both original account pages successfully load weekly and all-time listening ranks;
  the weekly refresh control also returns populated records. The two accounts have
  distinct totals/row sets. Standalone's leading weekly/all-time counts are 22/562;
  TV's are 22/22. Screenshots verify period selection and populated original rows.
- In both TV periods, The des Alizes and the previously observed successor each
  show two plays. The Repository reads `/api/v1/play/record` through the selected
  runtime, with type 1/weekData or type 0/allData, not local Room history. These
  positive server records are separate evidence from the earlier SDK worker markers;
  there is no before-play counter baseline proving an individual report's settlement,
  nor an independently observed raw HTTP receipt or full-track completion here.
- No playback command, account switch, social write, upload/download authorization,
  permission change or APK installation is sent. Both shared players remain paused
  at their observed positions (TV 156027ms/queue six; standalone 81766ms/queue 1512).
  The native TV player remains inactive and STOPPED. Capture PNGs stay outside Git
  under `/tmp/meilox-records-{week,alltime}-2026-10-03.png` and
  `/tmp/meilox-standalone-records-{week,alltime}-2026-10-03.png`.
- The new helper source scope listed in the review table is inspected without a
  production edit. It retains caller/signature/version checks, one-use microphone
  grants and finite capture, platform-only IPC, session-owned PiP data/commands,
  cancellation/death cleanup and the extracted original rendering. No new defect
  is established. No official source is tracked under `ncm_workspace`; shared assets,
  native DSP and non-string resources still match main. This does not replace the
  remaining real-account, capability or full merge/runtime gates.
- `git diff --check` passes. Production code/APKs are unchanged; the preceding
  paired JVM/build/release checks are retained, not represented as new executions.

## Parasite PiP OEM Confirmation Deadline (2026-10-03)

- This is a bounded helper-launch repair, not a shared UI or player redesign.
  API-038 records why the standalone in-Activity path has no equivalent launch
  confirmation. The pre-fix HyperOS confirmation/helper timestamps differ by
  24.471 seconds; the helper finishes after 108ms, with no native pinned task.
  That is consistent with the host endpoint's 20-second activation timeout.
- Only the parasite activation deadline changes to 120 seconds. The new two-case
  regression checks the observed delay and a finite ceiling; its delay assertion
  fails before the fix. Existing trust, account ownership, replacement/death and
  endpoint cleanup paths are preserved. No new frontend architecture is introduced.
- The paired unit/release command succeeds in 2m52s (163 tasks: 26 executed,
  137 up-to-date), including both vital lint tasks. The parasite suite freshly
  executes 1087 cases in 115 suites with zero failures/errors/skips; standalone's
  retained, up-to-date reports contain 1074 cases in 111 suites, also all passing.
  `ruby .github/tests/dual_runtime_release_test.rb --built-apks` passes all 79
  local gates, including actual-SDK and malformed-package negative scenarios.
- A development-signed parasite R8 APK is installed with `adb install -r`, without
  uninstalling or clearing either client. Its SHA-256 is
  `0e8fbdeb6bf1acfb2a9febffd75aaf6020e289e05e79eef4147cbe082cc6086b`.
  TV PID 12431's executed API 102 code marker matches the new R8 map ID
  `17d8ce30c001536a75b6c183f51db95d039d11dcdf8aec0e7766c09076dc741d`.
  The original standalone installation is not replaced.
- The ordinary player menu opens HyperOS confirmation. Only "Allow this time" is
  selected after a deliberate delay; 46.768 seconds elapse from confirmation
  creation to helper onCreate. The helper enters real pinned mode and renders the
  shared cover/lyrics window rather than finishing immediately. Screenshots are
  retained outside Git under `/tmp/meilox-pip-activation-after-2026-10-03.png` and
  `/tmp/meilox-pip-native-menu-final-2026-10-03.png`.
- Native menu controls remain unavailable to the tested tap; one dismissal swipe
  also leaves the pinned task present. Testing stops there. Force-stopping only
  the helper package cleans up the window; no native menu/close success is claimed.
  TV remains alive and PAUSED/error null at 156037ms, original standalone remains
  PAUSED/error null at 81766ms, and native TV is STOPPED at zero. No host crash entry
  or pinned helper remains. The existing light-theme readability issue is unchanged.
- Both updated R8 APKs are signed with the previously verified production key;
  SDK verification, unchanged unsigned ZIP payload and the exact workflow
  preparation gates pass. The current pair supersedes the earlier build above:
  standalone SHA-256
  `44e118213123cfb7954475a6b422feb0b50d5a5b42d8f1fe07250a81d145152e`,
  parasite SHA-256
  `ff1942d8e182a3eb90af56210057fc0164bfbde66f10fb78c40876cb675b5d00`.
  Artifacts and credential-free `verification.json` stay outside Git under
  `/tmp/meilox-production-pair-2026-10-03-20261003-60132-ag86cq`.
  No production-signed APK is installed over the development-signed AVD apps.
- Passed checkpoints are not reopened. Framework-free/production-upgrade and
  cooperating-account acceptance await the already requested user decisions;
  the other explicitly unqualified matrix items above are not silently marked
  complete. No additional test loop, remote CI, push, merge or release is started.

## Unscoped Standalone and Authorized Two-Account Acceptance (2026-10-03)

- The user clarified the independent-runtime requirement: standalone must not need
  or execute an Xposed module, rather than requiring an entirely framework-free
  device. The earlier clone/framework-disable proposal is not required for this gate.
  Neither LSPosed nor any module is disabled, and no temporary AVD is created.
- A read-only LSPosed database snapshot contains zero scope records for
  `com.neoruaa.meilox`, including zero enabled modules targeting it. Parasite
  remains enabled only for `com.netease.cloudmusic.tv`; other module settings are
  not changed. The existing standalone process and its subsequent cold process
  both have zero LSPosed/libxposed or parasite-APK mapping rows.
- TV and standalone are force-stopped once after room cleanup. Standalone alone
  cold-starts in 4938ms (PID 22773), displays its populated original Home and
  restores the paused 1512-item queue at 81766ms. TV has no process during this
  check, and the standalone PID-scoped crash buffer is empty. The installed APK
  SHA-256 remains
  `3e698fac38622a8de856181e3ff75cb9bfd04c6f6d25ad9732bb9422108d0d26`.
  Actual SDK inspection finds zero module metadata entries, libxposed defined
  classes or host/parasite implementation rows. This qualifies independence on
  the current unscoped process, not physical-device or every lifecycle acceptance.
- With explicit authorization, the original native private-message pages send one
  test text from TV to standalone and one in the reverse direction. Both original
  pages render both texts. The user-supplied Cookie is used only for a read-only
  diagnostic that verifies the standalone account before reading this conversation;
  the server has exactly one entry for each marker with the expected sender and
  recipient. It is not used to send a message, establish TV credentials or replace
  the official pipeline. No other recipient, follow or timeline write is involved.
- TV creates exactly one Together room and displays its creator/invitation. Joining
  that exact invitation in standalone fails with `AVAILABLE`. Raw read-only room
  check evidence instead establishes an explicit rejection: code 200,
  `status=AVAILABLE`, `joinable=false`, `type=HIGH_V_REJECTED`, with an upgrade
  requirement for the other participant. API-029 records the distinction; the
  existing main parser checks the false Boolean correctly. No production fix is
  justified by treating this as a missing field or overriding the rejection.
- TV ends that room through the original control. Its page returns to Create Room
  without a local-end error; server room check then returns `EXPIRED`/false. No
  second room is created. Both queues retain their original positions/counts.
  A private 0700/0600 standalone preference/checkpoint backup is kept outside Git
  before the attempted join; it is not restored over live settings or credentials.
- TV is relaunched once after the standalone check to restore the working test
  environment. No APK is replaced, account switched, permission/scope changed or
  production code edited. Existing unit/build/release results remain retained
  evidence, not newly executed tests. `git diff --check` covers this documentation
  increment; screenshots, scope snapshots, APK inspection and private UI dumps
  stay outside Git. No push, merge, remote CI, upload or quota-consuming download
  runs. API-028/029 now distinguish passed text delivery from rejected room joining.
- Together's two-member/playback acceptance is blocked on genuine host-version
  compatibility, not on the resolved test authorization. Per the host-selection
  requirement, do not claim completion or drop that feature; confirm the next
  host/compatibility investigation with the user. Production-signed upgrade and
  the other explicitly unqualified exit gates remain separate.

## Together Rejection Response Mapping (2026-10-03)

- The real `HIGH_V_REJECTED` response is still a rejected join. Repository mapping
  now returns nonblank official `copywriting` as the existing error explanation,
  falling back to `status`. It does not infer joinability from `AVAILABLE`, alter
  host identity/version fields, or change the shared Store, page or navigation.
  API-029 records the official model and actual TV version-getter DEX evidence,
  including the limits of attributing the server rejection to any one parameter.
- Paired JVM reports contain 1083 standalone and 1096 parasite passing cases,
  including nine new pure response cases per flavor, with no failures, errors or
  skips. Both minified release builds and vital lint pass. The new synthetic
  Repository/platform case compiles into both Android test variants but is not
  executed on the AVD in this increment. Updated native error rendering and
  cooperating-account room synchronization therefore remain unqualified.
- `dual_runtime_release_test.rb --built-apks` passes the paired local workflow
  and actual-SDK package checks. The current unsigned pair is also signed with
  the matching production certificate; v3 verification, unchanged ZIP entries/
  payloads, exact workflow preparation and the original unmodified key all pass.
  Signed SHA-256 values are:
  standalone `56bd9a617ed6c30728ea636d1926ccda387b37b345329c500c6553ab5e9e46fa`;
  parasite `7c53b7bf4ab018b06ed32c314908a4952a0be19a58330f15e11d8a08bc887372`.
- No APK is installed, framework/module state changed, new room/message created,
  account switched or download repeated. D1/D2 remain met under the clarified
  unscoped-process requirement; D3-D6 and genuine Together host compatibility are
  not closed by this response-only repair. No push, merge or remote run occurs.

## Current Phone APK Protocol Check (2026-10-03)

- Read-only inspection now uses the actual signed phone 9.6.05 APK rather than
  assuming its older decompiled source matches. API-029 records its manifest,
  SHA-256, verified signer, DEX request owners and native rejection branch.
  The phone controller treats HIGH_V_REJECTED as an older room creator and stops
  joining. Its explicit check/accept payloads match the existing contract; create
  additionally has optional invitation/robot/extJson inputs, none established as
  a TV version-compatibility fix. No client identity or false join flag is changed.
- This improves candidate/protocol evidence, not Together server acceptance or
  the current TV runtime. The user's HyperOS AVD name is rechecked; package
  inventory shows TV only. A temporary helper uses the SDK's existing dexlib2
  parser to locate literal owners and stays outside Git. No APK, generated
  official source, device log or credential is added to the repository.
- Changes are documentation-only; current c715f7d4 paired unit/R8/signing/package
  evidence is retained, not rerun or relabeled as new device acceptance.
  `git diff --check` covers this increment. Installing/validating the phone
  candidate, changing the selected host or another cooperating room requires
  the stated user decision; no install, scope change or real write occurs here.

## Phone Candidate Native Baseline (2026-10-03)

- Following explicit user authorization, phone 9.6.05 is installed on HyperOS 4.
  The first ADB install is rejected with `INSTALL_FAILED_USER_RESTRICTED`; the
  subsequent normal Xiaomi file-installer flow succeeds. The installed base APK
  matches the candidate SHA-256 recorded in API-029. The user completes login.
  Native Home/Player and Together entry pages render; the current phone process
  has no framework/module mappings and no PID-scoped crash rows. This is native
  candidate evidence, not phone-host injection or audible playback acceptance.
- Public identity fields from the actual phone preferences and standalone
  DataStore confirm distinct accounts; phone matches the existing TV test account.
  No official Cookie/token is read, exported or copied. Private standalone queue/
  checkpoint and phone playback-preference backups are kept outside Git with
  restricted permissions. Native queue-cache files are additionally archived
  before any join; these backups are not a tested phone queue-restore operation.
- The user authorizes one two-account room test. Native phone Copy Link presents
  a Two-person/Multiple-person choice; Two-person is selected once. No usable
  invitation is obtained through the standalone's original Paste Invitation
  control. It never submits a join, plays a synchronized command or sends a
  third-party invitation/message. Phone history shows no completed Together
  record, which does not prove that no pending server room was created.
- The user's native-screen follow-up supplies two actual toast failures:
  "Invitation failed" and "Together is temporarily unavailable; try later."
  These occur in the unscoped official app, before any MeiloX join. They establish
  native invitation failure, not a clipboard-only diagnosis, a server response
  code, an account restriction or TV's previous HIGH_V_REJECTED cause. Creation
  success, pending-room identity and compatibility remain unqualified; no further
  creation/retry is issued by the assistant. Standalone account,
  full 1512-item queue and paused 81766 ms checkpoint are unchanged after the
  attempt. Phone playback bookkeeping/cache files change during native navigation;
  no blanket phone-state preservation claim or raw backup restoration is made.
- TV remains the selected host and module scope is unchanged. No production code,
  frontend architecture, APK update, download, framework setting or account switch
  is made in this checkpoint. Prior paired build/signing results are retained,
  not rerun. `git diff --check` qualifies the documentation increment only.

### User-Owned iPhone Room Follow-Up

- The user supplies an invitation created successfully on iPhone, using the same
  account as the AVD's TV/phone. Its HTTPS short-link redirect resolves the official
  Together share route with room/inviter/song parameters. The distinct standalone
  account joins that exact room once through its original UI. Its native page
  shows the matching room, two members and the adopted six-item playlist. No new
  room or third-party invitation/message is created by the assistant.
- The user reports approximately twenty seconds of playback followed by pause,
  then manually tests a track change. The original invitation song changes in
  the AVD. The corrected simultaneous progress report is iPhone 00:57/AVD 00:58,
  not the superseded 00:27/00:58 typo. This is a bounded observed difference,
  not a measured synchronization-latency guarantee or full audio acceptance.
- A read-only diagnostic first verifies the user-supplied standalone Cookie's
  account identity. Status returns code 200 and the exact room; the occupied
  room check returns FULL/false, not HIGH_V_REJECTED. Playlist sync returns code
  200, PAUSE and progress 58555 ms. The standalone media session independently
  reports PAUSED/58555 ms, and the page retains two members. The command target
  differs from the invitation song after the user's track change. No diagnostic
  sends a play, heartbeat, invitation, room-create or end request; official host
  credentials are neither read nor used by this standalone-only diagnostic.
- The first platform sample at 25304 ms may already be after the user's earlier
  pause, so equal successive samples do not establish a synchronization failure.
  Server/native state agreement and the real track change qualify this specific
  standalone/iPhone interaction, not the TV parasite's room-creation compatibility
  or the AVD phone's native invitation cause. An AVD-specific cause is plausible
  but not isolated from platform/client/environment differences by this test.
- The user ends this one room on iPhone. Account-verified server reads establish
  inRoom=false and room check EXPIRED/false; no further playback read or room-end
  write is issued. With standalone stopped, a temporary structured DataStore helper
  replaces only playback.snapshot and checkpoint in freshly captured current maps.
  It verifies the account and every other preference remain unchanged. Staged
  files retain the original owner, mode and SELinux label; replacement bytes are
  verified before relaunch. No raw original preference map replaces current auth.
- Relaunch is COLD/1801 ms. Actual native Home renders the original track with a
  nonplaying control; the platform position is 81766 ms and speed zero. Fresh
  DataStore reads verify all 1512 song IDs in the original order, source type,
  queue title, selected index 3, repeat/shuffle modes and nonplaying checkpoint.
  Persistence epochs are regenerated by normal startup, not mistaken for queue
  loss. The current PID-scoped crash buffer is empty. Private backups, temporary
  diagnostics and device captures remain outside Git. This single test is closed;
  no further room creation, upload, download, host/scope change or UI repair runs.

### Authorized Passive Phone Diagnosis (2026-10-03)

- The user authorizes temporary extra scope for com.netease.cloudmusic, with an
  explicit warning about environment detection. A temporary opt-in debug observer
  verifies the exact phone version/code/signer, never initializes the module app
  graph/session/player there, and preserves all observed methods' arguments,
  results and thrown exceptions. It records only operation labels, business codes,
  exception class names and bounded response-state fields. It neither reads
  credentials nor changes detection, headers, device identity or original UI.
- Diagnostic build is ParasiteDebug with a temporary build property; it passes in
  54 seconds. Its certificate matches the original development-signed module.
  Actual APK metadata and DEX confirm phone observation is enabled only in this
  diagnostic. TV scope is retained; all other modules are untouched. The original
  module APK and native playback/queue files are backed up privately before the
  single native Copy Link -> Two-person attempt; no raw backup is restored over
  current phone state or authentication.
- Native PID 24947 reports loaded/identity-verified/observer-ready, then exactly
  one create_invite begin at 10:31:58, business code 491 and exception m42.b at
  10:31:59. The feature RoomInfoResult parser is not reached. The current crash
  buffer is empty. APK DEX traces JSON code -> z1 -> generic non-success exception;
  API-029 records why this does not prove an AVD/root/environment-detection cause
  or the earlier unscoped failure's code. No second create/accept, synchronized
  playback or external contact action is issued. Room success/absence and phone
  suitability remain unqualified; generic rejection is not a migration fix.
- Cleanup force-stops the phone before removing its scope. Original module SHA256
  0e8fbdeb6bf1acfb2a9febffd75aaf6020e289e05e79eef4147cbe082cc6086b
  is restored exactly. Structured queries verify all module enable flags/scopes
  equal the original snapshot, not only the MeiloX row. Fresh native PID 27487
  cold-starts successfully, has zero framework/module mapping rows, observer log
  rows and crash bytes; inspected Home retains the original nonplaying control.
  Temporary observer source, build properties and packaged extra scope are fully
  removed; ordinary ParasiteDebug passes in 35 seconds. Production source matches
  the pre-diagnostic revision. Private artifacts remain under
  /tmp/meilox-phone-observer-20261003-cQinHb outside Git; no credential backup or
  proprietary source/log/APK is committed. TV remains the selected host and this
  temporary authorization is not a permanent host/scope expansion.
- Restored TV PID 28711 cold-starts into the original portrait MeiloX Home without
  autoplay or crash rows. Executed code_source matches the original APK's actual
  R8 DEX map ID 17d8ce30c001536a75b6c183f51db95d039d11dcdf8aec0e7766c09076dc741d.
  Its current module session is PAUSED/156037 ms with six entries; native TV is
  inactive/STOPPED/empty. This current observation is not replaced with an older
  checkpoint's eleven-item queue. Standalone remains NONE/81766 ms/speed zero with
  1512 entries. The ordinary rebuilt debug APK contains TV-only scope and no phone
  observer class. Local release workflow fixtures pass; this is not a new release
  runtime qualification. `git diff --check` passes for the documentation-only
  increment; no production implementation, account data or frontend repair is
  included in this documentation change.

## Production-Signed Preserving Installs on Configured HyperOS 4 (2026-10-03)

- The user chose the existing AVD, enabled Core Patch and authorized one
  system reboot. The same `HyperOS_4_Official_API_37`/emulator-5574 boots with root,
  LSPosed and Core Patch available. All module enable flags and 21 scope records
  equal their pre-reboot values; MeiloX still targets only TV. No AVD disk, snapshot
  or clone is backed up or created. Only application APK/data recovery material is
  retained in the previously authorized private application-backup directory.
- Both current production APKs are installed with `adb install -r`, without
  uninstalling or clearing data. Installed SHA256 values match the already verified
  production pair: standalone `56bd9a617ed6c30728ea636d1926ccda387b37b345329c500c6553ab5e9e46fa`
  and parasite `7c53b7bf4ab018b06ed32c314908a4952a0be19a58330f15e11d8a08bc887372`.
  Their certificate remains `03bc4bbc1e9b3b2bfad4e8712ccbc964546a2648ec6992d697875270d2b1a737`.
  This qualifies preserving installation on the user's cross-signature-enabled AVD,
  not native cross-signature acceptance on an unmodified system.
- Standalone PID 8066 COLD-starts in 1517ms. Its original Home/artwork/glass and
  Settings account name are inspected. Before any playback test, all rows in all
  17 Room tables match their pre-upgrade hashes: schema 21, 152 songs, 22 playlists,
  1015 likes and two completed downloads. Both database integrity checks pass.
  AndroidX's actual protobuf parser and Gson verify unchanged Cookie/account and
  other preferences, all 1512 queue entries/modes/title, index 3 and the 81766ms
  checkpoint. Only the queue snapshot's save timestamp changes. The process has
  zero framework/module mappings and its PID-scoped crash buffer is empty.
- One bounded ordinary play resumes the original queued track, not a download.
  MediaSession progresses from BUFFERING/81766ms to PLAYING/90951ms and then
  PAUSED/92802ms with no error or queue change. AudioFlinger identifies an active,
  unmuted 96000Hz track owned by standalone UID 10309 with nonzero server frames.
  This proves bounded decoder/output-state execution, not audible acceptance,
  full-track completion or settled server statistics. No new download grant,
  upload, social write, logout or account switch is performed.
- TV PID 10784 COLD-starts in 2755ms into portrait original MeiloX Home. Its actual
  API-102 executed map ID `50ba403bb003b995e29e4b8f4a441b921413fa756e10dad9c2bed05aa3a0f8b2`
  matches the production APK's DEX; host version/signature/isolation and request,
  login/report/upload bridge bindings succeed. Its six queue entries, index 2,
  modes and 156037ms checkpoint remain unchanged. Module preferences have no
  Cookie key. Sixteen of its 17 Room tables retain identical rows; the remaining
  playback_count table keeps every identity but one counter changes 17 to 18 with
  a newer timestamp during cold restoration. No TV play command is issued, and
  the captured hosted state is PAUSED; that local counter is not counted as real
  playback/server evidence or silently restored over current data. Attribution
  remains unqualified. Native TV is inactive/STOPPED with no queue; crash bytes=0.
- Recovery artifacts and redacted comparisons remain under
  `~/.local/share/meilox-avd-migration/2026-10-03/production-certificate-preserving-upgrade`
  with private directory/file permissions. The temporary device UI dump is removed.
  Application source/UI, official APKs, account credentials and scopes are unchanged.
  No rebuild or fresh JVM execution is claimed; retained source/package results
  still apply. This closes the configured production-install/data-preservation
  gate, not Together compatibility, full authorization/lifecycle/merge acceptance
  or an unrelated shared-player repair. No agent push, remote dispatch or release
  is performed.

## Paired Production Offline Recovery (2026-10-03)

- One bounded read-only test runs on the current production-signed pair, whose
  installed hashes still match the preserving-upgrade checkpoint above. Standalone
  PID 8066 and TV PID 10784 are brought forward, not rebuilt, reinstalled or restarted.
  Both original weekly-rank pages initially contain their distinct account records.
- The existing refresh control calls `ListeningRankViewModel.load(force = true)`
  and bypasses its in-memory rank cache for `/api/v1/play/record`, type 1. Wi-Fi
  and mobile data are disabled once; ConnectivityService reports no active default
  network. Exactly one forced refresh is issued per runtime during this window.
  Standalone displays its DNS resolution failure; parasite displays
  `Official transport failed: a`. Existing rows remain visible with the error,
  and screenshots verify the original page rather than treating retained rows as
  successful offline requests. API-039 records the different error contracts.
- The original Wi-Fi/mobile-data enablement is restored in a cleanup block.
  ConnectivityService again reports a validated default network. One forced
  refresh per runtime removes the error and displays populated weekly records.
  Both Settings account rows remain logged in; each client returns to its original
  Home. Airplane mode remains disabled. Temporary device XML is removed.
- All four captured settings/progress protobuf files are byte-identical before
  and after the test, preserving standalone Cookie/account data and both complete
  queue/checkpoint stores. Standalone remains PAUSED at 92802ms/index 3 and TV at
  156037ms/index 2; MediaSession state lines are unchanged. No playback, download,
  upload, room creation/join, social write or authorization transition is invoked.
- Preservation uses complete before/after byte comparisons, not the upgrade-only
  validator's checkpoint-to-restored-queue assertion: no task/queue restoration
  occurs in this live-process test. No new queue mutation is demonstrated.
- The crash buffer contains unrelated HyperOS security/location records, not an
  empty device-wide buffer. It has zero records for either runtime/module, and
  both application PIDs remain unchanged. Private XML/PNG/datastore/comparison
  evidence stays under
  `~/.local/share/meilox-avd-migration/2026-10-03/paired-production-network-recovery`;
  no credentials, account identifiers, device logs or screenshots enter Git.
- This qualifies the named forced-read network failure/recovery path, not session
  expiry, account switching, all transport failures or full D3-D6 acceptance.
  The user defers the proposed unhooked phone Together join; no invitation is
  requested and no further phone-room test is run. Application/UI source and
  installed APKs are unchanged; prior build/unit/package evidence is retained,
  not represented as fresh executions. `git diff --check` passes.

## Native Cross-R8 Parcelable Codec Qualification (2026-10-03)

- Added opt-in `.github/tests/ParcelableStateCompatibility.java`, compiled against
  Android 37.0 and converted with D8. The fixture runs in an Android shell VM on
  the verified HyperOS 4 AVD, not a JVM Android stub or an installed test client.
  Separate boot-parent DexClassLoaders load two actual minified module APKs.
  Assertions check receiving-loader identity, complete payload consumption,
  decoded values/policies and untouched sibling sentinels. Neither loader may
  load the production `AppContext`.
- The first stable-name artifact `d5c9667b2c1b3854ca39fafa9902d603cfbfeadb4e3588628db87fcb7cebce03`
  and the currently installed production parasite
  `7c53b7bf4ab018b06ed32c314908a4952a0be19a58330f15e11d8a08bc887372`
  pass 18 positive scenarios: Float/Int/Long state, all three generic mutation
  policies, empty/mixed SnapshotStateList, and nested Bundle/Parcelable lists,
  in both APK directions. This strengthens the existing class-name/CREATOR gates
  with real Android decoding; it does not qualify a complete saved Activity task.
- One negative control uses the previously fingerprinted library-owner and
  lifecycle-fixed pre-rule APKs. Its writer's `z99` is first verified as generic
  state and its reader's `z99` as Long state. The cross-loader Bundle throws
  `BadParcelableException`. This reproduces the alias hazard without corrupting
  a real task; it does not prove the exact contents or sole cause of PID 1681's
  historical `Bad magic number` failure.
- No module/app installation, host restart, LSPosed scope change, saved-state
  clearing, business request, playback or account transition occurs. Standalone
  PID 8066 and TV PID 10784 remain alive and PAUSED at 92802ms and 156037ms.
  The five read-only fixture/APK files and their temporary device directory are
  removed. Local compiler/DEX/result evidence stays in
  `/tmp/meilox-parcel-native-20261003`; no APKs or device logs enter Git.
- The fixture source and `git diff --check` pass. Production code/APKs are
  unchanged; previous app builds/tests are retained, not rerun or represented as
  new results. The user accepts non-restoration of only the early pre-keepnames
  internal-test tasks, using the existing module-update host force-stop/restart
  procedure. This retires their transient page state, not login, settings, queues
  or downloads. No automatic/generic Bundle clearing is added. The historical
  failure remains recorded; complete stable-name cross-version Activity restoration
  and the separate module-update code/resource mismatch are not qualified by this
  codec test or the limited acceptance decision.

To reproduce, prepare a fresh local `classes` directory and stage the compiled
fixture plus two own module APKs as read-only `fixture.zip`, `older.apk` and
`current.apk` under a fresh temporary device directory. Verify the HyperOS AVD
name before selecting `$serial`, and remove only those staged files afterward.
Optional third/fourth APK arguments are the known generic/Long `z99` negative pair.

```sh
javac --release 8 -Xlint:-options -cp "$ANDROID_HOME/platforms/android-37.0/android.jar" \
  -d "$work/classes" .github/tests/ParcelableStateCompatibility.java
"$ANDROID_HOME/build-tools/37.0.0/d8" --min-api 33 \
  --lib "$ANDROID_HOME/platforms/android-37.0/android.jar" \
  --output "$work/fixture.zip" "$work"/classes/*.class
adb -s "$serial" shell "CLASSPATH=$device/fixture.zip app_process /system/bin \
  ParcelableStateCompatibility $device/older.apk $device/current.apk"
```

## Paired Production Native PiP Menu Qualification (2026-10-03)

- The unchanged, installed production-signed APKs retain SHA-256
  `56bd9a617ed6c30728ea636d1926ccda387b37b345329c500c6553ab5e9e46fa`
  (standalone) and
  `7c53b7bf4ab018b06ed32c314908a4952a0be19a58330f15e11d8a08bc887372`
  (parasite). Only HyperOS 4 is used. Neither APK is installed, rebuilt or
  force-stopped; framework scope, rotation, PiP flags and shared frontend stay unchanged.
- Entered PiP from each existing player-menu entry. One prompted helper launch uses
  only "Allow this time"; subsequent launches have no confirmation, without selecting
  a permanent allowance. ActivityManager identifies the correct pinned component:
  standalone MainActivity or module LyricsPipActivity, not an injected TV Activity
  falsely represented as PiP-capable. Later host-menu operations select labels from
  fresh UI XML rather than chained coordinates during sheet animations.
- The earlier unexposed-menu observations remain historical. In this checkpoint,
  native SystemUI menus render after a tap and animation settlement (450ms before
  capture/button input). SystemUI reports the actual touch and `mMenuState=1`.
  Early input-window flags such as `NOT_TOUCHABLE` or `globalScale=0` alone did not
  prove a system defect: later screenshots and successful clicks contradict that
  premature inference. No system flag change or menu replacement is used.
- Actual native play/pause buttons, not an instrumentation-sent PendingIntent,
  change standalone PAUSED-to-PLAYING-to-PAUSED at item 3, and the hosted player
  at item 2. Native next/previous clicks change standalone 3-to-4-to-3 and hosted
  2-to-3-to-2, retaining their paused state and queue sizes of 1512 and 6.
  The separate native TV player stays inactive, STOPPED at zero with no queue.
- Native expand restores standalone fullscreen. For parasite it exits pinned mode,
  finishes the auxiliary Activity and returns to the TV-hosted MeiloX player.
  Native close removes both pinned windows; the helper Activity is absent after
  close without killing its process or TV. A separate initial helper dismissal
  gesture also succeeds. This is actual menu interaction, separate from the prior
  immutable-PendingIntent/stale-replay and continuous-lyric-frame evidence.
- Tests intentionally include short playback and next/previous transitions. One
  early animation-time coordinate tap instead seeks TV to 84775ms; that is harness
  error, not application acceptance. UI seeking restores the same original items
  and leaves standalone PAUSED at 95136ms, hosted PAUSED at 156071ms (34ms from its
  original 156037ms). Restoring standalone progress after a paused item transition
  also requires a short preparation/play/pause before its duration is available.
  No exact byte-preserved playback checkpoint or whole-track/audible/server-statistics
  acceptance is claimed. Both authenticated Home pages are separately inspected,
  and final foreground is TV-hosted Home with no pinned/helper Activity.
- Parsed current settings against the previous production checkpoint using the
  DataStore protobuf and Gson parsers, without printing credentials. Account/Cookie
  values, all queue entries/order/title/source/modes and other settings are unchanged;
  only `navigation.lastSelectedTab` (Home) and `playback.snapshot` differ. The captured
  TV queue snapshot's index is 3 while its matching-epoch progress checkpoint is 2;
  applying that checkpoint restores the live item, as designed. Both current progress
  files match their queue epochs and the final paused indices/positions. Raw-byte
  comparison and direct old/new snapshot-index equality are not valid preservation
  assertions for these intentional operations.
- Current standalone PID 8066, TV PID 10784 and helper PID 20144 crash-buffer captures
  are empty. The auxiliary shell-only precise-seek fixture was terminated with
  `Killed` and is not counted as a successful recovery or application test; its
  attempted system-context and minimal-context implementations are abandoned.
  The temporary device DEX ZIP and UI XML are removed. Private source, parser,
  screenshots and results remain under `/tmp/meilox-pip-native-*` and
  `/tmp/MeiloxPipPreferenceRetention*`; no APK, credential or device log enters Git.
- No production source changes occur. Existing app builds/unit/package results are
  retained, not rerun or reported as fresh. `git diff --check` passes. This closes
  the named paired native PiP-menu gate only; full permission/account/configuration
  coverage, readability, stable-name Activity restoration and other D3-D6 gates remain.

### Matching-Code Cross-R8 Activity Attempt (2026-10-03)

- One bounded attempt uses only HyperOS 4 `emulator-5574`, TV-only scope and
  ordinary probe-disabled production artifacts. The old installed module SHA256
  is `7c53b7bf4ab018b06ed32c314908a4952a0be19a58330f15e11d8a08bc887372`,
  with executed R8 map `50ba403bb003b995e29e4b8f4a441b921413fa756e10dad9c2bed05aa3a0f8b2`.
  The new module SHA256 is
  `ebc9ab26247cc951e9f7b7f682f51d393952a7dc134f67648c32113ac5c4fedd`,
  with R8 map `e8a64187a082a14e6d21350e1ed8ef5c6c89a140f77a85933405fb08f29d2897`.
  Both use the production certificate qualified above. Signing and exact paired
  workflow preparation pass; only the parasite APK is preserving-installed.
- The existing Library/Podcasts subscription opens the original detail for
  `《明日方舟》游戏背景音乐合集`; a small scroll is captured without playing,
  subscribing or writing account data. Task 68 stops with a 4692-byte saved Bundle.
  `am kill` removes PID 10784 while preserving that task, its state and `app=null`.
  No host force-stop or CLEAR_TASK occurs before the restoration attempt.
- The installed APK hash matches the new artifact. Filtered framework logs do not
  provide a cache-refresh acknowledgment; two dex2oat errors alone do not establish
  which DEX will execute. Focusing the retained task starts PID 29116. Its module
  code_source and crash frames both match the new R8 map, and Activity creation
  reports `restored=true`. This attempt therefore executes the intended updated
  module, unlike the older mismatched-code observation. It does not explain or
  universally fix that historical framework cache/resource failure.
- Restoration fails before a usable detail page with `BadParcelableException`:
  Serializable object `r17` cannot bind an enum descriptor to a non-enum class.
  The old actual APK DEX identifies `r17` as the six-value LibraryPage enum
  (Songs/Playlists/Podcasts/Downloads/Cloud/History); the new matching R8 map assigns
  `r17` to `androidx.compose.material.icons.rounded.LibraryMusicKt`. LibraryScreen's
  unchanged rememberSaveable stores this enum inside ParcelableSnapshotMutableState.
  Keeping the outer Parcelable name is insufficient to stabilize this nested
  Serializable payload. The previous native primitive/list/policy codec tests
  remain valid, but never covered LibraryPage or full Activity state.
- No speculative dispatcher, loader, enum rule or shared frontend change is made
  in this checkpoint, and there is no second install/restore attempt. The existing
  manual force-stop/restart recovery returns ordinary portrait Home in PID 29709
  (cold launch 2304ms), with an empty current-PID crash buffer and matching new code.
  Standalone remains PID 8066 and is not updated or restarted.
- Before/after DataStore protobuf/Gson comparisons preserve account/Cookie values,
  all other settings, complete queue entries/order/source/modes and paused progress:
  standalone 1512 items, index 3, 95136ms; parasite 6 items, index 2, 156071ms.
  Only parasite navigation and playback.snapshot differ after recovery. An initial
  retention-helper assertion expected the persisted Home tab too early; normal
  Library-to-Home selection settles that value and the final comparison passes.
  No uninstall, clear-data, manual persistent-data rewrite, download or AVD backup occurs.
- Private captures and failure evidence stay under `/tmp/meilox-stable-activity-*`
  with mode 0600; the temporary device UI XML is removed. No APK, credential or
  device log enters Git. Existing paired build/unit/package checks from the CI
  memory checkpoint are retained, not rerun for this documentation-only increment;
  `git diff --check` passes. Full cross-APK Activity restoration remains failed/open,
  and the user's early pre-keepnames task waiver is not broadened.

### Nested Library Enum Wire Fix and Controlled Recovery (2026-10-03)

- Added only `-keepnames enum com.ljyh.mei.ui.navigation.LibraryPage` to the
  parasite R8 rules. The shared LibraryScreen/saveable state, standalone rules,
  dependencies, page architecture and visible controls remain unchanged. The old
  actual APK fails a lookup for this original enum name; the fixed actual DEX
  retains it as an enum with all six original constant names. Already-obfuscated
  task descriptors are not retroactively migrated or generically cleared, and
  the user's narrowly accepted early-task limitation is not broadened.
- The exact signed-pair workflow preparation now checks this nested Serializable
  identity as well as the five outer state names/CREATORs. Nine new negative
  fixtures reject renamed/missing enum types, a non-enum alias and each missing
  constant. Ruby syntax, 88 synthetic release checks and 92 combined real-SDK
  checks pass. Both ordinary R8 artifacts build/vital-lint successfully in 2m03s.
  Kotlin/application sources are unchanged; previous paired JVM results are
  retained rather than reported as fresh executions.
- The opt-in Android fixture's `--library-enums` mode covers six values and three
  mutation policies in each direction, plus the existing primitive/list/nested
  state cases: 54 positive transfers. Reflection verifies the actual decoded enum
  returned by the erased state getter belongs to the receiving module loader,
  not merely that its reserialized descriptor matches. Payloads/policies, nested
  lists and sibling sentinels survive; no production Application or account is loaded.
  The default fixture mode keeps its historical 18-case/legacy-control behavior.
- Two production-signed probe-disabled artifacts are used: ordinary A has SHA256
  `e6d432fad20adc5aae984fbdea59547ff36182f2cc956bea7d2ddf5b19c43db1`, R8 map
  `0b0af1c0275844bc2027bee467b311a41b9e3a989fb263ebd84205c0ff249b33`;
  validation B has SHA256
  `b27b662fe8cfe8b463b4a9eb1a402030cab8f1d0dd9757879fe12f3f734a71ee`, R8 map
  `b9820c3f61e5ece8adecf9f56cb18aef630f43f69621a2fbe70956b8dd6d7da3`.
  B uses a temporary `b/a` class-name dictionary, changing 3079 mapped class names
  while retaining LibraryPage and the module ReportFragment identity. This is a
  controlled minifier perturbation, not a second frontend or release configuration.
  Both pass production-certificate/signature and exact workflow preparation checks.
- On HyperOS 4 only, A's original subscribed-podcast detail is scrolled and task 72
  reaches STOPPED with a 4800-byte saved Bundle. `am kill` removes PID 31698 without
  force-stop/task removal. One preserving B install and task focus restores the
  same task in PID 1648, with restored=true and code_source matching B's actual R8
  map; installed SHA256 also matches. No crash is recorded for that PID. The original
  detail/artwork/program rows render, and Back returns to Library with Podcasts
  still selected. However the detail title moves from y=534 to y=1070, so the prior
  scroll offset is not retained; the paused mini-player is also absent from the
  restored detail, Library and Home despite its valid six-item MediaSession. Thus
  the enum crash/route/tab gate passes, not complete saved-page/UI restoration.
- Removed the temporary dictionary directive, rebuilt both ordinary artifacts
  in 2m26s, and verified both unsigned APKs are byte-identical to the checked A
  pair. The validation B APK is replaced by ordinary signed A under the existing
  force-stop/restart procedure. Final PID 4126 executes A's matching map and hash,
  reaches portrait Home and displays the mini-player again, with an empty PID
  crash buffer. Standalone remains PID 8066 and is neither installed nor restarted.
  Parsed before/after settings preserve account/Cookie values, all other settings,
  all queue entries/order/source/modes and paused checkpoints: standalone 1512/index
  3/95136ms; parasite 6/index 2/156071ms. Only parasite playback.snapshot changes.
- No playback command, room/invitation, download/upload, authorization/account
  change, clear-data, manual persistent-data rewrite, framework scope change or
  AVD backup occurs. Staged device APK/DEX/XML files and their dedicated directory
  are removed. Private artifacts/captures/results stay under the mode-0700
  `/tmp/meilox-library-enum-native-20261003`; no credential, APK or device log enters
  Git. `git diff --check` passes. The scroll/session-key and mini-player restoration
  observations need bounded attribution; unrelated main frontend repair is not
  authorized. D3-D6 and overall completion remain open.

## Acceptance and Remaining Decisions

- Run shared contract tests against both backends, plus flavor-specific transport,
  session, packaging and runtime tests. Check account generations, cancellation,
  expired sessions, failed recovery and late responses for both implementations.
- Shared-resource propagation is verified by 24 actual debug/R8 APK values from one
  shared source (checkpoint above). Keep subsequent UI/resources single-source; do
  not create permanent duplicate screen trees as a demonstration. Resource inheritance
  alone does not qualify complete visual or interaction parity.
- Preserve current CI semantics: pushes build artifacts, manual dispatch publishes;
  extend them to two unambiguous signed artifacts without triggering a remote run now.
- Keep interface differences and evidence in
  [Official Client API Differences](official-client-api-differences.md), distinguishing
  standalone contract, official-host contract, shared mapping and verification limits.
- Dedicated TV microphone/PiP helpers are implemented with bounded live evidence
  above; paired production native PiP-menu interaction now passes, while full
  permission/lifecycle gates remain open.
  Invoking the standalone APK as a helper, sharing credentials,
  hiding features, permanently expanding LSPosed scope or rewriting host metadata
  is not authorized. The explicitly approved temporary phone diagnostic above is
  closed and its additional scope revoked.
- Real login/logout/account switching, quota-consuming downloads/uploads and social
  writes still require the previously stated user cooperation/authorization boundaries.
- No standalone frontend bug cleanup is part of this migration. Record unrelated
  findings separately; do not fold them into backend or flavor commits.
- The observed standalone R8 near-end pause/resume stall remains an unqualified
  playback regression. Preserve the successful timer evidence separately from that
  failure and establish reproduction/attribution before any scoped repair.
- The user accepts only early pre-keepnames internal-test tasks not restoring across
  module versions, under the existing host force-stop/restart update procedure.
  Their historical Bundle failure stays recorded. This does not waive normal
  same-APK recreation or stable-name cross-R8 Activity qualification, authorize
  generic saved-state clearing, or permit clearing account/queue/settings/download
  data. The stable-name native codec test and full Activity restoration are distinct.

The shared dependency-boundary D1 and clarified independent-runtime/build-skeleton D2
exit conditions are met on the HyperOS 4 AVD. D3-D6 remain open, including the genuine
Together host-version rejection; final acceptance still requires the scoped evidence
above, not compilation alone.
