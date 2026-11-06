# Source plugin API 1

MeiloX runs imported JavaScript with AndroidX JavaScriptEngine 1.1.1 in an isolated V8 process. Plugins do not receive an Android object, WebView bridge, filesystem API, Room handle, NetEase session, or another plugin's configuration. A compatible Android System WebView is required. If the device lacks enforced heap/result limits, script execution returns `UNSUPPORTED` instead of switching to an unrestricted interpreter.

The package and SDK example are in `samples/music-source-plugin/`. The example is an **offline mock**, with an authored one-second test tone. It does not implement QQ Music or Kugou playback.

## Package

Import a ZIP file with a `.meisource` extension:

```text
manifest.json
index.js
assets/tone.wav       # Optional packaged audio fixture
```

Only the two root files and audio files immediately under `assets/` are accepted. Absolute paths, parent traversal, duplicate paths, directories, undeclared entry scripts, and arbitrary executable files are rejected. The host limits each script to 256 KiB, the manifest to 64 KiB, each asset to 4 MiB, the extracted package to 8 MiB, and the entry count to 64.

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

IDs are case-sensitive lowercase identifiers, 3–80 characters, using letters, digits, `.`, `_`, and `-`. `netease` and `local` are reserved. The package `id` identifies installed code. `providerId` identifies catalog content, defaults to `id`, and cannot change on update. The current instance ID is `script:<id>:default`. It is separate from track IDs and plugin-owned account identifiers. API 1 is the supported host contract; packages declaring another positive API version remain installable for inspection/removal but are incompatible for execution.

API 1 supports four optional capabilities. A lyrics-only plugin does not enter audio fallback. Album, artist, playlist, account and recommendation operations can be added in a future negotiated API; they are not advertised by this runtime.

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

An audio resource contains `uri`, `quality`, optional `headers`, `expiresAtMs`, `contentLength`, `contentId`, `mimeType`, and `cachePolicy: 'ALLOW' | 'NO_STORE'`. URL expiry does not invalidate already downloaded audio. An opaque host handle replaces URLs and headers before they enter Media3, cache metadata or download provenance. Audio range requests use the plugin-specific restricted client. Packaged audio can use `asset://tone.wav`; the host resolves it only inside the installed package's canonical `assets` directory. Artwork caches are limited to 64 files and 16 MiB per plugin.

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

`onResponse` receives `(operation, {status, headers, body}, state, context)` in the same isolate. It may return another request, with at most four requests per operation, or a final result. State is transient JSON. The host has no shared cookie jar and does not log request URLs, headers or response bodies. Plugins must use only their own supplied credentials.

Network permissions are **exact hostname** grants: no wildcards, non-HTTPS URLs, alternate ports, userinfo, fragments, direct IP domains, or local names. DNS results are checked at connection time. Loopback, private, link-local, shared IPv4, multicast, mapped IPv6 and transition addresses are rejected. Redirects are rejected. Requests are limited to 256 KiB, responses and artwork to 1 MiB. Header injection and connection/proxy/host header overrides are rejected. Each plugin client allows two host requests concurrently, with 8-second connection, 10-second read/write, and 15-second whole-call limits. The runtime serializes sandbox invocations because Android permits one sandbox process.

A fresh sandbox/isolate is created for every operation. Heap is capped at 16 MiB, result/error strings at 256 KiB, each evaluation at three seconds, and a whole operation at 20 seconds. Closing the sandbox terminates its isolated process on timeout/cancellation. There is no runtime fallback to WebView or arbitrary DEX/JAR loading. A plugin crash or denied request produces a typed error; it does not clear host accounts or user data.

## Configuration and storage

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

Import `offline-mock.meisource` from Audio Sources in settings. Select **MeiloX Offline Mock** for search and search for **Mock Recording**. It returns the SDK fixture and resolves the packaged test tone. The Android instrumentation test exercises import, real isolated JavaScript, search, detail, ordered fallback, an opaque audio read, disable/re-enable, and reload. The Node runner verifies the portable operation contract; it does not substitute for Android sandbox tests.

For QQ Music or Kugou development, obtain a documented, authorized API and verify the account/playback contract first. Declare only verified capabilities and exact API/CDN hostnames. Preserve the platform's string track IDs, actual audio quality, token expiry and version metadata. The existing QQ lyrics extension is not an audio provider. This SDK deliberately ships no universal parser, copied platform secrets, or unverified full-platform adapter.

## Distribution boundary

AndroidX JavaScriptEngine is licensed under Apache 2.0 and uses the device's WebView-provided isolated JavaScript service. Its support and available limits depend on that implementation. The app retains trust in Android, the WebView/V8 sandbox, TLS/DNS, its own mediation code and the user's permission review. This is not a claim that arbitrary third-party scripts or platform API usage receive app-store approval. Google Play's Device and Network Abuse rules permit certain interpreted-code mechanisms but still require compliance with all policies, including behavior performed by downloaded scripts. Distribution review and music-service permissions must be assessed for the intended plugin and release.

Primary references: [AndroidX JavaScriptEngine releases](https://developer.android.com/jetpack/androidx/releases/javascriptengine), [JavaScriptSandbox API](https://developer.android.com/reference/androidx/javascriptengine/JavaScriptSandbox), [isolate resource limits](https://developer.android.com/reference/androidx/javascriptengine/IsolateStartupParameters), [Google Play Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/9888379).
