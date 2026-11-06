# Music sources

## Compatibility baseline (P0)

The implementation starts at `5632235085006d01cc2ba589e6c9354033152802`, matching
`origin/main` on 2026-11-06. Room is version 18. Existing song, playlist, download,
lyric and history keys remain intact. `SourceType` continues to describe storage.

```text
Compose -> ViewModels -> repositories -> Netease Retrofit / Room
Media3 -> MediaUriProvider -> local files / URL quality ladder / Netease API
AutoMix -> source resolver -> CacheMediaDataSource
Downloads -> PlaylistRepository -> DownloadManager -> DownloadWorker -> Room
```

The provider boundary is introduced inside `:app` before considering Gradle modules.
Typed source references become authoritative; legacy numeric fields remain adapters.
New provider keys use a reversible versioned codec. Existing positive numeric IDs
remain Netease IDs. Local records require their storage/metadata context: negative
podcast placeholders also exist and must not be classified as local files.

Migration risks and decisions:

- Add identity columns without rekeying existing rows or rewriting offline paths.
- Read queue snapshots v1/v2 and write v3 with explicit content references.
- Keep numeric Netease cache compatibility; external audio uses its actual source.
- Preserve original metadata and action targets when audio falls back.
- Separate platform history reporting from host playback statistics.
- Never pass the account-bearing Netease HTTP client to plugins or generic audio.
- Retain QQ lyric mappings as lyric data, without claiming QQ playback support.
- Provider removal never removes library rows or downloaded files.
- Preserve native account, FM, cloud, podcast, social and together-listening pages.

Baseline command:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Result: 873 JVM tests passed; application and instrumentation APKs built. An existing
HyperOS AVD (`emulator-5580`) is available. Its original app data was backed up locally
before installation testing; backups contain credentials and are Git-ignored.
The original package was updated with `adb install -r`. Existing quality and queue
instrumentation passed all 23 tests on that AVD.

## Stage record

- P0: dependency and compatibility audit, reproducible baseline.
- P1: typed identity, additive migration and compatible queue persistence.
- P2: native Netease provider and retained compatibility facades.
- P3: routed playback and bounded, ordered cross-source resolution.
- P4: imported script runtime, permission-limited host API and SDK.
- P5: source management, source selection and playback source status.
- P6: regression checks and user/developer documentation.

Stage validation and remaining limitations are recorded as the implementation proceeds.

### P1

Added versioned UTF-8 source identities and generic models, additive Room 18→19
identity/provenance columns, and queue snapshot v3 with v1/v2 readers. Legacy primary
keys and file paths are retained. Song upserts no longer replace parent rows and
delete related playback history. External IDs never become hashed numeric IDs.

The shared integration build passed 895 JVM tests and built both APKs. On AVD,
`SourceCompatibilityInstrumentedTest` passed both tests: a complete SQLite v18 fixture
upgraded through Room v19 validation while preserving all seeded associations and
files; legacy and external snapshots restored through real Media3 items.

### P2

Netease catalog, playback and quality capabilities now have a native provider. Native
repositories and their DI graph own account/security, home, playlists, library,
podcasts, cloud, social and together-listening behavior. Old public repository names
remain compatibility aliases. Native UI operations use native catalog adapters;
generic catalogs route by explicit source through the registry. QQ lyrics are a
separate extension and do not advertise QQ playback.

All five native-provider JVM tests passed in the shared build. Eleven AVD tests for
playback identity, authentication isolation and playlist request transport passed.
These synthetic transport regressions preserve the real account; they do not claim
successful live login, cloud upload or social mutation.

### P3

Playback now resolves original content and actual audio separately. Native URL
refresh/quality ladders run before configured cross-source matching. Resolution has
typed failures, bounded timeouts, cancellation/invalidation epochs, shared request
gates, concurrency limits, positive/negative mapping caches and explicit manual
bindings. Ambiguous recordings are not selected automatically.

Media3, AutoMix and downloads preserve per-resource headers and cache permission.
Version 4 audio cache keys contain actual provider/instance/ref/quality/content;
legacy Netease v3 keys remain readable. Download tasks retain origin and actual
provenance without persisting headers. Platform history excludes cross-source audio,
including downloaded copies. Native-only UI actions and lyric cache keys now respect
the content provider. Negative podcast transports cannot collide with local songs.

The final shared build passed 913 JVM tests. Thirty-one AVD migration, snapshot, real
ExoPlayer, quality, native-before-external rejection, download-generation, live-native
catalog and queue tests passed. Isolated service-recovery tests require a dedicated
opt-in package and are not counted as successful recovery coverage.

### P4

Imported JavaScript runs in AndroidX's isolated V8 service, with fresh bounded
isolates and no Android bridge or host account access. Host-mediated HTTPS validates
declared hosts, DNS, redirects, headers and size limits. Audio uses opaque registered
handles and the same restricted transport. Configuration and namespaced storage use
Keystore encryption. Installation/update binds approval to inspected package bytes;
disable/remove leaves library records and independent downloads intact.

The portable offline SDK fixture passed its Node runner. Both real-sandbox AVD tests
passed: import/search/detail/fallback/audio/disable/reload, and runaway termination,
network denial and healthy recovery. The example provides an authored test tone;
no real QQ/Kugou playback service is claimed. API 1 advertises only search, track,
playback and lyrics; native advanced Netease features retain their existing pages.

### P5

Settings provide package import/permission review, source status/configuration,
enable/remove/update, independent browse/search defaults and persisted fallback
ordering. Generic track search routes every source through the same catalog API;
native extra search categories and the native home remain provider contributions.
Song information displays the actual audio source/quality without changing content
actions, and exposes per-song fallback permission and manual-match review.

The isolated real-DataStore search AVD test passed, including cancellation, paging,
typed errors, independent defaults and failed-provider propagation. Main package
startup, retained account/home/queue and the management settings entry were checked
on the AVD. The native smoke passed actual search→detail→full audio
resolution with the account unchanged, along with both migration/snapshot tests.

### P6

Added revision-gated script configuration/storage commits so account replacement or
uninstall cannot receive stale invocation state. Room schema 19 is exported under
`app/schemas`; the v18 fixture remains alongside migration instrumentation. User
instructions, SDK/security boundaries, third-party notices and the detailed
[validation matrix](source-validation.md) are included.

The final combined AVD suite passed 45 tests. The real v18→v19 database comparison
preserved all existing primary keys/counts and offline file facts. Debug/test and
optimized Release builds passed. Full debug lint retains 37 pre-existing errors;
the validation report identifies their baseline comparison and practical hardware
or live-account coverage limits.
