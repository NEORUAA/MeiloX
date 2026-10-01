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
  Cookie is verified before the page reports success, and the same rejected value is not
  repeatedly retried by the polling loop. No credential is copied from TV or the original
  standalone installation into the debug app.
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

## Acceptance and Remaining Decisions

- Run shared contract tests against both backends, plus flavor-specific transport,
  session, packaging and runtime tests. Check account generations, cancellation,
  expired sessions, failed recovery and late responses for both implementations.
- Verify that a single shared UI/resource modification is included by both build
  variants. Do not create permanent duplicate screen trees as a demonstration.
- Preserve current CI semantics: pushes build artifacts, manual dispatch publishes;
  extend them to two unambiguous signed artifacts without triggering a remote run now.
- Keep interface differences and evidence in
  [Official Client API Differences](official-client-api-differences.md), distinguishing
  standalone contract, official-host contract, shared mapping and verification limits.
- The TV microphone/PiP manifest gate remains unresolved. Producing a standalone APK
  does not authorize invoking it as a parasite helper, sharing credentials, hiding
  features, changing LSPosed scope or rewriting host package metadata.
- Real login/logout/account switching, quota-consuming downloads/uploads and social
  writes still require the previously stated user cooperation/authorization boundaries.
- No standalone frontend bug cleanup is part of this migration. Record unrelated
  findings separately; do not fold them into backend or flavor commits.
- The observed standalone R8 near-end pause/resume stall remains an unqualified
  playback regression. Preserve the successful timer evidence separately from that
  failure and establish reproduction/attribution before any scoped repair.

The shared dependency-boundary D1 exit condition is met and the dual-debug skeleton is
operational on the current AVD. D2's framework-free startup gate and D3-D6 remain open;
final acceptance still requires the scoped evidence above, not compilation alone.
