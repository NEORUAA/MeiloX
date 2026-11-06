# Source plugin APIs 1 and 2

MeiloX runs imported JavaScript with AndroidX JavaScriptEngine 1.1.1 in an isolated V8 process. Plugins do not receive an Android object, WebView bridge, filesystem API, Room handle, NetEase session, or another plugin's configuration. A compatible Android System WebView is required. If the device lacks enforced heap/result limits, script execution returns `UNSUPPORTED` instead of switching to an unrestricted interpreter.

The package and SDK example are in `samples/music-source-plugin/`. The example is an **offline mock**, with an authored one-second test tone. It does not implement QQ Music or Kugou playback.

## Package

Import a ZIP file with a `.meisource` extension:

```text
manifest.json
index.js
assets/tone.wav       # Optional packaged audio fixture
```

The two required root files, optional UTF-8 `LICENSE` / `NOTICE` (64 KiB each), and audio files immediately under `assets/` are accepted. Absolute paths, parent traversal, duplicate paths, directories, undeclared entry scripts, and arbitrary executable files are rejected. The host limits each script to 256 KiB, the manifest to 64 KiB, each asset to 4 MiB, the extracted package to 8 MiB, and the entry count to 64.

```json
{
  "id": "org.example.source",
  "providerId": "org.example.source",
  "name": "Example Source",
  "author": "Example author",
  "version": "1.0.0",
  "apiVersion": 1,
  "entry": "index.js",
  "capabilities": ["catalog.search", "catalog.track", "playback.resolve", "lyrics.get"],
  "permissions": {"network": ["api.example.com", "audio.example.com"], "storage": true},
  "config": [{"key": "token", "label": "API token", "secret": true, "required": true}]
}
```

IDs are case-sensitive lowercase identifiers, 3–80 characters, using letters, digits, `.`, `_`, and `-`. `netease` and `local` are reserved. The package `id` identifies installed code. `providerId` identifies catalog content, defaults to `id`, and cannot change on update. The current instance ID is `script:<id>:default`. It is separate from track IDs and plugin-owned account identifiers. APIs 1 and 2 are supported; packages declaring another positive API version remain installable for inspection/removal but are incompatible for execution.

API 1 retains its four optional capabilities and bare-track search format. A lyrics-only plugin does not enter audio fallback. API 2 adds optional typed content, feeds, accounts and libraries. Existing API 1 packages do not need changes.

## API 2 content

Declare only the operations implemented by the plugin. `searchTypes` and `collectionTypes` use `track`, `album`, `artist`, `playlist`. The SDK type declarations are the complete field contract.

| Operations | Results |
| --- | --- |
| `catalog.search` with `type`, `offset`, `limit`, optional `cursor` | `{items: SourceItem[], hasMore, nextCursor?}` |
| `feed.home`, `feed.discover` | Sections with stable IDs, titles, layouts and typed items; optional categories and pagination |
| `album.get`, `album.tracks` | Album metadata and paginated tracks |
| `artist.get`, `artist.tracks`, `artist.albums` | Artist metadata, tracks and paginated albums |
| `playlist.get`, `playlist.tracks` | Playlist metadata, permitted edit actions and ordered tracks |
| `account.get` | This instance's own account, display name, avatar and opaque session revision |
| `library.likedTracks`, `library.createdPlaylists`, `library.savedPlaylists`, `library.savedAlbums`, `library.followedArtists` | Paginated typed items owned by the current session |
| `track.liked`, `track.like` | Like status and explicit state changes |
| `collection.saved`, `collection.save` | Collection status and explicit state changes |
| `playlist.create`, `playlist.update`, `playlist.delete`, `playlist.addTracks`, `playlist.removeTracks`, `playlist.reorderTracks` | Optional individual edit capabilities |

A typed item is `{type: 'track', track: {...}}`, `{type: 'album', album: {...}}`, `{type: 'artist', artist: {...}}` or `{type: 'playlist', playlist: {...}}`. API 2 tracks use artist credits `{name, artistId?}` and an album credit `{title, albumId?}`. IDs remain strings and the host supplies the provider namespace. No script-supplied UI, route or numerical surrogate ID is accepted.

The host checks returned content types, identities, page limits, cursor progress, account-instance identity and edit permissions. Successful writes notify content views without invalidating current audio. Playlist ordering preserves repeated tracks. Paged queues load near the tail, and snapshots retain safe typed locators plus account/version fingerprints. Opaque cursors remain in memory; restoration verifies the saved prefix before resuming remote pagination.

Feed layouts select existing host components (`hero`, `playlists`, `tracks`, `albums`, `artists`, `grid`), rather than executable UI. Missing operations remain unsupported; they do not redirect to Netease.

## JavaScript contract

Set `globalThis.sourcePlugin` to an object with the declared operation names. Type declarations are in `samples/music-source-plugin/source-plugin.d.ts`. Each operation is synchronous and receives `(params, context)`. Promises, `fetch`, timers, arbitrary host calls and module loading are not part of this API.

```javascript
globalThis.sourcePlugin = {
  'catalog.search'(params, context) {
    return {items: [], hasMore: false};
  },
  'catalog.track'(params, context) {
    return null;
  },
  'playback.resolve'(params, context) {
    return {error: 'UNAVAILABLE'};
  }
};
```

- Search parameters: `{query, offset, limit}`. Return `{items: Track[], hasMore}`.
- Track parameters: `{trackId}`. Return a track or `null`.
- Playback parameters: `{trackId, quality}`. Return an audio resource with the **actual** returned quality, not the requested target.
- Lyrics parameters: `{trackId}`. Return `{text, translation?}` or `null`.
- Failure: return `{error: 'AUTH_REQUIRED'}` or another typed SDK error. Exception text is discarded, since it may contain credentials.

A track contains a string `trackId`, `title`, artist names, album title, `durationMs`, optional `version`, `explicit`, `isrc`, and `artworkUrl`. IDs may contain Unicode, punctuation and slashes. The host constructs provider-scoped identities; plugins do not invent numerical surrogate IDs. Artwork URLs use the same domain restrictions and are fetched to a bounded private cache before native image loading.

An audio resource contains `uri`, `quality`, optional `headers`, `expiresAtMs`, `contentLength`, `contentId`, `mimeType`, and `cachePolicy: 'ALLOW' | 'NO_STORE'`. URL expiry does not invalidate already downloaded audio. An opaque host handle replaces URLs and headers before they enter Media3, cache metadata or download provenance. Audio range requests use the plugin-specific restricted client. Packaged audio can use `asset://tone.wav`; the host resolves it only inside the installed package's canonical `assets` directory. Artwork caches are limited to 256 files and 16 MiB per plugin; cache hits refresh their LRU access time.

## Host network protocol

The plugin may return a host request instead of its final result:

```javascript
'catalog.search'(params, context) {
  return {
    request: {
      url: 'https://api.example.com/search?q=' + encodeURIComponent(params.query),
      headers: {Authorization: 'Bearer ' + context.config.token}
    },
    state: {offset: params.offset}
  };
},
onResponse(operation, response, state, context) {
  if (response.status === 401) return {error: 'AUTH_EXPIRED'};
  const payload = JSON.parse(response.body);
  return {items: payload.tracks, hasMore: false};
}
```

`onResponse` receives `(operation, {status, headers, body, bodyBase64?}, state, context)` in the same isolate. It may return another request, with at most four requests per operation, or a final result. State is transient JSON. Requests may specify `responseEncoding: 'base64'` for a binary response. The host applies the same raw byte limit before encoding, returns an empty `body` and the original bytes in `bodyBase64`; the default remains UTF-8 text. Invalid encodings fail before any request.

Each operation callback receives fresh `context.secureRandomBase64`, containing 256 bytes from the host CSPRNG. It is bounded data, not an Android or cryptography bridge. Plugins can use it for protocol nonces, must track consumption within a callback, and must fail rather than substitute `Math.random` when they need more entropy. All cryptographic implementation code remains inside the isolated script.

The host has no shared cookie jar and does not log request URLs, headers or response bodies. Plugins must use only their own supplied credentials.

Network permissions are **exact hostname** grants: no wildcards, non-HTTPS URLs, alternate ports, userinfo, fragments, direct IP domains, or local names. DNS results are checked at connection time. Loopback, private, link-local, shared IPv4, multicast, mapped IPv6 and transition addresses are rejected. Redirects are rejected. Requests are limited to 256 KiB, responses and artwork to 1 MiB. Header injection and connection/proxy/host header overrides are rejected. Each plugin client allows two host requests concurrently, with 8-second connection, 10-second read/write, and 15-second whole-call limits. The runtime serializes sandbox invocations because Android permits one sandbox process.

A fresh sandbox/isolate is created for every operation. Heap is capped at 16 MiB, result/error strings at 256 KiB, each evaluation at three seconds, and a whole operation at 20 seconds. Closing the sandbox terminates its isolated process on timeout/cancellation. There is no runtime fallback to WebView or arbitrary DEX/JAR loading. A plugin crash or denied request produces a typed error; it does not clear host accounts or user data.

## Configuration and storage

An optional plain-text `description` (up to 2,000 characters) appears beside configuration in the plugin detail page. Use it to explain what credentials or other fields expect; it cannot render HTML or execute links.

`context.config` contains only this plugin instance's declared user configuration. Required empty fields put the source in `NEEDS_CONFIGURATION`. Configuration and optional plugin-owned storage are encrypted with Android Keystore AES-GCM under separate plugin namespaces, stored in `noBackupFilesDir`. Secret values are never included in the installed-source list, diagnostics or exported packages.

When `permissions.storage` is enabled, a final envelope can return `{result: value, storage: {key: 'value'}}` to replace this plugin's storage map. Storage is limited to 64 keys and 64 KiB. It becomes the next invocation's `context.storage`. Without permission, storage writes fail. There is no raw file access. Configuration changes clear plugin-owned session storage and increment the resolution revision, preventing a previous account's tokens or mappings from being reused. Re-importing the same ID preserves configuration and user music data. Uninstalling clears plugin code/configuration, while Room playlist items, downloaded audio and independent player caches remain.

Every update, configuration, enable/disable and uninstall transition replaces a
per-source revision token. Reads and state commits validate that token atomically:
an older invocation cannot read a replacement account's configuration or restore
its old storage/diagnostics after the transition. Already-open audio handles follow
the current-playback policy below.

## Installation and updates

The manager first extracts and validates a staged package. The settings screen shows its identity, version and requested network/storage permissions. Installation requires explicit approval of this inspected package. A SHA-256 digest binds review to the staged bytes. Updates use the same import/review path; there are no silent remote updates or permission expansion. Replacement is transactional, retaining the previous package on failure. Users can disable, re-enable or uninstall broken plugins.

Provider status/configuration changes affect new resolution requests. The host does not replace a playing queue item's content identity or silently restart playback when settings change. Fallback order is independent from default browse/search selection. Matching remains conservative; uncertain candidates require a user binding.

## Run the offline fixture

```sh
node samples/music-source-plugin/test.mjs
python3 samples/music-source-plugin/package.py
```

Import `offline-mock.meisource` from **Settings → General → Audio Sources**. Select **MeiloX Offline Mock** for search and search for **Mock Recording**. It returns the SDK fixture and resolves the packaged test tone. The Android instrumentation test exercises import, real isolated JavaScript, search, detail, ordered fallback, an opaque audio read, disable/re-enable, and reload. The Node runner verifies the portable operation contract; it does not substitute for Android sandbox tests.

The [QQ Music website adapter](https://github.com/NEORUAA/MeiloX-QQMusic-Source) supports anonymous track search, track details and lyrics. Complete search, personal libraries and playback use the user's privately configured session. Its pinned 0.3 package passed authenticated catalog/library and full-resource playback checks on Android; see [validation scope and results](source-validation.md). It is separate from the existing QQ lyrics extension and from QQ's partner OpenAPI. Declare exact API/CDN hosts, retain string track IDs, and report actual audio quality and expiry. The SDK contains no bundled account, platform secrets, or third-party parsing service.

## Distribution boundary

AndroidX JavaScriptEngine is licensed under Apache 2.0 and uses the device's WebView-provided isolated JavaScript service. Its support and available limits depend on that implementation. The app retains trust in Android, the WebView/V8 sandbox, TLS/DNS, its own mediation code and the user's permission review. This is not a claim that arbitrary third-party scripts or platform API usage receive app-store approval. Google Play's Device and Network Abuse rules permit certain interpreted-code mechanisms but still require compliance with all policies, including behavior performed by downloaded scripts. Distribution review and music-service permissions must be assessed for the intended plugin and release.

Primary references: [AndroidX JavaScriptEngine releases](https://developer.android.com/jetpack/androidx/releases/javascriptengine), [JavaScriptSandbox API](https://developer.android.com/reference/androidx/javascriptengine/JavaScriptSandbox), [isolate resource limits](https://developer.android.com/reference/androidx/javascriptengine/IsolateStartupParameters), [Google Play Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/9888379).
