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

Planned build targets (not implemented at this checkpoint):

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

This is the first D1 increment, not its exit condition. Shared Activity/service attachment,
login content, network injection, notifications/WorkManager and graph bootstrap still
reference concrete host implementations. Isolate those bindings before moving them into
flavor source sets. Neither flavor targets nor the standalone backend are implemented yet.

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

All D1-D6 exit conditions remain pending; the checkpoint above records partial D1 work only.
