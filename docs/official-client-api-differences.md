# Official Client API Differences

This is the running compatibility ledger for `official_client_parasite`. Update it when
an official interface, response, session lifecycle, or transport contract differs from
the standalone implementation. Keep the original MeiloX UI/UX and adapt underneath it.

Baseline: MeiloX `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`; pinned official TV
`com.netease.cloudmusic.tv` 1.1.80 (1001080). Observations below were made on 2026-09-29.
See [the migration record](official-client-parasite.md) for APK fingerprints, actual DEX
entry points, stage status, and the unresolved full-feature host capability gate.

Do not store credentials, account identifiers, QR payloads, full response bodies,
official decompiled source, or raw device logs here. A passing business code does not
prove HTTP status, completed playback, or final listening-statistics settlement.

## Ledger

### API-001: Route Labels Do Not Select Signing

- Original assumption: `/api/`, `/weapi/`, and `/eapi/` select different module-owned
  transport/signing paths; some requests supply `header` or `e_r` overrides and retry
  through an alternate signature path.
- Confirmed difference: `HostRequestBridge` sends all three labels to the same official
  business-request pipeline. Host identity, credentials, and signing are authoritative.
  This is an adapter contract, not a claim that the public wire protocols are identical.
- Adaptation: normalize legacy route prefixes, retain business parameter values, reject
  credential overrides and ambiguous query/body values, and remove alternate-signature
  retries. Retrying a mutation through another label could submit it twice.
- Evidence: `HostCallFactoryTest`; eight typed read-only AVD operations succeeded through
  the official pipeline. See [Retrofit Transport Adaptation](official-client-parasite.md#retrofit-transport-adaptation).
- The JSON bridge rejects binary request bodies. A later dual-runtime audit found that
  cloud upload also had a separate shared direct-NOS client, outside this bridge's
  checks. API-019 replaces that path with runtime-owned binary transports; official
  SDK device/server acceptance remains open. The earlier JSON rejection did not prove
  that every binary path was official-only.

### API-002: Parsed JSON Is Not an HTTP Envelope

- Original assumption: Retrofit responses and playback diagnostics expose an observed
  HTTP response status from the module's network call.
- Confirmed difference: the selected official entry supplies parsed JSON, not the raw
  HTTP envelope. The Retrofit wrapper's status is synthetic.
- Adaptation: mark the wrapper `official-json`; preserve business codes; report
  `hostAccepted` separately and leave the actual wire HTTP status unknown.
- Evidence: transport tests cover synthetic-response diagnostics and business failures;
  the AVD typed probe observed business code 200, not independently captured HTTP 200.
- Remaining: listening-report acceptance and later server aggregation require separate
  evidence; neither is implied by successful search, URL resolution, or playback.

### API-003: Login Is an Official State Machine

- Original assumption: WebView/manual Cookie input and module-owned Cookie/user-ID
  preferences establish the application session.
- Confirmed difference: the TV QR controller owns polling and the cookie-to-profile
  authorization continuation. A public user ID alone is not proof of authentication.
  The full official logout entry differs from its login-expiration handler.
- Adaptation: expose QR Bitmap/status and public session stamps only; retain credentials
  in the host. Retire observers and controller scopes on refresh/stop. Guard publication
  by attempt/session generation, including same-account reauthorization. Suppress the
  retired module-owned controller's queued global-logout cleanup so it cannot invalidate
  a newer session; do not intercept unrelated official controllers.
- Evidence: pinned DEX analysis, login/session unit tests, and AVD QR refresh, expiry,
  offline failure, background return, and process recreation. See
  [Official QR Login Slice](official-client-parasite.md#official-qr-login-slice) and later
  account/session checkpoints in that record.
- Remaining: canceling local polling cannot revoke authorization already accepted by the
  server. Real logout/account-switch acceptance requires user cooperation; test-double
  transitions must not be reported as real account acceptance.

### API-004: Search Metadata Is Optional

- Endpoint: `search/get/`; affected model: `SearchResult` in `GetSearch.kt`.
- Original assumption: the top-level `trp` object is always non-null.
- Confirmed difference: AVD search through the official pipeline produced a deserialized
  result without a usable `trp`. Page merging called Kotlin `SearchResult.copy`, which
  rejected that null before the original result list could render. This observation does
  not distinguish an omitted field from explicit JSON null, or establish a TV-only
  server difference.
- Adaptation: `trp` is nullable with a null default. It is not needed to display or merge
  any search category. No fabricated metadata is inserted.
- Evidence: controlled search fixtures omit `trp`; the corrected AVD renders all five
  result categories in the original pages. A bounded song-search scroll exposes more
  than the initial 30 rows. `SearchSessionTest` exercises merging all five categories
  without that metadata. See [Session-Owned Search](official-client-parasite.md#session-owned-search).
- Paired minified execution (2026-10-02): both original result pages render all five
  categories, expose 47 distinct visible song title/artist pairs and navigate through
  artist/album/playlist/podcast detail and Back. This qualifies those read/navigation
  paths, not all-category pagination or equivalent podcast relevance; API-032 retains
  the separate observed relevance difference. No shared UI change is introduced.
- Defensive compatibility, not a fully established server guarantee: optional category
  totals and `hasMore` determine paging when present; otherwise a full raw page permits
  another request. Offset uses raw response count, display rows deduplicate by ID, and
  a non-advancing page advertising more results becomes an explicit error. Tests cover
  missing metadata, duplicate rows, terminal empty pages, and retry without cursor loss.

### API-005: Bulk Podcast Loading Can Be Rate Limited

- Original assumption: repeated program-list reads can finish full-list search/selection.
- Observed behavior: the AVD's 792-episode podcast bulk read returned the official
  rate-limit message (too many operations; retry later). Initial rows loaded normally.
  The threshold, cause, and whether this differs from standalone behavior are unverified.
- Adaptation: stop automatic continuation on business failure, preserve an explicit
  failure/retry surface, and never cache a partial list as complete. Keep all original
  selection/search controls; do not hide them to imply completion.
- Evidence: [podcast checkpoint](official-client-parasite.md#session-owned-podcast-browsing) and
  controlled pagination/bulk-failure tests. Full 792-episode loading is not accepted on
  the device yet, and retrying aggressively is not a qualification strategy.

### API-006: Album Collection State Is Owned by the Official Account

- Original assumption: `AlbumDetailViewModel` reads the module's `AlbumsRepository` table
  to decide whether an album is collected and inserts/deletes rows after a generic
  `Resource.Success`, even if the returned business code rejects the mutation.
- Source-confirmed official contract: TV uses `tv-artist-page/album/get` with a `request`
  parameter containing JSON `{ "albumId": "..." }`. Its `AlbumDetailVO` exposes `id`
  and `collect`; the source declares only `id` for `album/sub` and `album/unsub`, without
  a module Cookie, user ID, or `checkToken` parameter. This is a session/state-source
  difference; the old `v1/album/{id}` read remains useful for MeiloX's richer song model.
- Adaptation: pair the original album read with the TV collection-state read under the
  same captured official session. Validate album identity and a present Boolean flag;
  absent/error state is not interpreted as uncollected. Guest albums remain readable,
  but collection writes require official authentication. Accept mutations only on
  business code 200; serialize writes and defer refresh until an in-flight write settles.
  Notify only the current account's Library after an accepted write, without using the
  unscoped legacy album table as an account cache.
- Source evidence: local TV `artist/albumlist/a/a`, `artist/albumlist/b/b`,
  `artist/albumlist/bean/AlbumDetailVO`, and the shared resource-detail `RequestParam`.
  These are interface/schema observations, not new reflection hook names. No decompiled
  source or account response is included in this repository.
- Validation: 15 album session tests, five repository tests, two transport tests, and a
  Library invalidation test pass within the 420-test debug suite. They cover both flag
  values, malformed/error responses, captured-session dispatch, stale publication,
  business-code rollback, duplicate suppression, refresh ordering, and cancellation.
  The AVD reads a matching album ID and present uncollected flag through the official
  pipeline; refresh and background return preserve the original detail presentation.
  See [the album checkpoint](official-client-parasite.md#session-owned-album-details).
- Remaining: real collection changes, relogin, and account switches are user-coordinated
  acceptance. Source and substitute tests do not establish live mutation acceptance;
  canceling an old request cannot undo a server mutation already accepted.

#### Dual-Runtime Collection State (2026-09-30)

- `CatalogCollectionBackend` now owns runtime-specific album/artist collection reads.
  The shared album Repository, ViewModel and screen still expose the same Boolean
  state. The TV route and response DTO move to `src/parasite`; its request envelope,
  ID check and nullable `collect` handling are unchanged.
- Standalone uses the existing `/api/album/sublist` contract from baseline
  `ApiService`/`UserRepository`, not the TV endpoint or unscoped `AlbumsRepository`
  table. It requests `limit=100`, `total=true` and offset pages under one captured
  Cookie/session, returning true on a matching album or false only on a valid final
  page. Offsets advance by raw row counts; overlapping pages may advance but an empty
  or non-advancing nonterminal page fails instead of looping or reporting false.
- This is a backend state-source adaptation, not a literal restoration of the old
  cache-based flag. Missing/malformed lists, IDs, Boolean cursors and business errors
  are errors. It neither writes the legacy album table nor treats another account's
  cached membership as authentication. No arbitrary first-page cutoff is used.
- Both runtimes keep the existing common `album/sub` and `album/unsub` mutation
  contracts. Shared reads validate positive IDs and the session before and after
  dispatch; guests do not query private state. The account-owned Library refresh and
  original frontend remain unchanged. New source/substitute proof does not qualify
  live Cookie reads, cross-account device behavior or real album mutations.
- Verification: standalone substitutes cover multi-page matches/absence, overlapping
  progress, early positive termination, final empty pages, stalled cursors, malformed
  flags/IDs/lists, business rejection and mid-pagination session invalidation. The host
  substitutes preserve its exact envelope and reject missing flags/wrong identities.
  Shared tests cover guest/invalid/stale/late reads and cancellation. These pass in
  the paired 598/668 JVM suites; both debug and minified release builds pass. Release
  DEX retains the required Retrofit annotations and nullable list projection schema.

### API-007: Playlist Collection Uses Host-Generated Security Parameters

- Recorded: 2026-09-29.
- Original implementation: `playlist/subscribe` receives a fixed module `checkToken`;
  unsubscribe shares its security-header path. The old ViewModel deletes the local row
  after unsubscribe even when the server rejects it.
- Official TV source: the collection task sends `id` and a freshly obtained official
  `checkToken` to `multi/terminal/playlist/subscribe`; unsubscribe uses
  `multi/terminal/playlist/unsubscribe` with `id` only. The security helper delegates to
  the official security service and can return an empty string when disabled by host
  configuration. An empty official result is not replaced with a fabricated token.
- Adaptation: use the TV routes; keep token generation inside `TvHostRequestBackend`.
  Module callers cannot override that field on these routes. No token is persisted,
  logged, exposed as session state, or returned to a Repository. Accept business code
  200 only, serialize detail-page collection writes, and refresh the current account's
  Library only after acceptance. A rejection restores the previous detail flag and does
  not delete a shared local row.
- Evidence: local official collection-task source and APK DEX confirm the generator
  `com.netease.cloudmusic.h1.y.a.a(): String`. This is the actual DEX name, not the JADX
  alias. Parameter-policy, Retrofit, Repository, and ViewModel tests cover adaptation,
  missing generator failure, obsolete owners, and failure rollback.
- Remaining: live collection/uncollection and the generated token's server acceptance
  are not qualified. No real account mutation is performed by these tests. Host security
  initialization is retained; it is not emulated or bypassed by the module.

#### Dual-Runtime Revision (2026-09-30)

- A shared `PlaylistCollectionBackend` now separates this operation's runtime-specific
  paths and security parameters. `PlaylistRepository` and the existing ViewModel retain
  one interface and one frontend; runtime DI selects the adapter without UI flavor checks.
- Standalone restores the recorded `main` contract: `/api/playlist/subscribe` and
  `/api/playlist/unsubscribe`, both with the EAPI and check-token control headers. The
  original interceptor rewrites their wire paths to `/eapi/playlist/...`. Subscribe
  includes the existing standalone constant in its body; unsubscribe omits the body
  token but retains the original anti-cheat header/Cookie policy. These are baseline
  compatibility parameters, not newly generated official SDK credentials or a claim
  that the server will accept them for every account.
- Parasite retains `/api/multi/terminal/playlist/{subscribe,unsubscribe}` and submits
  only the numeric ID to the official bridge. Its host-owned token generation and
  rejection of caller-supplied security parameters are unchanged. No Cookie backend
  fallback is added. The TV paths are removed from the shared `EApiService`.
- Both adapters carry the captured `SessionStamp` as a Retrofit tag, not request JSON.
  The shared action validates a positive ID, current authenticated non-anonymous owner,
  cancellation, post-response ownership and code 200. The accepted-write-only Library
  refresh and rejected-write rollback remain unchanged; the old local-row deletion
  behavior is not restored.
- Scope: album and artist collection reads still use TV-specific shared endpoints and
  need separate dual-runtime adaptation. This playlist change does not qualify those
  reads, standalone login, real mutations or the complete D4 feature matrix.
- Evidence: paired JVM suites pass (585 standalone / 659 parasite tests, with common
  tests counted in each). Standalone wire substitutes verify both encrypted routes,
  numeric IDs, original token/header policy and captured Cookie ownership; stale
  owners and late responses are rejected. Host substitutes retain official routes and
  host-only security generation. Both debug/instrumentation and minified release builds
  pass; release DEX preserves Retrofit body/session annotations and the appropriate
  route only, and both release APKs pass 16 KB alignment. No device or real account
  mutation test is included in this checkpoint.

### API-008: Playlist IDs, Not Returned Row Counts, Own the Cursor

- Recorded: 2026-09-29.
- Original assumption: initial `tracks` occupies the first contiguous positions in
  `trackIds`; subsequent pages advance by the number of returned songs. A name ending
  in the liked-music suffix bypasses paging. Full-list search silently drops missing IDs.
- Compatibility constraint: the detail's `trackIds` defines order, while song-detail
  responses may omit unavailable entries. This is a correction to the old adapter's
  assumptions, not a claim that TV alone returns sparse or differently ordered data.
  MeiloX retains its richer `v6/playlist/detail` and `v3/song/detail` models through the
  official request pipeline; a requested detail size is not proof of complete data.
- Adaptation: deduplicate authoritative IDs, resolve each requested ID range against the
  initial snapshot and missing-song reads, reorder by requested ID, and advance by the
  requested range even if no song is returned. Business failures remain retryable errors.
  All playlists use this path irrespective of their display name. Full-list search and
  bulk loading reject incomplete snapshots rather than presenting partial results as
  complete; search failure/retry stays in the original list using existing glass controls.
- Session handling: details, pages, search batches, detail-page URL reads, and collection
  requests carry their captured owner. Page/account replacement invalidates presentation
  and rejects late work. Search snapshots are retained only after complete validation;
  obsolete UI collectors cancel their Paging loads rather than caching them for the
  lifetime of the ViewModel.
- Evidence: controlled tests cover sparse initial data, reversed/duplicate/extra rows,
  empty intermediate pages, complete search beyond 400 IDs, partial search retry, owner
  changes, and cancellation. On-device qualification is recorded in the migration log.
- Follow-up: the shared playlist picker and guarded write implementation are recorded in
  API-009/API-010; daily recommendations and complete account-owned liked reads are
  recorded in API-011/API-012. Real write acceptance, player heart-state ownership,
  and downstream downloads remain incomplete.

### API-009: Playlist Track Writes Use Business Codes, Not Cached Counts

- Recorded: 2026-09-29.
- Original implementation: `playlist/manipulate/tracks` sends `imme=true`; a successful
  add is inferred by comparing response `count` with a locally cached playlist count.
  Duplicate handling also requires one exact localized message. The player and song
  lists maintain separate, unscoped picker caches limited to an initial 100 playlists.
- Official TV evidence: local `com.netease.cloudmusic.app.f0.b` sends `op=add`, `pid`,
  a JSON string array in `trackIds`, `reverse=true`, and the official security helper's
  `checkToken` to `v1/playlist/manipulate/tracks`. It treats code 200 as accepted and
  code 502 as already present, without comparing counts or requiring message text.
  It separately reads optional `offlineIds` as a JSON array.
- Adaptation: preserve the official add parameters and generate security fields only in
  the host backend. Deduplicate and validate input IDs; carry the captured session on
  every write. Optional count metadata is not a success oracle. Code 502 has a distinct
  duplicate outcome; code 200 with offline IDs produces a partial-result notice, not an
  unqualified all-songs-added notice. This defensive notice does not establish the exact
  server meaning or completeness of every offline-ID response.
- Integration: both original picker surfaces now use the account-membership Library
  cache and its full-pagination refresh. Only playlists created by the current account
  are selectable. Guest writes, stale picker owners, duplicate clicks, and late mutation
  callbacks are rejected. Accepted writes request a same-owner Library refresh; refresh
  failure cannot turn an accepted server write into a failed write or trigger a retry.
- Remaining: the `op=del` supplemental call uses the same route without add-only reverse
  ordering. No corresponding TV delete-song caller was found in the inspected source.
  Controlled tests cover it, but live add/duplicate/remove acceptance remains pending.
  Cancellation prevents obsolete publication; it cannot undo a server-accepted write.

#### Dual-Runtime Update (2026-09-30)

- `PlaylistTracksBackend` now owns the runtime-specific route and payload. The shared
  Repository validates add/delete operations and positive playlist/song identities,
  removes duplicate song IDs in input order, and rejects guest, stale and canceled
  results. Existing pickers, result handling and Library refresh remain unchanged.
- Standalone restores `/api/playlist/manipulate/tracks` from baseline
  `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`, with string `pid`, a JSON string array
  in `trackIds`, and `imme=true` for both operations. Its captured Cookie is signed by
  the standalone transport. No TV `reverse` field or host token generator is used.
- Parasite retains `/api/v1/playlist/manipulate/tracks`, the same ID encoding and
  `reverse=true` only for adds. Official security fields remain generated inside the
  host transport. Delete is still a supplemental contract, not a source-confirmed TV
  caller. Neither implementation retries an uncertain write through another route.
- Both adapters preserve business codes and optional count/offline-ID fields for the
  shared caller. Counts do not establish success; a partial result or duplicate does
  not become an unconditional success because the transport returned HTTP 200.
- Substitute tests cover both operation payloads, route isolation, standalone Cookie
  ownership, raw business/partial results, guest/invalid/stale rejection and late
  responses. These are contract checks, not real add/remove/duplicate acceptance;
  no user playlist is modified in this increment.

### API-010: Playlist Creation Returns a Nested Playlist Identity

- Recorded: 2026-09-29.
- Official TV evidence: local `com.netease.cloudmusic.i0.f.a` sends `name`, numeric-text
  `privacy`, `type`, and the official `checkToken` to `playlist/create`. On code 200 it
  reads the nested `playlist` object. Codes 507 and 400 have separate failure paths.
- Adaptation: host-generated security only; retain privacy 0/10 and the original type
  choices. Require business acceptance and a positive created-playlist ID; a nested ID
  is sufficient, while conflicting nested/top-level IDs are rejected. Missing optional
  top-level ID or an error response without a playlist must not crash the decoder.
  Both original creation sheets use the same captured-session write path and feedback.
- Supplemental deletion: `playlist/remove` with JSON `ids` is retained from MeiloX,
  now with a mandatory owner tag and checked business code. No equivalent TV caller was
  found in the inspected source, so it is not documented as a verified official TV
  contract. The ViewModel rejects foreign, missing, and liked-playlist targets and does
  not remove local rows on failure. No new deletion UI is introduced.
- Evidence: source inspection plus parameter, decoder, ownership, cancellation, and
  failure tests. Live create/delete and returned-state reconciliation are pending user
  authorization to operate only on a temporary private test playlist. No user playlist
  or account response is copied into this ledger.

### API-011: Daily Recommendations Retain the Official Normal Scene

- Recorded: 2026-09-29.
- Original implementation: sends an empty body to `v3/discovery/recommend/songs`, assumes
  a first song exists for the cover, and retains results without an official-session
  owner. Playback actions can reuse stale results after account replacement.
- Official TV evidence: `com.netease.cloudmusic.app.n.c(int)` sends `ispush=false`,
  `limit=30`, and the supplied `trialMode`; its parser reads `data.dailySongs`. The normal
  daily redirect supplies `FreeTrialScene.NORMAL`, whose value is 1. These names are
  resolved from the local TV source; `C2538n` is only its JADX file alias. Normal-scene
  parameters do not replace or bypass official playback permission checks.
- Adaptation: retain those parameters through the host request pipeline, with a mandatory
  captured-session tag. Require business code 200 and a valid song list; an empty list
  is valid. Optional empty translation arrays are safe. Leaving the page cancels its
  read; invalidation clears results, and late replies cannot restore obsolete data.
  Search and queue selection stay in the original page. Playback uses the full validated
  response, not only the filtered rows, and rejects obsolete snapshots.
- Evidence: parameter, Retrofit-tag, decoder, empty/error/retry, cancellation, and
  session-replacement unit tests pass. The AVD returned 33 songs on the first visit and
  30 on re-entry despite the same requested limit. The cause of that variation is not
  established; the client does not truncate valid returned rows to 30. Selecting the
  filtered second song retained the full 33-song queue and selected index 1.
- Remaining: real account replacement, device network-failure recovery, complete audio,
  and server listening-statistics acceptance are not qualified by this checkpoint.

### API-012: Liked Library Reads Need an Owner and a Complete Snapshot

- Recorded: 2026-09-29.
- Original implementation: a display name ending in the liked-music suffix writes a
  global Like table. Library reads lack a captured owner and fall back to the initial
  `tracks` subset when full-song loading fails. Neither a title nor a partial response
  proves the current account's complete liked collection.
- Compatibility constraint: this is an ownership/completeness correction to MeiloX,
  not a newly discovered TV endpoint. It retains the account-membership identification
  and supplemental detail/song-detail contracts described in API-008, all through the
  official request pipeline.
- Adaptation: require an authenticated owner, its marked liked-playlist membership,
  matching cached creator, and matching returned creator. Every detail and missing-song
  batch carries the same session tag. Complete results follow authoritative track IDs;
  missing songs and failed batches remain retryable errors, never successful partial
  lists. Membership metadata changes reload the list even when its ID is unchanged;
  replaced or canceled reads cannot publish late results. Remove the title-driven
  global-table write without deleting the existing database schema or user data.
- Evidence: tests cover 405 songs across three detail batches, ordered results, zero
  songs, missing rows, foreign/guest/unmarked memberships, changed server ownership,
  invalidation between requests, cancellation, and Library refresh/replacement. The
  AVD displays the existing four-song liked collection after installation and return
  from daily recommendations, using the unchanged Library page.
- Remaining: no live like/unlike mutation is performed. The player's control is adapted
  separately in API-013; neither checkpoint qualifies real mutation acceptance.

### API-013: Ordinary Song Favorites Are Not FM Radio Likes

- Recorded: 2026-09-29. Host: TV 1.1.80.
- Original MeiloX: `song/like/check` queries one ID; `radio/like` writes with hardcoded
  `alg=itembased` and `time=3`. The player assumes unknown means unliked, ignores the
  mutation business code, and toggles its icon without account or selection ownership.
- Official ordinary-song contract: runtime `i0.f.a.H` posts `song/like`, using `trackId`,
  `like`, `userid` (the private-cloud owner, zero for ordinary catalog songs), optional
  `userActionMap`, and host-generated `rqRefer`/`checkToken`. Code 200 requires
  `playlistId`; the official async caller also treats 502/404 as completed operations.
  Other business failures, including 505/506/511/512, are not generic success.
- Official read: `i0.f.a.l0` requests `song/like/get`; non-200 fails, null `ids` is an
  empty snapshot. The official implementation also stores a checkpoint for its delta
  refresh. The module uses full snapshots and does not alter the host checkpoint.
- Separate FM contract: `i0.f.a.s`/`f` use `v1/radio/like`, returning replacement radio
  songs with playback-time/algorithm/recommendation context. This is not the ordinary
  favorite contract and is not substituted with invented `time` or `alg` values.
- Adaptation: mandatory captured-session tags on full reads and ordinary mutations;
  current-session and cancellation checks before dispatch and after parsing; validate
  business codes and identities. The host token generator `h1.y.a.a` and referer
  provider `s0.l.a.A().h()` run inside the pinned host backend, not the UI or DTO.
  Null host referers are omitted, and caller-supplied referer/security overrides fail.
  Duplicate/missing responses re-read authoritative state rather than toggling blindly.
- Player behavior: retain the original star symbols, dimensions, glass, and layouts.
  Selection revisions reject stale callbacks even after switching away and back to the
  same song. Invalidation clears account state synchronously; obsolete requests are
  canceled and late results ignored. Unknown/failed state permits read retry only;
  duplicate pending clicks do not write again. Accepted changes notify the existing
  Library refresh flow; refresh failure does not undo an accepted favorite result.
- Evidence: repository/transport/state tests cover ordinary payloads, null/failed
  snapshots, guest/stale owners, business failures, duplicate reconciliation, cancellation,
  repeated clicks, delayed old reads/writes, track replacement, account replacement,
  same-account reauthorization, recovery, and refresh failure.
  `apkanalyzer dex code` confirms runtime `i0.f.a.H`, `i0.f.a.l0`, and `s0.l.a.A`/`h`
  in the pinned APK. The AVD displays a filled star for an existing liked song, then an
  outlined star for FRAGILE after selection, without touching either favorite button.
- Remaining: live like/unlike acceptance is not performed without explicit test-write
  approval. Full-ID snapshots are currently fetched on selection, without a shared
  account cache. Private-cloud library-ID/owner translation and FM queue semantics need
  separate migration; the original metadata does not yet carry those host fields.
  Podcasts and device-local files do not dispatch ordinary song-favorite requests.
  API-023 subsequently adds private-cloud source selection and favorite argument
  ownership; real cloud-write acceptance and privilege qualification remain open.

#### Dual-Runtime Update (2026-09-30)

- `SongFavoritesBackend` separates state reads, mutations and response interpretation
  from the shared player Repository. Player/Library UI, selection ownership, serialized
  writes and refresh behavior remain single-source. Authenticated non-guest owners
  and positive song IDs are required; cancellation and late account results cannot
  publish a new star state.
- Standalone restores the baseline `/api/song/like/check` query with one ID encoded in
  `trackIds`. Code 200 must contain an ID list containing only the requested identity
  (an empty list is valid). A missing list or unexpected ID is unknown/error, not false.
  Parasite keeps the official full `/api/song/like/get` snapshot, including its distinct
  null-as-empty rule. That host response rule is not applied to the standalone query.
- Standalone preserves the original `/api/radio/like` compatibility request: string
  `trackId`, Boolean `like`, `alg=itembased`, and string `time=3`. These are legacy
  request fields, not measured playback duration or a verified official FM context.
  This restoration does not claim that the legacy route is equivalent to the official
  ordinary-song contract. Response replacement songs remain unused, as in baseline.
- Parasite retains `/api/song/like`, numeric `trackId`, Boolean `like` and `userid=0`
  for ordinary catalog songs; its referer/security fields stay host-owned. It does not
  borrow the standalone radio route or its compatibility fields.
- Both mutations require a positive `playlistId` on code 200. Only the host adapter
  reconciles 502/404 with one authoritative snapshot read, without repeating the write.
  Standalone reports those codes as failures rather than importing host semantics or
  restoring the old unchecked optimistic toggle. Unknown writes never fall back to
  the other protocol/runtime.
- Shared and flavor substitute tests cover the exact requests, null/malformed/rejected
  responses, duplicate reconciliation, session invalidation, Cookie replacement and
  cancellation. No real like/unlike, account switch or AVD instrumentation is performed
  for this increment; live standalone reads/writes still require scoped validation.
- Paired verification: 608 standalone and 678 parasite JVM tests pass, alongside both
  debug/instrumentation and unsigned minified release builds. Release DEX retains the
  favorite/track Retrofit annotations, response generics and nullable fields, and
  contains only the matching runtime's routes. Both APKs pass 16 KB ZIP alignment.
  This also covers the API-009 extraction; it is not device or real-write acceptance.

### API-014: Artist Collection State Is Separate From the Linked User

- Recorded: 2026-09-29. Host: TV 1.1.80.
- Original MeiloX: the artist header reads `artist/head/info/get` and derives the
  button state from `data.user.followed`. Its writes use `artist/sub` or `artist/unsub`
  with both `artistId` and `artistIds`. Requests, follow callbacks, and complete-song
  pagination do not capture a shared account/artist owner.
- Official contract: runtime `tv.artist.artistdetail.b.a.a` reads
  `tv-artist-page/artistdetail` with `artistId`. Collection is the artist's own
  `data.artistDetail.followed`, not the linked account's follow state. Its `b` method
  posts `v1/artist/sub/` with one `artistId`; `c` posts `artist/unsub` with JSON
  `artistIds`. The datasource formats the latter as `[id]`. DEX annotations in the
  pinned APK confirm these routes and parameter names. The common bridge normalizes
  the trailing slash, as it does for other business routes.
- Adaptation: keep the existing rich header, hot-song and album DTOs, and original
  `artist/head/info/get`, `v1/artist/{id}`, `artist/albums/{id}`, `v1/artist/songs`
  operations as supplemental business requests through the host transport. No TV
  page replacement or reduced catalog substitutes for the original MeiloX views.
  The complete-song body retains `limit=100`, `order=hot`, `private_cloud=true`, and
  `work_type=1`. Artist collections read the official TV field and use the two exact
  official mutation payloads. Every request requires a captured session tag.
- Validation: non-200 or malformed responses fail instead of becoming empty/unfollowed
  results; a missing optional artist detail still reaches the existing unavailable
  view. Empty final song pages are valid. An empty/non-advancing nonterminal page
  becomes a retryable error; append failures preserve rows and offset. Overlapping
  pages deduplicate in order while advancing by the raw response length.
- Ownership: account invalidation clears both pages synchronously; cancellation plus
  artist/revision checks discard late results. Follow writes serialize; a refresh
  during a write waits for completion. Unknown or failed follow state retries a read,
  and guests never submit collection changes. Stale page callbacks cannot start a
  queue, open a track menu, or navigate using the previous artist/session.
- UI: keep the original hero, metadata, star-independent follow button, hot-song rows,
  album carousel, complete-song list and routing. Optional missing aliases/biography
  are tolerated. Error retry stays in the existing section error area.
- Runtime reads: the pinned TV process rendered the original artist header and
  catalog. Scrolling the full-song list and selecting an appended item grew the
  MeiloX queue from 100 to 300 songs. Artist-to-album navigation also rendered.
  These checks do not establish the full catalog size or live collection mutations.
- Remaining: live follow/unfollow writes require explicit test authorization and are
  not automatically performed. The original album carousel still loads its first
  50-entry response; this checkpoint does not claim full album pagination, release
  runtime qualification, or completion of every artist/account workflow.

#### Dual-Runtime Artist Collection (2026-09-30)

- `CatalogCollectionBackend` separates artist state and writes from the shared
  `ArtistRepository`; TV endpoints/DTOs are now parasite-only. The official adapter
  preserves `data.artistDetail.followed`, the identity check, `v1/artist/sub/` with
  only `artistId`, and `artist/unsub` with only the JSON `artistIds` array.
- Standalone reads `/api/v1/artist/{id}` with the existing `GetArtistSong` parameters
  (`limit=50`, `offset=0`, `total=true`) and checks its `artist.id`/`artist.followed`.
  This field already exists in the original `ArtistSong.Artist` contract; it is not
  `data.user.followed` from the rich header. A narrow nullable response projection
  avoids turning an omitted Boolean into Gson's primitive false. Missing/non-Boolean
  flags, wrong IDs or non-200 responses fail; linked-user follow state is ignored.
- Standalone writes restore the original `MeloXRepository.setArtistFollowed` primary
  WeAPI route (`/weapi/artist/sub` or `/weapi/artist/unsub`) on `music.163.com`, with
  numeric `artistId` and JSON `artistIds` in both directions. Its own interceptor and
  captured Cookie sign the request; it does not borrow official host credentials.
- Deliberate safety difference: the original generic helper retried failed writes
  through EAPI, including failures with an uncertain server result. This adapter does
  not add that application-level protocol retry. Business rejection is returned to
  the shared acceptance check, and transport failure remains an error. This is not
  a promise of server-side exactly-once execution or rollback after cancellation.
- Shared serialization, code-200 acceptance, owner/revision checks and existing UI
  retry behavior remain intact. Account authorization and real follow/unfollow
  acceptance remain unqualified; all new write tests use substitutes only.
- Verification: substitutes cover true/false flags independent of the linked user,
  missing/malformed flags, wrong identities, exact runtime-specific routes/payloads,
  Cookie ownership, rejected/uncertain writes and obsolete owners. Shared tests reject
  late and canceled collection results. The 598 standalone / 668 parasite JVM suites
  and both debug/instrumentation/minified-release builds pass. Actual release DEX
  retains body/session annotations, generic signatures and nullable artist fields;
  TV collection routes are absent from standalone and the standalone WeAPI mutation
  route is absent from parasite. Both releases pass 16 KB alignment. No device or real
  account write is part of this checkpoint.

### API-015: Playback Sources Belong to an Official Authorization

- Recorded: 2026-09-29. Host: TV 1.1.80.
- Original MeiloX: playback V1 requests use numeric JSON `ids`, requested `level`,
  `encodeType=flac`, and `immerseType=c51` for sky quality. URL-cache keys have a song
  and quality but no account generation; persistent media-cache keys have no account.
  Downloads and automatic cache also resolve through the playback URL operation.
- Official contract: actual DEX `audio.player.i.a.c` builds quoted `songId_userId`
  tuples, `level`, `encodeType=aac/mp3`, `trialMode`, and optional `sceneParams` for
  `song/enhance/player/url/v1`. It can add source, effects, immersive, experiment and
  client-capability parameters. Downloads instead use `song/enhance/download/url/v1`
  with a single `id=songId_userId`; their `data` is an object, unlike playback's list.
  The pinned APK DEX confirms the route strings and parameter names; JADX's renamed
  source class is not used as a hook target.
- Current adaptation: keep the original numeric-ID/flac business request through
  the host's existing signing/authentication/network bridge. Ordinary-song playback
  and quality changes work on the logged-in AVD. Every V1 caller now requires an
  explicit session tag, including album/playlist download preparation, the player,
  and delayed automatic cache. The unused legacy unowned URL endpoint is removed.
  This does not establish parity for cloud-owner tuples or official download rights.
  API-017 subsequently replaces the download callers with the dedicated official
  download operation; ordinary playback resolution remains as described here.
- URL ownership: one captured identity/generation spans all quality attempts and
  publication. Invalidation clears URLs, including same-account reauthorization.
  Canceled, recovery-required, or stale results cannot populate the current cache.
  Fallback remains limited to successful responses without a full matching source;
  network/authentication/malformed responses fail. Trial sources are never accepted
  as full songs. Expiry starts at request dispatch with a 30-second safety margin;
  explicit short/zero lifetimes are not artificially extended.
- Byte ownership: host stream caches use `meilox-host-media-v1` plus a hash of public
  account identity, server-returned quality and source identity. No credentials are
  included. Complete-cache lookup and recovery removal cannot select another account's
  entries or legacy unowned entries. Same-account bytes survive process recreation;
  legacy bytes and existing downloads are not deleted or migrated. Local files and
  the current account's completed cache remain usable during login recovery; acquiring
  a new online source still requires a usable current authorization.
- Service behavior: invalidation stops the old player source and resets AutoMix and
  preload work without starting a new source. Queue construction, delayed automatic
  cache and source recovery are canceled; late metadata cannot update the replacement
  queue. Pending history submissions are discarded, not finalized under a new account.
  Queue contents and original player/quality-menu UI are preserved.
- Evidence: 13 resolver tests cover stale/noncooperative/canceled responses, recovery,
  expiry, fallback and cache identity. Two device tests use substitute identities and
  isolated temporary files/cache to exercise actual Android Uri and SimpleCache paths.
  AVD quality changes retain position and the 4:33 timeline; audio-output activity and
  advancing playback were observed. No real account change or download was performed.
- Remaining: download endpoint/permissions and worker ownership, cloud-song owner
  semantics, FM/queue request ownership, final official reporting, complete AutoMix
  and effects acceptance, and release runtime qualification. Stopping old sources is
  not a promise of synchronous removal of already-decoded audio frames. Substitute
  tests do not qualify real logout/account switching, audible quality, or server totals.
- Standalone follow-up (2026-09-30): the Cookie-authenticated debug app played a
  236,434 ms catalog source through its natural next-track transition. Pause retained
  the 61,592 ms position; resume, background operation, screen-off playback and media
  notification entry worked. The next song also resumed after changing the existing
  quality menu from exhigh to standard without resetting its 18,615 ms position.
  AudioFlinger identified an active, unmuted 44.1 kHz PCM track owned by standalone;
  this is output-pipeline evidence, not confirmation of audible AVD sound or quality.
  A new opt-in device read test verifies a matching full, non-trial playback source
  with a usable lifetime through the actual graph's service. No download grant was
  requested. ABI-011 records the cold-restore failure found during this qualification.
- Private-cloud source follow-up (2026-10-01): API-020 now retains separate cloud
  entry, audio, file-owner and account-affinity identities. Playback sends the quoted
  `audioId_fileOwnerId` tuple and validates the returned numeric audio ID, not the
  deletion/UI entry ID. Ordinary standalone/catalog numeric requests and their quality
  policy are unchanged. No cloud failure falls back to the ordinary entry ID.
- The full source identity reaches MediaItem loader keys/platform extras, AutoMix,
  source recovery and account-scoped byte caches. Queue snapshot v3 persists this
  credential-free key and rejects malformed/conflicting keys on restore. Old v1/v2
  ordinary snapshots remain readable; old private-cloud snapshots without source
  identity cannot be qualified or retroactively assigned a file owner.
- Cloud local-file reuse requires a completed task with the same source key and
  account, including during session recovery. A numeric-ID collision or different
  cloud owner cannot adopt an ordinary/other-owner file. Substitute tests cover distinct
  entry/audio/file-owner/account IDs, loader boundaries, cache separation, snapshot
  restore and account changes. The shared frontend/navigation is unchanged.
- Live checks: standalone's existing Cookie account returned cloud entries and full
  playback metadata through the original player route. After the user populated the
  separate official test account, its cloud row rendered in the original Library and
  played through a 123,871 ms timeline with natural repeat. Advancing MediaSession
  positions, an active unmuted 48 kHz PCM output track and a distinct cloud byte-cache
  key were observed; the official player stayed inactive. This is stream/output-pipeline
  evidence, not audible-output or server-statistics acceptance. See the paired plan
  checkpoint for cold-restore and final verification evidence.
- Cloud lyric lookup (`cloud/lyric/get` with song/owner), favorite/playlist/reporting
  library-ID mapping, private-cloud identities returned by other catalog endpoints,
  real account switching and complete AutoMix/effects/release-runtime acceptance remain
  separate work. Playing this one uploaded entry does not complete the cloud feature.
  API-021 subsequently implements source-aware lyric requests and Worker propagation;
  API-022 adapts current/preloaded lyric state and cache ownership. Real cloud text and
  karaoke rendering still need their own acceptance.
- Catalog click handoff follow-up (2026-10-02): daily recommendations, playlist,
  album, both artist pages and listening rank now retain their displayed session
  through PlayerConnection and its deferred queue build. Existing-item clicks check
  before seek and again before play, rather than bypassing the builder's owner checks.
  Retired/recovering clicks also reject synchronous title publication without leaking
  SessionChangedException into the UI callback. Composed content uses value snapshots;
  retained callbacks do not reread a replacement owner's delegated state. Page guards
  finish before player publication to avoid backend identity reads under their monitor.
- Selected placeholder hydration now forwards that same owner to the existing
  `/api/v3/song/detail` supplemental request. It checks dispatch and response ownership
  without requiring FM authentication for a public catalog guest; the existing FM
  authorization rule remains separate. Hydrated queues do not acquire a new request.
  Standalone signing/transport, official host signing/session ownership, default local
  and offline queue paths, source identities, layouts and playback engine are unchanged.
- Before-fix actual PlayerConnection fixtures fail two of six cases on uncaught stale/
  recovering title publication. Each current debug flavor passes sixteen connection
  cases plus twenty FM/intelligence queue-manager regressions on emulator-5554. The
  connection fixture uses its real binder/queue manager/StableDeckPlayer and muted
  silence-source ExoPlayer decks, an in-memory database and closed synthetic metadata
  responses; it does not start the registered MusicService lifecycle or access official
  credentials, media, accounts or servers. Guest hydration succeeds; a response retired
  during its request cannot replace the sentinel queue. Ordinary recovery-independent
  queue and existing-item behavior remains covered. This is not audible, live-account,
  Home/library callback, full lifecycle or release-runtime acceptance.
- Remaining consumer handoff follow-up (2026-10-02): Home private/similar/podcast,
  Library liked/FM, search, podcast detail, recognition, wiki and private-message song
  cards now forward the composed result's owner instead of capturing a replacement
  session at playback time. Home also validates the exact displayed Resource before
  returning its owner. FM and heart seeds accept that owner explicitly. Home's existing
  play/pause action checks ownership separately before prepare and toggle.
- Cloud page and History guards now finish before invoking PlayerConnection; its
  asynchronous queue publications keep the captured owner. History alone permits a
  current owner for hydrated queues during recovery, retaining the offline policy while rejecting
  retired generations. This flag does not authorize online metadata/media requests.
  Local/download default paths, queue ordering, signing, source identities, layouts
  and playback engine remain unchanged. No cloud operation is repeated for this check.
- Each matched debug flavor now passes 27 real connection cases plus 20 FM/heart
  queue-manager regressions. They check FM seed owner/retired success, recommendation
  toggle, owned offline-history recovery and retirement, and heart consumption outside
  the session monitor. The recovery exception rejects a placeholder's actual metadata
  dispatch, checked after the queue reaches its terminal state. The same closed, muted,
  in-memory fixtures apply; this does not qualify actual clicks on every listed screen,
  real account changes or audible output.

### ABI-016: Standalone Legacy Media Spans Need Current Source Authorization

- Recorded: 2026-10-02. Main's Media3 cache uses unowned
  `meilox-media-v3:<song>:<effective-quality>:<md5-or-size>`, while shared runtime
  lookup uses public-account-scoped `meilox-host-media-v1` keys. The same cache
  directory/database survives an upgrade, but preserving files alone does not make
  their bytes readable by the new player. This is a persistence/runtime difference,
  not a replacement official endpoint or frontend change.
- Standalone now reuses catalog spans only after the current usable session resolves
  a full, non-trial source with the same song, effective quality, valid 32-hex MD5 and
  exact positive content length. A credential-free per-owner metadata receipt is
  recorded under the session publication guard; no spans are copied or re-keyed.
  Existing partial spans can continue at their missing range; only complete spans
  become later cache-only candidates. Saved UserId, length alone, unverified legacy
  entries and numeric/cloud collisions are not authorization evidence.
- Other accounts/guests cannot inherit a receipt. Private-cloud sources never adopt
  these catalog aliases. Source recovery retires only the current owner's receipts
  and preserves the old spans and other independently authorized owners. A rejection
  marker prevents repeatedly adopting that same failed legacy fingerprint. Parasite's
  flavor policy stays a no-op and never inherits standalone legacy bytes.
- Verification: fourteen standalone JVM cases cover matching/mismatched sources,
  identity flags, independent second-account authorization, partial/complete spans,
  invalid sizes/MD5s, retirement and stale/recovery/transition publication guards.
  Source-recovery retirement also uses the session guard, preventing an old task
  from retiring a newly authorized same-account receipt.
  A cache-metadata write failure preserves the already authorized normal source key
  instead of making optional legacy reuse block online playback.
  Shared resolver assertions retain server MD5/size through actual-quality fallback.
  Four private AVD SimpleCache fixtures exercise the production resolver/provider/
  cache helper, actual cache-only byte reads, partial upstream continuation and
  database reopen. A cache-only 500 ms WAV reaches ExoPlayer STATE_ENDED with a
  48 kHz mono format and positive rendered output-buffer count, using no upstream.
  Nine standalone and six parasite selected package/cache device cases pass.
  All fixture source responses/identities/bytes are synthetic; no real
  playback URL, grant, media mutation or credential copying is involved.
- Limits: this qualifies the adapter's bytes and metadata, not actual original-install
  upgrade, production signing, first offline startup with an unverified Cookie,
  arbitrary legacy fingerprints or minified complete-track decoding. The ordinary
  account recovery rule is unchanged; first legacy reuse still requires a successful
  current source response. Those broader acceptance gates remain open.

### API-016: Playback Reports Use the Native Official SDK

- Recorded: 2026-09-29. Host: TV 1.1.80.
- Original MeiloX: `PlaybackHistoryReporter` called a Cookie-gated repository
  `feedback/weblog` operation and a separate client implementing NCBL payloads,
  encryption, compression, device/session metadata and HTTP delivery. That second
  client read module preferences and maintained an independent reporting identity.
- Official contract: actual DEX `module.player.m.q` emits native `startplay`/`play`
  through `IStatistic.logJSONWithMspm`, with mspm
  `5f3ab1eab1b200b0c2e37ee6`. When the official `h.e()` configuration enables BI,
  `bilog.k.d` publishes `_plv`/`_pld` through the initialized data-report SDK. The
  native channels own account metadata, file queues, encryption, batching and uploads
  via `clientlog/upload` and `clientlog/encrypt/upload`. These are not the previous
  independently authenticated WebView/NCBL clients.
- Adaptation: an internal bridge passes platform maps into the host's own builders
  and fastjson objects; no host Kotlin/JSON type escapes the boundary. The official
  BI switch is respected and its SDK must be initialized. Only actual playback
  starts create `startplay`; completion sends active playing seconds, excluding
  pause/buffering, with the original epoch-second `startlogtime`, millisecond
  `logtime`, song/source and end reason. Native song reports do not receive the old
  `mainsiteWeb` profile flag. Host SDK enrichment supplies its environment metadata.
- Ownership: each queued channel has a one-use scalar marker associated with the
  captured public identity and authorization generation. Hooks at actual DEX workers
  `core.statistic.p0.I(String, JSONObject, long)` and
  `core.statistic.j1.r(String, JSONObject)` remove that marker before invoking their
  serializers. Expired, replayed, guest, recovery-required and stale events are
  discarded. Unowned original-host events for these four playback actions are also
  discarded to prevent a second reporting source; unrelated analytics proceed.
  Pending markers are bounded and expire after five minutes. Delayed invalidation
  callbacks remove only older generations, not newly queued events.
- Delivery boundary: SDK enqueue/worker processing is not an upload receipt. Partial
  native-channel failures are not automatically retried by the module, since the
  other channel may already be queued. The final owner check deliberately does not
  hold the module session monitor across host calls, whose account stores have their
  own locks. Native account metadata rotation remains intact; a real account change
  during the worker call is not yet qualified as an atomic delivery guarantee.
- Evidence: unit tests cover ordering, captured timestamps, pause exclusion, duplicate
  completions, draining/cancellation, guests/recovery, stale identities, one-use markers,
  expiry, bounded retention and delayed invalidation. On the AVD, both native workers
  processed start and completion channels with the same start timestamp; a roughly
  165-second wall-clock interval containing a pause produced 88 active seconds.
  This does not prove file persistence, HTTP/business acceptance, complete-song
  playback, listening-history refresh, or later server statistics aggregation.
- Cleanup: removed the unused independent NCBL client, codec, metadata provider,
  dedicated OkHttp wiring, tests and Zstd JNI dependency. Old uncalled weblog helper
  fixtures/DTOs and the unused legacy interceptor remain for the final cleanup stage;
  this checkpoint does not claim all independent NetEase backend code is gone.
- Remaining: real logout/reauthorization/account-transition checks, original-player
  duplicate-event runtime injection, full song/source/effects metadata parity, FM's
  special real-time path, podcast/cloud semantics, release runtime, and server-side
  listening-history/statistics acceptance. No account change or social action was
  performed for this checkpoint.

### API-017: Download Permission Is Not Playback Permission

- Recorded: 2026-09-29. Host: TV 1.1.80.
- Original MeiloX: playlist/album menus, the player menu, podcast bulk actions and
  automatic permanent cache all resolved playback V1 arrays, retried lower playback
  qualities, then persisted those URLs for `DownloadWorker`. An accessible full
  playback source was treated as sufficient permission to download.
- Official contract: `i0.f.a.U` posts to `song/enhance/download/url/v1` with one
  `id=songId_cloudSongUserId`, a requested `level`, and `immerseType`. Its `data` is
  an object. `MusicInfo.getCloudSongUserId()` returns zero for ordinary songs, not
  the logged-in user ID; private-cloud songs require their separate owner. The TV
  transfer implementation also resolves a podcast's main song through this route.
  The native method can supply optional effects, Dolby support and environment
  parameters; those are not inferred from an ordinary playback grant.
- Adaptation: added a separate typed download response and source resolver through
  `ApiService`/`HostCallFactory`, retaining official authentication/signing/transport.
  All five existing producers enqueue owner-bound intent/metadata; the worker uses
  this resolver when it actually executes. The playback resolver remains separate.
  Ordinary-song requests use the `_0` tuple and the selected quality, with `c51`
  only for sky and `ste` otherwise. There is no playback-route or lower-quality
  retry when download permission is denied. Each resolver call captures one
  authenticated, non-anonymous authorization generation for its entire ID list.
- Validation: require outer business success, a single source object, an explicit
  per-source status, matching ID, non-trial HTTP(S) URL without userinfo, known audio
  type, positive 64-bit size and MD5. Returned quality is retained separately from
  requested quality; a missing level or expiry is not invented. Explicit expiration
  is measured from dispatch and rechecked across the whole batch. Responses and
  resolver state are not cached. Cancellation, recovery, reauthorization or account
  changes reject late results, including identity changes without a callback.
- Denials: native transfer code recognizes `-103`, `-105`, `-120`, `-125`, `-130`,
  `-140` and `404` as unsuccessful downloads. They remain explicit per-song rejected
  results; transport/authentication/malformed/unknown errors abort resolution
  instead of silently treating it as a smaller successful batch. No old URL is
  reused after a denial. Existing menus, quality choices and page layout are retained.
- Evidence: 17 focused resolver tests, the updated stale-album/podcast preparation
  tests and Retrofit request-tag tests pass. One Android device test exercises the
  real module Retrofit/Gson transport against a substitute host backend, validating
  the tuple, object response, 64-bit size, denial and stale-owner paths without any
  account request or file write. Full suite: 584 tests in 71 suites, zero failures;
  six focused Android tests pass. These do not establish real account download rights.
- Remaining: the live standard-quality URL-only request awaits user approval because
  its effect on official download quota is not established. No real download request
  or media-file transfer was performed in this checkpoint. Private-cloud owner
  propagation, optional advanced-quality capabilities and real podcast grants are
  not yet qualified. The following ownership checkpoint implements persisted request
  identity, fresh grants and transfer cancellation, but durable MediaStore publication
  and end-to-end worker/process-resume checks remain unfinished. See
  ABI-004 for the host scheduler boundary; stage 5 is not complete.

#### Download Ownership Checkpoint (2026-09-29)

- The old queue persisted a temporary URL and identified all attempts by song ID.
  Room v19 stores a public account ID, unique request UUID and destination instead;
  the UUID is also the WorkManager request ID. New tasks leave the legacy URL column
  empty. Migration clears old addresses and fails unfinished unowned rows without
  assigning the current account, copying cookies or deleting completed rows/media.
- Enqueue/pause/resume/delete operations serialize local mutations. Progress and
  file metadata updates require matching song/request/account and an active state;
  stale UI actions cancel only the captured WorkManager ID. Resume retains the stored
  destination but creates a new request after checking the official account. Worker
  retries reacquire a current authorization stamp for the same persisted public ID;
  no in-memory generation is assumed to survive process death.
- Removed producer-side prefetch to avoid consuming one official grant at enqueue
  and another at execution. Album, playlist, podcast, player and automatic-cache
  producers now enqueue the requested quality; permission denial becomes a failed
  queue item when the worker resolves it. Existing pages, quality controls, queue
  actions and layout are unchanged. An unavailable scheduler fails before storing
  new rows or requesting a grant. This timing difference is intentional, not a
  playback-permission fallback.
- Each attempt uses an isolated UUID-named temporary file. Media responses require
  HTTP 200, the authorized byte length and original MD5 before tags are written.
  Blocking HTTP calls are canceled by a structured child coroutine; a canceled read's
  IOException is converted back to coroutine cancellation. Session invalidation
  cancels the entire owned operation. Lyric/cover work is attached to that operation,
  never launched in an unrelated scope.
- Download lyrics retain the AMLL-first preference. The old fallback posted directly
  through `NeteaseInterceptor`; it now uses `song/lyric/v1` with `GetLyricV1` and the
  captured official session via `ApiService`/`HostCallFactory`. Cover/media/AMLL resource
  requests remain direct and cancelable. No additional cookie client is retained here.
- Removed filename-only MediaStore adoption and in-place repair of unrelated existing
  files. The worker creates a pending row, copies only its own temporary file, validates
  task/session ownership before publishing, and rolls back its newly created URI on
  caught failure. This does not yet cover abrupt process death: a durable URI receipt,
  orphan/replacement cleanup and reconciliation across DB/WorkManager/MediaStore are
  still required before enabling ordinary scheduling. Concurrent download notification
  aggregation and foreground-worker behavior also remain unqualified.
- Evidence: 598 unit tests in 74 suites, including five ownership tests and four
  substitute transfer tests, pass. Fourteen focused Android tests pass, including
  actual Room migration/conditional updates and a substitute host lyric request with
  stale-session rejection. Tests use temporary private files/databases and fake HTTP
  calls; no real grant, user media write, account change or complete live worker run
  occurred. The ordinary/release scheduler gate remains disabled.

#### Private-Cloud Download Source Identity (2026-10-01)

- New cloud tasks persist the full source key separately from logical `songId` and
  authorization `ownerId`. Room v20-to-v21 adds only a default-empty `sourceKey` column;
  it does not invent cloud owners, change old status/request/destination data or delete
  media. The standalone frozen v17 upgrade path and injected-failure rollback/reopen
  checks pass through v21; a separate v20 fixture covers all five task states.
- Parasite workers request one dedicated grant with `id=audioId_fileOwnerId`, verify
  its audio response ID and map the neutral result back to the logical entry. Standalone
  still uses playback URLs and lower-quality fallback, retaining the same cloud tuple.
  Cloud requests are isolated into single-source batches because responses echo only
  audio IDs; ordinary batching remains unchanged. Conflicting entry identities and
  foreign-account affinity fail before any grant or request.
- Pause/resume, missing-work recovery, automatic cache and downloaded Library adapters
  preserve source ownership. Corrupt tasks fail individually without preventing valid
  recovery. Android Worker/publication tests use closed substitutes, including distinct
  audio/file-owner/account identities, and clean up their own artifacts. The standalone
  live cloud check reads player metadata only. No real private-cloud dedicated grant,
  download quota or media publication is exercised by this checkpoint. The already
  accepted TV-owned 22,705,573-byte download remains intact.
- Cloud lyric/metadata tagging, actual private-cloud download permission/transfer,
  provider/reboot lifecycle and minified release execution are not qualified by these
  substitute tests. Legacy empty source keys retain their original numeric semantics;
  no historical private-cloud affinity is inferred from a filename or current account.

## Runtime Boundary Notes

These are integration differences, not server API semantics.

### ABI-001: Suspend Parameter Annotations

- The first AVD Retrofit run observed two parameters but one annotation-array slot for
  a module suspend method. Retrofit consequently failed to recognize the continuation.
- A narrow API 102 adapter restores only the missing empty continuation slot for known
  isolated module service interfaces. It leaves host methods and complete arrays alone.
- Evidence: reflection-adapter unit tests and the corrected eight-operation Retrofit
  probe. See [Retrofit Transport Adaptation](official-client-parasite.md#retrofit-transport-adaptation).
- Dual-runtime follow-up (2026-10-01): the extracted host favorite, playlist-track,
  playlist-collection, catalog-collection and download Retrofit interfaces were absent
  from that exact registry. A real host-process cloud-favorite read failed before
  network dispatch at Retrofit's call-adapter creation; module-package instrumentation
  and ordinary JVM fixtures did not reproduce the host-loaded annotation truncation.
  These five explicit module interface classes are now registered alongside the six
  original services. No package wildcard, host interface, global annotation rewrite
  or additional framework scope is introduced. Tests preserve every Body/Tag slot,
  pad only the single missing continuation slot and leave complete/malformed arrays
  unchanged. Real collection writes/download grants are not inferred from this fix.

### ABI-002: Launcher Activity Replacement Is Separate From System Orientation

- Recorded: 2026-09-29.
- Original prototype: substitutes only `tv.test.TextMainActivity`; the real desktop
  `app.LoadingActivity` still runs the official startup, calendar/ad, and TV-home path.
  Successful debug-carrier launches therefore did not qualify desktop cold starts.
- Adaptation: after host identity verification and official Application initialization,
  substitute the actual launcher, home, and calendar entry classes before construction.
  They instantiate the original MeiloX MainActivity instead of executing official page
  lifecycle code. Notification intents use the launcher with CLEAR_TOP and SINGLE_TOP.
  The isolated runtime probe retains its debug carrier and does not take over the launcher.
- Orientation boundary: the official APK declares landscape on these entries. The
  system can apply that declaration and show its package-icon starting window before
  any host-process hook runs. MainActivity then requests portrait. This causes a visible
  rotation without additional device configuration; the system icon is not an official
  in-app advertisement. No system-framework scope or APK modification is introduced.
- Authorized AVD workaround: Android per-package compat changes `OVERRIDE_ANY_ORIENTATION`
  (265464455) plus `OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT` (265452344) override the
  fixed direction before host startup. Both were initially default-disabled, with no
  TV override. The user authorized testing and then retaining only these TV overrides.
  Global rotation settings and other packages are unchanged. These flags affect all TV
  activities, not just the MeiloX entry, and are not automatically set by the module.
- Evidence: [Android's compat-change reference](https://developer.android.com/about/versions/16/reference/compat-framework-changes#OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT)
  documents the combination. On the API 37 AVD, recorded desktop-icon cold startup stays
  portrait; the system logs the landscape-to-portrait policy override and adds no display
  rotation. The shell appears with its existing login and paused queue. Unit mapping
  tests, three on-device Intent tests, task inspection, and notification-return checks
  cover the entry replacement separately from the orientation workaround.
- Remaining: the system package-icon starting window is still visible. Reboot/update
  persistence and other Android/OEM versions are unverified. Arbitrary external deep
  links, release obfuscation, and the separate recording/PiP manifest gate remain open.
  Setup and exact rollback commands are in the migration log's launcher checkpoint.

### ABI-003: Official Media Buttons Bypass the Replacement Player

- Recorded: 2026-09-29. Host: TV 1.1.80.
- Original assumption: the module's own active Media3 session was sufficient for
  system media controls. AVD disproved this: a pause key during track buffering
  changed the official session while the module later continued playback.
- Official contract: actual DEX `module.player.o.d` constructs the host's
  `android.support.v4.media.session.MediaSessionCompat`, names it `MediaSession`,
  and calls `setActive(true)`. Its media callback forwards to the registered
  `receiver.MediaButtonEventReceiver`; that receiver directly calls official
  PlayService actions, including delayed headset-button actions. Merely replacing
  the Activity does not replace this control path.
- Adaptation: only after package/signature verification, hook the host-loader legacy
  compatibility class to keep its sessions inactive. Do not hook the module's
  isolated Media3 session or system_server. The official receiver forwards events
  to the bound module platform token; Media3 still interprets the key semantics.
  Disposing an old binding cannot clear a newer service's binding.
- Cold startup: when no module session exists, only initial play, play/pause, or
  headset key-down events may start the existing registered service carrier. The
  receiver uses a fresh intent with only the KeyEvent. Pause/skip/stop do not launch
  a foreground service. This follows the
  [Media3 receiver policy](https://developer.android.com/reference/androidx/media3/session/MediaButtonReceiver)
  without relying on service filters absent from the TV manifest. Platform start
  restrictions are retained. The request/login bridges also bind for service-only
  startup, without launching an Activity.
- Restore ordering: receiver startup suppresses snapshot autoplay before restore;
  incoming startup media commands wait for disk restoration, then enter Media3's
  normal service handler. An empty/unavailable queue stops the requested service
  instead of leaving an unfulfilled foreground start. No playback command is issued
  from a play-state observer or fade callback.
- Evidence: unit key-policy tests, two device receiver/binding tests, the existing
  audio-focus device regression, and actual host foreground/background key tests.
  AVD cold media-button resumption recreated the module service after `am stop-app`,
  with no Activity launch. The legacy session remained inactive and STOPPED while
  module play/pause/next/previous and buffering-time pause operated correctly.
- Additional evidence (2026-10-01): actual Recents-card removal while playing passes
  in the development-signed parasite R8 and the isolated debug-ID standalone R8
  fixture. Settled Activity stacks no longer contain the removed task, while each
  foreground service and PLAYING MediaSession persist with advancing progress. The
  actual system media notification recreates portrait MeiloX Home in a new task, not
  an official TV page. The official session stays inactive STOPPED with no queue.
  This is task-removal/notification evidence, not process death or audible output;
  standalone production identity/signing remains a separate qualification.
- Remaining: this retires the verified media-session/receiver path, not every
  possible official widget, external playback intent, or reporting path. Slow-disk
  restoration, empty/corrupt stored queues, real Bluetooth peripherals, other OS
  versions, broader release runtime, and audible output remain separately unqualified.

### ABI-004: WorkManager Components Are Not Installed in the Host

- Recorded: 2026-09-29. At the initial checkpoint, download producers enqueued the module's isolated
  WorkManager, but only the module APK declares its normal AndroidX components.
  The pinned TV APK manifest has no `androidx.work.impl.background.systemjob.SystemJobService`
  or WorkManager initializer. Ordinary module builds did not initialize a
  separate WorkManager runtime. A successful URL response therefore does not prove
  that a download can run or resume in the host.
- Authoritative artifact: APK manifest inspection finds `BIND_JOB_SERVICE` only on
  Tinker's patch service (separate `:patch` process) and default result service (main
  process). Those are occupied official components, not free declarations. They
  must not be replaced blindly at the cost of official Application/patch behavior.
- Prototype checkpoint: `-PparasiteWorkProbe=true` enables a separate module WorkManager
  using the existing main-process `DefaultTinkerResultService` declaration. The real
  class extends platform `IntentService`, not `JobService`. Its instance, attachment,
  start commands, patch-result handling and destruction remain official; a separately
  attached module `SystemJobService` delegate supplies the system job binder only for
  the verified carrier's explicit, actionless bind. Partial hook installation rolls back.
- AndroidX 2.11.2 otherwise targets a nonexistent module component, filters pending
  work by that component and uses its common namespace. The adapter changes only the
  module-loaded converter/filter/scheduler paths, isolates API 34+ jobs under
  `com.neoruaa.meilox.parasite.work`, and requires a reserved ID range, owner marker,
  canonical WorkSpec UUID and generation. It suppresses only module AndroidX component
  enablement, never enabling/disabling a host declaration. Pre-34 collision handling is
  implemented but not device-qualified. Builder bounds must differ by at least 1000;
  an inclusive 1000-ID range is rejected by this dependency's runtime validation.
- AVD evidence: after the host process was killed with work pending, a targeted
  `cmd jobscheduler run -f -n ...` started a new host process and completed the isolated
  test worker without an Activity. A second delayed worker remained RUNNING while the
  official result service processed an empty intent through its own null-result path;
  cancellation then stopped the system job and coroutine. No valid patch result was
  fabricated. Background `am startservice` was rejected by Android, so the coexistence
  test brought MeiloX to the foreground first; no background-start exemption was added.
- The initial evidence was debug-gated qualification, not production download acceptance. The probe
  writes only its dedicated module preferences and WorkManager test records, with no
  network or media operations; cleanup targets only its unique work. Ordinary/release
  builds initially kept the gate closed; see the activation checkpoint below. The host has no boot receiver or `RECEIVE_BOOT_COMPLETED`
  permission, while AndroidX jobs are non-persisted, so automatic post-reboot recovery
  is not supplied by this prototype. App-open reconciliation is not equivalent to it.
- Next gate: durable download ownership, renewed grants, cancellable transfer and
  publication, notification/foreground-worker routing, real transfers, natural system
  scheduling, reboot behavior and release qualification. Do not repack
  the host, add a system-framework scope, claim worker acceptance from an interface
  test, or hide the existing download controls to mask this unfinished integration.

#### Scheduler Activation and File Publication (2026-09-29)

- User reproduction: selecting download displayed "Failed to obtain link" before any
  grant request. The ordinary build gated both host scheduler hooks and initialization
  behind `PARASITE_WORK_PROBE`, while the player caught every enqueue exception with
  that misleading message. Source and installed build state establish this pre-request
  failure; the exact exception from the user's original click was not captured.
- Ordinary app builds now install and initialize the qualified host scheduler, including
  service-only startup. Only explicit test broadcasts/workers remain probe-gated. AVD
  cold-start logs confirm isolated WorkManager initialization with the probe disabled.
  Installing the module alone does not replace classes in an already running TV process;
  the repeated old toast occurred while that old process was still alive. Force-stop and
  cold launch load the new module without clearing the official account or app data.
- Enqueue errors distinguish scheduler readiness, official-session changes and queue
  insertion failures. Worker notifications distinguish authorization, transfer and local
  publication failures. No playback URL is substituted for denied download permission;
  queued requests wait for connected networking and obtain fresh official grants at execution.
- Room v20 adds a per-request publication receipt independently of the replaceable task
  row. Before MediaStore insertion it records a private UUID staging directory; after
  insertion it records the URI, owner package, provider version and generation. Tagged
  bytes are synced and checked against their SHA-256/length. Song path, completed task
  and committed receipt are written together inside the official-session publication
  lock. The MediaStore row is then moved to the original configured destination and
  made visible. No page, quality selector, menu or navigation architecture changed.
- Startup/worker recovery discards uncommitted pending files or finishes visibility for
  committed files without another grant. URI reuse, changed provider identity, moved or
  edited detached media fail closed. Unreferenced, unchanged receipt-owned files can be
  removed after replacement/deletion; unjournaled legacy files are never adopted by name.
  Storage-management deletion uses the same serialized path. An app-open reconciliation
  also repairs active account-owned queue rows missing their WorkManager request; this
  is not a boot receiver, and its crash-gap scheduling path still needs live acceptance.
- Evidence: 599 unit tests and 22 selected device tests pass. Publication tests simulate
  death after insertion/copy and before/after visibility, reject invalidated ownership,
  and preserve edited/reused media. Both instrumentation and an explicit host-process
  probe publish and remove synthetic WAV bytes through the real MediaStore without a
  permission change. The host probe uses a private in-memory database, not the account's
  queue; it is not a real song download or a full DownloadWorker run.
- Still open: user-authorized real grant/complete transfer, permission-denied response
  display, live pause/resume/replacement, actual mid-transfer process death, long-running
  foreground-worker routing/notification aggregation, natural job scheduling, reboot,
  and release runtime. The final installed build has test commands disabled. No real
  grant was requested automatically in this checkpoint; the user has not yet answered
  the specific quota-consuming download test authorization.

#### Full Worker Qualification and User-Initiated Download (2026-09-29)

- Kept the public `DownloadWorker(Context, WorkerParameters)` constructor and its real
  module graph. A per-worker environment permits isolated qualification without replacing
  `AppGraph`, reading official credentials, or rebinding the live session. The explicit
  debug worker inherits the same execution body; only its account/request/resource edges,
  database and notification sink are substitutes. MediaStore publication remains real.
- Eight device cases exercise that execution body: completion/idempotent completed work,
  official denial without a resource connection, truncated/corrupt media, a different
  account before authorization, cancellation of a blocked read while preserving PAUSED,
  in-flight session invalidation, and rejection of a replaced request. They check task
  state, URI publication, temporary-file cleanup and grant/transfer counts. These are
  substitute authorization responses, not observations of real server error behavior.
- Actual host-process qualification: enqueue a delayed synthetic request, return Home,
  stop the paused player service, kill the verified TV process and confirm its absence.
  A namespaced `cmd jobscheduler run -f` then starts a new host process through the
  original job carrier without any Activity. The inherited worker resolves exactly one
  substitute grant, transfers once, and reaches WorkManager SUCCEEDED, task COMPLETED
  and artifact PUBLISHED. This proves service-only execution, not natural cold-start
  scheduling latency; the job was still waiting before the targeted force-run.
- A second held transfer starts in the background without a force-run, reaches RUNNING /
  DOWNLOADING, and is canceled through WorkManager. Its blocked read closes, the coroutine
  exits and no artifact is published. Direct WorkManager cancellation leaves a pending
  task (the normal app-open reconciler handles terminal work); the distinct UI PAUSED
  invariant is covered by the device case. Probe cleanup removes only its private database,
  test WorkSpecs and synthetic media. No pending module jobs or synthetic media remain.
- While this qualification was being prepared, the user retried a real download in the
  ordinary build. Its production `DownloadWorker` returned SUCCESS after approximately
  four seconds, and the original Library download row displayed Complete. MediaStore
  contained a published, TV-owned FLAC in the configured single-song destination:
  22,705,573 bytes, 208.373333 seconds, stereo 44.1 kHz / 16-bit, with embedded cover art.
  A read-only local copy passed full-file `ffmpeg -xerror` decoding and was then removed;
  the AVD original remains intact. No new grant was requested by the agent. Requested
  quality, audible output and offline Android playback are not inferred from the format.
- The real retry retires the immediate queue/link-failure report for that song and account,
  not the full download matrix. Still open: other qualities/permissions, real network loss,
  live pause/resume/replacement, actual mid-transfer process death, long-running foreground
  work and notification aggregation, natural cold-start latency, reboot and release runtime.
  Final ordinary debug build and unit suite pass; 30 selected device tests pass. The probe
  is disabled again, the real download is preserved, and the full playback stage stays open.

### ABI-005: Foreground Work Needs a Separate Host Carrier

- Date: 2026-09-29. The host has no installed AndroidX `SystemForegroundService`.
  A successful ordinary Worker or a posted progress notification does not establish
  a long-running foreground Worker. Reusing the Tinker job carrier would mix its
  original IntentService start/stop behavior with the foreground dispatcher's lifetime.
- The pinned TV APK's actual DEX confirms `org.chromium.wow.extension.usage.WowIPCService`
  directly extends platform Service and overrides only `onBind`. Its original Binder
  speaks `org.chromium.wow.extension.usage.WowIPCServer`; the examined official callers
  bind to it, not start it. Its manifest entry is non-exported, main-process, target 29,
  with no foreground service type. LocalMusicTaskService, VideoPlayService and the
  network detection service have occupied lifecycles and were not substituted.
- The original IPC Service and Binder remain installed and instantiated. Android's
  attachment/token is mirrored into an isolated AndroidX delegate, created lazily on
  the first owned start command. Only explicit module dispatcher commands route to
  this carrier; canonical WorkSpec IDs, generations, reserved notification IDs, payloads
  and an ownership marker are checked. Original bind/unbind and unrelated starts are
  untouched. This is in-process ownership separation, not a security sandbox against
  another component in the same UID.
- The delegate forwards start/stop/destroy and available platform timeout callbacks.
  Platform Service's default timeout callbacks are empty; its started and bound
  lifetimes are independent ([AOSP Service source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/app/Service.java)).
  The pinned target-29 host uses two-argument `startForeground` with type zero, as the
  existing player does. Typed requests are rejected, not fabricated in package metadata.
  Android's permission/start/quota enforcement remains active; no system scope, global
  setting, manifest repack or permission grant is involved.
- Four unit cases cover command, identity, ID and type boundaries. Three device cases
  use the installed AndroidX command factories and real Parcelable transport, including
  unchanged official binding intents and rejection of altered commands. Fourteen selected
  device tests initially passed with the existing job-carrier and full download-worker
  regressions; the expanded final regression passes all 33 parasite device cases.
- In the injected host, two no-network workers start naturally and post separate
  notifications. The first owns the foreground notification; canceling it promotes the
  second. System service state confirms the original registered carrier is foreground
  with type zero. The original Binder remains alive. Playback advances and its distinct
  notification remains present; neither observation proves audible output. The remaining
  worker runs for twelve minutes and reaches SUCCEEDED (the first is CANCELLED).
  Foreground notifications disappear and the processor wake lock is released; the
  original Binder survives STOP and the service is destroyed only after test unbinding.
  The run includes a screen-off interval of approximately 90 seconds, not twelve minutes
  of continuous screen-off or forced idle. Cleanup removes the two test WorkSpecs and
  channel; neither namespaced system job remains registered.
- Production `DownloadWorker` is deliberately not switched by this checkpoint: its shared
  progress notification ID still needs per-work ownership and completion aggregation
  before calling `setForeground`. This carrier qualification alone does not close long
  download, process-redelivery, reboot, quota-exhaustion, other-system or release-runtime
  acceptance. No official download grant is requested by these synthetic workers.
  Final ordinary debug build and 603 unit tests pass with the probe disabled. Release
  R8 build and 16 KB APK alignment pass; its mapping preserves the AndroidX service
  name and removes the debug foreground probe. This is unsigned build/package evidence,
  not release runtime acceptance. The ordinary debug APK is reinstalled and cold launch
  confirms both work adapters and the isolated manager initialize without probe commands.

#### Production Download Promotion and Aggregation (2026-09-29)

- The production worker now awaits foreground registration after session/task validation,
  before waiting for a transfer slot or requesting official download authorization.
  A rejected registration fails without making a grant or media request. The original
  notification content and entry route are retained; overlapping downloads share one
  progress notification, with execution leases fenced by account and session generation.
- AndroidX foreground handoff may cancel the promoted worker's notification as well as
  the finished worker's notification. A shared ID therefore cannot use the unmodified
  per-worker cancellation policy. The carrier renders the latest batch snapshot and
  retains progress while any owned download remains active. A separate completion ID
  prevents a late foreground stop from removing the final result.
- An injected two-worker test exposed another ordering difference: cancellation can stop
  the foreground service before the canceled coroutine completes cleanup. Publishing
  only from the service stop callback lost the cancellation result. Worker cleanup and
  service stop now both publish the terminal snapshot idempotently. Old/replaced leases
  cannot update a new batch, and a failure/cancellation never reports all downloads done.
- Seven new JVM cases cover aggregation, ownership, replacement and terminal outcomes.
  Two new device cases verify promotion-before-grant and refusal-without-grant using the
  full worker body. In-host substitute runs exercise actual foreground service promotion,
  real private publication and notification UI: one completion plus one cancellation,
  two completions, and authorization refusal with zero transfers. The intermediate
  one-of-two notification remains foreground; terminal results are non-foreground and
  survive service destruction. Clicking the refusal notification returns to the original
  portrait MeiloX Home. Probe state, channel and IDs are isolated from production work.
- Final unit coverage is 610 tests; all 35 parasite device cases pass. Debug and unsigned
  R8 release builds pass; release APK 16 KB alignment passes. No new real official grant,
  download quota, account mutation or page redesign is involved. Actual long network
  downloads, process redelivery, reboot, permission/quota failure, other Android versions
  and release runtime remain separate acceptance gates.

#### Queue Controls and Process Redelivery (2026-09-29)

- DownloadManager keeps its existing UI API, while its suspend queue operations use
  explicitly supplied database, official session bridge and WorkManager dependencies.
  The qualification queue uses the same operations with private data and a closed
  synthetic worker. It does not replace the live app graph or use the real account.
  Resume is restricted to PAUSED/FAILED rows; pending or completed work cannot consume
  another grant through a stale resume command. A resumed request retains destination,
  quality and public owner ID but gets a new UUID and no saved authorization URL.
- WorkManager 2.11.2 REPLACE cancels and deletes old WorkSpecs; explicit pause retains a
  CANCELLED record. This distinction was observed on-device and checked against the
  installed library's EnqueueRunnable source. Tests check replacement removal, not an
  assumed retained CANCELLED state. Seven new device cases cover queue controls, stale
  requests, account rejection, enqueue crash-gap recovery and scheduler refusal.
- In a held full-worker transfer, killing the verified host PID leaves a 4,096-byte
  temporary file. Without opening an Activity or forcing JobScheduler, Android redelivers
  the registered foreground carrier and restarts the WorkManager request in a new process.
  A newly constructed worker acquires a fresh substitute grant, restarts transfer rather
  than appending, publishes one verified artifact and removes the temporary file/service.
  This is actual process-death evidence with synthetic transport, not real network,
  reboot, forced-stop recovery or a guarantee of restart latency on every system.
- In-host queue controls stop a held transfer, persist PAUSED, cancel its WorkSpec,
  remove temporary bytes and show `下载已暂停`. Recovery leaves it paused; resume replaces
  the request, then completes publication. Deleting that completed test task removes its
  receipt-owned media. A separate pending-then-paused request remains PAUSED after process
  death and explicit recovery, with zero grants/transfers. These calls exercise the exact
  queue implementation, not automated taps on the visible pause/resume controls.
- AVD startup was not uniformly healthy: several process-attach/startup timeouts affected
  both the host and the instrumentation process, and a system_server input-monitor
  pre-watchdog was captured during the same interval. The traces do not establish a
  single root cause. Failed startups are excluded from passing test evidence; subsequent
  ordinary cold launch and all 42 parasite device cases pass. Keep startup stability,
  real-network interruption, UI control interaction, quota/permission failures and
  release runtime open. The ordinary build is restored with probes disabled; the user's
  existing real file remains published and unchanged. No real grant was requested.

### API-018: Comment Reads Need Query-Owned Cursors and Optional Reply Metadata

- Date: 2026-09-29. Original standalone contracts are `/api/v2/resource/comments`
  (`threadId`, `pageNo`, `pageSize`, `sortType`, `cursor`, `showInner`) and
  `/weapi/resource/comment/floor/get` (`parentCommentId`, `threadId`, `limit`, `time`).
  Parasite passes these business parameters to the official generic pipeline as
  `v2/resource/comments` and `resource/comment/floor/get`. No TV-only endpoint
  substitution, copied Cookie or standalone fallback was introduced. At this initial
  checkpoint the restored standalone transport was not qualified; the paired read
  checkpoint below adds scoped runtime evidence without qualifying the full matrix.
- Both calls now carry the expected shared `SessionStamp` as a Retrofit request tag,
  not a serialized credential. Host transport tests verify normalized paths, parameters
  and rejection of obsolete owners before dispatch. The shared repository rejects
  non-200 business responses and missing data/rows/continuation instead of treating
  them as successful empty results; coroutine cancellation propagates.
- Pages belong to a captured song/sort/session/revision. Sorting, navigation to another
  song, recovery and account invalidation invalidate old sources and clear reply state;
  late non-cancellable results cannot replace the current query. Each page key retains
  its page number, cursor and raw-row offset, so initial/append size differences and
  retry do not reset another query's cursor. Duplicate identities are filtered without
  advancing offsets by the deduplicated count.
- A returned nonblank cursor takes precedence. The existing sort-specific fallback
  uses the final row time for TIME, `normalHot#<raw offset>` for HOT and the raw offset
  for RECOMMEND. Replies follow returned `time`, falling back to the final reply time.
  Repeated cursors, duplicate-only continuing pages and missing continuation fail
  instead of looping. These fallback and multi-page rules are substitute-test evidence,
  not a claim that all production responses omit or require a particular cursor shape.
- Floor reply decoration, IP location and other optional reference metadata can be
  absent. Gson does not enforce Kotlin non-null constructor declarations. DTO nullability
  now reflects that, including nullable continuation fields so absence is distinguishable
  from a terminal page. A hash-code regression test covers missing optional metadata;
  the existing row only reads the optional IP label safely, with no layout change.
- Read-only AVD evidence uses song `41416743` and the original CommentScreen in the
  explicit debug page carrier: recommend, hot and time first pages show the same total
  of 365; an expanded thread displays all three replies, including a reply without IP
  metadata. These observations do not establish full scrolling/pagination, live failure
  recovery, logged-out/expired-account behavior or release runtime. The total is a
  point-in-time observation, not a fixture or invariant.
- The ordinary player More > View Comments attempt landed on Library rather than the
  comment screen during this check. Its cause was not established; direct page-carrier
  success does not qualify that entry route. No frontend/navigation repair was made
  under the backend-only scope. Newly proposed retry controls were withdrawn; existing
  collapse/reopen semantics are retained and covered with substitute failures.
- The checkpoint passes 641 JVM tests, including comment repository/session/paging,
  transport ownership and shared backend contracts. No comment, like, social message,
  logout, account switch or new download was submitted. Keep the ordinary entry route,
  full comment matrix and both restored standalone/release runtimes open.
- All 43 parasite device tests and debug/unsigned R8 builds pass. The release APK passes
  16 KB alignment; this does not qualify its runtime. The ordinary debug package is
  reinstalled with all probes disabled and cold-launches the original portrait Home.
  Runtime screenshots/logs remain local and are not committed.

#### Paired Minified Read Check (2026-10-02)

- The original player More > View Comments route reaches CommentScreen twice in the
  existing TV R8 process. Isolated standalone R8 and restored ordinary debug both
  return to Settings on their first activation and reach comments on their second.
  Back returns to the underlying Settings page. Repeated-entry success does not
  qualify first-entry reliability; the cause remains unestablished and no frontend,
  navigation, endpoint or transport change is made to work around it.
- Reads use the existing paused tracks and separate accounts, not a new queue:
  TV `Écoute Chérie` displays 23,338 comments; standalone `Full Moon Serenade`
  displays 421. These are point-in-time observations, not matching-account/server
  equivalence or invariants. All three original sorts render data and continue beyond
  the configured 20-row initial load. Fully visible nickname/content pairs are counted
  with structured XML and SHA-256 deduplication; clipped/obscured pairs are excluded.

| Runtime | Recommend | Hot | Time |
| --- | ---: | ---: | ---: |
| Parasite R8 | 31 | 23 | 43 |
| Isolated standalone R8 | 28 | 21 | 43 |

- TV also renders one complete single-reply thread and 45 distinct visible replies
  from a thread labelled 98 replies. Standalone renders all six replies from a thread
  labelled six. Both collapse back to zero reply text nodes. This proves rendered
  continuation beyond the initial window, not captured wire cursors/request counts,
  full traversal of every thread, absent-IP coverage on both backends, or live errors,
  expired sessions and account switches. Earlier substitute-test evidence remains
  separate from these live reads.
- The focused four comment JVM suites pass 23 cases per flavor (46 total); ordinary
  standalone debug, parasite release and the isolated standalone R8 build pass.
  Runtime images/logs and hashed pair counts stay under `/tmp/meilox-paired-comments-*`.
  No comment, like, account mutation, recording, playback, upload or download grant
  is submitted. Development signing/test identity is not production upgrade acceptance;
  full paired release/business regression and the overall goal remain open.

### API-019: Cloud File Transfer Is Separate From Business Authorization

- Date: 2026-09-30. Original standalone cloud upload requests `cloud/upload/check`,
  allocates metadata and file tokens through `nos/token/alloc`, transfers bytes through
  NOS, registers `upload/cloud/info/v2`, then publishes `cloud/pub/v2`. These supplemental
  business parameters are retained, including the legacy bitrate field and distinct
  metadata/file allocation. They are not presented as a complete TV cloud feature API.
- Audit correction: the previous shared `CloudUploadClient` could transfer bytes directly
  even though `HostCallFactory` rejects binary bodies. That shared provider and transfer
  implementation are removed. `CloudBinaryUploader` is bound independently in each
  flavor; a failed/unavailable host uploader cannot fall back to standalone OkHttp.
- Pinned host source and actual DEX expose `core.upload.c.upload(File, b, l)` through
  `common.ServiceFacade.get(Class)`. `core.upload.k` setters `p/y/z/r` carry bucket,
  object key, token and MIME type; `core.upload.l$b` supplies cache/server options and
  `core.n.b.a(long,long)` / `core.n.c.a()` progress/cancellation callbacks. The adapter
  uses the initialized official uploader, with Java proxies and platform types across
  class loaders, no module OkHttp/Kotlin models passed to the SDK, and resume caching
  disabled for private temporary files. SDK return value 1 is transfer completion,
  not business publication or an independently observed HTTP receipt.
- The TV high-level helper `h1.e0.b.a.a.c(...)` returns null and its token-refresh
  helper `d(...)` returns an empty string in actual DEX. The adapter therefore supplies
  captured-session business authorization to the generic uploader rather than invoking
  those stubs. Expired NOS authorization fails instead of fetching a new-account token,
  inventing a refresh contract, or switching transport. This limitation needs explicit
  live qualification; source signatures alone do not prove the SDK succeeds on AVD.
- Standalone retains its own NOS lookup and transfer protocol. Lookup responses are
  bounded; token-bearing uploads require an HTTPS `.127.net` origin with no userinfo,
  alternate port, path/query/fragment, redirects or connection-failure retries. Only
  the allocated upload token and file digest are attached, never account Cookies.
  Cancellation and session invalidation close its current call; token/raw server-error
  logging is not enabled. A bounded NOS receipt must acknowledge the full file length
  through `offset`, following the official uploader's offset-based completion contract;
  HTTP 200 alone cannot advance registration. Host URL selection/chunking stays inside
  the official SDK.
- A common coordinator captures one authenticated account across check, both token
  allocations, byte transfer, registration and publication. Every business call is
  tagged and checked before/after dispatch. Missing decisions/identities, rejected
  responses, cancellation or stale owners cannot continue the sequence. Pre-registration
  `songId=0` remains valid for a new object; publication requires a positive returned ID.
  Binary progress remains below completion until publication code 200 is accepted.
- The source URI is copied once to a private runtime-cache snapshot. Metadata, measured
  size, digest and transfer reference that snapshot; the original source is untouched.
  Success/failure/cancellation delete the temporary copy. After process death, the next
  Repository upload initialization removes only owned snapshot files in its dedicated
  directory. It does not claim persistent or automatically resumed upload jobs.
- Substitute tests cover phase ordering and payloads, existing-object skip, failure/no
  retry, wrong/stale owners, blocked-call cancellation, destination validation, changed
  bytes, incomplete/invalid NOS receipts, progress/publication boundaries and narrow
  snapshot cleanup. No user file is uploaded, no AVD state is changed, and no real account
  mutation is performed here. All 627 standalone / 692 parasite JVM tests pass, as do
  both debug, instrumentation and unsigned minified release builds. APK/DEX checks show
  flavor-specific uploader isolation, retained SDK reflection names/receipt guard and
  16 KB ZIP alignment. These are build/contract checks, not SDK runtime execution.
- Remaining: actual SDK callback/transfer acceptance, Android document-provider and
  metadata/snapshot lifecycle tests, expired upload-token behavior and live publication
  reconciliation. Official cancellation is checked through SDK callbacks; interruption
  latency during blocked host I/O is not qualified. API-020 subsequently adds cloud
  listing/deletion paging and ViewModel-level account/revision ownership. This is not
  complete cloud-feature, paired device, release-runtime or D4 acceptance; no
  UI/layout/navigation change is bundled.

### API-020: Cloud Library Reads and Actions Share One Session Owner

- Date: 2026-09-30. Both builds retain the original supplemental WeAPI business
  routes: `v1/cloud/get` with `offset/limit`, and `cloud/del` with a `songIds` array.
  Their payloads do not require separate flavor adapters; the named service selects
  the standalone Cookie transport or the official-host request pipeline. These routes
  are not newly discovered native TV cloud methods. No alternative-account/transport
  fallback or application-level mutation retry is introduced.
- The original frontend loaded at most 200 rows with no continuation control. A shared
  backend now reads all pages under one captured session before publishing a snapshot,
  without adding pagination UI or changing page architecture. It requires explicit
  business success, valid data/count/quota fields and advancing pages. Missing
  `hasMore` can use a valid count; malformed flags, duplicates, changed counts, stalled
  pages and inconsistent terminal pages fail rather than silently truncate the library.
  Detected drift is not retried automatically and this is not a server-atomic snapshot.
- Cloud rows must have a positive `songId`. The old `simpleSong.id` fallback could
  substitute a catalog identity for a missing cloud deletion identity. The pinned
  `PrivateCloudSong` separately stores `id`, `songId`, `userId` and
  `originalAudioSongId`; it does not justify treating these fields as interchangeable.
  Missing cloud identity now rejects the snapshot. Existing display fallbacks remain;
  private-cloud playback/download owner translation is still open under API-015/017.
- `CloudMusicSource` carries credential-free session stamps through list, deletion,
  file preparation and the upload coordinator. The document picker captures its owner
  when opened, not when its result returns. Canceled/orphan/stale results cannot select
  another account, and picker ownership is not restored across process death.
- Shared page state clears synchronously on account invalidation, cancels old-account
  work and rejects late reads/errors/progress. Same-account reauthorization also
  invalidates ownership. Refresh uses latest-request ordering while preserving current
  upload/delete flags; duplicate mutations are reserved before dispatch. Accepted
  deletion/upload refreshes the current account only. Canceled operations are not
  reported as success. Row callbacks verify their rendered page/session at dispatch;
  this does not qualify ownership throughout asynchronous private-cloud playback.
- All 30 added substitute cases pass in both variants: complete/short pagination,
  malformed identities/envelopes, changed snapshots, exact mutation payload/session,
  invalidation/cancellation, concurrent refresh/write state, stale document results,
  same-account reauthorization, unavailable session readers and in-flight invalidation
  after ViewModel disposal. No real deletion/upload or
  quota-consuming download is used for these checks. Device/provider, actual private
  cloud playback, account switching and full cloud-feature acceptance remain separate.
- Source follow-up (2026-10-01): cloud `songId` remains the deletion/UI entry while
  positive `simpleSong.id` supplies the audio identity. Explicit `simpleSong.pc.uid`
  and row `userId` must be positive and agree; malformed private-cloud metadata is
  rejected. When this own-library route omits both owners, the fallback is the account
  captured for that response, following the pinned `MusicInfo.getCloudSongUserId()`
  own-account fallback, never an account read later during playback. Real standalone
  samples omitted both owner fields. The file owner and authenticated account remain
  independent fields even when their values happen to match.
- Both cloud entry points now forward that identity into the existing player and
  download actions without page/layout changes. API-015/017 describe subsequent source
  validation, persistence and execution. The official test account was initially empty,
  as confirmed by the user; that was not a read failure. The user then manually uploaded
  one entry, which rendered and played on the AVD. No upload/delete request was performed
  by the agent and no Cookie/session was copied between the two different accounts.

### API-021: Private-Cloud Lyrics Have a Different Response Contract

- Recorded: 2026-10-01. Ordinary MeiloX lyrics use `song/lyric/v1`, numeric `id` and
  nested LRC/YRC/translation objects. The pinned TV cloud implementation requests
  `cloud/lyric/get` with `songId`, `userId`, `kv=0` and `lv=0`, and reads flat `lrc`
  and `krc` strings. Its own-cloud controller supplies the official account ID. Native
  business `404` means no lyrics; other failures are not an empty successful lyric.
  This analysis adds no hook using a JADX-generated alias or a new reflection target.
- `SongLyricBackend` selects that cloud route from the previously persisted source
  identity, retaining separate audio ID, file owner and authorization account. Own
  sources have the captured account as file owner unless explicit metadata says otherwise.
  Distinct file-owner/account tuples are covered by substitutes, not a real foreign-owner
  file. Catalog V1 parameters remain unchanged. Both routes carry an explicit session
  stamp through their already selected Cookie or official transport.
- Cloud response parsing accepts only business `200` or explicit no-lyric `404`.
  Missing/blank/null lyric strings remain absent; they do not prove pure music. Nested
  catalog-shaped or malformed fields, denied/unknown status, HTTP/network failures,
  cancellation, stale/recovery/foreign-account ownership are not no-lyric success.
  There is no retry as a catalog song or under another session/transport. Native cloud
  karaoke is retained in `klyric`, not mislabeled as catalog YRC or declared rendered.
- Shared repository requests now use this adapter with one captured session. Workers
  pass their full durable source key into embedded-lyric preparation rather than dropping
  it to the logical entry ID. AMLL remains first and uses the audio ID; official fallback
  preserves source affinity. Existing ordinary YRC/LRC precedence is unchanged; native
  karaoke-only data can be retained as download text. No user-facing control/layout is
  changed. End-to-end real cloud download/tag acceptance has not been performed.
- All nine new shared backend cases pass in both variants. They exercise exact IDs,
  session tags, catalog compatibility, karaoke retention, null/blank/no-lyric responses,
  malformed/denied responses, cancellation/transport failures and late account changes.
  Initial checked-exception injection was corrected to use the suspend continuation
  rather than Java Proxy wrapping; production code was not changed to hide that failure.
  An Android host substitute verifies exact route/payload plus stale-session rejection;
  both existing closed Workers assert that the cloud key reaches lyric preparation.
- Live read-only evidence: standalone's existing Cookie cloud account passes the new
  opt-in lookup. An explicitly enabled debug host probe reads the persisted one-entry
  cloud queue under the official session and returns an accepted result with no LRC or
  karaoke text. Only result/presence booleans are logged, never identities, credentials
  or lyric content. The probe neither uploads lyrics nor requests a download grant;
  ordinary debug/release flags remain disabled after qualification.
- Remaining: current/preloaded/manual-QQ/System Lyrics state and Room/in-memory cache
  ownership must consume the complete cloud identity rather than numeric entry IDs.
  API-022 subsequently implements that ownership path without changing page architecture.
  Native cloud karaoke still needs verified parser/timing adaptation in the existing
  merge path. Actual text/timing/rendering, real account switches, real embedded tags
  and minified release execution are not proven by successful endpoint reads or closed
  fixtures. This checkpoint is not full cloud-lyrics or D4/D5 acceptance.

### API-022: Cloud Lyric Consumers Must Retain Source and Request Ownership

- Recorded: 2026-10-01. API-021's request adapter was source-aware, but shared current
  and preloaded consumers still passed the logical entry ID. Memory/Room lyrics, QQ
  mappings, Song Info lookup and existing mapping-reset actions also keyed that ID.
  Two cloud files can have the same logical ID but different audio/file-owner/account
  tuples. Catalog lyrics and private-cloud lyrics must not share that numeric namespace.
- Current and preloaded loads now capture the complete `SongSourceIdentity` and one
  session stamp. Official lyric requests receive the full source key; public AMLL
  receives only its audio ID. Memory/Room/QQ entries use the full cloud key, while
  ordinary catalog keys and V1 bodies remain numeric. There is no lookup fallback to
  a cloud entry's numeric cache or QQ mapping, and no reinterpretation of old records.
  Song Info and all existing player styles pass their unchanged metadata to source-owned
  QQ lookup/reset operations. Their controls, navigation, layout and lyric precedence
  are unchanged; this is backend identity propagation, not a frontend redesign.
- Each current load, force reload or manual QQ selection starts a distinct request
  batch even if its stable key is unchanged. One immutable source-result envelope
  retains that batch through sampling and off-main parsing, including QQ's LRC
  fallback. Network, public-resource, search, merge, duet and cache publications check
  the captured session and batch. Cancellation propagates rather than becoming an
  empty lyric. AMLL's actual OkHttp call is canceled through its suspend continuation.
  Session invalidation/recovery clears private current state and session-affine memory
  caches, cancels pending work, and retries the unchanged metadata only after its
  account/session becomes valid; a foreign cloud account does not dispatch any source.
- System Lyrics compares the complete player/lyric source key, advances its track
  generation on source changes, and clears an old lyric timeline when its ownership
  no longer matches. Shared player/PiP lyric effects and next-item preloading also react
  to source changes, not just entry IDs. External provider/device acceptance remains
  separate from this source adaptation.
- Verification and live acceptance are recorded in the paired build-plan checkpoint.
  Native cloud karaoke parser/timing, real cloud text rendering, actual account-switch
  cooperation, embedded download tags and minified release execution remain open.
  Existing same-source lyric-priority/update behavior is deliberately retained; this
  entry does not claim fixes to inherited frontend behavior or full D4/D5 acceptance.
- Follow-up source review: the pinned TV request reader retains `krc`, but its reviewed
  lyric-controller display path parses common LRC; no native KRC-to-word parser was
  identified in that path. The older local watch client's `C2220a` karaoke parser and
  `FloatLyricView` supply a distinct historical format: word field one is a suspension,
  not an absolute YRC timestamp. Its view computes start as the preceding cursor plus
  suspension, end as start plus duration, and advances the cursor to end plus one ms.
  This is evidence against blindly passing `klyric` to `YRCParser`, not proof that the
  current TV cloud endpoint returns that watch format. These JADX names are analysis
  references only, not added hook targets. A real nonempty cloud response and timing
  qualification are still needed before declaring a native karaoke adapter complete.

### API-023: Cloud Favorites Need the Audio Identity and File Owner

- Recorded: 2026-10-01. The shared favorite selection still used the cloud UI/deletion
  entry ID after API-020 introduced a separate audio/file-owner identity. A cloud entry
  and its audio ID must not be treated as interchangeable or share star state merely
  because their logical entry IDs match.
- Pinned TV analysis: the ordinary favorite task calls `MusicInfo.getMusicLibraryId()`
  and `getCloudSongUserId()`. For this remote `MusicInfo` path, library ID delegates to
  filter/matched ID and ultimately `getId()`; it is not `CloudSong.songId`. Cloud file
  owner comes from private-cloud metadata, with the official own-account fallback
  already captured by API-020. Local-file matching is a separate path and remains
  excluded from this remote favorite control. The TV task also checks `canSub()` before
  writing; the current identity adapter does not qualify that privilege model.
- `SongFavoritesBackend` now requires the full credential-free `SongSourceIdentity`.
  Numeric compatibility methods construct only ordinary sources. The Repository
  checks source/account affinity before dispatch and current session/cancellation after
  returning; malformed and foreign sources never fall back to their numeric entry IDs.
- Parasite reads retain the full `song/like/get` snapshot and compare its IDs with the
  audio ID. Writes use `song/like` with `trackId=audioId`, lowercase `userid=fileOwnerId`
  and Boolean `like`. Ordinary songs retain `userid=0`, not the authenticated account.
  Host-generated referer/security fields remain in the pinned pipeline. Existing
  502/404 reconciliation re-reads audio state without repeating the mutation or
  dropping source/session ownership. Existing business failure rules remain unchanged.
- Standalone keeps `song/like/check` with the audio ID in its single-track query, and
  its original `radio/like` compatibility write with string `trackId`, `alg=itembased`
  and string `time=3`. It validates source/account affinity but does not invent a TV
  `userid` field or change routes. Real legacy Cookie cloud-write acceptance, especially
  unmatched private files, is not established by a successful read or substitute test.
- Shared player state retains logical entry ID for presentation and full source key
  for selection/request ownership. Changing audio or file owner under the same entry
  clears the previous star state, cancels work and rejects late results/old clicks.
  Recovery and account invalidation clear state; a foreign account cannot query the
  retained cloud selection. The existing controls, icons, layout and navigation are
  unchanged; only metadata/effect ownership is forwarded through the shared frontend.
- Validation and live-read evidence are recorded in the cloud-favorite checkpoint of
  `dual-runtime-build-plan.md`. Real like/unlike writes are not automatically issued;
  closed substitutes test both directions and reconciliation. Playlist identity,
  playback-report identity, native cloud karaoke timing and complete cloud parity
  remain separate unfinished gates.

### API-024: Cloud Playlist Mutations Must Retain Source Identity Until the Wire Boundary

- Recorded: 2026-10-01. The original shared add-to-playlist overlay retained only
  `MediaMetadata.id`. For own-cloud rows that is the UI/deletion entry, not necessarily
  the audio identity. Passing it as an ordinary catalog track silently discarded the
  file owner and captured account before the selected runtime could validate them.
- Pinned TV DEX: `CollectMusicFragment$d$a.invokeSuspend` calls `MusicInfo.getId()`
  and then `com.netease.cloudmusic.app.f0.b.M`; the private `R` method sends that
  audio ID as a quoted numeric string in `trackIds` to `v1/playlist/manipulate/tracks`.
  Its add body contains `op`, `pid`, `trackIds`, `reverse=true` and native `checkToken`.
  Unlike API-023's favorite write, this controller does not send `userid`, an
  `audioId_fileOwnerId` tuple, or the own-cloud row's deletion entry. MusicInfo parsing
  assigns the remote song JSON's `id` to `getId()` and reads `pc.uid` separately.
  These actual DEX names are analysis references, not new hook targets.
- Shared adaptation: all existing player/menu/download add entry points forward the
  original metadata through the same overlay and picker. ViewModel entry/source
  validation occurs inside the guarded mutation, so malformed metadata cannot crash a
  click or fall back to an entry ID. Repository batches retain typed credential-free
  sources, reject every malformed/foreign source before dispatch, and preserve input
  order. Both runtime adapters validate the captured account and map to distinct audio
  IDs only at the wire boundary. Numeric compatibility methods represent ordinary
  tracks only; cloud sources never use that fallback.
- Parasite preserves the source-confirmed add route and `reverse=true`; security and
  authentication stay in the host. Standalone preserves its original Cookie route
  `playlist/manipulate/tracks` and `imme=true`, without importing TV ordering/security
  fields. Neither backend serializes cloud entry/account/file-owner fields or retries
  a write through another route. Same-audio entries/files collapse to one wire ID only
  after every source's account affinity is validated.
- The existing supplemental remove path also accepts typed source metadata but remains
  unqualified as a native TV remove caller, as recorded in API-009. Business codes and
  optional offline-ID metadata remain distinct outcomes. Pending recovery, stale
  sessions, cancellation and late replies cannot publish an accepted mutation or an
  obsolete callback. Recovery also clears the account-owned picker; no screen, layout,
  icon, text, navigation or backend-specific frontend fork is added.
- Verification is recorded in the cloud-playlist checkpoint of the dual-runtime plan.
  The host-process probe uses its own synthetic transport/session, never rebinds the
  real graph, and verifies the production adapter/Retrofit Body/Tag compatibility
  without sending official writes. Real cloud add/remove/duplicate acceptance,
  native privilege behavior, cloud playback-report IDs and paired release runtime
  qualification remain separate unfinished gates. No real playlist is modified.

### API-025: Cloud Playback Reports Use Audio Identity, Not the UI Entry or File Tuple

- Recorded: 2026-10-01. Shared MusicService previously took a report's song ID and
  timer identity directly from MediaItem.mediaId. For cloud metadata that is the
  UI/deletion entry; the audio, file owner and captured account can differ. A new
  file under the same entry could also inherit the old timer/completion identity.
- Pinned TV DEX: `com.netease.cloudmusic.module.player.m.q.a1` builds a song event
  with `id=MusicInfo.getFilterMusicId()`. The remote MusicInfo implementation of
  `getMatchedMusicId()` returns `getId()`, and `getFilterMusicId()` falls back to that
  ID when no positive match is available. This is the parsed remote audio identity,
  not the own-cloud deletion entry. The event builder does not encode the playback
  `audioId_fileOwnerId` tuple or favorite-write `userid` into its `id` field. Local
  matching uses separate native subclasses and remains outside song reporting here.
- Shared adaptation: both player event callbacks use the full source key as an opaque
  timer identity. Reporter start captures the typed source and checks key/source/account
  consistency before queuing; dispatch and both runtime sink boundaries recheck the
  captured session and source. Only the audio ID is serialized as `id`; entry/file-owner/
  account fields and the internal key remain out of the wire body. A stale entry-only or
  different-file completion cannot finish the currently selected private file. Existing
  active-time accounting, pause/buffer exclusion and captured start time are retained.
- Parasite still sends actual playback events through the official reporting SDK and
  its existing legacy/BI ownership guards; it does not introduce a direct report route.
  Standalone keeps its original Cookie weblog and NCBL channels. Its NCBL context key
  now includes the internal source key so equal audio IDs/start times on different
  private files do not overwrite each other. No TV file-owner parameter, marker or
  credential transfer is added to the standalone report body.
- Playback origin is a separate contract. Native `q.h1` uses PlayExtraInfo's log type;
  `q.i1` uses its source context, not file/account identity. PlayExtraInfo maps source
  type 50 to `cloudSong`, but a native cloud caller and its exact sourceId have not been
  qualified. Shared code retains its existing `track`/audio-ID fallback instead of
  inventing a sourceId or inferring every cloud file's playback origin. Native bitrate,
  privilege, algorithm and other ancillary event fields also require separate parity
  evidence; correcting `id` does not qualify the complete native reporting contract.
- Verification is recorded in the cloud-report checkpoint of the dual-runtime plan.
  Closed report sinks exercise source/account/queued-event ownership without sending
  server statistics. Official processing, HTTP/business acceptance and later listening
  history/aggregation are distinct evidence levels. Local history replay still requires
  a separate source-preserving persistence adapter; paired release execution and final
  server statistics are not established by this identity correction.

### API-026: Device-Local Cloud History Must Retain Source and Account Affinity

- Recorded: 2026-10-01. Shared playback previously persisted the numeric MediaItem
  entry ID as Room Song.id. Room mapping hashed nonnumeric IDs, history merging used
  the numeric entry and both history click paths rebuilt ID-only placeholders. These
  steps lost the cloud audio/file-owner/account tuple and could overwrite or replay
  a catalog/download row with the same UI entry. Official remote recent history
  (`/api/play-record/song/list`) is a separate contract; its present AccountSong model
  has no qualified private-file source tuple. A matching title or numeric ID is not
  evidence that a server row belongs to an own-cloud file.
- Both runtimes now use the existing canonical source key as the string primary key
  for new local cloud history only. The key retains entry, audio, file owner and
  captured account; the original numeric entry stays in MediaMetadata.id. New cloud
  rows retain exact milliseconds. Ordinary/local IDs, their existing duration units,
  database version 21 and legacy rows are unchanged. No credentials, database migration,
  historical rewrite or alternate remote history endpoint is introduced.
- Playback captures the source's current SessionStamp before asynchronous persistence.
  The repository checks cancellation and ownership before dispatch, before insertion
  and after insertion inside the existing Room transaction. A changed session rolls
  back both metadata replacement and its cascading history effects; cloud failures
  cannot take the legacy split-write constraint fallback. Owned device-local metadata
  remains available during recovery, matching the existing offline source policy;
  this does not authorize a network request during recovery.
- History publication filters private rows to their current account without deleting
  foreign data. Account/recovery transitions cancel old remote work and reject late
  results. Retained or substituted click callbacks must still match visible entries
  and the captured session. Merging uses full source identity; cloud clicks pass their
  complete MediaItem into the existing queue, avoiding catalog hydration. Ordinary
  and local entries retain their original placeholder strategy. No layout, resource,
  navigation, label or backend-specific frontend tree is added.
- Malformed or unknown reserved cloud keys cannot be hashed into catalog IDs. They
  remain stored but are excluded from history presentation/replay. Legacy numeric
  rows are not inferred to be private files, so a new source-owned row may appear
  beside an older same-title row. Native unmatched/private remote recent history
  decoding and legacy source reconstruction remain unqualified; the visible distinction
  is preferable to inventing affinity or hiding an incomplete mapping.
- Verification: ten new shared JVM cases and twelve new closed device cases per
  runtime cover full-source merge, stale/recovery/late results, retained clicks,
  transaction rollback, malformed keys, duration and database reopen. The actual TV
  process passes an isolated in-memory Room probe without rebinding AppGraph or calling
  an SDK. Real cloud selection writes a source-owned local record, the existing
  history tab replays it, and a read-only probe after process recreation confirms the
  selected full source and exact duration in its 48-entry queue. The dual-runtime
  checkpoint records normal-module restoration and final build/device checks.
- This qualifies the new local cloud record/replay path, not server recent-history
  source parity, audible output, real account-switch cooperation, cloud uploads,
  final listening aggregation, production upgrade or paired signed release execution.

### API-027: Cloud Snapshot Preparation Has a Provider Cancellation Boundary

- Recorded: 2026-10-01. Both runtimes still use API-019's one-owner upload coordinator
  and private snapshot. The previous synchronous preparation checked cancellation
  between reads but queried/opened ContentResolver without a CancellationSignal.
  A provider blocked in those operations did not receive the preparation job's
  cancellation. This is a shared runtime/session boundary, not a new cloud business
  endpoint or a frontend redesign.
- Preparation now connects a cancellable continuation to Android CancellationSignal
  for query and asset-descriptor opening, and closes its active descriptor/stream on
  cancellation. AssetFileDescriptor.createInputStream retains a provider's offset and
  declared length. Owner/recovery/job checks follow query, opening, each read, local
  metadata extraction and MIME lookup. A late result cannot reach allocation for a
  changed account. Rejection, copy failure and prompt cancellation of a completed
  result all remove the owned temporary copy; the selected URI is never rewritten.
- Size, MD5, metadata and transfer continue to reference one snapshot. Filename/MIME
  and missing-tag fallbacks, business phase ordering, token handling, runtime uploader
  selection and publication semantics are unchanged. No persistent job, automatic
  restart, saved picker authorization, cross-runtime URI/data transfer or new permission
  is introduced. The existing picker/result ownership guard remains in place.
- Recovery is checked independently of session generation at every coordinator
  business/progress boundary and both binary adapters. A same-generation recovery
  cannot allocate, register, publish or return transfer success. Standalone also
  observes recovery while a NOS call is blocked and cancels that call; the host SDK
  receives recovery through its existing cancellation/progress callbacks. Neither
  flavor borrows another token, session or transport to recover.
- On the current AVD, ContentResolver's read-only content opening uses the provider's
  typed-asset overload. Cooperative provider tests forward its signal explicitly;
  inheriting a default overload does not prove cancellation support. A denied MIME
  lookup can be converted by the platform to null, retaining the original `audio/mpeg`
  fallback after a separately authorized byte read. MIME lookup is not a read-permission
  or account-authorization substitute.
- The pinned TV APK's actual Uploader DEX hashes the file with `core.n.c.a()` cancellation
  checks before source allocation/LBS/transfer. Zero length returns -1; digest cancellation
  returns -2. A debug-only pre-transfer probe invokes the real ServiceFacade-backed
  adapter with an empty file and an always-canceling SDK digest callback, synthetic
  bytes and an invalid fixture token. This cannot qualify successful NOS transfer or
  publication. No real token allocation, source file, account or completed download
  is used as a substitute. Probe runtime evidence is recorded in the dual-runtime plan.
- Device tests use a test-APK-only generated-audio provider and actual ContentResolver
  IPC; coordinator/business/binary sinks are closed substitutes. Their manifest is
  not part of either app APK. Synthetic denial and cancellation do not establish a
  real external provider's persistent grants, reboot access or cooperation. Embedded
  tag formats, successful official progress/transfer callbacks, expired NOS tokens,
  live publication reconciliation and paired signed release remain separate gates.
- CancellationSignal only works where the provider/platform supports it. MIME lookup
  has no signal argument; an uncooperative provider/read or blocked official SDK I/O
  may still delay return. Post-return ownership checks prevent continuation, but this
  change does not claim immediate interruption of every provider or host network call.

### ABI-006: Component Attachment and Transport Factories Belong to the Runtime

- Date: 2026-09-29. Standalone components use their own Application, registered Activity,
  playback service and platform media receiver. Parasite components are instantiated
  inside the pinned TV process after its module graph is initialized, use a module
  resource/storage Context, and receive media events through the official carrier.
  A module-specific Context wrapper or receiver state must not become a shared frontend
  requirement when standalone is restored.
- Shared Activity/service code now consumes `ComponentRuntime`. The host adapter alone
  chooses wrapping, portrait initialization, startup-key deferral, resume ownership and
  platform controller binding. No hook scope, manifest permission, global orientation
  setting or existing Activity/service lifecycle order is changed. Graph initialization
  must still precede component attachment in both future flavor bootstraps.
- Shared Retrofit construction accepts distinct named API/WeAPI/audio-match factories.
  The parasite module binds all three to `HostCallFactory`, so official authentication,
  signing and request ownership are unchanged. No standalone client is created or used
  as a fallback. Factory isolation tests are not proof of equivalent endpoint semantics
  or a working standalone session.
- Shared download notifications build ordinary MainActivity PendingIntents. The existing
  host-scoped PendingIntent hook maps them to the registered launcher; the explicit
  second routing call was redundant and is removed. Notification IDs/content remain
  unchanged. Host-only probe selection and foreground dispatcher callbacks stay in the
  carrier adapter. Standalone foreground types and aggregation still require qualification.
- Evidence: 642 JVM tests and 46 parasite device tests pass, including runtime identity,
  host-only wrapping/orientation, disabled resume ownership and platform MediaSession
  adapter binding/replacement. An actual host-process synthetic DENIED worker promotes
  the carrier, executes one substitute grant with zero resource transfers, leaves no
  artifact and posts a failure notification. Tapping it returns to the original portrait
  Home. Cleanup removes only its private test rows/channel. This is notification-routing
  evidence, not a new user download failure, real server denial or standalone acceptance.
- Debug/instrumentation and unsigned R8 builds pass; release APK 16 KB alignment passes.
  The restored ordinary debug package has probes disabled and cold-launches portrait
  Home. System PLAY/PAUSE controls the active module session and advances position from
  47,014 ms to 74,255 ms while the official session remains inactive; playback is left
  paused. This is not audible-output, cold media-receiver resumption, server-statistics
  or release-runtime acceptance. The existing real download remains published unchanged
  in size/ownership; no new real download authorization was requested.

### ABI-007: Source Sets Own Package Registration and Legacy Migration Policy

- Date: 2026-09-30. The standalone backend requires its own registered launcher,
  playback service, Cookie/signing implementation and upgrade policy. The official TV
  runtime instead instantiates the same shared Activity/service classes behind installed
  host carriers. A module APK does not need standalone music components registered in
  its own manifest, and module metadata must not leak into the standalone artifact.
- Added a `runtime` flavor dimension with the current `parasite` target. All host hooks,
  bridges, QR login and probes, API 102 compile dependency, Xposed metadata and reflective
  WorkManager R8 rules are parasite-owned. Flavor-specific graph extension/bindings
  expose host bootstrap operations without requiring concrete host types in common code.
  Shared UI/navigation/player source and resources are unchanged.
- Original NetEase interceptor/header, RSA/EAPI/WeAPI signing, IP helper, component
  manifest and header R8 rule are preserved under standalone-only paths. The standalone
  flavor is not yet registered; these retained files do not constitute a restored or
  validated backend. Shared media resource mapping and bounded response parsing remain
  available to both future variants. Parasite has no standalone network fallback.
- The Room 18-to-19 policy is now supplied by `RuntimeDatabaseMigrations`. Parasite
  retains the existing URL clearing and unowned pending-task failure policy exactly.
  Standalone must not inherit this policy blindly: account ownership, pending work and
  legacy completed-file preservation need their own fixtures before upgrade acceptance.
- The original standalone music Activity/service declarations move out of the common
  manifest. Parasite keeps its module label and TV-only static scope; it does not alter
  the installed TV manifest, permissions, orientation compatibility flags or host APK.
  APK paths and current CI signing/metadata paths now include the parasite flavor.
  Two-artifact CI, standalone startup and both release runtimes remain open.
- Evidence: 642 parasite/shared JVM tests and all 49 parasite device tests pass. The
  installed APK has no registered standalone music Activity/service or launcher, has
  only the TV static scope, and cannot load the original standalone signing classes.
  The existing isolated Room 18-to-20 test still preserves completed rows and rejects
  unowned pending grants. Five standalone interceptor tests remain retained but are
  not part of the currently executed suite; common bounded-body coverage is separate.
- Debug/instrumentation and unsigned R8 builds pass; release alignment passes at 16 KB
  and its mapping preserves shared component/module-entry classes. The ordinary debug
  package cold-starts the original portrait MeiloX Home in TV, with the official account
  and paused queue restored. One module music-service session is created and no
  current-process crash is recorded. System PLAY/PAUSE advances its position from
  74,257 ms to 94,809 ms; the official player remains inactive and playback ends paused.
  The existing published TV-owned download remains 22,705,573 bytes. This does not
  qualify audible output, new grants, standalone behavior or release runtime.

- Follow-up package qualification on 2026-10-02: the release gate now parses actual
  manifest XML, requiring the shared Application and original usable standalone
  launcher/playback service while rejecting independent launcher aliases, module-owned
  component registrations in parasite, host component registrations in standalone and
  production instrumentation. Missing/failed/malformed manifest inspection is fatal.
  A missing-standalone-launcher fixture was accepted before this gate and is rejected
  after it. All 56 local release checks pass, including the current signed R8 pair;
  component-negative XML fixtures are distinct from the actual APK negatives.
- Ordinary release forces the app on and diagnostic flags off. Actual R8 removes the
  capability/Retrofit/work-worker/storage diagnostics; its retained work receiver is
  a no-op. Prototype Activity/service classes remain due to component keep rules, but
  have no module manifest registration and are not selected by the ordinary carrier.
  The opt-in Room fixture now validates current-schema reopen rather than asserting
  an obsolete 17-to-18 upgrade. This is diagnostic/package evidence, not original
  installation upgrade, complete host storage qualification or server acceptance.

### ABI-008: Standalone Cookie Publication Must Not Borrow Host Ownership

- Date: 2026-09-30. The original standalone interceptor read `CookieKey` on every send;
  manual login temporarily wrote a candidate Cookie before profile verification. This
  cannot preserve the shared generation/ownership contract introduced for the official
  backend: a queued request could otherwise use a different account's credentials.
- Standalone now captures credentials with its public `SessionStamp`, while private login
  verification targets `/api/w/nuser/account/get` through the original EAPI signer before
  any durable/live account replacement. Saved Cookie/UserId pairs are reverified rather
  than trusted as a matching identity. Same-account renewal also advances generations.
  Failed/canceled verification leaves the prior account untouched, and late verification
  cannot undo logout or overwrite a newer attempt. Official TV login/session code is
  unchanged; no host credentials are exported to standalone.
- The shared generation-aware transport wrapper guards dispatch, callbacks and unread
  bodies, cancels pending work on invalidation and pins clones to their original owner.
  Standalone API/WeAPI retain their own signing and a separate plain Android-UA audio-match
  client. Default TLS validation replaces the original debug trust-all configuration;
  signed redirects are disabled. QQ/artwork/media paths are not redirected through TV.
- Original WebView/manual Cookie controls and layout are restored in the standalone
  source set, with Dagger bindings instead of Hilt. WebView polling only reports verified
  success and does not repeatedly submit an unchanged rejected Cookie. The parasite
  QR page, common navigation, player and glass components are unchanged.
- Standalone weblog start/end events are wired to the common playback lifecycle, using
  the original EAPI playback-history profile. NCBL, expired-session recovery behavior,
  real account transitions, authenticated request coverage and listening statistics are
  still open. Keeping the standalone migration's legacy rows is not proof that old
  pending workers, queues or media references can upgrade; that adapter remains pending.
- Evidence: 555 standalone JVM tests and 654 parasite JVM tests pass (shared tests are
  counted in each variant). Five signing tests inspect captured MUSIC_U in the Cookie
  and decrypted EAPI header, anonymous omission, missing-credential rejection, WeAPI form
  construction and retained weblog profile. These are fixture wire checks, not server
  acceptance. Three standalone package/graph and all 49 parasite device tests pass.
- With TV stopped, the isolated standalone debug package cold-starts its own anonymous
  Home with live content, opens the original WebView and Cookie sheet, and has neither
  Xposed metadata nor host adapter classes. The AVD still has LSPosed installed; no
  framework-free device run or real standalone login has been accepted yet. Parasite
  still cold-starts the original portrait Home with its official account and paused queue.
  The original standalone installation and the existing real TV download are preserved.
- Follow-up (2026-09-30): the user supplied a Cookie for isolated standalone testing.
  It was entered through the unchanged manual sheet, verified against the exact file
  contents without printing them, and accepted by the live verification endpoint.
  The UI showed successful login, then authenticated Library and cloud rows. A subsequent
  process-cold launch restored the account/avatar without entering the Cookie again.
  Only the separate standalone debug package was updated; neither TV credentials nor
  the original standalone installation/data were read or replaced. This qualifies the
  manual Cookie path and this account's read/recovery path, not interactive WebView
  authentication, logout/account switching, playback/reporting or release upgrades.

#### Original Candidate Account Verification Alternative (2026-10-02)

- Bounded comparison with current main `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29`
  confirms that both original WebView completion and manual Cookie login use the
  account-profile helper: EAPI `/api/w/nuser/account/get`, then EAPI
  `/api/nuser/account/get` after a failed first request. The private candidate verifier
  initially retained only the first route. A first-route failure could therefore deny
  standalone login/recovery even when the original alternative would succeed. This is
  a source-contract regression, not an observed failure of the user's live account.
- The standalone verifier now restores exactly one alternative through the existing
  flavor request policy. Both requests use `{}`, original EAPI signing, the same private
  candidate Cookie and captured public stamp. A positive parsed account identity is
  still required before publication. Original 2xx business-code validation, omitted
  code compatibility and `NetEase user` default nickname are restored. Accepted but
  missing/invalid profiles do not trigger an alternative, matching main's parse order.
- Cancellation, changed owner or changed recovery state prevents alternative dispatch
  or account publication. A recovery already pending at verification start remains
  allowed: blocking all recovery-state requests would prevent persisted Cookie
  verification itself. Failed alternatives cannot overwrite the active credentials;
  durable publication remains in the existing latest-attempt controller/session store.
  Official QR login/session/request code, UI controls and transport budgets are unchanged.
- Three targeted assertions fail before restoration. Ten production-verifier/signing
  JVM cases cover HTTP/business/transport/JSON failure, terminal alternatives, original
  success/default mapping, pending recovery, changed accounts/recovery, stale owners
  and canceled noncooperative completion. All responses and credentials are synthetic;
  the terminal interceptor opens no socket. Existing account-publication race tests
  remain separate. Live server-induced fallback, WebView authorization, expiry/logout/
  account switching and original-install upgrade still require their own evidence.
- Final paired JVM suites pass 883 standalone and 922 parasite cases, with no
  failures/errors/skips; both release artifacts and real-SDK release gates pass. The
  isolated standalone R8 and restored ordinary debug cold-start their existing account
  and Home, without entering a Cookie or switching accounts. Five standalone and four
  matched-debug parasite package/device cases pass. Initial runner/R8 classpath and
  post-install attach failures are excluded, not counted as executed test success.
  The build-plan checkpoint records hashes, runtime evidence and data-preservation
  checks separately from the still-unqualified live alternative/failure matrix.

#### Original Transport Timeout Budgets (2026-10-02)

- Comparison with `main` at `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29` found a
  standalone restoration regression: business connect/read/write and audio-match
  connect/read timeouts had changed from 30 seconds to 15. They now retain 30 seconds;
  audio-match write remains OkHttp's original 10-second default. Neither client gains
  a total-call deadline. Candidate Cookie verification uses the same business client.
- NCBL retains its original 15-second connect/read/write/call limits, disabled retries
  and redirects. Default TLS, captured session credentials, guarded dispatch/response
  ownership and disabled signed redirects remain unchanged. The official TV pipeline,
  QQ/media clients, signing algorithms and playback-URL download policy are untouched.
- Three tests exercise the actual production client factories: two timeout assertions
  fail before the repair and all three pass afterward. The audio-match UA/unsigned
  request check ends in a synthetic interceptor without opening a socket. R8 constructor
  and builder DEX retain the 30-second and 15-second policies. Paired full JVM suites,
  debug/release builds and real-SDK package isolation checks pass; the build-plan
  checkpoint records the isolated R8 device read and data-preserving debug restoration.
- This establishes original configuration and ordinary read-path restoration, not
  measured slow-server timing, actual microphone recognition, listening statistics,
  production upgrades or a fix for the separately observed near-end playback stall.

### ABI-009: Standalone and Official Playback Reporting Stay Separate

- Date: 2026-09-30. Dual-runtime scope supersedes API-016's module-only cleanup as a
  project-wide policy. Standalone restores its original weblog plus NCBL `_plv`/`_pld`
  client, while parasite continues to use only the TV's initialized native SDK. Neither
  is a fallback for the other, and the NCBL/Zstd implementation is absent from parasite.
- The original standalone codec/payload are restored unchanged from the recorded `main`
  baseline. Its context provider now captures credentials from the verified standalone
  session and tags uploads with that authorization generation. It no longer rereads an
  unowned Cookie preference. Original Android reporting profile, device metadata,
  multipart endpoint and exact-filename acceptance rules remain intact.
- The shared event contract adds immutable start metadata and suspendable delivery,
  without changing player timing, page layout or official SDK payload fields. Standalone
  starts and ends share one context keyed by owner, song and exact millisecond start,
  avoiding collisions between same-song starts in the same second. Active elapsed
  seconds still exclude pause/buffering. Failures in one standalone channel do not
  suppress the other; no automatic cross-channel or account fallback is added.
- Owner checks cover context creation/publication, dispatch, unread response bodies and
  acceptance. Invalidation cancels calls and removes older contexts; caller cancellation
  cancels both channels and drops the pending start context. The NCBL client is restricted
  to its original HTTPS upload endpoint with matching credentials, redirects disabled,
  no connection-failure retry and bounded response consumption. Logs exclude credentials
  and raw server errors. HTTP/business/file acceptance does not prove final statistics.
- Evidence: 578 standalone and 656 parasite JVM tests pass; both debug/instrumentation
  APKs build. Tests cover original golden codec fixtures, Zstd round trips, payload and
  response rules, independent channel failure, queued metadata, cancellation, stale
  sessions and unchanged host fields. APK/DEX inspection confirms source/dependency
  isolation. Both minified unsigned release builds also pass, with reporting/JNI entry
  points retained. Debug/release ZIP alignment and the restored Zstd ELF's `0x4000`
  LOAD alignment pass. At that checkpoint the compiled Android native smoke test had
  not run; no AVD state was touched while user login was pending. This was not Android native, real upload, audible/full-song
  playback, release runtime or listening-history/statistics acceptance.
- Follow-up (2026-09-30): after the user supplied standalone login credentials, the
  native smoke test passed on the API 37, 16 KB AVD. Packaged Zstd compress/decompress
  and NCBL envelope encoding are now device-qualified. That checkpoint did not perform
  reporting requests or music playback.
- Playback follow-up (2026-09-30): actual standalone playback produced accepted
  weblog start/end requests and NCBL `_plv`/`_pld` file receipts (HTTP/business 200),
  retaining the same start timestamp across pause/resume. A separate authenticated
  `/api/play-record/song/list` read found the selected song within the explicitly
  bounded playback time window, using the existing recent-history decoder. The
  opt-in test reads the server, not the local history database; it does not emit
  synthetic play events or copy host credentials.
- Timing remains unqualified: the observed NCBL end reported 257 active seconds
  against a 236,434 ms source timeline. The shared elapsed-realtime counter is
  unchanged from `main`; the cause of this AVD discrepancy has not been established.
  Do not equate receipt acceptance or recent-history presence with accurate listening
  duration, weekly totals, cross-day settlement or audible playback. Release runtime,
  real account switching and paired reporting acceptance remain open.

### ABI-010: Legacy Standalone Work Inputs Do Not Carry Request Ownership

- Date: 2026-09-30. Source audit against the recorded standalone `main` baseline.
  Old `DownloadWorker` inputs contain a JSON song-ID list plus playlist and destination
  path. Its Room task holds an already resolved playback URL and song metadata, but
  no account ID or work-request UUID. The current shared worker instead requires one
  song ID, a public account owner and a matching persisted UUID, then obtains a fresh
  runtime-selected source. Parasite uses a dedicated download grant; standalone uses
  the original playback contract. See API-017 for the separate permission/response
  contract and the confirmed policy below.
- The standalone migration preserves those rows/URLs, unlike the parasite migration,
  but this alone does not restore execution: old `song_ids_json` input is not recognized
  by the current `song_id`/`owner_id` worker. Old destination metadata is in WorkManager,
  not those Room rows. A conversion must preserve it, keep completed file references
  and never silently transfer unfinished work to whichever account logs in next.
- A persisted account ID can only establish legacy affinity, not prove the current
  Cookie belongs to that account. Authentication must be verified before any resumed
  account-bound operation. Unknown ownership and changed accounts need explicit handling;
  do not infer a grant from the existence of a stale URL or completed task row.
- The download-policy choice was pending at this source audit and has since been
  confirmed below. Parasite behavior and its already accepted real download are
  unchanged. No real request, file transfer, WorkManager mutation or AVD upgrade was
  performed for this initial audit. Legacy work conversion and persisted-work execution
  acceptance are not implemented or passed.

#### Standalone v17 Fixture Qualification (2026-09-30)

- The standalone 18-to-19 migration deliberately differs from parasite: it adds the
  ownership/destination columns without clearing legacy URLs or failing pending rows.
  Three new device tests now exercise the complete 17-to-20 chain using a frozen,
  independently created 13-table v17 entity-schema fixture from the recorded `main`
  baseline. They do not downgrade a current Room database or copy installed user data.
- Populated/empty upgrades and populated reopen pass current Room schema validation.
  All old columns, SQLite value types and values survive, including every download
  status/URL/progress, song metadata/file references, playlist order, history and
  lyric/color caches. DAO reads expose the completed fixture downloads, and the private
  dummy file's bytes are unchanged. Its synthetic content URI is compared as stored
  text only; no real MediaStore permission, playback or transfer is implied.
- Added ownership fields remain empty/zero with the default destination; the new
  account membership and artifact tables stay empty. A retained remote playlist row
  is not proof of current-account membership, so account-filtered queries await the
  authoritative refresh. Local playlists remain visible. Tests do not assign any
  unfinished legacy work to the currently authenticated account.
- An injected failure after the final migration rolls back all three steps to the
  original v17 schema/data. Retrying real migrations succeeds and preserves history
  autoincrement. All three migration cases and four existing standalone package/graph/
  native-codec checks pass on the API 37, 16 KB AVD; the tests clean their UUID-named
  private databases/files. No production migration or frontend code changed.
- This is database-fixture evidence, not signed production update acceptance. Earlier
  pre-v17 migration histories, external provider grants, preferences/queue files and
  WorkManager inputs/execution remain unqualified. The download-policy choice was still
  pending at this fixture checkpoint and is now resolved below; the legacy work
  converter remains required. No quota-consuming grant, social mutation or user-media
  write occurred during the database-fixture checkpoint.

#### Confirmed Dual Download Policies (2026-09-30)

- User decision: retain original playback-link downloads in standalone. Parasite keeps
  its verified dedicated official download authorization, with no playback fallback.
  One shared `DownloadSourceBackend` now supplies both repository preflight and the
  real `DownloadWorker`. Menus, navigation, quality controls, transfer validation,
  publication and queue ownership remain shared; no frontend layout was changed.
- The dedicated Retrofit method, object DTO, tuple request and all 17 official resolver
  tests now belong to parasite source sets. `HostDownloadSourceBackend` still executes
  through the official host factory/session. Its resolver and denial/quality semantics
  are retained; the shared `ApiService` no longer declares the dedicated endpoint.
- `StandaloneDownloadSourceBackend` sends numeric ID arrays to the original
  `/api/song/enhance/player/url/v1` through standalone Cookie signing/transport. It
  reuses the original playback quality fallback list and retries only missing songs,
  in batches of at most 200. It never sends the official download tuple or calls the
  dedicated endpoint. Returned server quality remains distinct from requested quality.
- Standalone array decoding requires complete, unique, requested identities and
  explicit statuses. Trial/missing sources and known per-song denials may try the next
  original quality; authentication, unknown status, network and malformed responses
  abort instead of becoming partial success. Accepted sources require HTTP(S), known
  type, positive 64-bit size and MD5. Optional expiry/level are not invented, and
  dispatch-based expiry is rechecked after the whole resolution. Neither adapter stores
  a resolved URL or allows recovery/account-generation changes to publish late results.
- Four new shared repository tests and 14 standalone backend tests cover selection,
  signing/tags, batching/fallback, malformed/trial/expired responses, account changes,
  recovery and cancellation. All 667 standalone and 735 parasite JVM tests pass.
  The 17 official resolver cases moved to parasite rather than being removed.
- On the rooted API 37, 16 KB AVD, the graph-selected standalone backend obtains a
  complete standard-quality source for public song `30245467` using the existing
  isolated debug account. The opt-in test does not print or transfer the signed URL.
  This proves a live playback-source read, not a real completed download or dedicated
  download permission. No new official grant or quota-consuming TV download occurred.
- Five new standalone device cases run the actual shared Worker, production standalone
  backend/signing, private Room and Android MediaStore with fake credentials and closed
  network/media substitutes. Success/completed retry, denial/trial, corruption/truncation,
  wrong owner and session invalidation pass. Fixture publications are uniquely named
  and removed; installed credentials, user work and existing media are not fixture data.
  These direct Worker calls do not qualify WorkManager scheduling/process recovery.
  Seven standalone package/graph/codec/migration regressions and 12 parasite source/
  Worker substitute tests also pass, for 25 selected device tests including the live read.
- Both debug and unsigned minified release builds pass. Release package IDs remain
  correct and both APKs pass 16 KB ZIP alignment. Standalone DEX has the playback route
  but not the dedicated download route; Xposed metadata counts remain zero/three for
  standalone/parasite. R8 retains each adapter's Retrofit session-tagged method and
  nullable source DTOs. These are build/package checks, not release-device acceptance.
- Started the AVD with the user-provided rooted launcher; root and LSPosed modules are
  present, and both TV-specific orientation overrides survive this cold boot. Both
  authenticated original Home screens and paused restored mini-players return after
  debug update/restart. The existing TV-owned MediaStore row remains published at
  22,705,573 bytes. The crash buffer is empty. No original standalone app update,
  real media transfer, logout, account switch, upload or social write occurred.
- Legacy `song_ids_json` conversion, old ownership/destination recovery, startup
  scheduling, real account changes and signed-release upgrades remain unqualified.
  Confirming the source policy and preserving old rows do not complete those gates.

#### Legacy Work Conversion and Startup Gate (2026-10-01)

- Implemented standalone-only legacy work conversion and download startup coordination.
  Standalone disables the default WorkManager startup initializer and provides its
  gated factory through the flavor-owned Application base. The parasite Application
  base remains an ordinary Application; its initializer and isolated host scheduler
  configuration are unchanged. Common UI, components and Room schema remain shared.
- Before asynchronous Cookie verification can change preferences, standalone freezes
  the previous public account ID once in its own DataStore. This value is affinity,
  not an authenticated session or copied credential. Private preference tests verify
  that account preference replacement, logout-like removal and reopening cannot
  overwrite it. Unknown/nonpositive affinity never becomes the next login's account.
- The converter reads original `song_ids_json`, playlist and directory through the
  WorkManager `WorkSpecDao`; public WorkInfo does not expose input Data. This restricted
  read is isolated in one standalone adapter. Both the recorded `main` and current
  checkout pin WorkManager 2.11.2, so this checkpoint does not introduce a library/schema
  upgrade. Stored WorkManager Data is decoded structurally, never by raw blob/string
  substitution or by copying the user's work database.
- Matches require original unique work name/tags, one canonical string song identity,
  one unambiguous work record, valid directory and known quality. Original playlist,
  destination, song metadata and creation time become durable owner/request intent.
  Pending/downloading work gets a fresh UUID, zero progress and no old URL/type/name;
  paused/failed work keeps its state/progress. A terminal cancellation is treated as a
  pause, not permission to redownload. Completed rows, song paths and files are not
  rewritten or given fabricated publication receipts.
- Room conversion commits before retiring old WorkSpecs. The factory prevents an old
  request from entering the old implementation during this boundary, including an
  unavailable/minified worker name recognized by its old input/tag contract. A crash
  after Room commit can rerun retirement without changing UUID/affinity or confusing
  our cancellation with a user pause. Existing shared orphan repair schedules the
  converted request only after authentication resolves to its original public owner.
- Modern standalone work waits for metadata preparation. Pending Cookie recovery
  returns retry without failing the task, constructing a source request or publishing
  media. Canceling before preparation leaves the row intact. The normal worker retains
  its existing account-generation/transfer/publication guards once execution begins.
  Preparation failure also retries without bypassing the gate; a later process startup
  retries conversion. Authenticated session changes trigger shared queue recovery.
- Unknown owner, absent/pruned/ambiguous WorkSpecs, malformed input or unsupported
  quality cannot be reconstructed as authenticated work. Their entries remain visible
  and unowned/failed (paused rows remain paused), old URLs are cleared and no automatic
  request is scheduled. A fresh download intent through the existing menu is required;
  the converter does not invent the original account or directory. This is an explicit
  safety limit where historical metadata is unavailable, not hidden feature removal.
- Nine new pure policy cases pass. All 676 standalone and 735 parasite JVM tests pass,
  with no failures/errors/skips. On the rooted API 37/16 KB AVD, 23 selected standalone
  device tests pass: eight recovery cases, seven complete Worker cases, four package/
  graph/factory checks, three database migration cases and the native codec check.
  Nineteen parasite queue/source/Worker regressions also pass, for 42 selected cases.
- The recovery fixture upgrades independently created v17 tables with all five old
  task states, associates UUID-named delayed WorkSpecs, preserves a completed fixture
  path/bytes and checks reopen/idempotence. Other cases cover same/different/recovering
  sessions, paused resume with original destination, missing/malformed/unknown metadata,
  commit-before-cancel fault injection, and durable public affinity. A naturally
  scheduled invalid old worker name reaches the gated factory's explicit retirement
  result. Its input is synthetic; no source request or media transfer occurs.
- Closed standalone Worker substitutes additionally verify waiting/retry and cancellation
  before preparation, then actual publication after the fixture session recovers.
  Room/WorkSpec/DataStore/media fixtures are removed by their UUID paths/tags; real
  credentials, user work and accepted TV media are never used as those fixtures.
  These tests do not prove a real owned download finishing across a device reboot.
- One initial device assertion compared nullable Long ownership with Integer and was
  corrected without production changes. The first parasite instrumentation attempt
  failed the platform's 10-second process attach timeout before any tests began;
  crash buffer was empty and the recorded rerun passed all 19 cases. Its underlying
  cause remains undetermined; it is not classified as a proven download-code failure.
- Both debug/instrumentation and unsigned minified release builds pass; release APKs
  keep their production IDs, pass 16 KB ZIP alignment and retain zero/three Xposed
  metadata entries. R8 retains the standalone provider/factory/converter/gate, while
  standalone legacy-affinity/retirement strings are absent from parasite DEX. The
  merged standalone manifest omits only WorkManager's initializer; parasite retains it.
  Release package checks are not signed-upgrade or release-device acceptance.
- Debug cold starts restore authenticated original Home and paused players at 60,347 ms
  (standalone) and 94,813 ms (TV). The existing TV-owned published media row remains
  22,705,573 bytes. No original standalone update, real download grant/transfer,
  logout/account switch, upload, social write or screen-off action is performed.
  Real production upgrade histories, provider grants, remaining paired behavior and
  release qualification remain required; the overall dual-runtime goal is not complete.

### ABI-011: Asynchronous Cookie Recovery Must Not Cancel Local Queue Restoration

- Date: 2026-09-30. Standalone cold-start device regression after real playback.
  Standalone restores its persisted Cookie by asynchronous server verification.
  During that recovery, shared `MusicService` invalidation canceled its local
  `playbackRestoreJob`. There was no later restart of the job; cancellation before
  coroutine entry also skipped its `finally`. Home regained its account but the
  mini-player and persisted queue stayed absent. This was a runtime/session migration
  conflict, not a layout or navigation defect.
- Adaptation: account invalidation still stops the active source, queue construction,
  preload, AutoMix and old reporting work, but no longer cancels the disk-only snapshot
  read. A per-service restore policy captures the initial session and synchronously
  marks invalidation. Only a still-current, recovery-free authorization may prepare
  the primary restored source or honor saved autoplay; otherwise the same metadata,
  queue order and position return unprepared and not playing. The existing Play
  control prepares on explicit user action. A newer selected queue still wins over
  the pending disk read, and service destruction still cancels that read.
- Verification: nine shared JVM tests cover stable authentication/guest state, initial
  and mid-read recovery, same-account renewal, account replacement, queued invalidation
  and unavailable session readers. After updating only the isolated standalone debug
  app, cold startup restored 1,517 entries at index 879 and position 35,153 ms without
  autoplay. The existing mini-player then resumed explicitly and advanced to 59,712 ms.
  No persistence schema, data path, resource, screen or control was changed.
- Scope: this fixes the observed recovery/metadata conflict. It does not qualify
  legacy production upgrades, private-cloud ownership, all FM/hydration requests,
  exact listening-duration accounting, or signed release lifecycle behavior. The TV
  app was not updated or restarted for this checkpoint; shared tests cover both flavors,
  while a new in-host device pass remains part of paired regression.

### ABI-012: Account Settings and Login Copy Belong to the Session Backend

- Date: 2026-10-01. Dual-runtime review against `main` found that the module-only
  removal of General settings' MUSIC_U export had also removed it from the restored
  standalone APK. Shared waiting text still described QR scanning for WebView login;
  download-scheduler errors told the standalone user to restart a host.
- Original standalone contract: keep the existing Account group, export row, blank
  Cookie disabled state and explicit clipboard action. Restore these from the original
  General settings layout in a standalone-owned LazyListScope section. The shared page
  invokes that section without reading credentials or maintaining a second page tree.
  Parasite supplies no item: official credentials remain exclusively in the TV session.
- Restore the original English, Simplified Chinese and Traditional Chinese WebView
  waiting strings. Parasite resource overlays retain its existing QR waiting and host
  restart text; standalone scheduler text names the app rather than an absent host.
  No session storage, transport, navigation, signing or clipboard policy is changed.
- Verification: 1,551 paired JVM tests pass, both debug/instrumentation and unsigned R8
  builds pass, and matched debug package tests pass 5 standalone plus 4 parasite cases.
  New device cases resolve the actual English login/runtime strings. Standalone's real
  General screen shows the original export row; actual parasite R8 General has no
  MUSIC_U node or Account export group. The export action is not clicked and no Cookie
  is copied, printed or committed. This is not blank-state or clipboard-action device
  acceptance, interactive WebView authentication, or production-upgrade qualification.
- An initial debug-test/R8-target combination crashed in the test runner before tests
  began: its unremapped Kotlin Intrinsics reference could not resolve (R8 mapping names
  the target class `ns5`). That mismatched attempt is not release acceptance. The
  matched debug rerun passes; the normal R8 host app separately cold-starts and renders.
  No production keep rule or new dependency was added for the invalid test pairing.

#### Legacy Library Selection Uses Verified Standalone Affinity (2026-10-02)

- Merge review finds a standalone upgrade omission: original main reads the selected
  library photo from `UserPhotoKey` (`userPhoto`), while the shared account-owned
  library reads `official_user_photo_<userId>`. Preserving the old file/key alone does
  not preserve the selection. This is preference adaptation, not a new photo endpoint
  or frontend redesign; no Library screen/layout/resource is changed.
- Standalone's existing account persistence freezes the old public account ID once,
  before its first replacement or removal. It copies a nonblank legacy photo only
  during verified publication of that same positive account and only if the scoped
  key is absent. The old key is retained; an existing scoped value, including an
  empty value, wins. Account writes and adaptation share one DataStore transaction.
- Frozen identity is affinity, not authentication. Missing/invalid old identity never
  acquires a later login; another account cannot inherit the photo after repeated
  verification, logout or file reopen. Parasite owns separate module preferences
  and receives no standalone migration or credentials.
- Eight disk-backed JVM cases exercise the production persistence and a synthetic
  login controller: original selection, replacement/repeated login, scoped overrides,
  logout, unknown identity, missing/blank photo, reopen and rejected publication.
  Five assertions fail before adaptation; all eight pass afterward. Current paired
  totals are 891 standalone and 922 parasite tests, with zero failures/errors/skips.
  All Cookie/account/URI values and files are fixtures, not user data or authorization.
- Paired debug/R8/test builds and the actual SDK release gate pass. Sixteen matched
  standalone Android package/v17-database/legacy-work fixture cases pass. An isolated
  normal-minification R8 package restores the existing account/feed/paused queue;
  its immediate recovery-required capture is retained separately from settled success.
  Ordinary debug is restored, and TV's existing R8/data/download remain unchanged.
- This closes the confirmed preference-consumption gap, not original-install upgrade,
  production signing, live account switching, photo URI permissions or visual
  qualification of an actual preserved user selection. Those remain separate gates.

### API-028: Social Identity and Requests Must Share One Session Owner

- Baseline standalone social pages read `UserIdKey` from Cookie account preferences.
  The official session deliberately does not persist that legacy field. Consequently,
  a shared private-message page could treat the current user as ID 0, choose the wrong
  conversation participant or classify an outgoing message as incoming in parasite.
  Fetching a profile and then starting another unowned contact request could also join
  two different authorizations across an account change.
- Both runtimes now supply the same credential-free `SessionStamp` to `SocialSource`.
  The existing Repository and selected flavor transports remain authoritative; there
  is no copied official Cookie, new signing implementation or fallback. Supplemental
  routes and their existing payloads are retained:

  | Operation | Route and retained payload |
  | --- | --- |
  | Conversation list | `/api/msg/private/users`; `offset`, `limit`, `total` |
  | Message history | `/api/msg/private/history`; recipient `userId`, `time`, `limit`, `total` |
  | Contacts | `/api/user/getfollows/{captured session userId}`; `offset`, `limit`, `order` |
  | Text/resource send | `/api/msg/private/send`; existing `type`, `msg`, serialized `userIds`, optional resource `id` |
  | Timeline share | `/api/share/friends/resource`; existing `type`, `msg`, `id` |

- Every request carries the captured stamp as Retrofit's expected-session tag. Guest,
  anonymous, recovery-required and obsolete owners are rejected before dispatch; the
  same owner and cancellation are checked after transport completion. All contact
  pages use that identity, not a subsequently loaded profile. Standalone's original
  EAPI/WeAPI factory selection remains; parasite uses the official pipeline for both.
- Conversation, contact and share state clear synchronously on invalidation. Same-user
  reauthorization also replaces the generation. Old results/errors and queued writes
  cannot populate the replacement account; old send callbacks cannot clear its draft
  or dismiss its sheet. UI participant/bubble ownership comes from the visible stamp.
  Draft and recipient-selection lifetimes follow it. Layout, routes, controls, message
  presentation, glass and share modes remain unchanged.
- Verification: 19 shared JVM cases pass in each flavor, including noncancellable late
  reads/writes, recovery, same-ID reauthorization, retained callbacks, recipient changes,
  duplicate sends and failure/retry. Nine real-Repository Android cases pass per matched
  debug flavor using only substitute transports, verifying tags, identity, route/body
  mapping, guest/recovery/stale rejection, late acknowledgements and cancellation.
  Real read-only UI additionally loads conversation and contact lists in standalone
  debug and the locally signed parasite R8 package under their separate existing
  accounts. No conversation is opened and no real social write is dispatched.
- Limits: substitute send success is not server delivery acceptance. A request already
  accepted by a server cannot be revoked by local invalidation. Real logout/relogin,
  cooperating-account send/delivery and complete minified standalone qualification
  remain separate gates. At this checkpoint `ListenTogetherStore` still read the
  legacy user preference and had unstamped room sequences; API-029 subsequently
  adapts that ownership path. Neither entry qualifies real room cooperation. No room
  is created/joined for this checkpoint.

### API-029: Together Rooms and Player Commands Need a Runtime Session Owner

- Recorded: 2026-10-01. The original shared Store read standalone `UserIdKey` and
  substituted `room.creatorId` when that preference was absent. Official login does
  not write that preference, so a participant could be classified as the creator;
  invitation and playlist-version ownership could also use the wrong identity.
  Room requests and supplementary song-detail pages did not retain one authorization
  across their asynchronous sequence.
- `ListenTogetherSource` now takes a credential-free `SessionStamp` for all nine
  operations. The existing Repository retains the original supplemental contracts:

  | Operation | Route and retained contract |
  | --- | --- |
  | Status | `/api/listen/together/status/get`; `data.inRoom`, optional `roomInfo` |
  | Create/check | `/api/listen/together/room/create`, `/api/listen/together/room/check`; original `refer`/`roomId` |
  | Accept | `/api/listen/together/play/invitation/accept`; `refer`, `roomId`, `inviterId` |
  | Playback | `/api/listen/together/sync/playlist/get`; display/random list and original command mapping |
  | Playlist report | `/api/listen/together/sync/list/command/report`; serialized `playlistParam`, version owned by the captured user |
  | Command report | `/api/listen/together/play/command/report`; serialized `commandInfo`, sequence/progress/play status |
  | Heartbeat/end | `/api/listen/together/heartbeat`, `/api/listen/together/end/v2`; original room/song/progress parameters |

- Standalone keeps its original WeAPI status/EAPI other-operation selection. Parasite
  passes these supplemental requests through the official transport and session, not
  a copied Cookie or standalone fallback. This table is an adaptation of the existing
  business contract, not evidence of equivalent native TV room-controller behavior.
- Requests are tagged with the captured stamp, checked before and after transport,
  and reject guests, recovery-required sessions, stale generations and cancellation.
  Role, invitation inviter and playlist version use its public user ID. The Store
  additionally owns each action, monitor and player-event report by session generation,
  room-work generation and attached player; invalidation synchronously clears room
  state and cancels that job tree. A late check cannot accept under a new authorization,
  and late playback/detail results cannot seek, prepare or play the replacement player.
- Existing page architecture, controls, glass, queue/shuffle mapping and invitation
  parsing stay shared. Actions capture the visible session/room; manually edited
  invitation state resets with the session while the incoming navigation seed remains
  available. No hidden feature, new page or unrelated frontend repair is introduced.
- Verification: each matched debug flavor passes 23 Store/platform cases and nine
  real-Repository/platform JSON cases on emulator-5554. They cover all route tags and
  retained payloads, role/inviter/version ownership, queue/shuffle/progress mapping,
  same-ID reauthorization, late check/create/accept/playback results, detail-page
  interruption, retired listeners, queued reports, duplicate actions, local end
  failure, monitor retirement/recovery and reentrant player invalidation. Nine social
  Repository regression cases also pass per flavor after the shared helper rename.
  Fixtures use only synthetic rooms/transports/players; no real room is involved.
- Limits: real room creation/join/end, heartbeat or playback-command acceptance is not
  qualified for this increment. Substitute success does not prove cooperating-account
  synchronization or server acceptance, and cancellation cannot undo a write already
  accepted remotely. Paired real read-only page and release-package evidence is
  recorded separately in the matching dual-runtime checkpoint.

### ABI-013: Playback Checkpoints Need Stable Cross-Build Disk Keys

- Recorded: 2026-10-01. This is a shared persistence boundary, not an official
  endpoint difference. The local-only standalone R8 execution fixture restored and
  played its queue, but replacing it with the ordinary debug build unexpectedly
  resumed playback after an explicit pause. The progress checkpoint was rejected,
  leaving the older full queue snapshot's playing state in effect.
- The actual pre-fix R8 APK DEX and mapping rename the seven checkpoint fields to
  `a` through `g`, respectively: `queueSavedAtEpochMs`, `savedAtEpochMs`,
  `currentIndex`, `positionMs`, `repeatMode`, `shuffleModeEnabled`, `playWhenReady`.
  Gson's default field-name contract therefore differed between minified and debug
  readers. This mapping is verified for that artifact, not assumed for every older
  release. Unrelated historical obfuscation mappings remain unqualified.
- Both runtimes now write canonical keys using `@field:SerializedName`; alternates
  accept only the verified pre-fix `a`-`g` mapping. Queue-epoch association and the
  existing index, position and repeat-mode bounds still apply. Storage namespaces,
  paths, full queue schema, restore policy, player behavior and frontend are unchanged.
  Existing annotation-aware R8 rules suffice; no broader keep rule is added.
- Verification: nine frozen JSON cases pass per debug flavor, covering canonical
  writes/reads, legacy paused/playing reads, canonical rewriting, long precision,
  unrelated queues and invalid values. Both actual production R8 DEX files retain
  the seven canonical fields and their runtime-visible annotations. In the isolated
  debug-ID R8 fixture, brief playback followed by pause writes canonical disk keys
  with `playWhenReady=false`. A data-preserving replacement with ordinary debug and
  cold start retains 1,517 entries, index 548 and position 109,186 ms without playing
  (restored MediaSession NONE/speed 0, not an already prepared PAUSED player).
- Limits: this fixture uses the existing development certificate and isolated debug
  package. It proves this cross-build checkpoint contract, not production package
  upgrade/signing compatibility, arbitrary legacy mappings, framework-free startup
  or full paired release qualification. No original standalone data is copied or
  modified. See the matching dual-runtime checkpoint for other execution evidence.

### API-030: Intelligence Playback Must Retain the Seed's Session Until Queue Commit

- Recorded: 2026-10-01. The original Home heart-mode action fetched seed song detail
  and then recommendations without passing one owner through both calls. Transport
  pinning at call creation did not cover the sequence: the second call could adopt a
  replacement authorization, and an already delivered result could later start playback.
- Both runtimes retain the original supplemental routes and DTOs: seed
  `/api/v3/song/detail` and `/api/playmode/intelligence/list`, with `c`,
  `type=fromPlayOne`, `playlistId`, `startMusicId` and `count=1`. Standalone retains
  its existing crypto/transport; parasite uses the official session/transport, without
  a copied Cookie or standalone fallback. This does not claim a native TV heart-mode
  controller with an identical internal request sequence.
- The shared source now requires one captured `SessionStamp`. Repository checks before
  and after each request reject guests, recovery, stale generations, malformed IDs and
  non-200 business responses. A shared one-shot state holder keeps seed, list and owner
  together, clears synchronously on invalidation and rejects late/cancelled results.
  Same-account reauthorization, replacement clicks, recovery and disposal retire work.
  The original seed-failure/list-only fallback is retained for the same valid owner.
- Home preserves its original card, page layout and seed-first queue assembly. It
  claims the visible result once and passes the captured owner through PlayerConnection
  into deferred queue construction. Short queue/player publications revalidate it,
  including between replacement, prepare and play; ordinary callers retain their
  unowned/default queue path. Heart-mode items are already hydrated, so this handoff
  does not add an unrelated metadata request. This is not a blanket qualification of
  every FM, placeholder or server-shuffle continuation.
- Verification: 12 state and seven real-Repository/proxy cases pass per JVM flavor.
  Each matched debug runtime passes six actual queue-manager/platform-item device
  cases, including deferred status and reentrant invalidation after replacement.
  Fixtures never access a real account or network. Actual production R8 DEXes retain
  Body/Tag slots and the supplemental POST route. The original Home card also starts
  a real 149-item heart queue in parasite R8 and a 150-item queue in standalone debug;
  advancing MediaSession progress and active unmuted AudioFlinger tracks confirm the
  playback path. Latest isolated debug-ID standalone R8 also starts a 150-item heart
  queue through that same card with advancing playback and an active unmuted track.
  The official TV player stays inactive STOPPED with an empty queue.
- Limits: device evidence uses two different existing accounts, not equal recommendation
  content. Controlled account-switch acceptance, complete-track/statistics aggregation,
  audible output and full paired release qualification remain separate gates. No library
  mutation, quota download, upload or social operation is performed. Latest isolated
  standalone R8 execution is recorded in the matching build-plan checkpoint separately.
- Consumer-lock follow-up (2026-10-02): a real PlayerConnection fixture fails before
  repair because Home heart consumption invokes its callback under SessionStore's
  publication monitor, causing a nested backend identity read. Consumption now claims
  and clears once under the locks, then rechecks ownership/recovery/revision and hands
  off outside them. Home supplies the displayed feed's owner to seed startup as well.
  Two added paired JVM cases cover retained seeds and failed-callback non-replay; the
  actual connection fixture verifies successful queue publication without the monitor.

### API-031: Personal FM Refills Must Keep Their Triggering Session and Queue

- Recorded: 2026-10-01. Original FM seed/detail and refill requests did not carry an
  explicit shared owner. Deferred service/listener launches could adopt a newer account;
  an old refill's Boolean cleanup could also reopen a newer refill's reservation.
- Retained request contract: `/api/v3/song/detail` for an optional seed and
  `/weapi/v1/radio/get` with the original empty body for recommendations. Standalone
  retains its original signing/transport. Parasite normalizes the latter to host path
  `v1/radio/get` and uses the official session, signing and request SDK. It does not
  copy credentials, invoke a native TV FM controller or fall back to standalone.
- One captured SessionStamp now covers seed loading, FM activation, recommendations,
  FM metadata hydration and short queue/player publications. Generation, authenticated
  identity and recovery are checked before dispatch and after responses. Publication
  checks between insertion, preparation and play; backend readers stay outside the
  session monitor. Restored FM retains only its startup owner, never a later account.
- Refill work captures the active FM queue synchronously, uses a lazy Job reservation
  and releases only its own reservation. Queue replacement, invalidation and disposal
  retire it; cancelled/non-cooperative results cannot append to a replacement queue.
  Duplicate listener/service callbacks share one outstanding refill. Deferred local
  trash and history cleanup also validate the triggering queue/session.
- Original shared Home/library entries, layouts, seed-first playback, no-seed queue
  retention, current-seed exclusion, repeat/shuffle rules and refill thresholds remain.
  Trash still removes locally only. This does not implement or qualify native
  `v1/radio/like`/dislike feedback; API-013's context distinction remains in force.
- Verification: six Repository/proxy JVM cases per flavor; 14 new actual queue-manager
  device cases per matched debug flavor, plus six existing heart-handoff cases. They
  use synthetic identities, transports and players, with no login/library mutation.
  Current production R8 DEXes retain the Radio DTO, Body/Tag slots and POST route.
  Actual original Home actions start FM in standalone debug and both minified runtime
  fixtures. TV shows six entries with successful next/playback; standalone R8 grows
  from four to seven entries after next. Both have advancing MediaSessions, no
  reported error and active unmuted 48 kHz AudioFlinger tracks. Official TV playback
  remains inactive STOPPED/empty. Accounts and recommendation contents differ.
- Limits: first post-update parasite instrumentation attempts fail to attach and execute
  no cases; bounded retries of the latest matched APKs pass. This is not fresh-install
  startup acceptance. Real reauthorization, expiry, server feedback/listening statistics,
  audible output, every hydration/shuffle continuation and complete paired release
  qualification remain separate gates. The earlier standalone near-end stall is not
  repaired or qualified by this FM check. No quota grant, upload or social write occurs.

### API-032: Paired Podcast Search Relevance Remains Unqualified

- Recorded: 2026-10-02. Shared input is `GetSearch(s="HOYO", type=1009,
  limit=30, offset=0)` through `ApiService.search` at `/api/search/get/`.
  The DTO retains explicit serialized `s`, `type`, `limit` and `offset` names.
  Standalone keeps its original direct signing/transport; parasite normalizes the
  route to `search/get` and uses the official session/request SDK. The common result
  model reads `djRadios`; no alternate result category is fabricated.
- Observed on the same AVD with two different existing accounts: parasite R8
  starts with HOYO-MiX/HoYoFair-related radios. Isolated standalone R8 starts with a
  broad hot-song radio and other weakly related rows despite confirmed `HOYO` input
  and the selected Podcast tab. Ordinary standalone debug after restoration produces
  the same broad leading rows. This is not established as an R8-only failure.
- The selected radio detail in each runtime renders its cover, metadata and program
  list, and Back preserves the Podcast result category. These paths demonstrate
  response consumption/navigation, not complete query relevance or equivalent server
  behavior. No program is played, subscribed to, downloaded or bulk-loaded here.
- Adaptation: none at this checkpoint. Do not replace standalone's contract with a
  TV-only route, hide/filter results or modify original pages to mask the observation.
  Original-main reproduction and bounded request/server attribution remain open;
  account, transport/client context and server ranking causes are not distinguished.
  Confirmed UI input and source serialization alone do not prove the encrypted wire
  payload. No credentials or raw account responses are committed.
- Focused source comparison (2026-10-02) against main
  `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29` confirms that GetSearch's request fields
  and `/api/search/get/` route are unchanged; the added Retrofit Tag is local session
  ownership, not another body parameter. The restored interceptor retains main's
  payload/crypto logic with captured credentials replacing global Cookie reads.
  This source evidence does not reproduce main's server results or establish the
  cause; no search endpoint or frontend behavior is changed during the review.
- Evidence: settled screenshots/UI XML from both minified runtimes and restored
  standalone debug; shared DTO, Repository and route inspection; five focused
  search/album/artist/podcast suites pass 68 cases per flavor. Fixture success does
  not qualify live search relevance. See the paired-search build-plan checkpoint.

### API-033: Recognition and Song Wiki Reads Need Their Triggering Session

- Recorded: 2026-10-02. Recognition retains GET `/api/music/audio/match` and its
  original `sessionId="0123456789abcdef"`, `algorithmCode="shazam_v2"`, `duration`,
  `rawdata`, `times=1` and `decrypt=1` fields. That `sessionId` is the existing
  fingerprint-protocol field, not an account ID, Cookie or shared SessionStamp.
  The new required Retrofit Tag stays local and is not another query parameter.
- Standalone keeps its existing public audio-match transport without copying a Cookie
  into this request. Parasite keeps the official HostCallFactory/session/request SDK;
  no direct NetEase transport or standalone fallback is introduced. Both capture the
  owner before recording, rather than silently borrowing whichever account is current
  after recording/fingerprinting finishes. Anonymous recognition remains eligible.
- Song wiki retains EAPI `/api/song/play/about/block/page` with only `songId` in
  the business body and the existing block/model conversion. The response can include
  personalized FIRST_LISTEN/TOTAL_PLAY memories, so a song-ID-only cache is insufficient.
  The Repository now requires an explicit owner before dispatch and after parsing;
  the ViewModel clears private data synchronously on invalidation and reloads the
  selected song for the replacement session. Anonymous wiki reads remain eligible.
- Recording, fingerprinting and matching are canceled on session changes/recovery.
  Recovery never automatically opens the microphone. Request revisions reject old
  cancellation cleanup, non-cooperative late results/errors and old wiki loads, even
  under the same account's reauthorization. Temporary identity-reader failures clear
  state rather than escaping from state publication; explicit retry remains available.
- Original recognition phases, 3/6/9-second options, continuous nine-second windows,
  newest-first deduplication and 50-result cap are unchanged. Recorder, DSP, fingerprint
  assets, permission launcher, Compose page bodies and navigation are not modified.
- Device scope: actual wiki Repository tests use synthetic transports and identities,
  not real account changes. TV R8 reads for `Écoute Chérie` display tags, French/BPM 92
  attributes and similar songs; standalone debug for `Full Moon Serenade` displays the
  original empty state. Different songs/accounts do not prove identical server results
  or that the latter response contains no unsupported blocks. Both original recognition
  pages initialize at Ready; Start is never pressed and permissions are unchanged.
- Verification: paired full JVM suites pass 835 standalone/894 parasite cases. The
  three new suites contribute 36 cases per flavor; real Retrofit serialization is
  captured with a synthetic response and no socket dispatch. Fresh matched-debug wiki
  instrumentation passes seven cases per flavor. Paired debug/release builds, release
  vital lint, real-SDK packaging/signing/isolation fixtures and diff checks pass.
  Both release DEX files retain the six Query fields, required local Tag and GET route.
- Navigation observation: the first More Actions -> Wiki activation returned to an
  existing root page on both runtimes; repeated activation reached the wiki. The final
  standalone retry also returned to Settings. Timing/navigation attribution is open,
  and repeat-entry success does not qualify first-entry reliability. Page bodies and
  navigation are unchanged; no unrelated frontend repair is made in this checkpoint.
- Limits: live microphone/fingerprint/match acceptance, personalized-memory reads under
  real account changes and complete minified standalone wiki coverage remain open.
  TV's RECORD_AUDIO/PiP manifest gate is not solved by these owner guards. No helper,
  framework scope, host APK modification, recording or quota-consuming operation occurs.

### API-034: Home Requests Must Keep the Feed's Triggering Session

- Recorded: 2026-10-02. The original standalone homepage uses EAPI
  `/eapi/link/page/rcmd/resource/show`; parasite normalizes that route into the official
  SDK. The current homepage caches personalized blocks by public account identity and
  validates results against a SessionStamp, but its Retrofit request did not carry that
  stamp. An account change between cache lookup and route creation could therefore
  borrow the replacement account for a request whose result was later rejected.
- The same triggering owner now travels from Repository fetch through a required local
  Retrofit Tag. It is not a business body field, query value or exported credential.
  Standalone still signs through its own captured Cookie client; TV still uses the
  official request bridge without direct fallback. The existing business body builder,
  response model, cache names/day boundary, homepage layout and navigation are unchanged.
  API-001's existing host-owned header/signing distinction remains separate.
- Recovery-required sessions reject cached/fresh Repository reads and cache publication.
  The ViewModel cancels its request and clears personalized success state even when the
  recovery flag changes without a generation transition. Resolution can reuse a valid
  same-account cache; explicit refresh still requests new data. Cancellation, stale
  owners and same-account reauthorization do not publish late cache/UI results.
- Regression evidence: the missing required Tag assertion fails before repair, and three
  recovery Repository/ViewModel assertions also fail before their guards are added.
  The final full JVM suites pass 843 standalone and 899 parasite cases. Seven matched
  device fixtures per flavor exercise production Repository, body builder and Retrofit
  with separate synthetic sessions/transport/cache directories: local-only Tag, stale
  dispatch, recovery, late invalidation, HTTP/business failure, retry and cancellation.
  These fixtures neither switch real accounts nor send a NetEase request.
- Both current production release DEX files preserve the required Tag and unchanged
  POST route. The paired build-plan checkpoint records real-account minified homepage
  reads separately. Real login/logout/account-switch/expiry cooperation, complete home
  failure behavior under official server conditions and full release acceptance remain
  open; fixture cancellation does not establish server-side cancellation.

### API-035: Account Profile and Library Reads Keep Their Triggering Owner

- Recorded: 2026-10-02. Profile reads retain the original logical primary/secondary
  account paths and empty business body. Both attempts now carry the same required
  SessionStamp; stale/cancelled attempts do not select a newer account for the fallback.
  This secondary business path is not a fallback to standalone from the official host.
- Library album reads formerly took a fresh snapshot inside the Repository, while photo
  reads supplied an account ID but no Retrofit owner. Both now receive the ViewModel's
  triggering stamp; every album page and photo request carries a required local Tag.
  Photo account ID is derived from that stamp. Original business fields, album cursor
  progression and default photo page (size 10 with null cursor omitted) are retained.
- Recovery without a generation change now stops profile/library network reads and
  rejects late publication. Account presentation removes the online profile and marks
  authorization unavailable, but retains a still-current public stamp for owned offline
  history. It does not retain credentials or grant network authorization. Library
  observes recovery as well as generation; resolution reloads the current account.
- Four new assertions fail before repair. Final paired JVM suites pass 851 standalone
  and 907 parasite cases, including existing recovery/offline-history preservation.
  Returning the pending account without its public stamp initially broke that offline
  regression; retaining the validated stamp repairs it without changing History UI.
  Required photo/album tags and their routes survive both production R8 DEX files.
- Twelve Android substitute cases pass per flavor, using the real repositories and
  Retrofit requests without server or database writes. They cover the profile's
  secondary path, album pagination, photo payload, invalid/recovering owners,
  cancellation, late responses and retry. An initial fixture incorrectly expected an
  empty photo cursor; only that assertion was corrected to match main's omitted null.
- The build-plan checkpoint records Android fixtures and real-account reads separately.
  Real expiry/logout/account-switch cooperation, complete server failures and release
  acceptance remain open. The independent effective-signing regression in ABI-015
  also remains open; this owner repair is not proof of restored EAPI/WeAPI wire modes.
- Follow-up review on 2026-10-02 finds the same missing owner in active account detail,
  account playlist, listening-rank and recent-history reads. All four now require the
  triggering SessionStamp. AccountHome passes its published account stamp; History
  passes its refresh stamp; both attempts of the original detail-path fallback retain
  the owner. The shared dynamic request helpers no longer accept an omitted owner.
  Business paths/fields and standalone signing/retry versus official-host dispatch
  remain unchanged; the second detail business route is not a standalone transport.
- Listening-rank presentation/cache now belongs to a session generation and target
  user/period. Invalidation, recovery and account transitions retire its request and
  cached rows; old responses and retained queue callbacks cannot populate or replay
  replacement data. Public guest reads, both time periods, refresh and same-session
  caching remain available. The original page, controls and queue body are retained.
- Eleven ranking JVM cases and an additional history-owner assertion pass in both
  variants. Seven additional AccountReadDeviceTest cases execute actual Repository,
  Retrofit providers and session-bound substitute calls: exact original business
  tuples/local-only tags, secondary detail path, stale/recovering/transition owners,
  route-creation races, late responses, HTTP/business failure/retry and cancellation.
  With the retained account and generic dynamic-request fixtures, 28 Android cases
  pass per flavor (0.758s standalone, 0.717s parasite); the full JVM pair passes
  951 standalone plus 974 parasite cases. No fixture changes real accounts, opens a
  network socket, writes databases or establishes official-server statistics.
- Both production R8 artifacts and all 42 local real-SDK release checks pass. The
  ordinary parasite R8 is restored with development signing and portrait Home
  startup. Both its shared MainActivity and standalone debug consume their existing
  accounts' detail/playlists and switch weekly/all-time rank reads through the
  original UI without playback or a probe activity. This qualifies bounded TV R8
  read consumption, not new listening-statistics acceptance or updated standalone
  R8/production-ID execution; the full paired account/history and real authorization
  matrices remain separate.

### API-036: Standalone Photo Metadata Is Not Host Business Data

- Recorded: 2026-10-02. Original main's GetUserPhotoAlbum includes `header="{}"`
  and `e_r=true` in addition to userId/page. The migration removed those transport
  fields from the shared DTO to avoid replacing the official SDK's signing metadata.
- The standalone photo route remains `/api/user/photo/album/get`, with no explicit
  crypto header. Its original API form encoder does not inject those removed fields.
  Therefore restoring the original interceptor alone does not restore this payload.
  API-035 preserves the business fields, not the missing standalone transport fields.
- Required next work: restore these two defaults at the standalone transport boundary
  only. Keep the common DTO and official SDK request free of module-owned signing
  metadata. This is a source/configuration fidelity gap, not an observed photo-server
  failure; photo wire acceptance is still unqualified.
- Repair: the standalone API form encoder now supplies the original defaults only on
  `/api/user/photo/album/get`. The shared DTO still contains userId/page only, and the
  host never receives these module transport defaults. A production-provider/Retrofit/
  interceptor JVM test fails before repair and passes after it; unrelated typed API
  forms remain unchanged. No separate live photo-server acceptance is claimed.

### ABI-014: Sleep-Timer Notifications Belong to the Posting Runtime

- Recorded: 2026-10-01. This is component/permission adaptation, not a new business
  endpoint. Both builds retain the original SleepTimer, sheet, options and notification
  implementation. Countdown state lives in the music service; notifications do not
  own the deadline or pause playback themselves.
- Standalone contract: the posting package owns channel `sleep_timer`, notification
  1002, its MainActivity content intent and MusicService cancel intent. On the isolated
  debug-ID R8 fixture, POST_NOTIFICATIONS was initially ungranted. The five-minute
  timer and background playback started without its status notification. Granting
  through the original system dialog posted the remaining countdown without resetting
  the deadline. The permission and its original flags were restored after testing.
- Parasite mapping: the posting UID/package is TV, while the status icon resolves
  from the installed module APK. Actual system PendingIntent records route content to
  `com.netease.cloudmusic.app.LoadingActivity` and CANCEL_SLEEP_TIMER to the existing
  `com.netease.cloudmusic.service.LocalMusicMatchService` carrier. No module-only
  component is submitted as an installed TV component; no package metadata or system
  scope is changed.
- Device evidence: five-minute timers in parasite R8 and isolated standalone R8
  expire during background playback, publish PAUSED/speed 0 and remove notification
  1002. Actual notification clicks return to the original portrait player; screenshots
  show the decreasing active deadline, then Off after expiry. The TV notification
  cancel action is sent to its carrier, removes the timer and leaves playback running.
  Standalone's actual cancel action reaches its own service and clears the end-of-track
  timer while paused; this is not cancel-while-playing or natural end-of-track proof.
- Limits: eight existing timer JVM cases pass per flavor, and paired debug/release
  builds pass. During a separate standalone resume-after-expiry/cancel setup, Media3
  reports `Player stuck playing with no progress for 30000 ms` near the next track's
  end. ERROR at 129,910 ms is retained as an open playback regression; next/play
  recovers another queue item. AudioPlayer, StableDeckPlayer and SleepTimer match
  current main, and both use Media3 1.10.1, but no baseline reproduction establishes
  the cause. Empty crash buffers do not erase this handled player error. No engine/UI
  repair is included in this checkpoint. Natural end-of-track, process-loss timer
  behavior, audible output and complete release playback remain unqualified.

### ABI-015: Dynamic Standalone Requests Lost Their Signing Labels

- Recorded: 2026-10-02. Bounded source comparison with current main
  `1d830d3f9cd11294e2bb977c7d0ba77f0fb8ca29` confirms that the original MeloX helper
  rewrites logical `/api/` paths to `/weapi/` or `/eapi/`; the WeAPI helper can retry
  through EAPI. Those prefixes select the original standalone interceptor's mode.
- The host migration removed those rewrites/retries appropriately for the official
  SDK, which owns its signing (API-001). Dual-runtime restoration did not restore an
  equivalent standalone boundary: both named direct services still receive the same
  standalone business Call.Factory, and the named Retrofit service alone does not
  choose crypto. A dynamic `/api/` request without a control header therefore selects
  plain API form handling instead of its original intended EAPI/WeAPI signing.
- This is a confirmed source/configuration fidelity gap, not proof of a particular
  server failure. SearchResult API-032 uses the separate unchanged typed API search
  route; this finding does not establish the cause of its observed relevance difference.
- Required next work: restore the original standalone dynamic mode and retry semantics
  at the flavor-owned boundary with captured-session/cancellation tests and actual
  signed-request evidence. Keep parasite on its single official pipeline with no
  standalone/signature fallback. Do not copy frontend trees or alter business bodies
  to mask the gap. Full standalone restoration and merge acceptance remain open.
- Repair: flavor-owned MeloXRequestPolicy binds the two dynamic service qualifiers to
  their original path prefixes in standalone only. It does not change typed Retrofit
  APIs or raw playback-history profile handling. Host qualifiers still return the
  unwrapped SDK service. All consumers of these named services receive the restored
  mode, including the extracted cloud adapters; no credentials cross runtimes.
- The Repository's generic helper again retries validated WeAPI failures through EAPI
  only in standalone. Both attempts retain one captured SessionStamp, with pre/post
  cancellation/current/recovery checks. Cancellation, account replacement and
  reauthorization cannot retry under a new account. Explicit account WeAPI/EAPI calls
  do not acquire this generic retry behavior; the host never invokes the alternative.
- Three production-provider/interceptor assertions fail before repair. Paired JVM
  suites pass 862 standalone and 913 parasite cases. Forty-six Android substitute
  cases pass per flavor, including actual Repository retry and late-response behavior.
  An initial test's numeric Map inference incorrectly boxed offset/limit as Long;
  its expected map was corrected to retain original Int pagination, not production.
  Standalone's path rewrites survive production R8; both new R8 runtimes restore their
  existing account and display real subscribed podcasts without playback or writes.
- Follow-up repair: CloudLibraryBackend's list/delete and CloudUploadCoordinator's
  binary NOS token allocation now reuse the flavor policy to restore their original
  standalone WeAPI-to-EAPI retry. Both attempts retain the original body and captured
  owner, with cancellation/current/recovery guards before and after transport. The
  host still makes one official request; its original SDK exception is not normalized
  into the standalone alternative's precondition error. No credentials cross runtimes.
- Explicit EAPI upload check, metadata allocation, registration and publication do not
  gain retries, and binary transfer is not repeated. Completion still follows accepted
  publication. Four targeted assertions fail before repair; nine shared and two
  standalone tests cover business/HTTP failures, terminal alternatives, canceled late
  responses, recovery and account replacement. Final JVM totals are 873 standalone
  and 922 parasite, with zero failures/errors/skips. Production Retrofit/interceptor
  fixtures verify the secondary EAPI signature and unchanged business payload without
  sockets, followed by one substitute binary transfer only.
- Sixty-one Android substitute cases pass per flavor, including real provider IPC
  with synthetic authorization/files; opt-in live cloud fixtures are not run. Both
  R8 runtimes cold-start and restore their distinct existing Home accounts. These
  checks do not qualify real uploads, server-induced fallback or the complete session
  matrix. A bounded audit of removed shared DTO transport fields finds the repaired
  photo defaults, homepage fields already overwritten by the original EAPI encoder,
  and an unused search DTO; no new gap is established by that audit. Other real
  cooperation, original-install upgrade, full release and merge gates remain open.

### ABI-017: Standalone Native Reporting Needs Target-Only R8 Evidence

- Recorded: 2026-10-02. Standalone owns the original NCBL v3/Zstd encoder; parasite
  uses official host reporting and does not package the standalone codec/JNI library.
  Encoding success is separate from transport acceptance and later listening statistics.
- Merge review finds the original blanket Zstd keep rule absent after source-set
  extraction. The pinned 1.5.7-20 AAR has no consumer rules, but the actual R8 mapping
  retains JNI names under the shared native-method rules and rewrites the reflective
  AutoCloseBase lock updater. This alone does not qualify native execution.
- The opt-in standalone test Instrumentation verifies the installed isolated APK's
  SHA256 and uses a fresh target-only PathClassLoader/native path. Mapping-derived
  members are invoked without linking debug app/AndroidX classes or bootstrapping
  account/network/work initialization. It is never registered in a production APK.
- The existing development-signed isolated R8 artifact passes 32 native compressions
  and 32 NCBL encodings with frame/header accounting and distinct UUIDs; a mismatched
  SHA256 is rejected. Ordinary debug tests additionally pass compression/decompression
  and package/bootstrap checks after restoring the default test runner. The verified
  encoding path needs no additional production keep rule.
- No credentials, real report, upload, download grant or social operation is used.
  This is native/minified codec evidence on the rooted 16KB AVD, not full reporting,
  statistics, framework-free execution, production upgrade or complete release proof.

### ABI-018: Pending Cookie Recovery Can Retain an Anonymous Public Stamp

- Recorded: 2026-10-02. StandaloneSessionStore.initialize ends its initialization
  transition after exposing an anonymous stamp; a saved Cookie keeps recoveryRequired
  true until separate candidate verification succeeds. TV's HostAuthorizationGuard
  instead retains its transition lease while official login/recovery is unresolved.
  A readable/unchanged SessionStamp is therefore not sufficient evidence of readiness.
- Shared search results, suggestions and discovery observed recovery but only retired
  state when snapshot failed or the stamp changed. Three JVM cases reproduce dispatch,
  retained content/cache and late-result publication while recovery remains required.
  This is a dual-runtime session-adapter gap, not an original main frontend cleanup.
- Repair: both existing search ViewModels gate dispatch/publication and retire work,
  cache and displayed results independently of generation. Explicit retries remain
  blocked; the latest query/type/input resumes once recovery clears. Ready guest
  search, original endpoints/signing, debounce, pagination and page layout remain
  unchanged. No guard is added to the candidate-verification route or host login API.
- Paired JVM suites pass 1833 cases, including 19 search cases each. Two Android
  substitute cases pass per flavor using actual ViewModels with synthetic sessions,
  without sockets/credentials/real account changes. Both production R8 artifacts
  build, and the 41 workflow fixtures plus real-SDK built-pair gate pass. Separate
  development-signed minified runtimes display suggestions and Coldplay artist results
  through the unchanged UI using their persisted accounts; no follow is requested.
  This ready-state consumption does not reproduce real pending-Cookie recovery.
  Ordinary standalone debug is restored; the rooted AVD remains awake at TV-hosted
  MeiloX Home with existing queues paused. Real startup Cookie expiry/account
  cooperation, API-032 relevance, full
  minified lifecycle and production upgrade remain distinct unqualified gates.
- Album consumer follow-up: the same readable-stamp assumption also retains album
  presentation/actions during pending recovery. Four added JVM cases fail before
  repair, including non-cooperative reads and a late collection notification.
  AlbumDetailViewModel now retires pending reads/writes/content and fences captured
  actions and publication, retaining the latest album for same-stamp resumption.
  The transport was already guarded; these fixtures do not establish real pending
  requests escaping that transport. Endpoints and the existing UI remain unchanged.
  Paired suites pass 1841 cases (19 album cases each); two Android substitutes pass
  per flavor with synthetic sessions/sources and no real collection/download writes.
  Both production R8 builds/vital lint and the real-SDK release gate pass; the updated
  development-signed module cold-launches portrait MeiloX Home. That bootstrap does
  not qualify real recovery, an actual collection write or full paired release parity.
- Library consumer follow-up: playlist explicit retries, captured detail/daily-track
  actions and raw reads also assumed that a readable stamp was ready. Both podcast
  consumers retained discovery/subscriptions/detail and bulk-program caches under
  that assumption. Twelve new JVM cases fail before repair, covering initial
  anonymous recovery, queued dispatch, immediate cache/actions, forced retries and
  non-cooperative reads/writes. The shared consumers now fence reservation, dispatch
  and publication and retire pending work/content; latest playlist/detail and daily
  intent resume after same-stamp recovery. Endpoints, signing, raw pagination offsets,
  candidate verification, download policy and existing UI/navigation are unchanged.
  Paired suites pass 1865 cases (24 playlist and 26 podcast cases per flavor). Three
  Android substitutes pass per flavor using private sessions and closed synthetic
  sources, with no sockets/credentials/real writes. The real tagged transport was
  already guarded: these reproductions establish consumer gaps, not real pending
  requests escaping it. Artifact/bootstrap qualification and remaining real-account
  gates are recorded in the dual-runtime plan.
- Category-discovery follow-up: the existing FindMusic consumer had no session-bound
  cache/retirement, while `/api/playlist/highquality/list` omitted an originating
  Retrofit session tag. The transport still guarded the execution owner it captured;
  that alone did not bind the request/cache/result to the originating consumer. A
  closed typed source exposes seven failing consumer cases and four failing API/
  repository cases before the repair. Requests, ready-category cache and results now
  retain their owner and reject recovery/invalidation/lifecycle retirement; latest
  category/limit resumes after readiness. Business failures and missing rows cannot
  become successful cached content. The endpoint/body/crypto, default and explicit
  limits, ready guest browsing, ranking alias and original page remain unchanged.
  Fresh paired suites pass 1895 cases, including 11 discovery cases per flavor; two
  Android substitutes pass per flavor with private sessions and a socket-free source.
  Current standalone debug consumes All/Western results with its persisted account.
  Fresh minified parasite and isolated-ID standalone also consume Home/All/Western
  through their persisted accounts. This does not qualify real expiry/account
  switching, production-ID signing/upgrade or complete paired release execution;
  further artifact/runtime evidence is in the build plan.

### ABI-019: Log Sharing Needs the Host Provider's Actual Root Context

- Recorded: 2026-10-02. This is a runtime component difference, not a NetEase business
  endpoint or a change to the original log page. Standalone's registered
  `${applicationId}.fileprovider` exposes its private files root; TV 1.1.80's
  `com.netease.cloudmusic.tv.fileprovider` instead exposes selected external roots and
  `cache/apk`, with no private-files root. Using the module's files directory and
  wrapped context cannot make the host provider serve that file.
- Repair: the existing ComponentRuntime supplies the URI. Standalone retains the
  original direct FileProvider call. Parasite requires the verified enabled host and
  ModuleContext, accepts only direct regular files in its app_logs/crash_logs folders,
  and copies the selected file into a UUID-scoped host-cache export directory. URI
  generation uses the underlying host context, matching the already registered
  provider's roots; no provider/path metadata, scope or official APK is modified.
- Sharing runs off the main thread and keeps the original chooser, MIME, stream and
  read-grant flags. Source logs remain unchanged. Copies older than 24 hours are
  pruned on the next share, and explicit log clearing removes owned copies after
  retiring an in-flight share. Cache eviction may also remove them; no exact-time
  automatic expiry is promised. Foreign paths, nested files and symbolic-link escapes
  are rejected, and cleanup does not follow links or delete unrelated cache entries.
- Verification: six JVM staging/security/cleanup cases and paired debug suites pass;
  two standalone Android cases read a synthetic private log through the original
  provider and capture the unchanged chooser without launching a recipient. Four
  host-runtime Android cases include disabled/foreign-context rejection. A separate
  ordinary UID reads two synthetic files through TV's actual provider, rejects an
  ungranted URI and rejects both selected URIs after revocation; TV independently
  acknowledges success and cleanup. Five paired host/runtime cases pass in 2.298s.
  This is warm-host evidence: cold fixture delivery and startup timeout observations
  remain separate. Both final production R8 artifacts build and all 42 local
  real-SDK release checks pass. The ordinary probe-disabled parasite R8 is restored
  with development signing and reaches portrait MeiloX Home; detailed artifact and
  preservation evidence is recorded in the build plan. These fixtures do not
  authorize sending real logs or qualify every external
  share target; no credentials, real logs, device captures or APKs enter Git.

## Adding an Entry

As of 2026-09-29, the project targets both standalone and parasite APKs; see
[Dual Runtime Build Plan](dual-runtime-build-plan.md). Future entries must distinguish
the original standalone contract, the official-host contract, their shared frontend
mapping and the verification status of each backend. Existing host-only observations
do not authorize replacing the standalone contract with a TV-specific operation.

Use a stable ID and record the date, endpoint or entry point, original assumption,
observed behavior, adaptation, source/test/device evidence, and remaining uncertainty.
Update acceptance evidence in place as later stages verify it. Do not mark speculative
fallbacks as official requirements or transport success as feature completion.
