# Source refactor validation

Baseline: `56322350` (Room 18). Validation device: the existing arm64 HyperOS AVD
`emulator-5580`, Android System WebView 150.0.7871.181. The original application ID
`com.neoruaa.meilox` was updated with `adb install -r`; its data was backed up locally.
No account logout, remote social writes or destructive database reset was used.

## Automated checks

### Current API 2 extension

The 943-test JVM run and 51-test AVD regression run precede the final bounded
artwork-cache and feed-background changes (`9e4beaa8`, `54b64dda`). The final Debug
at `54b64dda` built successfully with the 1 GiB profile below (1m 21s) and was
installed over the original application for the final UI and QQ acceptance checks.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -Pksp.incremental=false --console=plain
```

- 943 JVM tests passed.
- Debug application and instrumentation APKs built successfully.
- The broad AVD regression run passed all 51 selected tests.
- The pinned QQ 0.3 package (`0e88f488…`) passed both final Android acceptance tests:
  captured payloads in the real isolate, and authenticated live catalog/library/playback.
  All four search types, home/discovery/categories/charts, album/artist/playlist
  details and all five personal collections returned real data. Known nonempty
  collections were explicitly asserted. An independent muted ExoPlayer played the
  authorized resource and its decoded duration matched the full track metadata.
  Both native credentials and saved QQ configuration were retained.

The first API 2 Release attempt was stopped during R8 because of memory pressure
on the 16 GB host. The optimized Release subsequently passed before those two
visual/cache changes with a 2 GiB heap, one worker and in-process Kotlin compilation
(4m 35s). Later Debug and instrumentation builds
use a 1 GiB heap, one worker, one active processor and no persistent compiler daemon.
The AVD is kept running through the user's original launcher and configuration:

```sh
nice -n 19 ./gradlew :app:assembleDebugAndroidTest --no-daemon --max-workers=1 \
  -Dorg.gradle.parallel=false \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -Pksp.incremental=false --console=plain
```

AVD instrumentation uses the installed application and `androidx.test.runner.AndroidJUnitRunner`:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class com.ljyh.mei.source.SourceCompatibilityInstrumentedTest,com.ljyh.mei.playback.MediaUriProviderQualityTest,com.ljyh.mei.playback.PlaybackQueueIntegrationTest,com.ljyh.mei.source.script.ScriptPluginInstrumentedTest,com.ljyh.mei.ui.screen.search.SourceSearchInstrumentedTest,com.ljyh.mei.data.network.NeteasePlaybackIdentityInstrumentedTest,com.ljyh.mei.data.network.NeteaseSourceAuthInstrumentedTest,com.ljyh.mei.data.network.NeteasePlaylistRequestInstrumentedTest \
  com.neoruaa.meilox.test/androidx.test.runner.AndroidJUnitRunner
```

The selected 51 tests cover real SQLite migration/schema validation, legacy/current
Media3 snapshots, original-platform quality recovery, exact-byte rejection, real
ExoPlayer fallback, per-resource headers/UA, `NO_STORE`, download provenance and
generation isolation, queue controls, actual native search/detail/audio resolution,
real isolated JavaScript import/search/detail/fallback/audio/lifecycle, infinite-loop
termination, network denial, revision revocation, binary transport errors, search
cancellation and preference isolation. The separate captured QQ fixture test uses
the real isolate without live account mutations. The final QQ checks used:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class com.ljyh.mei.source.script.QQMusicSourceInstrumentedTest#shippedAdapterParsesCapturedResponsesInsideTheIsolatedRuntime,com.ljyh.mei.source.script.QQMusicSourceInstrumentedTest#installedQQSourceSupportsDeclaredReadOnlyCapabilities \
  -e qqMusicLive true -e qqMusicUpdate true -e qqMusicReviewedUpdate true \
  -e qqMusicAccount true -e qqMusicPlayback true -e qqMusicQuery 城南花已开 \
  -e qqMusicRequireNonemptyCollections library.likedTracks,library.savedPlaylists,library.savedAlbums,library.followedArtists \
  -e keepQqPlugin true \
  com.neoruaa.meilox.test/androidx.test.runner.AndroidJUnitRunner
```

The QQ contract pins exact capabilities, configuration and eleven hosts. The observed
`sjy6` audio CDN requires explicit reviewed-update opt-in. Unknown permissions are
rejected. Optional diagnostics emit field presence, numeric status or QQ CDN
hostnames, never Cookie values, account identifiers or signed audio URLs.
Hash-gated cache package input allows adapter retests without recompiling the host.

`MusicServiceQualityRecoveryTest` has three explicit isolated-package opt-ins. They
were skipped on the original application ID and are not counted among these tests.

### Original P6 validation

```sh
./gradlew :app:assembleRelease -Pksp.incremental=false --console=plain
node samples/music-source-plugin/test.mjs
```

Before the API 2 extension, P6 passed 913 JVM tests against the original 873-test
baseline and all 45 selected AVD tests (7.729 seconds). Its optimized Release APK
completed R8 and vital lint, the portable offline plugin contract runner passed,
and independently checked P1/P2 commits compiled in detached worktrees. These
earlier results are separate from the current API 2 runs above.

## Static analysis

```sh
./gradlew :app:lintDebug -Pksp.incremental=false --console=plain
```

The current API 2 full debug lint reported **37 errors, 280 warnings and 5 hints**,
matching the error/warning counts from P6. All 37 offending statements also exist
verbatim in the original baseline: permission handling in recognition, Media3
constant/opt-in restrictions, vendored Netease SDK shims, locale/resource reads and
an existing instrumentation layout. No error was reported in the new source runtime,
source core or source management code. These existing errors were retained in the
report rather than hidden by disabling checks or creating a broad lint baseline.

## Acceptance matrix

| Requirement | Implementation / evidence |
| --- | --- |
| Default Netease and offline playback | Built-in native provider, retained native features; actual online catalog/audio and local/ExoPlayer regression coverage |
| User data upgrade | Additive Room 18→19, no rekey/delete; complete v18 fixture preserves all seeded associations and files under Room validation |
| Old/new queues | Snapshot v1/v2 readers and v3 typed references; actual Media3 capture/restore tests |
| Netease advanced features | Native repository/UI ownership and compatibility facades retain login/security, FM, cloud, podcast, library, social and together-listening code; domain regressions pass |
| Existing player behavior | Quality/cache/recovery, queue, lyrics, downloads, Media3 and AutoMix integration retained; AVD and JVM regressions |
| Collision-free content | Provider-scoped typed refs, canonical UTF-8 codec, legacy adapters, string-ID tests |
| Capability discovery/routing | Registry/Router plus native feature contributions; unsupported operations are explicit |
| Plugin lifecycle | Real imported ZIP and V8 tests cover search, detail, audio, disable/re-enable and reload; transactional update and remove preserve library/files |
| Browse/search/order/settings | Separate persistent preferences, top-bar dropdowns, General settings entry and Glasmio drag ordering; real DataStore and process-restart checks |
| Ordered bounded fallback | Original-first/override/disabled policies, deduplication, gates, timeout/cancel/invalidation and byte rejection tests |
| Recording identity | ISRC validation, title/artist/duration/version/explicit guards, confidence/ambiguity threshold and manual binding |
| Origin actions after fallback | Queue metadata/ref unchanged; native-only actions use origin; remote reporting rejects foreign audio and downloaded copies |
| Actual audio provenance | Actual provider/instance/ref/quality/content cache keys and download fields; headers stay in memory |
| Untrusted-code isolation | Isolated V8, no Android/Room/account bridge; controlled HTTPS and opaque audio transport; runaway/denial/recovery tests |
| Permissions/limits/credentials | Size/time/memory/domain/DNS limits, Keystore storage, revision-gated account state, sanitized errors and bounded caches |
| Regression tests | Aggregated source, native, policy and existing domain suites; real migration, Media3 and sandbox instrumentation |
| Documentation/SDK | Architecture, migration, user guide, API/security spec, type declarations, authored offline sample and portable runner |

## Practical limits

Script API 1 remains compatible with its search, track, playback and lyrics contract.
API 2 optionally advertises typed catalogs, home/discovery feeds, account, library,
likes, collections and granular playlist operations. The host routes external
content through shared screens and overlays while preserving native Netease pages.
The portable SDK example is an offline test tone. The independently maintained
[QQ plugin](https://github.com/NEORUAA/MeiloX-QQMusic-Source) has its own fixtures and
transport implementation; the pinned host fixture is used for real isolated-runtime
and explicitly enabled live tests.

The API 2 extension adds no Room migration. Paged source queues preserve loaded
tracks and duplicate positions, stop fetching after account/provider changes, and
can resume through the optional safe snapshot-v3 content context. Stale script
invocations cannot start another HTTP hop or publish storage/audio results after
revocation. Ordinary optional-artwork failures do not discard catalog content.

No physical USB DAC/bit-perfect hardware acceptance or whole-app performance claim
is made from the AVD. Account creation/security changes, cloud uploads and remote
social mutations were not repeated against the user's live account. Their existing
code and automated domain regressions are preserved.

The management page and settings persistence were checked through ADB. Import opens
the system file picker. The AVD's file manager presented its first-use agreement;
that agreement was left unaccepted. Package installation and permission review
semantics were verified through the actual manager and sandbox instrumentation.

The final installed Debug also displayed real QQ home artwork, discovery charts
and chart tracks, and the user's created playlists. Feed and library categories
open from top-bar dropdowns. Native search rows use the standard continuous grouped
list. The source-detail push transition and Glasmio drag ordering were checked on
the AVD; the original fallback order, disabled switch and original-first setting
were restored afterward. QQ playlist and collection writes were validated with
offline protocol fixtures, without changing the user's live account.

The original AVD database was independently compared before and after the upgrade.
Its primary keys and record counts remained identical: 37 songs, 3 playlists,
15 likes, 31 history rows, 29 playback-count rows, 1 download task, 0 playlist-song
associations, 39 QQ lyric mappings and 16 cached lyrics. Offline paths/file facts and
QQ mappings also remained identical. The automatic-fallback switch survived process
restart and was restored to its initial disabled state after the UI check.
