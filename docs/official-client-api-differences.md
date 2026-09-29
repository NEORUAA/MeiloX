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

## Runtime Boundary Notes

These are integration differences, not server API semantics.

### ABI-001: Suspend Parameter Annotations

- The first AVD Retrofit run observed two parameters but one annotation-array slot for
  a module suspend method. Retrofit consequently failed to recognize the continuation.
- A narrow API 102 adapter restores only the missing empty continuation slot for known
  isolated module service interfaces. It leaves host methods and complete arrays alone.
- Evidence: reflection-adapter unit tests and the corrected eight-operation Retrofit
  probe. See [Retrofit Transport Adaptation](official-client-parasite.md#retrofit-transport-adaptation).

## Adding an Entry

Use a stable ID and record the date, endpoint or entry point, original assumption,
observed behavior, adaptation, source/test/device evidence, and remaining uncertainty.
Update acceptance evidence in place as later stages verify it. Do not mark speculative
fallbacks as official requirements or transport success as feature completion.
