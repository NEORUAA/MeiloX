# Source plugin APIs 1 and 2

A source is an imported `.meisource` package. Its JavaScript runs through AndroidX JavaScriptEngine in a bounded isolated V8 process. The host owns navigation, Compose UI, playback, files and Android SDK access. Source code owns service endpoints, authentication protocols and response projections.

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
  "permissions": {
    "network": ["api.example.com", "audio.example.com"],
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

Updates preserve private configuration and enabled state. Removed sources are not reinstalled. Download, digest, index or update failures retain the installed version and do not fail the source's other operations. A directory-replacement journal recovers an interrupted installation. Automatic replacement defers while the source owns the current paused/playing track or queue restoration; it does not interrupt already opened audio.

## Core content

| Operations | Result |
| --- | --- |
| `catalog.search` | API 2 page of tagged items matching `type`; API 1 page of bare tracks |
| `catalog.track` | Track or `null` |
| `album.get`, `artist.get`, `playlist.get` | Typed metadata with the requested identity |
| `album.tracks`, `artist.tracks`, `artist.albums`, `playlist.tracks` | Ordered pages |
| `feed.home`, `feed.discover` | Stable sections/categories using existing host layouts |
| `account.get` | This instance's own account or `null` |
| `library.likedTracks`, `library.createdPlaylists`, `library.savedPlaylists`, `library.savedAlbums`, `library.followedArtists` | Session-owned pages of typed items |
| `track.liked`, `collection.saved` | Boolean status |
| `track.like`, `collection.save` | Confirmed explicit boolean change |
| `playlist.create`, `playlist.update`, `playlist.delete`, `playlist.addTracks`, `playlist.removeTracks`, `playlist.reorderTracks` | Individually declared editing capabilities |
| `playback.resolve`, `playback.qualities` | Audio resource, verified qualities, or bounded quality continuation |
| `lyrics.get` | Optional source lyrics; host lyric selection remains independent |

A tagged item is `{type:'track', track:{...}}`, or its `album`, `artist` or `playlist` equivalent. Pages contain `{items, hasMore, nextCursor?}`. Search and library types must match manifest `searchTypes`/`collectionTypes`. The host validates identities, collection/edit capabilities, counts and advancing cursors. Playlist positions retain duplicate recordings.

Tracks include `trackId`, `title`, artist names/credits, album title/credit, `durationMs`, optional artwork, `isrc`, recording `version` and `explicit`. Playlists may include a nonnegative integer `playCount`; omit it or return null when unknown instead of inventing zero. Album metadata may include `publishedAtMs`; artists may include `alternateNames`, optional track/album/music-video counts, identities, expertise, translated names and aliases. These fields populate the original artist information grouped list without invented counts. Feeds use host `hero`, `playlists`, `tracks`, `albums`, `artists` and `grid` layouts. Home caches contain normalized typed sections and card targets only, scoped to exact provider/instance/version/account revision. They expire at 06:00 Asia/Shanghai. Explicit refresh bypasses the cache and sets `PageRequest.refresh`; a completed feed replaces the file atomically after at most ten pages. Failed, cancelled, stale or incomplete refreshes retain the previous file. Each record is bounded to 8 MiB, with at most 32 MiB and 16 account/version records retained.

Hero sections may also contain up to 30 source-owned cards with typed content, declared feature-route, radio or queue targets. Square `playlists`, `albums`, `artists` and `grid` sections may contain up to 100 cards with owned content targets matching the section type. Cards preserve source order and stable unique IDs independently of the canonical `items` list. Optional `coverCaptions` (at most ten lines), `detail` and `titleMaxLines` (one or two, default two) populate the original square-card component; `title` and `subtitle` are its primary and secondary text. A square card may have an empty primary title. Hero cards continue using `subtitle` as their existing extra text. Artwork processing and service caption recipes belong to the plugin. The host fixes typography, spacing, badge placement and scrolling, and caches the normalized presentation fields. Route/card actions stay bound to the source and account that supplied them. Plugins do not supply Compose code or numerical surrogate UI IDs.

Audio resources report the **actual** quality and may include per-resource headers, expiry, size, content identity, MIME type and `cachePolicy: 'ALLOW' | 'NO_STORE'`. The host converts the URL/headers to an opaque audio handle before player/cache/download persistence. `asset://tone.wav` resolves only inside the owning package. `NO_STORE` resources cannot be downloaded or cached.

`playback.qualities` may return a string array or `{items, nextCursor?}`. `playback.resolve` may return `{nextCursor}` while continuing a bounded quality ladder. The host accepts at most three quality segments, rejects repeated/oversized cursors, and limits the combined quality list to 32 entries. These cursors are separate from host-effect continuations.

## JavaScript and network requests

Operations are synchronous functions on `globalThis.sourcePlugin`. Promises, direct `fetch`, timers, module loading, raw files, Room and Android objects are unavailable. Every operation gets `(parameters, context)`; `onResponse` runs in that operation's isolate.

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
    if (response.error) return {error: response.error};
    if (response.status === 401) return {error: 'AUTH_EXPIRED'};
    return {items: [], hasMore: false};
  }
};
```

A host request contains `url`, optional method/headers/body and JSON `state`. At most four HTTP requests run in one isolated operation. `onResponse(operation, response, state, context)` may request another hop or return the final value. Normal responses contain `status`, `headers`, `setCookies`, `body` and optional `bodyBase64`.

- `bodyEncoding: 'utf8' | 'base64'` controls how `request.body` becomes bytes. Base64 decoding precedes the raw request limit.
- `responseEncoding: 'utf8' | 'base64'` controls the response. Binary mode keeps `body` empty and supplies original bytes as `bodyBase64`.
- `errorResponse: true` converts only transport `NETWORK`/`TIMEOUT` into `{status:0,body:'',error:<code>}`. That response has no required header/cookie fields. Cancellation, permission failure and other failures stop the operation. Recovery must not blindly replay writes.
- `sdk` and bounded `sdkConfig` select an explicitly permitted host codec. They do not bypass hostname, DNS, redirect or byte limits. `netease.transport` accepts `mode` (`api`, `eapi`, `weapi`), `signingPath`, a fresh `weapiKey`, and JSON-encoded `formFields`. The source supplies the complete URL, headers, cookies, client profile and plaintext body; the host only encodes/decodes bytes.

Network grants are exact hostnames, with at most 32 per manifest. HTTPS on port 443 is required. Userinfo, fragments, IP literals, wildcards and local names are rejected. The restricted client checks DNS at connection time, rejects non-public addresses, disables proxies and rejects redirects. Requests are capped at 256 KiB raw, responses/artwork at 1 MiB raw; callback envelopes remain subject to the result limit, including Base64 overhead. Forbidden headers and CR/LF injection are rejected. The client limits concurrency to two, connection timeout to eight seconds, read/write to ten seconds and a call to fifteen seconds.

Optional artwork outside those grants is omitted without opening a connection. A rejected audio resource produces an operation-level `INVALID_RESPONSE` and leaves other source capabilities available. An actual script network request outside its grants remains a plugin permission violation.

Each operation creates a fresh sandbox/isolate. Limits are 16 MiB heap, 256 KiB returned JSON, three seconds per evaluation and twenty seconds for an isolated operation. Runtime calls are serialized. Required WebView/V8 resource-limit support is feature-gated; unavailable support returns `UNSUPPORTED`.

`context.secureRandomBase64` contains 256 fresh CSPRNG bytes for every operation and response evaluation. Consume bytes once for protocol nonces/IVs, never persist this pool, and fail if it is insufficient. `context.device` provides bounded OS/model/build, display resolution, a source-specific installation identifier and this app's process metadata; it does not expose account stores or hardware advertising identifiers.

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

Web parameters include a URL/title, page `allowedHosts`, `cookieHosts`, explicit returned `cookieNames`, this source's `cookieHeaders`, fixed `bridgeAliases`, and typed `urlMappings`. Host manifest domains bound all resource loads; page host restrictions do not implicitly authorize extra static-resource domains. `web.auth` requires a cookie return whitelist. The result is `{closed:true,cookies:{name:value},cookie:'name=value; ...'}` from that profile only.

The host requires AndroidX WebKit multi-profile and document-start support. Each interaction uses an isolated profile and destroys it on completion/cancellation. Chromium and service-worker direct network loads are blocked; a fixed host shim mediates resource/fetch/XHR/form traffic through domain/DNS/redirect checks. There is no fallback to the global CookieManager.

Prompt verbs are fixed: `device.info`, `page.info`, `page.title`, `page.ui`, `navigation.open`, `navigation.close`, `session.present`, `session.profile`, `session.login`, `toast.show`. Aliases map to these verbs only. Unknown methods receive 404. Account/profile reads expose this source's public account only. Navigation mappings resolve a declared `web.openPage` recipe inside the current checked WebView history. `session.login` closes that effect with `{loginRequested:true}`; the source returns `{status:'login_required'}` and the shared screen opens its owner's login page. Plugins cannot install Android objects or submit JavaScript for evaluation.

Web file inputs use the system picker and page-scoped immutable snapshots. Selected files and multipart bodies stream through the same approved transport; unselected paths are never accepted. Generated inline blobs stay within the small request-byte limit. Closing the page revokes file handles and cancels transfers. File effects likewise bind handles to one invocation chain and release unconsumed snapshots when that chain ends.

## Storage, account identity and lifecycle

`context.config` and optional `context.storage` belong to one package instance. They are encrypted with Android Keystore AES-GCM in separate namespaces under `noBackupFilesDir`. A final `{result,storage:{key:'value'}}` replaces that source's storage, limited to 64 keys and 64 KiB. Writes require `permissions.storage` and commit only while the source revision is current. Exceptions and typed errors do not expose arbitrary remote error strings.

Declare account-bearing keys in `sessionKeys` (at most 32). Their storage value takes precedence over configuration. With `sessionCookieNames`, only the named cookies define that key's account identity. Cookie order and unrelated cookie refreshes do not change the account fingerprint. Explicit empty storage values retain logout semantics. Version/configuration changes also invalidate old bindings. Transient challenge/continuation storage should not be listed as session identity unless it changes the account.

Updates, configuration edits, enable/disable and uninstall replace source revision gates. Old calls cannot commit stale storage or diagnostics after a replacement. Import/update approval is bound to reviewed package bytes. Reimport preserves that package's configuration and music data; uninstall removes its code/configuration but leaves Room rows and downloaded audio.

The separate [MeiloX-Netease-Source](https://github.com/NEORUAA/MeiloX-Netease-Source) repository maintains the default NetEase package. The host vendors its artifact and SHA-256 in `BundledSourceProvisioner`. Only those exact first-install bytes can receive the former built-in account through a one-time encrypted migration. A pre-existing imported package never inherits those credentials. The persistent default marker records migration or explicit removal, so uninstalling does not cause automatic restoration. Other sources continue loading if default provisioning fails.

## Links and lyrics

`links.resolve` requires exact `linkHosts`, each also present in network permissions. The host extracts a bounded set of URLs from pasted/shared text and sends only a matching URL to its declared owner. Ambiguous hosts use the user's explicitly selected matching source. Return `{contentRef:{providerId,type,contentId}}`, `{featureRoute,parameters}`, or `null`; cross-provider targets and undeclared feature routes are rejected. Link results retain provider/account ownership through navigation.

The host lyric repository remains a multi-source system for every playback source. NetEase, QQ and extended lyric selection/matching/cache behavior does not depend on the preferred browse/search source or on installing the NetEase content plugin. Optional `lyrics.get` adds provider lyrics; it does not replace the existing lyric choices. Foreign numeric track IDs are never treated as NetEase lyric identities.

## Example and validation

```sh
node samples/music-source-plugin/test.mjs
python3 samples/music-source-plugin/package.py
```

The sample is an offline API 1 fixture with an authored one-second tone. It exercises portable search/detail/audio/lyrics behavior. Real Android sandbox, profile, SDK, source migration and account behavior require their corresponding checks. Dated build/device evidence is maintained in [source-validation.md](source-validation.md).

The [QQ adapter](https://github.com/NEORUAA/MeiloX-QQMusic-Source) is maintained independently from the host's QQ lyric extension. It uses the same source boundary and shared UI. Host source management is under **Settings → General → Audio Sources**.
