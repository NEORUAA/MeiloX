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
- Remaining: binary uploads have no accepted official adapter yet. They fail explicitly
  rather than falling back to module-owned signing or direct NetEase transport.

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

## Runtime Boundary Notes

These are integration differences, not server API semantics.

### ABI-001: Suspend Parameter Annotations

- The first AVD Retrofit run observed two parameters but one annotation-array slot for
  a module suspend method. Retrofit consequently failed to recognize the continuation.
- A narrow API 102 adapter restores only the missing empty continuation slot for known
  isolated module service interfaces. It leaves host methods and complete arrays alone.
- Evidence: reflection-adapter unit tests and the corrected eight-operation Retrofit
  probe. See [Retrofit Transport Adaptation](official-client-parasite.md#retrofit-transport-adaptation).

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
- Remaining: this retires the verified media-session/receiver path, not every
  possible official widget, external playback intent, or reporting path. Slow-disk
  restoration, empty/corrupt stored queues, real Bluetooth peripherals, other OS
  versions, release runtime, and audible output remain separately unqualified.

## Adding an Entry

Use a stable ID and record the date, endpoint or entry point, original assumption,
observed behavior, adaptation, source/test/device evidence, and remaining uncertainty.
Update acceptance evidence in place as later stages verify it. Do not mark speculative
fallbacks as official requirements or transport success as feature completion.
