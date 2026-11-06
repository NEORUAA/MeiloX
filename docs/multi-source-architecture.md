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
files; legacy and external snapshots restored through real Media3 items. One existing
quality test in the concurrently integrated P3 path exposed an exception-adapter
regression, tracked for correction in P3.

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
