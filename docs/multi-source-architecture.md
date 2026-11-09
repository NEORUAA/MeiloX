# Multi-source architecture

The host owns playback, persistence, shared UI and the independent multi-source lyric system. Installed providers own online catalog, account and service behavior. The current registry permanently contains local music; the default NetEase provider is a bundled, removable script package maintained in the separate [MeiloX-Netease-Source](https://github.com/NEORUAA/MeiloX-Netease-Source) repository. QQ is maintained in [MeiloX-QQMusic-Source](https://github.com/NEORUAA/MeiloX-QQMusic-Source).

```text
Original Compose presenters
  -> source-bound ViewModels / shared feature repositories
  -> SourceRouter / SourceFeatureClient
  -> installed provider capabilities
  -> isolated JavaScript service protocol
  -> restricted HTTP or explicit host SDK effects

Media3 / AutoMix / downloads
  -> typed content reference
  -> PlaybackResolver
  -> actual audio resource + opaque host handle

Player lyric selection
  -> host LyricRepository and lyric adapters
  -> independent NetEase / QQ / extended lyric lookup
```

## Identity and user data

`TrackRef`, `AlbumRef`, `ArtistRef` and `PlaylistRef` carry provider ID plus an opaque string identity. Account identity additionally includes the installed provider instance. Package IDs, provider IDs, instance IDs, account IDs and track IDs are distinct concepts.

Room is version 19. The additive 18→19 migration retains legacy primary keys, associations and offline paths while recording typed identity and audio provenance. Existing positive numeric NetEase IDs retain their storage/cache compatibility. External IDs use the reversible versioned codec; they are never hashed into numerical surrogates. Local files require their storage context. Historical negative podcast placeholders do not identify local music by themselves.

Queue snapshot v3 reads the existing v1/v2 formats. Content identity and original metadata remain authoritative even when another source supplies audio. Persisted locators retain non-secret provider/account/version facts; opaque remote cursors remain in memory. Restoring a paged queue verifies its saved prefix before recovering pagination. Repeated playlist recordings retain their positions.

Disabling/removing a provider leaves host playlists, history, downloaded audio and independent lyric data intact. New remote work fails with a typed source error. Existing resources follow their playback/cache policy rather than rewriting a queue item's original identity.

## Provider boundary

`ProviderRegistry` discovers capability interfaces for catalog/search, album/artist/playlist, feeds, accounts/libraries, likes, collections, editing, playback/quality, lyrics, features and links. `SourceRouter` checks capability declarations and provider-scoped references. There is no implicit NetEase fallback for an unsupported action.

`SourceFeatureClient` creates a screen-lifetime binding. It pins the provider object, installed instance/version and account revision. Changing the browse/search default cannot retarget a conversation, login, editor or upload already open on another source. `BoundSourceFeatures` validates calls and supports revocation watchers. Removing/replacing/disabling a source or changing its account cancels pending work and discards stale results.

The shared domain/presentation layer is in `source/shared/`. Account, cloud, podcast, wiki, comment, messaging, recognition and together-listening identities remain strings. Service thread prefixes, authentication sequences, request bodies and response projections belong to the plugin. Native filenames and old route keys retained for compatibility do not grant a built-in online provider.

## Original UI and navigation

Home uses the original recommendation and square playlist-card components, including cover captions, secondary titles, play-count details and snapping. Optional source-owned card metadata selects those existing presentation fields; service-specific tags, caption placement recipes and artwork processors remain in the plugin.

Playlist/album screens use the shared `SongListDetailScreen` presentation extracted from the original page. Artist screens share the original hero, header/actions, featured release and song/album cards. Providers select existing capabilities and data; they do not provide executable Compose UI or surrogate numeric IDs.

Advanced features reuse the existing account/security, cloud, podcast, statistics/history, wiki, comments, private-message, recognition and listening-together screens. Menus and write actions reflect the owning source's declared capabilities. Account/status data comes from the selected source rather than global NetEase preferences. Explicit provider queries survive both supported navigation hosts and ViewModel saved-state creation.

Home/discovery/search/library source switching uses top-bar actions and dropdowns. Source ordering uses the shared Glasmio drag list. Source management lives in the General settings page. Legacy route names are compatibility keys; destinations use the same provider-bound shared UI.

`SourceLinkRouter` extracts a bounded set of shared/pasted URLs. It dispatches only to ready providers declaring the exact host. When hosts overlap, the selected matching source owns the result. Content and feature targets retain the owner/account binding through navigation, and undeclared routes or foreign-provider references are rejected.

Catalog indexes, verified account identity, metadata/page caches and prefetching belong to the host, scoped to the provider instance, configuration and account revision. Optional `catalog.tracks` and complete playlist/album indexes let the host shuffle the full collection without waiting for every metadata page. Sources return service shuffle orders or normalized experiment responses; the host owns local ordering, history and fallback policy. First-page browsing requests only its metadata window, without whole-collection warming. Album metadata and pages reuse one typed snapshot; detail headers publish before the first song page completes. Protocol sessions and bounded continuations remain in private source storage, with obsolete cache keys removed through `discardStorageKeys`.

## Script runtime and host effects

The API 1/2 contract and declarations are documented in [source-plugin-spec.md](source-plugin-spec.md). API 2 gains optional `feature.invoke`, links, session identity and host effects while preserving API 1 behavior.

Each isolated operation has enforced heap, result, evaluation and HTTP-hop limits. Source configuration/storage is encrypted per package instance. Revision gates protect reads, network hops, storage writes, artwork and audio publication. Generic source HTTP has no account-bearing shared cookie jar. Declared exact or `*.` subtree network grants, concrete URL hosts, system-DNS address checks, HTTPS, redirect rejection and bounded bodies apply to both ordinary and SDK-coded requests. Subtree grants exclude their apex and match only descendants at a dot boundary. The DNS policy accepts public addresses and the system proxy's `198.18.0.0/15` Fake-IP range while retaining hostname/TLS checks and rejecting literal IP URLs. Transport and bounded response failures do not disable an otherwise valid source.

A feature returns a bounded `{hostEffect,continuation}` plan. `SourceFeatureExecutor` releases the isolate before invoking a host handler, then resumes the same action with a host-issued continuation token and structured outcome. The entire chain remains bound to provider/version/account/screen. In-flight handlers are cancelled on revocation. A final session transition is opt-in from named host login/logout/account-web flows, while pending effects remain pinned to the original account.

The host exposes closed file-picking/upload, WebView, QR/SMS and native SDK primitives. Unsupported handlers return `UNSUPPORTED`. Selected files become expiring, single-use opaque handles bound to the original execution context. No plugin receives a raw Android object, arbitrary native class invocation or unrestricted filesystem handle.

The cloud screen requests 30 raw rows for its first page and 200 for subsequent pages, advancing from the service offset rather than the deduplicated display count. Its normalized first-page cache belongs to the host and is bound to an immutable provider/version/account identity. Explicit refresh and upload/delete operations invalidate it, and retired reads cannot repopulate a newer generation.

Web effects use AndroidX WebKit profiles, source-owned cookies and a fixed prompt bridge. Direct Chromium/service-worker networking is blocked; host-owned resource and fetch/XHR/form mediation applies the approved network boundary. Page navigation may narrow exact or subtree network grants, while cookie import/export targets remain concrete hosts. The profile is destroyed when the effect ends. Missing multi-profile/document-start support does not fall back to the global CookieManager.

The vendor security SDK has process-global state without a verified reset API. The host serializes its work and fails closed for a second source-instance owner during the same process. Provider-private SDK storage remains namespaced. Switching that SDK to another owner requires a process restart.

## Removable default and credential migration

The default package uses canonical package ID `org.meilox.source.netease` and provider ID `netease`. That provider namespace is accepted only for the canonical package ID. The registry has no permanently registered built-in NetEase provider.

`BundledSourceProvisioner` checks the bundled package's exact SHA-256 before its one-time installation. Only this pinned first-install path can copy the former built-in account/device/session fields into the source's encrypted namespace. A previously imported package, even with the canonical ID, does not receive legacy credentials. The original stored music keys remain valid.

The default marker records completed provisioning, an existing external package, or explicit removal. Bundled upgrades require the previously recorded package contents to match exactly, preserve enabled state and encrypted account storage, and recover interrupted directory/marker swaps from a bounded transaction journal. Imported or modified installations do not enter this upgrade path. Uninstall writes the removal marker, preventing automatic reinstallation on the next launch. Normal reviewed import can install a source again. A damaged or unavailable default reports a recoverable startup error while allowing other installed sources, including QQ, to load.

Updates and ordinary package imports use the inspected-byte approval path. Configuration changes, replacement and uninstall invalidate source revisions. The pinned package hash and actual migration implementation live in `BundledSourceProvisioner`; verification evidence belongs in [source-validation.md](source-validation.md).

## Marketplace and lifecycle updates

`SourceUpdateManager` reads the community GitHub registry after initialization, verifies immutable package URLs and ZIP digests, and keeps repository trust bound to the installed payload. Automatic replacement requires no expanded authority or changed session contracts, preserves enabled/configuration state, and never reinstalls removed packages. Permission changes and unknown origins use the existing import review. The transaction journal finishes a complete replacement or restores its previous payload after process interruption.

`SourceUpdatePlaybackGuard` coordinates installation reservations with queue restoration and resource resolution. Current original/actual audio providers remain protected even while paused; cancellation releases pending resolution leases. Deferred updates remain available for a later startup or explicit review without a polling loop. Existing HTTPS audio handles retain their validated transport.

The host-owned marketplace transport uses system DNS routing for its fixed HTTPS raw GitHub endpoints. It retains hostname/TLS validation, redirect rejection and size/digest limits. Plugin requests additionally enforce their own reviewed domain grants and DNS address policy.

## Playback and independent lyrics

`PlaybackResolver` separates original content from resolved audio. Ordered source fallback, conservative matching, manual bindings, negative/positive caches, cancellation and invalidation epochs limit remote work. Ambiguous recordings require user selection. Original metadata and action targets remain tied to the original content provider.

Audio handles retain actual provider/instance/session provenance, expiry, headers and cache permission. Media3, AutoMix and downloads resolve through this boundary. Source-scoped cache identities prevent account/provider collisions. `NO_STORE` blocks persistent caching/download. Downloaded copies retain their recorded provenance without depending on a still-installed provider.

The host lyric subsystem remains independent. `LyricRepository`, lyric selection, matching, word timing, extended lyrics, local/downloaded sidecars and caches remain available across playback sources. NetEase content-plugin removal and browse/search selection do not remove or retarget lyric choices. Cross-service matching uses recording metadata; a numeric QQ ID is never forwarded as a NetEase lyric ID.

## Implementation and verification records

Historical P0–P6 commits established additive identity migration, queue compatibility, source playback, isolated imports and source management. The current refactor moves the remaining online feature entry points to removable providers while retaining those compatibility contracts.

Build, unit, sandbox, AVD, live-account and SDK checks have different scopes. Dated results and remaining acceptance work are maintained in [source-validation.md](source-validation.md); this architecture document tracks the current code boundaries.
