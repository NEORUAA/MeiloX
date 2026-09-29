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

Build targets (both debug variants build; paired release qualification remains pending):

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
