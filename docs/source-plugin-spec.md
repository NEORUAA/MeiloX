# Source plugin APIs 1 and 2

A source is an imported `.meisource` package. Its JavaScript runs in an application-bundled QuickJS engine inside a bounded Android isolated process. The host owns navigation, Compose UI, playback, files and Android SDK access. Source code owns service endpoints, authentication protocols and response projections.

API 1 remains compatible with its original four operations. API 2 includes typed content and optional online features, effects and links. These additions use `apiVersion: 2`; there is no API 3 contract. The executable declarations are in [source-plugin.d.ts](../samples/music-source-plugin/source-plugin.d.ts).

## Package and identity

```text
manifest.json
index.js
LICENSE             optional UTF-8 plain text
NOTICE              optional UTF-8 plain text
assets/tone.wav     optional packaged audio
```

The host accepts only these root files and supported audio files directly inside `assets/`. It rejects directories, duplicate paths, traversal, absolute paths and other executable files. Limits are 256 KiB for `index.js`, 64 KiB each for the manifest/legal files, 4 MiB per asset, 8 MiB extracted total and 64 entries. Only `index.js` executes.

```json
{
  "id": "org.example.source",
  "providerId": "org.example.catalog",
  "name": "Example Source",
  "author": "Example author",
  "version": "1.0.0",
  "apiVersion": 2,
  "entry": "index.js",
  "capabilities": ["catalog.search", "catalog.track", "playback.resolve", "feature.invoke"],
  "concurrentReadOperations": ["catalog.search", "catalog.track", "playback.resolve"],
  "concurrentReadFeatures": {"login": ["sessionState"]},
  "permissions": {
    "network": ["api.example.com", "*.audio.example.com"],
    "storage": true,
    "effects": ["web.auth"]
  },
  "features": {"login": ["webLogin", "sessionState", "logout"]},
  "featureRoutes": ["account/login"],
  "sessionKeys": ["cookie"],
  "sessionCookieNames": {"cookie": ["SESSION"]},
  "config": [{"key": "cookie", "label": "Website Cookie", "secret": true}]
}
```

Package `id` and catalog `providerId` use lowercase letters, digits, `.`, `_` and `-`, start with a letter, and have 3–80 characters. `providerId` defaults to `id` and remains fixed across an update. `local` is reserved for the host. Only package `org.meilox.source.netease` may claim provider ID `netease`. This namespace rule does not grant imported packages legacy account access.

An installation has instance ID `script:<package-id>:default`. Catalog IDs, account IDs and comment/program IDs are opaque strings; preserve Unicode, punctuation, slashes and leading zeros. The host supplies provider/instance namespaces. Never hash or coerce an external ID into a numerical NetEase ID.

API 1 may declare only `catalog.search`, `catalog.track`, `playback.resolve` and `lyrics.get`. It has bare-track search results and no API 2 feature, route, session, SDK, link or effect declarations. Other positive API versions can be inspected/removed but remain incompatible with execution.

## Marketplace and updates

The [source registry](https://github.com/NEORUAA/MeiloX-Source-Registry) publishes a bounded, declarative `index.json`. Entries bind package/provider identity, SemVer version, API version and SHA-256 to a `.meisource` URL pinned to a full Git commit in the declared repository. Submission and metadata validation are maintained in that repository; the host never executes index content.

Startup checks run after source initialization in the background. Automatic updates require a matching, payload-bound repository origin and no additional permissions, capabilities or required configuration. Session fingerprint contract changes also require review. A file-imported source can acquire origin only after its exact current payload matches the digest-verified same-version market package, or after an explicit reviewed market update. Reimporting a file clears market origin.

Changing an exact network grant to a wildcard still requires permission review. Wildcard grants require a host supporting this syntax; older hosts reject the manifest.

Updates preserve private configuration and enabled state. Removed sources are not reinstalled. Download, digest, index or update failures retain the installed version and do not fail the source's other operations. A directory-replacement journal recovers an interrupted installation. Automatic replacement defers while the source owns the current paused/playing track or queue restoration; it does not interrupt already opened audio.

## Core content

| Operations | Result |
| --- | --- |
| `catalog.search` | API 2 page of tagged items matching `type`; API 1 page of bare tracks |
| `catalog.track` | Track or `null` |
| `catalog.tracks` | Optional batch of tracks in requested identity order; `catalogBatchSize` declares a limit from 1 to 1000 (default 30) |
| `album.get`, `artist.get`, `playlist.get` | Typed metadata with the requested identity |
| `album.tracks`, `artist.tracks`, `artist.albums`, `playlist.tracks` | Ordered pages |
| `feed.home`, `feed.discover` | Stable sections/categories using existing host layouts |
| `account.get` | This instance's own account or `null` |
| `library.likedTracks`, `library.createdPlaylists`, `library.savedPlaylists`, `library.savedAlbums`, `library.followedArtists` | Session-owned pages of typed items |
| `track.liked`, `collection.saved` | Boolean status |
| `track.like`, `collection.save` | Confirmed explicit boolean change |
| `playlist.create`, `playlist.update`, `playlist.delete`, `playlist.addTracks`, `playlist.removeTracks`, `playlist.reorderTracks` | Individually declared editing capabilities |
| `playback.resolve`, `playback.qualities` | Audio resource, candidate qualities, or bounded quality continuation |
| `lyrics.get` | Optional source lyrics; host lyric selection remains independent |

A tagged item is `{type:'track', track:{...}}`, or its `album`, `artist` or `playlist` equivalent. Pages contain `{items, hasMore, nextCursor?}`. Search and library types must match manifest `searchTypes`/`collectionTypes`. The host validates identities, collection/edit capabilities, counts and advancing cursors. Playlist positions retain duplicate recordings.

`catalog.tracks` returns a track array in requested order, omitting unavailable IDs. Declare both `catalog.track` and `catalog.tracks` before setting a nondefault `catalogBatchSize`. `discardStorageKeys` lists obsolete source storage keys for host migration and must not overlap `sessionKeys`.

`album.get` may optionally return a complete ordered `trackIds` array (at most 100000, including repeated positions) and up to 5000 normalized `tracks`, alongside its normal album fields. The host requests `metadataLimit: 30` for first-page browsing and caches the typed snapshot, so `album.tracks` can hydrate only the requested slice through `catalog.tracks` or `catalog.track`. Legacy metadata-only responses retain the original `album.tracks` path. A snapshot requires `catalog.track`; incomplete indexes shorter than the declared count are rejected. A source can require an explicit `metadataLimit` or `includeTracks: true` to opt into its extended response; `includeTracks: false` must return plain album metadata for oversized-result recovery.

`playlist.get` may also return a complete `trackIds` array and available normalized `tracks`. The host may request `metadataLimit` from 1 to 5000; absent values retain the source's small default. `library.likedTracks` may accept `metadataOnly: true` with the same snapshot result, including the resolved `playlistId`. Older plugins can continue returning their existing page. The host owns complete indexes, metadata, pages, prefetching and invalidation, scoped to provider/configuration/account. These values are not script storage or a JavaScript cache API. Explicit refresh and mutations invalidate host snapshots, and late responses cannot repopulate a retired cache generation.

The host's process-local catalog cache has a 4 MiB weighted metadata budget and at most 8192 entries; pages expire after 60 seconds. Verified account identity stays in a separate owner-lifetime slot. Normal playlist and liked-track browsing requests only first-page metadata (normally 30 rows), reuses any existing complete index and hydrates only the requested page. It does not wait for whole-collection metadata warming. Album snapshots share the same host budget and invalidation rules. Playlist/album headers publish before the first song page finishes; explicit refresh retains existing content while replacing the cached snapshot. A cold shuffle identity lookup requests only the complete index with `metadataLimit: 1`; it does not wait for every song's metadata. Sources without catalog hydration retain their original playlist pagination.

Tracks include `trackId`, `title`, artist names/credits, album title/credit, `durationMs`, optional artwork, `isrc`, recording `version` and `explicit`. Playlists may include a nonnegative integer `playCount`; omit it or return null when unknown instead of inventing zero. Album metadata may include `publishedAtMs`; artists may include `alternateNames`, optional track/album/music-video counts, identities, expertise, translated names and aliases. These fields populate the original artist information grouped list without invented counts. Feeds use host `hero`, `playlists`, `tracks`, `albums`, `artists` and `grid` layouts. Home caches contain normalized typed sections and card targets only, scoped to exact provider/instance/version/account revision. They expire at 06:00 Asia/Shanghai. Explicit refresh bypasses the cache and sets `PageRequest.refresh`; a completed feed replaces the file atomically after at most ten pages. Failed, cancelled, stale or incomplete refreshes retain the previous file. Each record is bounded to 8 MiB, with at most 32 MiB and 16 account/version records retained.

Hero sections may also contain up to 30 source-owned cards with typed content, declared feature-route, radio or queue targets. Square `playlists`, `albums`, `artists` and `grid` sections may contain up to 100 cards with owned content targets matching the section type. Cards preserve source order and stable unique IDs independently of the canonical `items` list. Optional `coverCaptions` (at most ten lines), `detail` and `titleMaxLines` (one or two, default two) populate the original square-card component; `title` and `subtitle` are its primary and secondary text. A square card may have an empty primary title. Hero cards continue using `subtitle` as their existing extra text. Artwork processing and service caption recipes belong to the plugin. The host fixes typography, spacing, badge placement and scrolling, and caches the normalized presentation fields. Route/card actions stay bound to the source and account that supplied them. Plugins do not supply Compose code or numerical surrogate UI IDs.

Audio resources report the **actual** quality and may include per-resource headers, expiry, size, content identity, MIME type and `cachePolicy: 'ALLOW' | 'NO_STORE'`. The host converts the URL/headers to an opaque audio handle before player/cache/download persistence. `asset://tone.wav` resolves only inside the owning package. `NO_STORE` resources cannot be downloaded or cached.

`playback.qualities` may return a string array or `{items, nextCursor?}` of catalog candidates. It need not resolve every candidate URL: the host verifies full resources for the current account when a quality menu is opened. The currently playing quality comes from its resolved resource independently. `playback.resolve` may return `{nextCursor}` while continuing a bounded quality ladder. The host accepts at most three quality segments, rejects repeated/oversized cursors, and limits the combined quality list to 32 entries. These cursors are separate from host-effect continuations.

### API 2 catalog example

The packaged [offline sample](../samples/music-source-plugin/index.js) intentionally stays on API 1 to exercise compatibility. The following independent API 2 fixture shows the optional catalog extensions. Declare `catalog.track`, `catalog.tracks`, `playlist.get` and `library.likedTracks`; set `catalogBatchSize: 1000`. Compile this TypeScript to `index.js`; only the emitted JavaScript executes. Its constants stand in for service responses, not a persistent script cache.

```typescript
import type { Plugin, Track, PlaylistSnapshot } from '../samples/music-source-plugin/source-plugin';

const track: Track = { trackId: 'demo:1', title: 'Demo', artists: ['Example'], durationMs: 1000 };
const snapshot: PlaylistSnapshot = {
  playlistId: 'demo:liked', title: 'Liked', trackCount: 2,
  trackIds: ['demo:1', 'demo:1'], tracks: [track]
};
const plugin: Plugin = {
  'catalog.track'({ trackId }) { return trackId === track.trackId ? track : null; },
  'catalog.tracks'({ trackIds }) {
    return trackIds.filter(id => id === track.trackId).map(() => track);
  },
  'playlist.get'({ playlistId, metadataLimit }) {
    if (playlistId !== snapshot.playlistId) return { error: 'NOT_FOUND' };
    return { ...snapshot, tracks: snapshot.tracks?.slice(0, metadataLimit ?? 1) };
  },
  'library.likedTracks'({ metadataOnly, metadataLimit }) {
    if (metadataOnly) return { ...snapshot, tracks: snapshot.tracks?.slice(0, metadataLimit ?? 1) };
    return { items: [{ type: 'track', track }, { type: 'track', track }], hasMore: false };
  }
};
globalThis.sourcePlugin = plugin;
```

The host retains the complete index (including repeated positions), reuses metadata and hydrates only the needed batch. A real source reads its own service and session; `context.account.userId` can supply the host-verified account ID. It does not persist playlist indexes, profiles, listening history or random-order caches in `context.storage`. Configure `discardStorageKeys` when retiring old cache keys, while keeping session credentials intact. Runtime heap and result budgets are described below; they do not require script-side caching.

## JavaScript and network requests

Operations are synchronous functions on `globalThis.sourcePlugin`. Promise return values are unsupported. Direct `fetch`, timers, module loading, raw files, Room and Android objects are unavailable. Every operation gets `(parameters, context)`; `onResponse` runs in that operation's isolate.

Core reads from plugins without storage permission already run concurrently. API 2 plugins with state may opt independent reads into concurrent execution with `concurrentReadOperations` and `concurrentReadFeatures`. Core entries must be declared catalog/feed/library/playback/lyric/link reads from the host allowlist; account bootstrap and mutations remain serialized. Feature entries must name declared feature actions and must be reads. These calls receive `context.readOnlyStorage: true`: returned storage must be omitted or exactly match the supplied snapshot. A differing map returns `INVALID_RESPONSE` without saving it. Stateful operations remain serialized with each other within the same twenty-second admission/execution budget, while immutable reads may overlap them; account/provider revision checks reject stale results. Older hosts ignore the optional declarations and retain serialized behavior. Plugins must keep read continuation state in the returned host-owned continuation, rather than writing a persistent pending plan.

```javascript
globalThis.sourcePlugin = {
  'catalog.search'(parameters, context) {
    return {
      request: {
        url: 'https://api.example.com/search?q=' + encodeURIComponent(parameters.query),
        headers: {Authorization: 'Bearer ' + context.config.token},
        errorResponse: true
      },
      state: {offset: parameters.offset}
    };
  },
  onResponse(operation, response, state, context) {
    if (response.error) return {error: response.error === 'RESPONSE_TOO_LARGE' ? 'INVALID_RESPONSE' : response.error};
    if (response.status === 401) return {error: 'AUTH_EXPIRED'};
    return {items: [], hasMore: false};
  }
};
```

A host request contains `url`, optional method/headers/body and JSON `state`. At most four HTTP requests run in one isolated operation. `onResponse(operation, response, state, context)` may request another hop or return the final value. Normal responses contain `status`, `headers`, `setCookies`, `body` and optional `bodyBase64`.

- `bodyEncoding: 'utf8' | 'base64'` controls how `request.body` becomes bytes. Base64 decoding precedes the raw request limit.
- `responseEncoding: 'utf8' | 'base64'` controls the response. Binary mode keeps `body` empty and supplies original bytes as `bodyBase64`.
- `errorResponse: true` converts transport `NETWORK`/`TIMEOUT` and oversized transport bodies (`RESPONSE_TOO_LARGE`) into `{status:0,body:'',error:<code>}`. That response has no required header/cookie fields. Cancellation, permission failure and other failures stop the operation. Recovery must not blindly replay writes.
- `sdk` and bounded `sdkConfig` select an explicitly permitted host codec. They do not bypass hostname, DNS, redirect or byte limits. `netease.transport` accepts `mode` (`api`, `eapi`, `weapi`), `signingPath`, a fresh `weapiKey`, and JSON-encoded `formFields`. The source supplies the complete URL, headers, cookies, client profile and plaintext body; the host only encodes/decodes bytes.

Network grants are exact hostnames or subtree patterns with a single leading `*.`, with at most 32 per manifest. A subtree grant matches one or more labels at any depth and excludes its apex: `*.music.126.net` permits `m803.music.126.net` and `deep.m803.music.126.net`, but not `music.126.net` or a lookalike suffix. Wildcards cannot target public suffixes such as `*.com`, `*.co.uk` or `*.github.io`. Actual request URLs must contain concrete hosts. HTTPS on port 443 is required. Userinfo, fragments, IP literals and local names are rejected. The restricted client checks system DNS at connection time, permits public addresses and the system proxy's synthetic `198.18.0.0/15` Fake-IP range, disables explicit HTTP proxies and rejects redirects. Normal TLS hostname verification remains required; scripts cannot supply DNS answers or connect to literal Fake-IP addresses. Other non-public addresses remain blocked. Requests are capped at 256 KiB raw and API responses at 4 MiB raw; callback envelopes remain subject to the result limit, including Base64 overhead. Artwork streams separately, with a 16 MiB limit per image and partial-file cleanup. Forbidden headers and CR/LF injection are rejected.

API, artwork and audio use independent dispatchers with limits of four, two and two asynchronous calls, sharing the source's connection pool. SDK wrappers are reused, while credentials, codec arguments and revocation checks belong to each request. Cached artwork is checked before download admission. API/artwork deadlines cover admission, dispatch, SDK work and consuming the body, with a fifteen-second total, eight-second connection and ten-second read/write limits. Progressive audio keeps connection/read stall limits without a whole-song fifteen-second cutoff. Caller cancellation owns queued calls and sockets through body consumption. Transport failures do not cause implicit retries of remote writes.

Optional artwork outside those grants is omitted without opening a connection. A rejected audio resource produces an operation-level `INVALID_RESPONSE` and leaves other source capabilities available. An actual script network request outside its grants remains a plugin permission violation.

Each operation gets a fresh QuickJS runtime and globals. Up to four independent sessions share an isolated worker connection, with fifteen seconds of idle connection reuse; network waiting holds no global execution mutex. Each runtime has a 32 MiB heap and 1 MiB stack, normally 256 KiB returned JSON, three seconds per evaluation and twenty seconds including operation admission. Declared batch catalog and complete playlist/album metadata results have a bounded 2 MiB result budget; continuation state and storage keep their smaller limits. Runtime availability depends on the application's bundled native engine, not the system WebView version. Code and result streams use bounded file descriptors rather than oversized Binder transactions. The worker has no host account, file, network or Android-object bindings. Cancellation interrupts its own evaluation and HTTP. If native execution does not acknowledge interruption, the watchdog terminates the worker; affected calls fail without replaying requests already sent.

DNS rejection and server redirects produce `NETWORK`; oversized remote bodies, returned JSON or permitted storage updates produce `INVALID_RESPONSE`. These bounded operation failures leave the source available for later requests. Undeclared permissions and isolate memory exhaustion remain `PLUGIN` failures. Large JSON parsers should retain original string ranges instead of allocating one intermediate string per character. Audio bytes stream directly through the host's Media3 data source and decoder; the JavaScript heap and metadata-response limits do not cap audio size or quality.

`context.secureRandomBase64` contains 256 fresh CSPRNG bytes for every operation and response evaluation. Consume bytes once for protocol nonces/IVs, never persist this pool, and fail if it is insufficient. `context.device` provides bounded OS/model/build, display resolution, a source-specific installation identifier and this app's process metadata; it does not expose account stores or hardware advertising identifiers.

`context.account.userId`, when present, is the host's verified identity for the current source session. Read-only interfaces can use it instead of rediscovering the account on every page. `account.get` still contacts the service when the host explicitly verifies or refreshes it. Authentication failures clear the verified identity; temporary transport failures retain it. Session credentials and bounded protocol continuations remain private to the source transport. Application data, account profiles, listening history and random-order policy caches belong to the host. A source may expose `discovery.shufflePolicy` to return normalized experiment responses; the host applies the playback algorithm without passing playlist contents or history through JavaScript.

## Features and shared UI

Declare `feature.invoke` plus a `features` map of exact family/action names. Optional `featureRoutes` opt into existing host entry points. Both declarations are checked; arbitrary routes and executable plugin UI are not accepted.

```javascript
'feature.invoke'(request, context) {
  if (request.feature !== 'login' || request.action !== 'sessionState') {
    return {error: 'UNSUPPORTED'};
  }
  return {authenticated: false, kind: null};
}
```

Cloud list parameters retain their explicit `offset` and `limit`; the screen requests 30 rows initially and 200 when appending. `nextOffset` must advance from raw service rows even when projections omit invalid entries or the UI deduplicates IDs. First-page reuse is host-owned typed state, with refresh/mutation and immutable session identity guards; sources must not persist cloud pages in JavaScript storage.

The host binds feature calls to one provider object, instance, version, account revision and screen scope. Changing the default browse source cannot retarget an open edit, conversation, upload or login form. Disabling, replacing, removing or changing that owner's account cancels in-flight work and closes pending host interactions. Host callers alone may opt named login/logout/account-web flows into a final same-provider session transition. Pending effects still require the original account.

Shared presenters retain the app's original playlist/album/artist UI, grouped rows, menus and playback controls. Advanced shared schemas cover account/security/login, cloud, podcasts, listening statistics/history, song wiki, comments, private messages, recognition and listening together. IDs stay strings throughout these schemas. For example, `podcasts.search` returns `{podcasts,hasMore,totalCount,fetchedCount}`; account playlist pages return `{playlists,hasMore,nextOffset}`. Aggregation across larger libraries runs outside one isolated response.

Comments use semantic `contentId`, `contentType` (`track`, `album`, `playlist`, `program`) and `sortType` (`recommended`, `hot`, `time`). Service thread prefixes, sort integers and cursor recipes belong to the plugin. Existing UI actions use only the selected source's capabilities; an unsupported action cannot fall back to NetEase.

Core catalog and known advanced image fields become durable local file URIs containing a URL hash. URL descriptors are encrypted in the owning package namespace; raw URLs and credentials do not appear in the URI. The shared image loader fetches a missing file only on demand through the currently approved source and DNS policy, with account and cancellation guards through the full response body and atomic file commit. Descriptors survive process restart and compatible provider updates; downloaded files remain usable by retained queues and libraries after disabling or removing a source. Uninstall removes encrypted descriptors. A page of covers or avatars does not trigger eager image downloads while its JSON is parsed. Legacy in-memory `meisource-artwork:` handles remain readable during their original process.

## Host effects

An operation can finish with an effect envelope, optionally inside `{result,storage}`:

```javascript
return {
  result: {
    hostEffect: {type: 'files.pick', parameters: {mimeTypes: ['audio/*'], multiple: false}},
    continuation: {step: 'selected-file'}
  },
  storage: updatedOwnStorage
};
```

The isolate and operation lock are released before the host waits for UI, a file picker, SDK work or upload. The host re-enters the **same** core operation or feature/action with the original parameters plus:

```javascript
parameters.$continuation = {
  state: previousOpaqueContinuation,
  effect: {ok: true, result: hostResult}, // or {ok:false,code:'UNSUPPORTED'}
  token: hostIssuedToken
};
```

`$continuation` is reserved on initial calls. Tokens contain 64 CSPRNG bytes and are newly generated for each step. The executor retains owner/screen binding independently of token contents. Lifecycle/owner cancellation stops the chain. A user-declined interaction may return `{ok:false,code:'CANCELLED'}`; it never fabricates success.

A chain permits eight host effects. The effect specification and opaque continuation are each bounded at 64 KiB, host results at 128 KiB, and combined callback/final JSON at 256 KiB with bounded depth. Store large operations as small plans, not accumulated response dumps. Core operations use the same privileged effect handler; for example, a collection write can request a fresh security proof without preventing an independent catalog read. Core operations also support bounded internal `source.continue` segments with empty parameters. Pending plans must be unique per invocation: parallel calls to one operation cannot overwrite each other's continuation state.

| Effect | Behavior |
| --- | --- |
| `source.continue` | Internal continuation, automatically succeeds with `null`; no privileged handler or permission grant |
| `files.pick` | User-selected immutable file snapshot becomes an opaque handle with name, extension, size, MD5 and optional audio title/artist/album |
| `files.upload` | Streams a selected handle through the owner's restricted client |
| `sdk.call` | Calls a fixed, separately declared native primitive |
| `web.auth`, `web.page` | Source-owned WebView profile and fixed prompt bridge |
| `qr.scan`, `sms.compose` | Host-owned camera/image scan or explicit SMS composition |
| `audio.capture`, `audio.fingerprint` | Recognized protocol names; a missing handler returns `UNSUPPORTED` |

Privileged effects require membership in `permissions.effects`. `source.continue` cannot be declared there. File handles are single-use, expire after ten minutes, and are bound to provider/version/account/screen/feature/action. They do not expose raw content URIs. The current file limit is 1 GiB and at most eight outstanding handles.

`sms.compose` receives `{destination, body}` and presents the shared outgoing-SMS instructions, copy actions, SMS draft, refresh and explicit continue button. It does not send SMS. Continue returns `{status:'sent'}`; refresh returns `{status:'retry'}` so the plugin can renew its own challenge. A cancelled sheet returns `CANCELLED`. Login success must explicitly return `{authenticated:true,...profile}`. `login.requestMobileSmsCode` returns `{status:'sent'}` for an incoming code or the same authenticated result if an outgoing-SMS challenge completed the login. The host dismisses the original login form only for that authenticated result.

`discovery.dailySongs` returns `{tracks, title?, creatorName?, description?, updatedAtMs?, recommendations?:[{trackId,reason}]}` using standard typed tracks. Empty days are valid. The original daily recommendation song-list UI uses those identities for queues and source actions. `VERIFICATION_REQUIRED` preserves a device-removal SMS challenge without treating the response as an expired account.

The native SDK whitelist is `netease.transport`, `netease.urs`, `netease.securityHeaders`, `netease.ursPuzzle`, `netease.ursCaptcha` and `netease.history`. The first two are HTTP codecs; effect SDK calls supply closed proof/captcha/puzzle/packet primitives. Every SDK requires `permissions.nativeSdks`. Request overrides are restricted to documented source-owned configuration fields. No plugin chooses a native class, method or arbitrary SDK endpoint.

The vendor security runtime used by `netease.securityHeaders` has process-wide state and no verified reset API. The host serializes calls and permits only the first source-instance owner for that process. A second owner receives `UNSUPPORTED`. Switching that SDK to another owner requires a process restart.

### Web effects

Web parameters include a URL/title, page `allowedHosts`, `cookieHosts`, explicit returned `cookieNames`, this source's `cookieHeaders`, fixed `bridgeAliases`, and typed `urlMappings`. Host manifest network grants bound all resource loads. Page `allowedHosts` may narrow those exact or subtree grants, but cannot broaden them; page restrictions do not implicitly authorize extra static-resource domains. Returned `cookieHosts` must be concrete hosts inside the page scope. `cookieHeaders` keys must be concrete hosts covered by the manifest grants; each header seeds only its named host, never an entire wildcard subtree. `web.auth` requires a cookie return whitelist. The result is `{closed:true,cookies:{name:value},cookie:'name=value; ...'}` from that profile only.

The host requires AndroidX WebKit multi-profile and document-start support. Each interaction uses an isolated profile and destroys it on completion/cancellation. Chromium and service-worker direct network loads are blocked; a fixed host shim mediates resource/fetch/XHR/form traffic through domain/DNS/redirect checks. There is no fallback to the global CookieManager.

Prompt verbs are fixed: `device.info`, `page.info`, `page.title`, `page.ui`, `navigation.open`, `navigation.close`, `session.present`, `session.profile`, `session.login`, `toast.show`. Aliases map to these verbs only. Unknown methods receive 404. Account/profile reads expose this source's public account only. Navigation mappings resolve a declared `web.openPage` recipe inside the current checked WebView history. `session.login` closes that effect with `{loginRequested:true}`; the source returns `{status:'login_required'}` and the shared screen opens its owner's login page. Plugins cannot install Android objects or submit JavaScript for evaluation.

Web file inputs use the system picker and page-scoped immutable snapshots. Selected files and multipart bodies stream through the same approved transport; unselected paths are never accepted. Generated inline blobs stay within the small request-byte limit. Closing the page revokes file handles and cancels transfers. File effects likewise bind handles to one invocation chain and release unconsumed snapshots when that chain ends.

## Storage, account identity and lifecycle

`context.config` and optional `context.storage` belong to one package instance. They are encrypted with Android Keystore AES-GCM in separate namespaces under `noBackupFilesDir`. A final `{result,storage:{key:'value'}}` replaces that source's storage, limited to 64 keys and 64 KiB. Writes require `permissions.storage` and commit only while the source revision is current. Exceptions and typed errors do not expose arbitrary remote error strings.

Declare account-bearing keys in `sessionKeys` (at most 32). Their storage value takes precedence over configuration. With `sessionCookieNames`, only the named cookies define that key's account identity. Cookie order and unrelated cookie refreshes do not change the account fingerprint. Explicit empty storage values retain logout semantics. Version/configuration changes also invalidate old bindings. Transient challenge/continuation storage should not be listed as session identity unless it changes the account.

Updates, configuration edits, enable/disable and uninstall replace source revision gates. Old calls cannot commit stale storage or diagnostics after a replacement. Import/update approval is bound to reviewed package bytes. Reimport preserves that package's configuration and music data; uninstall removes its code/configuration but leaves Room rows and downloaded audio.

The separate [MeiloX-Netease-Source](https://github.com/NEORUAA/MeiloX-Netease-Source) repository maintains the default NetEase package. The host vendors its artifact and SHA-256 in `BundledSourceProvisioner`. Only those exact first-install bytes can receive the former built-in account through a one-time encrypted migration. A pre-existing imported package never inherits those credentials. The persistent default marker records migration or explicit removal, so uninstalling does not cause automatic restoration. Other sources continue loading if default provisioning fails.

## Links and lyrics

`links.resolve` requires exact, concrete `linkHosts`, each covered by a declared network grant. A network subtree grant does not widen link routing beyond those exact link hosts. The host extracts a bounded set of URLs from pasted/shared text and sends only a matching URL to its declared owner. Ambiguous hosts use the user's explicitly selected matching source. Return `{contentRef:{providerId,type,contentId}}`, `{featureRoute,parameters}`, or `null`; cross-provider targets and undeclared feature routes are rejected. Link results retain provider/account ownership through navigation.

The host lyric repository remains a multi-source system for every playback source. NetEase, QQ and extended lyric selection/matching/cache behavior does not depend on the preferred browse/search source or on installing the NetEase content plugin. Optional `lyrics.get` adds provider lyrics; it does not replace the existing lyric choices. Foreign numeric track IDs are never treated as NetEase lyric identities.

## Example and validation

```sh
node samples/music-source-plugin/test.mjs
python3 samples/music-source-plugin/package.py
```

The sample is an offline API 1 fixture with an authored one-second tone. It exercises portable search/detail/audio/lyrics behavior. Real Android sandbox, profile, SDK, source migration and account behavior require their corresponding checks. Dated build/device evidence is maintained in [source-validation.md](source-validation.md).

The [QQ adapter](https://github.com/NEORUAA/MeiloX-QQMusic-Source) is maintained independently from the host's QQ lyric extension. It uses the same source boundary and shared UI. Host source management is under **Settings → General → Audio Sources**.
