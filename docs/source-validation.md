# Source refactor validation

Baseline: `56322350` (Room 18). Validation device: the existing arm64 HyperOS AVD
`emulator-5580`, Android System WebView 150.0.7871.181. The original application ID
`com.neoruaa.meilox` was updated with `adb install -r`; its data was backed up locally.
No account logout, remote social writes or destructive database reset was used.

## Automated checks

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -Pksp.incremental=false --console=plain
./gradlew :app:assembleRelease -Pksp.incremental=false --console=plain
node samples/music-source-plugin/test.mjs
```

- 913 JVM tests passed, compared with 873 at the baseline.
- Debug application and instrumentation APKs built successfully.
- The optimized Release APK built successfully, including R8 and vital lint.
- The portable offline plugin contract runner passed.
- Independently checked P1 and P2 commits compiled in detached worktrees.

AVD instrumentation uses the installed application and `androidx.test.runner.AndroidJUnitRunner`:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class com.ljyh.mei.source.SourceCompatibilityInstrumentedTest,com.ljyh.mei.playback.MediaUriProviderQualityTest,com.ljyh.mei.playback.PlaybackQueueIntegrationTest,com.ljyh.mei.source.script.ScriptPluginInstrumentedTest,com.ljyh.mei.ui.screen.search.SourceSearchInstrumentedTest,com.ljyh.mei.data.network.NeteasePlaybackIdentityInstrumentedTest,com.ljyh.mei.data.network.NeteaseSourceAuthInstrumentedTest,com.ljyh.mei.data.network.NeteasePlaylistRequestInstrumentedTest \
  com.neoruaa.meilox.test/androidx.test.runner.AndroidJUnitRunner
```

The selected 45 tests cover real SQLite migration/schema validation, legacy/current
Media3 snapshots, original-platform quality recovery, exact-byte rejection, real
ExoPlayer fallback, per-resource headers/UA, `NO_STORE`, download provenance and
generation isolation, queue controls, actual native search/detail/audio resolution,
real isolated JavaScript import/search/detail/fallback/audio/lifecycle, infinite-loop
termination, network denial, search cancellation and preference isolation.
All 45 passed in the final combined AVD run (7.729 seconds).

`MusicServiceQualityRecoveryTest` has three explicit isolated-package opt-ins. They
were skipped on the original application ID and are not counted among these tests.

## Static analysis

```sh
./gradlew :app:lintDebug -Pksp.incremental=false --console=plain
```

Full debug lint reported **37 errors and 280 warnings**. All 37 offending statements
also exist verbatim in the baseline: permission handling in recognition, Media3
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
| Browse/search/order/settings | Separate persistent preferences, source picker, management controls; real DataStore and process-restart checks |
| Ordered bounded fallback | Original-first/override/disabled policies, deduplication, gates, timeout/cancel/invalidation and byte rejection tests |
| Recording identity | ISRC validation, title/artist/duration/version/explicit guards, confidence/ambiguity threshold and manual binding |
| Origin actions after fallback | Queue metadata/ref unchanged; native-only actions use origin; remote reporting rejects foreign audio and downloaded copies |
| Actual audio provenance | Actual provider/instance/ref/quality/content cache keys and download fields; headers stay in memory |
| Untrusted-code isolation | Isolated V8, no Android/Room/account bridge; controlled HTTPS and opaque audio transport; runaway/denial/recovery tests |
| Permissions/limits/credentials | Size/time/memory/domain/DNS limits, Keystore storage, revision-gated account state, sanitized errors and bounded caches |
| Regression tests | Aggregated source, native, policy and existing domain suites; real migration, Media3 and sandbox instrumentation |
| Documentation/SDK | Architecture, migration, user guide, API/security spec, type declarations, authored offline sample and portable runner |

## Practical limits

Script API 1 advertises search, track details, playback and lyrics. Optional external
album/artist/playlist/account integrations are not advertised. Native Netease pages
provide their existing advanced functionality. The shipped example is an offline
test tone, not a verified QQ/Kugou service.

No physical USB DAC/bit-perfect hardware acceptance or whole-app performance claim
is made from the AVD. Account creation/security changes, cloud uploads and remote
social mutations were not repeated against the user's live account. Their existing
code and automated domain regressions are preserved.

The management page and settings persistence were checked through ADB. Import opens
the system file picker. The AVD's file manager presented its first-use agreement;
that agreement was left unaccepted. Package installation and permission review
semantics were verified through the actual manager and sandbox instrumentation.

The original AVD database was independently compared before and after the upgrade.
Its primary keys and record counts remained identical: 37 songs, 3 playlists,
15 likes, 31 history rows, 29 playback-count rows, 1 download task, 0 playlist-song
associations, 39 QQ lyric mappings and 16 cached lyrics. Offline paths/file facts and
QQ mappings also remained identical. The automatic-fallback switch survived process
restart and was restored to its initial disabled state after the UI check.
