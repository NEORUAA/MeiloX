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

The dual-debug skeleton is now operational on the current AVD. D1/D3-D6 remain incomplete;
the complete D2 independence gate and all final acceptance conditions still require the
scoped evidence above to be supplemented, not inferred from successful compilation.
