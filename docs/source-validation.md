# Source refactor validation

This report distinguishes the current removable-source refactor from earlier P6
and API 2 checkpoints. Earlier test totals and screenshots do not describe the
current build unless explicitly repeated below.

The compatibility baseline is `56322350` (Room 18). Device checks use the existing
arm64 HyperOS AVD, `emulator-5580`, and the original application ID,
`com.neoruaa.meilox`. Application updates retain data with `adb install -r`. The
user's AVD launcher and configuration remain unchanged.

## Current architecture

- Local playback remains a host capability. NetEase online business is supplied
  by a removable, separately maintained script source rather than a built-in
  native provider. Its reviewed bundled package supports initial provisioning
  and legacy-session migration; an explicit uninstall prevents automatic
  reinstallation.
- NetEase and QQ use the same host screens and original presentation components
  for browsing, search, details and the media library. Source-owned feature
  recipes also drive the shared account, messages, recognition, together-listening,
  cloud, podcast and other online flows. Capabilities determine which actions are
  available.
- The host supplies bounded source-scoped networking, cryptographic codecs,
  recording/fingerprinting, file and interactive effects. The source owns endpoint
  selection, request fields, protocol profiles and response interpretation.
  Interactive effects run outside the serialized script segment and resume only
  the owning source/account operation.
- Lyrics remain an independent host feature. The existing source chooser,
  manual QQ search and binding, NetEase lyric variants, phonetics, TTML, offsets
  and caches do not depend on the selected browsing/search/playback source or on
  installing the NetEase online extension.
- Provider-scoped string identities preserve arbitrary external IDs. Legacy
  database keys, local files, queue snapshots and user lyric bindings retain
  their compatibility readers.

The standalone producers are maintained in the separate
`MeiloX-Netease-Source` and `MeiloX-QQMusic-Source` repositories.

## Current verified results

Verified on 2026-11-07 against the final removable-source and marketplace changes.

| Check | Result |
| --- | --- |
| Host JVM suite | **800 tests passed**, no failures or skips |
| Final Debug build | Application and instrumentation APKs built and installed with data retained |
| Final optimized Release | R8 and release assembly passed with a 2 GiB G1 heap and one Gradle worker |
| Final AVD regression batch | **61 tests passed**, including real NetEase reads/resolve, QQ authenticated catalog/library and muted full-resource decoding |
| Runtime lifecycle | Rapid sandbox cancellation/reconnection and rejected artwork/audio metadata retain usable source capabilities |
| Source removal and compatibility | Earlier 10-test AVD batch passed actual NetEase removal, no automatic reinstall, and data restoration; final batch passed queue and account compatibility checks |
| Marketplace | Real GitHub index and both immutable packages downloaded and inspected on the user's unchanged Fake-IP network; installed payloads acquired the correct repository origins |
| Update transactions | Actual isolated Android installation exercised payload-based origin adoption, automatic upgrade, disabled/configuration preservation, changed-permission review, stale review rejection, hash refusal and no reinstall after removal |
| Interrupted updates and playback | JVM checks covered transaction recovery, automatic-update authorization and resolution leases; AVD restored the saved paused queue |
| Standalone producers | Both aggregated Node suites, builds and package checks passed; published package bytes match the registry SHA-256 values |
| Registry | Offline schema/contract validation and GitHub metadata workflow passed |

The earlier failing checkpoints were resolved before this final batch. NetEase's
missing image/audio CDN declarations were corrected with exact hostnames. Optional
artwork rejection no longer discards a valid page; rejected audio metadata fails
only that operation. Direct script network permission violations still fail closed.
The QQ favorite acknowledgement and canonical status readback, finite home feed,
and album total metadata were corrected without changing the shared presenters.

Foreground checks confirmed the original home cards, distinct discovery layout,
QQ library and album presentation, and the standard grouped source management and
market pages. The user's selected source and library style are restored after
checks; the paused queue is retained.

The JVM count is not directly comparable with the historical 943-test checkpoint.
Obsolete native repository and transport tests were removed with their
implementations; protocol assertions moved to the producers' aggregated suites.
Host lifecycle, mutation, paging, cache, playback and update behavior remains
covered by the host suites.

Builds run serially with one worker and in-process Kotlin compilation. Debug used
a 1.5 GiB heap; Release used a 2 GiB G1 heap. The user's AVD launcher, AVD settings,
LAN and proxy configuration were not changed. No full-lint result is claimed for
this refactor; historical findings below remain separate.

## Compatibility coverage

| Requirement | Current implementation and evidence |
| --- | --- |
| Removable online sources | NetEase and QQ script packages, source-scoped sessions and shared feature execution; actual NetEase removal/no-reinstall check passed |
| Existing online presentation | Shared original components, source-owned feed actions and presentation metadata, library-style preferences and top-bar category menus; current visual acceptance remains separate from compilation |
| User data and local playback | Additive legacy migration, unchanged keys/files and compatibility readers; current early AVD data-restoration batch passed |
| Player and queues | Typed origin references, legacy snapshot readers, bounded source queues, quality/fallback and FM behavior; current JVM suite passed |
| Independent lyrics | Host-owned full lyric workflow, manual bindings and caches across all selected content providers |
| Online feature ownership | Source recipes for account/security, social, recognition, together-listening, cloud, podcast and discovery; closed generic host SDK/effect contracts |
| Mutations and sessions | Source/account-pinned operations, serialized segment commits, explicit receipts and revocation guards; current JVM and producer contract suites passed |
| Network and artwork | Exact declared hosts, DNS/size/time limits, source-owned credentials, lazy artwork ownership and cancellation guards |
| Source settings | Separate browse/search preferences, General settings entry, top-bar dropdowns and Glasmio drag ordering |
| Origin after fallback | Queue metadata and original references remain stable; actions and reporting use the owning source rather than the fallback audio provider |

## Historical checkpoints

These results apply to the earlier implementations and packages named here. They
remain useful compatibility evidence but do not replace current acceptance.

### API 2 before removable NetEase

- The earlier API 2 extension passed **943 JVM tests** and **51 selected AVD
  tests**. That run preceded the artwork-cache and feed-background changes at
  `9e4beaa8` and `54b64dda`.
- Debug at `54b64dda` built and was installed over the original application.
  An optimized Release had passed before those two later changes. The first
  Release attempt was stopped during R8 because of host memory pressure.
- The pinned QQ 0.3 package (`0e88f488…`) passed real-isolate captured fixtures
  and explicitly enabled authenticated read-only catalog/library/playback tests.
  Those checks covered all four search types, feeds/categories/charts, detail
  navigation, personal collections and decoded full-resource playback. Saved QQ
  configuration was retained.
- A later pre-push run passed **40 AVD tests**, with one explicitly supplied
  real-audio fixture skipped. It covered legacy negative local IDs, serialized
  script storage commits and resumed downloads waiting for plugin loading. Both
  authenticated QQ acceptance tests then passed again.

At this historical stage, NetEase still used the built-in native provider and
native repositories. Descriptions of that ownership must not be read as the
current architecture.

The earlier original-application database comparison retained identical primary
keys and counts: 37 songs, 3 playlists, 15 likes, 31 history rows, 29 playback-count
rows, 1 download task, 0 playlist-song associations, 39 QQ lyric mappings and 16
cached lyrics. Offline paths/file facts and QQ mappings also matched. These are
historical comparison values, not a claim about today's account contents.

### Original P6 and lint

Before API 2, P6 passed **913 JVM tests** against the original 873-test baseline
and **45 selected AVD tests**. Its optimized Release completed R8 and vital lint;
the portable offline plugin runner and isolated P1/P2 compilation checks passed.

The historical API 2 full Debug lint reported **37 errors, 280 warnings and 5
hints**, matching the P6 error/warning counts. The 37 statements were verified
against the original baseline at that time. They were reported rather than hidden
with disabled checks or a broad lint baseline. These counts have not been rerun
for the current removable-source refactor.

## Acceptance boundaries

Compilation, offline producer fixtures, device instrumentation and visual checks
are separate evidence. No physical USB DAC/bit-perfect hardware
acceptance or whole-app performance claim is made from the AVD.

Account creation/security changes, cloud uploads and remote social writes were
not repeated against the user's live account. Their source recipes are exercised
by offline contract fixtures. Credentials, signed audio URLs and private account
identifiers are excluded from diagnostics and this report.
