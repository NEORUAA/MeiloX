/** MeiloX source APIs 1 and 2. API 2 additions do not require an API-version bump. */
export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };
export interface Context {
  readonly apiVersion: 1 | 2;
  readonly config: Readonly<Record<string, string>>;
  /** Protocol sessions/continuations only; application data and caches belong to the host. */
  readonly storage: Readonly<Record<string, string>>;
  /** Verified identity for this source session; absent until the host verifies the account. */
  readonly account?: Readonly<{ userId?: string }>;
  /** Fresh standard Base64 of 256 CSPRNG bytes per evaluation, including onResponse.
   * Consume once for nonces/IVs. Never persist the pool or substitute Math.random. */
  readonly secureRandomBase64: string;
  readonly device?: Readonly<{
    osVersion: string; model: string; brand: string; processName: string; pid: number; buildId: string;
    installationId: string; resolution: string;
  }>;
}
export type ErrorCode = 'AUTH_REQUIRED' | 'AUTH_EXPIRED' | 'UNAVAILABLE' | 'NOT_FOUND'
  | 'NETWORK' | 'TIMEOUT' | 'INVALID_RESPONSE' | 'PLUGIN' | 'UNSUPPORTED'
  | 'MISSING_PROVIDER' | 'AMBIGUOUS_MATCH' | 'CANCELLED' | 'PERMISSION_DENIED' | 'VERIFICATION_REQUIRED';
export type ContentType = 'track' | 'playlist' | 'album' | 'artist';
export interface ContentRef { providerId: string; type: ContentType; contentId: string }
export interface Track {
  trackId: string; title: string;
  artists: Array<string | { name: string; artistId?: string }>;
  album?: string | { title: string; albumId?: string };
  durationMs: number; artworkUrl?: string; isrc?: string; version?: string; explicit?: boolean;
}
export interface AudioResource {
  uri: string; quality: string; headers?: Record<string, string>; expiresAtMs?: number;
  contentLength?: number; contentId?: string; mimeType?: string; cachePolicy?: 'ALLOW' | 'NO_STORE';
}
export interface PageRequest { offset: number; limit: number; cursor?: string; refresh?: boolean }
export interface Page<T> { items: T[]; hasMore: boolean; nextCursor?: string | null }
export type PlaylistEditAction = 'create' | 'update' | 'delete' | 'addTracks' | 'removeTracks' | 'reorderTracks';
export interface Playlist {
  playlistId: string; title: string; artworkUrl?: string; creatorName?: string;
  trackCount?: number; description?: string; editableActions?: PlaylistEditAction[]; playCount?: number | null;
}
/** Complete ordered IDs (including repeats) and optional hydrated metadata, owned/cached by the host. */
export interface PlaylistSnapshot extends Playlist { trackIds?: string[]; tracks?: Track[] }
export interface Album {
  albumId: string; title: string; artworkUrl?: string;
  artists?: Array<string | { name: string; artistId?: string }>; trackCount?: number;
  description?: string; publishedAtMs?: number;
}
export interface Artist {
  artistId: string; name: string; artworkUrl?: string; description?: string; alternateNames?: string[];
  trackCount?: number; albumCount?: number; musicVideoCount?: number; identities?: string[];
  expertise?: Array<{ name: string; count?: number }>; translatedNames?: string[]; aliases?: string[];
}
export type SourceItem = { type: 'track'; track: Track } | { type: 'playlist'; playlist: Playlist }
  | { type: 'album'; album: Album } | { type: 'artist'; artist: Artist };
export type FeedLayout = 'hero' | 'playlists' | 'tracks' | 'albums' | 'artists' | 'grid';
export type FeedTarget =
  | { kind: 'content'; contentRef: ContentRef }
  | { kind: 'route'; route: string; parameters?: Record<string, string> }
  | { kind: 'radio'; seedIds?: string[]; similar?: boolean }
  | { kind: 'queue'; feature: string; action: string; parameters?: JsonObject };
export interface FeedCard {
  id: string; title: string; artworkUrl?: string; subtitle?: string; iconUrl?: string; target: FeedTarget;
  /** Existing square-card presentation; the host supplies all typography and layout. */
  coverCaptions?: string[]; detail?: string | null; titleMaxLines?: 1 | 2;
}
export interface FeedPage {
  sections: Array<{ id: string; title: string; layout: FeedLayout | Uppercase<FeedLayout>; items: SourceItem[]; cards?: FeedCard[] }>;
  categories?: Array<{ id: string; title: string }>; hasMore?: boolean; nextCursor?: string | null;
}
export interface DailyRecommendations {
  tracks: Track[]; title?: string; description?: string; creatorName?: string; updatedAtMs?: number;
  recommendations?: Array<{ trackId: string; reason?: string }>;
}
export interface LoginSuccess { authenticated: true; [profileField: string]: JsonValue }
export type SmsCodeResult = { status: 'sent'; authenticated?: false } | LoginSuccess;
export interface Account { accountId: string; displayName: string; avatarUrl?: string; sessionRevision?: string }
export interface CollectionRef { type: 'playlist' | 'album' | 'artist'; id: string }

export type NetworkSdk = 'netease.transport' | 'netease.urs';
export type EffectSdk = 'netease.securityHeaders' | 'netease.ursPuzzle' | 'netease.ursCaptcha' | 'netease.history';
export interface NetworkRequest {
  url: string; method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD';
  headers?: Record<string, string>; body?: string;
  /** body is UTF-8 text by default, or standard Base64 decoded before the raw-byte limit. */
  bodyEncoding?: 'utf8' | 'base64';
  /** Base64 leaves response.body empty and supplies response.bodyBase64. */
  responseEncoding?: 'utf8' | 'base64';
  /** Explicitly declared host codec; it does not authorize extra network domains. */
  sdk?: NetworkSdk;
  /** Pure NetEase wire encoding; the source supplies URL, headers, cookies and complete JSON body. */
  sdkConfig?: {
    mode?: 'api' | 'eapi' | 'weapi'; signingPath?: string; weapiKey?: string;
    /** JSON-encoded string map appended to the encoded form. */
    formFields?: string;
  };
  /** NETWORK/TIMEOUT and oversized transport bodies become status:0 responses. Cancellation and permission denial stop execution. */
  errorResponse?: boolean;
}
export interface HostRequest { request: NetworkRequest; state?: JsonValue }
export interface HttpHostResponse {
  status: number; headers: Record<string, string>; body: string; setCookies: string[];
  bodyBase64?: string; error?: never;
}
export interface FailedHostResponse {
  status: 0; body: ''; error: 'NETWORK' | 'TIMEOUT' | 'RESPONSE_TOO_LARGE'; headers?: never; setCookies?: never; bodyBase64?: never;
}
export type HostResponse = HttpHostResponse | FailedHostResponse;

export type PrivilegedEffectType = 'files.pick' | 'files.upload' | 'sdk.call' | 'web.auth' | 'web.page'
  | 'qr.scan' | 'audio.capture' | 'audio.fingerprint' | 'sms.compose';
export type HostEffectType = PrivilegedEffectType | 'source.continue';
export type HostEffect =
  | { type: 'source.continue'; parameters: Record<string, never> }
  | { type: 'files.pick'; parameters: { mimeTypes?: string[]; multiple?: false } }
  | { type: 'files.upload'; parameters: { handle: string; url: string; method: 'POST' | 'PUT'; headers?: Record<string, string>; offset: 0; length: number } }
  | { type: 'sdk.call'; parameters: { name: EffectSdk; arguments?: JsonObject } }
  | { type: 'web.auth' | 'web.page'; parameters: WebEffectParameters }
  | { type: 'qr.scan'; parameters: Record<string, never> }
  | { type: 'sms.compose'; parameters: { destination: string; body: string } }
  | { type: 'audio.capture' | 'audio.fingerprint'; parameters: JsonObject };
export interface HostEffectContinuation { hostEffect: HostEffect; continuation: JsonValue }
export type HostEffectOutcome = { ok: true; result: JsonValue } | { ok: false; code: ErrorCode };
export interface ContinuationCallback {
  state: JsonValue; effect: HostEffectOutcome;
  /** Host-issued, per-step token. The host also binds the provider, instance, account and screen. */
  token: string;
}
export interface FeatureParameters {
  [key: string]: JsonValue | ContinuationCallback | undefined;
  /** Host-reserved callback input. Never provide this on an initial call. */
  $continuation?: ContinuationCallback;
}
export interface FeatureInvocation { feature: string; action: string; parameters?: FeatureParameters }
export type BridgeVerb = 'device.info' | 'page.info' | 'page.title' | 'page.ui' | 'navigation.open'
  | 'navigation.close' | 'session.present' | 'session.profile' | 'session.login' | 'toast.show';
export interface WebEffectParameters {
  url: string; title?: string;
  /** Exact hosts or *. subtrees covered by manifest grants; may only narrow page navigation. */
  allowedHosts?: string[];
  /** Concrete hosts inside the page scope; never wildcard patterns. */
  cookieHosts?: string[];
  /** Explicit return whitelist, required for web.auth. Never returns the global WebView cookie store. */
  cookieNames?: string[];
  /** This source's own cookie headers, indexed by a concrete approved hostname, never a wildcard. */
  cookieHeaders?: Record<string, string>;
  bridgeAliases?: Record<string, BridgeVerb>;
  urlMappings?: Array<{ scheme: string; host: string; pathPrefix?: string; feature: string; action: string; parameters?: JsonObject }>;
}
export interface SelectedFile {
  handle: string; fileName: string; extension: string; size: number; md5: string;
  title?: string; artist?: string; album?: string;
  metadata?: { title?: string; artist?: string; album?: string };
}
export type WebEffectResult = { closed: true; cookies: Record<string, string>; cookie: string } | { loginRequested: true };

export type Result<T> = T | { result: T; storage?: Record<string, string> }
  | HostRequest | HostEffectContinuation | { result: HostEffectContinuation; storage?: Record<string, string> }
  | { error: ErrorCode };
export type LinkTarget = { contentRef: ContentRef } | { featureRoute: string; parameters?: JsonObject } | null;
export interface QualityPage { items: string[]; nextCursor?: string | null }
export interface PlaybackContinuation { nextCursor: string }
export type Operation = 'catalog.search' | 'catalog.track' | 'catalog.tracks' | 'playback.resolve' | 'playback.qualities' | 'lyrics.get'
  | 'feed.home' | 'feed.discover' | 'album.get' | 'album.tracks' | 'artist.get' | 'artist.tracks' | 'artist.albums'
  | 'playlist.get' | 'playlist.tracks' | 'account.get' | 'library.likedTracks' | 'library.createdPlaylists'
  | 'library.savedPlaylists' | 'library.savedAlbums' | 'library.followedArtists' | 'track.liked' | 'track.like'
  | 'collection.saved' | 'collection.save' | 'playlist.create' | 'playlist.update' | 'playlist.delete'
  | 'playlist.addTracks' | 'playlist.removeTracks' | 'playlist.reorderTracks' | 'feature.invoke' | 'links.resolve';
export interface Manifest {
  id: string; providerId?: string; name: string; author: string; version: string; apiVersion: 1 | 2;
  entry?: 'index.js'; capabilities: Operation[]; description?: string;
  /** Up to 32 lowercase exact hosts or leading *. subtrees; excludes apex/public suffixes.
   * Concrete HTTPS URLs, DNS and TLS checks still apply. Older hosts reject wildcard grants. */
  permissions?: { network?: string[]; storage?: boolean; nativeSdks?: Array<NetworkSdk | EffectSdk>; effects?: PrivilegedEffectType[] };
  config?: Array<{ key: string; label: string; secret?: boolean; required?: boolean }>;
  searchTypes?: ContentType[]; collectionTypes?: Array<CollectionRef['type']>;
  /** API 2. Both family and action must be declared for feature.invoke. */
  features?: Record<string, string[]>;
  /** Existing host routes only; never executable UI or arbitrary navigation destinations. */
  featureRoutes?: string[];
  /** Source storage keys whose identity changes invalidate bound screens and private resources. */
  sessionKeys?: string[];
  /** For a cookie-header key, only these cookie names define the account identity. */
  sessionCookieNames?: Record<string, string[]>;
  /** API 2 batch size, 1..1000 (default 30); requires catalog.track and catalog.tracks. */
  catalogBatchSize?: number;
  /** Obsolete private storage keys to remove; cannot overlap sessionKeys. */
  discardStorageKeys?: string[];
  /** Concrete hosts eligible for links.resolve, covered by network grants. */
  linkHosts?: string[];
}

/** Shared feature presenters add the provider namespace; plugins return only their own opaque IDs. */
export interface SessionState { authenticated: boolean; kind: 'mobile' | 'web' | string | null }
export interface AccountProfile {
  id: string; nickname: string; avatarUrl?: string | null; backgroundUrl?: string | null; signature?: string | null;
  follows?: number | null; followers?: number | null; eventCount?: number | null; playlistCount?: number | null;
  playlistSubscribedCount?: number | null; artistId?: string | null; hasVerifiedIdentity?: boolean;
}
export interface AccountDetail { profile: AccountProfile; level: number; listenSongs?: number | null; createDays?: number | null }
export interface AccountPlaylist { id: string; name: string; coverUrl?: string | null; trackCount: number; creatorId?: string | null; creatorName?: string | null }
export interface AccountPlaylistPage { playlists: AccountPlaylist[]; hasMore: boolean; nextOffset: number }
export interface AccountConnectionPage { users: AccountProfile[]; hasMore: boolean; nextOffset: number }
export interface AccountDevice { key: string; name: string; region: string; lastActiveTime: number; current: boolean; os: string }
export interface AccountAuthorization { clientId: string; name: string; description: string }
export interface AccountAuthorizationPage { records: AccountAuthorization[]; cursor?: string | null; more: boolean }
export interface AccountBinding { type: number; bound: boolean; displayName: string }
export interface AccountSecuritySummary {
  phone?: string | null; phoneBound: boolean; protectionEnabled?: boolean | null; deviceCount?: number | null;
  bindings?: AccountBinding[] | null; errors: string[];
}
export interface MusicianAccount { musician: boolean; dj: boolean; creator: boolean; live: boolean; userStatus: number; certificationStatus: number; remainingPublications?: number | null }
export interface ListeningStatsReport {
  period: 'Week' | 'Month'; startTimeMs: number; endTimeMs: number; totalMinutes: number; listenedDays: number;
  bins: Array<{ period: string; durationMinutes: number }>; isCurrent: boolean;
  sections?: Array<{ type: 'LateSleep' | 'EarlySleep' | 'DurationIncrease' | 'DurationDecrease' | 'LikedSongsPercent' | 'RecommendedSongsPercent'; value: number }>;
}
export interface ListeningStatsYear { year: number; totalSeconds: number; songCount?: number | null }
export interface AccountSong {
  id: string; name: string; artists: string[]; artistIds?: string[]; album?: string; albumId?: string;
  coverUrl?: string | null; durationMs: number; playedAt?: number | null;
}
export interface UserPlayRecord { song: AccountSong; playCount?: number | null; score?: number | null }
export interface CloudSong {
  id: string; name: string; artist: string; album: string; coverUrl?: string | null;
  durationMs: number; fileSize: number; bitrate: number; addTime: number;
}
export interface CloudPage { songs: CloudSong[]; count: number; usedSize: number; maxSize: number; hasMore: boolean; nextOffset: number }
export interface PodcastHost { id: string; nickname: string; avatarUrl?: string | null }
export interface Podcast {
  id: string; name: string; picUrl?: string | null; description?: string | null; recommendation?: string | null;
  categoryId?: string | null; category?: string | null; secondCategory?: string | null;
  programCount: number; subscriberCount: number; playCount: number; host?: PodcastHost | null; isSubscribed: boolean; feeType?: number | null;
}
export interface PodcastCategory { id: string; name: string; picUrl?: string | null }
export interface PodcastProgram {
  id: string; name: string; coverUrl?: string | null; description?: string | null; createTime?: number | null;
  durationMs: number; listenerCount: number; likedCount: number; commentCount: number; serialNumber?: number | null;
  radioId: string; radioName: string; host?: PodcastHost | null; mainSongId?: string | null;
}
export interface PodcastHome { categories: PodcastCategory[]; featured: Podcast[]; personalized: Podcast[] }
export interface PodcastPage { podcasts: Podcast[]; hasMore: boolean; totalCount: number; fetchedCount: number }
export interface PodcastProgramPage { programs: PodcastProgram[]; hasMore: boolean; totalCount: number; fetchedCount: number }
export interface PodcastDetail { podcast: Podcast; programs: PodcastProgram[]; hasMore: boolean; totalCount: number; nextOffset: number }
export type CommentContentType = 'track' | 'album' | 'playlist' | 'program';
export type CommentSort = 'recommended' | 'hot' | 'time';
export type CommentUser = { nickname: string; avatarUrl?: string | null } &
  ({ id: string; userId?: string } | { id?: string; userId: string });
export interface CommentRow {
  commentId: string; user: CommentUser; content: string; time: number; timeStr?: string;
  liked: boolean; likedCount: number; replyCount: number; showFloorComment?: { replyCount: number } | null;
}
export interface CommentPage { comments: CommentRow[]; hasMore: boolean; cursor?: string | null; totalCount: number }
export interface FloorCommentPage { comments: CommentRow[]; hasMore: boolean; time: number; totalCount: number }
export interface SongWiki {
  memories: Array<{ id: string; kind: 'FirstListen' | 'TotalPlay'; date?: string | null; playCount?: number | null; durationMinutes?: number | null; text?: string | null }>;
  tagGroups: Array<{ id: string; title?: string | null; values: string[] }>;
  attributes: Array<{ id: string; title?: string | null; value: string }>;
  associationGroups: Array<{ id: string; title?: string | null; countText?: string | null;
    details: Array<{ id: string; title?: string | null; subtitle?: string | null; body?: string | null }> }>;
  reviews: Array<{ id: string; attribution?: string | null; body: string }>;
  similarSongs: Array<{ id: string; title: string; artist?: string | null; note?: string | null; artworkUrl?: string | null }>;
  relatedPlaylists: Array<{ id: string; title: string; artworkUrl?: string | null; playCount: number }>;
  contributionUrl?: string | null;
}
export interface MessageContact { id: string; nickname: string; avatarUrl?: string | null; signature?: string | null; remarkName?: string | null; avatarBadgeUrl?: string | null }
export interface ShareResource { kind: 'Song' | 'Album' | 'Playlist'; id: string; title: string; subtitle?: string | null; artworkUrl?: string | null }
export interface PrivateConversation { id: string; fromUser?: MessageContact | null; toUser?: MessageContact | null; lastMessageTime: number; summary: string; unreadCount: number; isOfficialGroup?: boolean }
export interface PrivateMessage { id: string; fromUser?: MessageContact | null; toUser?: MessageContact | null; time: number; payload: { text: string; resource?: ShareResource | null } }
export interface TogetherRoom { id: string; creatorId: string; users: Array<{ id: string; nickname: string; avatarUrl?: string | null }>; createTime?: number | null; effectiveDurationMs?: number | null }
export interface TogetherPlayback { songIds: string[]; playMode?: string | null; command?: {
  commandType?: string | null; targetSongId?: string | null; formerSongId?: string | null;
  progressMs: number; isPlaying?: boolean | null; clientSequence: number; serverSequence: number;
} | null }
export interface RecognizedSong { id: string; name: string; artists: string[]; album: string; coverUrl?: string | null; durationMs: number; startTimeMs?: number | null }

/** Common result schemas consumed by the shared host UI; custom declared actions may return other JSON. */
export interface SharedFeatureResults {
  'discovery.dailySongs': DailyRecommendations;
  'login.requestMobileSmsCode': SmsCodeResult;
  'login.loginWithMobilePassword': LoginSuccess;
  'login.loginWithMobileSms': LoginSuccess;
  'login.loginWithUpSms': LoginSuccess;
  'login.webLogin': LoginSuccess;
  'login.importCookie': LoginSuccess;
  'login.sessionState': SessionState;
  'login.preparePcQrLoginSecurity': { ready: true };
  'login.markPcQrScanned': string;
  'login.authorizePcQrLogin': true;
  'login.cancelPcQrLogin': true;
  'login.logout': true;
  'account.accountProfile': AccountProfile;
  'account.accountDetail': AccountDetail;
  'account.accountPlaylists': AccountPlaylistPage;
  'account.accountConnections': AccountConnectionPage;
  'security.summary': AccountSecuritySummary;
  'security.musician': MusicianAccount;
  'security.devices': AccountDevice[];
  'security.authorizations': AccountAuthorizationPage;
  'stats.listeningStatsSummary': { totalSeconds: number };
  'stats.listeningStatsReport': ListeningStatsReport;
  'stats.listeningStatsYears': ListeningStatsYear[];
  'stats.userPlayRecords': UserPlayRecord[];
  'stats.recentSongs': AccountSong[];
  'cloud.cloudSongs': CloudPage;
  'podcasts.podcastHome': PodcastHome;
  'podcasts.podcasts': Podcast[];
  'podcasts.podcastDetail': PodcastDetail;
  'podcasts.podcastProgram': PodcastProgram;
  'podcasts.podcastPrograms': PodcastProgramPage;
  'podcasts.subscribedPodcasts': PodcastPage;
  'podcasts.search': PodcastPage;
  'social.songWiki': SongWiki;
  'social.comments': CommentPage | { data: CommentPage; code?: number };
  'social.floorComments': FloorCommentPage | { data: FloorCommentPage; code?: number };
  'messages.privateConversations': PrivateConversation[];
  'messages.officialConversations': { conversations: PrivateConversation[]; hasMore: boolean; nextTime: number };
  'messages.privateMessages': PrivateMessage[];
  'messages.messageContacts': { contacts: MessageContact[]; hasMore: boolean; nextOffset: number };
  'together.listenTogetherStatus': { isInRoom: boolean; room?: TogetherRoom | null; status?: string | null };
  'together.listenTogetherPlayback': TogetherPlayback;
  'recognition.match': RecognizedSong[];
}

export interface Plugin {
  /** API 1 returns bare tracks. API 2 returns tagged items matching params.type. */
  'catalog.search'?(params: PageRequest & { query: string; type?: ContentType }, context: Context): Result<Page<Track> | Page<SourceItem>>;
  'catalog.track'?(params: { trackId: string }, context: Context): Result<Track | null>;
  /** API 2. Return requested tracks in order, omit unavailable IDs; no script metadata cache. */
  'catalog.tracks'?(params: { trackIds: string[] }, context: Context): Result<Track[]>;
  'playback.resolve'?(params: { trackId: string; quality: string; cursor?: string }, context: Context): Result<AudioResource | PlaybackContinuation>;
  'playback.qualities'?(params: { trackId: string; cursor?: string }, context: Context): Result<string[] | QualityPage>;
  'lyrics.get'?(params: { trackId: string }, context: Context): Result<{ text: string; translation?: string } | null>;
  'feed.home'?(params: PageRequest & { categoryId?: string }, context: Context): Result<FeedPage>;
  'feed.discover'?(params: PageRequest & { categoryId?: string }, context: Context): Result<FeedPage>;
  'album.get'?(params: { albumId: string }, context: Context): Result<Album>;
  'album.tracks'?(params: PageRequest & { albumId: string }, context: Context): Result<Page<Track>>;
  'artist.get'?(params: { artistId: string }, context: Context): Result<Artist>;
  'artist.tracks'?(params: PageRequest & { artistId: string }, context: Context): Result<Page<Track>>;
  'artist.albums'?(params: PageRequest & { artistId: string }, context: Context): Result<Page<Album>>;
  /** metadataLimit is 1..5000; return the complete index even when metadata is limited. */
  'playlist.get'?(params: { playlistId: string; metadataLimit?: number }, context: Context): Result<PlaylistSnapshot>;
  'playlist.tracks'?(params: PageRequest & { playlistId: string }, context: Context): Result<Page<Track>>;
  'account.get'?(params: Record<string, never>, context: Context): Result<Account | null>;
  /** metadataOnly requests the liked playlist snapshot; older sources may return their usual page. */
  'library.likedTracks'?(params: PageRequest & { metadataOnly?: boolean; metadataLimit?: number }, context: Context): Result<Page<SourceItem> | PlaylistSnapshot>;
  'library.createdPlaylists'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'library.savedPlaylists'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'library.savedAlbums'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'library.followedArtists'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'track.liked'?(params: { trackId: string }, context: Context): Result<boolean>;
  'track.like'?(params: { trackId: string; liked: boolean }, context: Context): Result<true>;
  'collection.saved'?(params: CollectionRef, context: Context): Result<boolean>;
  'collection.save'?(params: CollectionRef & { saved: boolean }, context: Context): Result<true>;
  'playlist.create'?(params: { title: string; description?: string; privacy?: boolean; type?: 'NORMAL' | 'VIDEO' | 'SHARED' }, context: Context): Result<Playlist>;
  'playlist.update'?(params: { playlistId: string; title?: string; description?: string }, context: Context): Result<true>;
  'playlist.delete'?(params: { playlistId: string }, context: Context): Result<true>;
  'playlist.addTracks'?(params: { playlistId: string; trackIds: string[] }, context: Context): Result<true>;
  'playlist.removeTracks'?(params: { playlistId: string; trackIds: string[] }, context: Context): Result<true>;
  'playlist.reorderTracks'?(params: { playlistId: string; trackIds: string[] }, context: Context): Result<true>;
  'feature.invoke'?(params: FeatureInvocation, context: Context): Result<JsonValue>;
  'links.resolve'?(params: { url: string }, context: Context): Result<LinkTarget>;
  onResponse?(operation: Operation, response: HostResponse, state: JsonValue, context: Context): Result<JsonValue>;
}
/** Only index.js executes. Optional root LICENSE/NOTICE are bounded UTF-8 plain text. */
declare global { var sourcePlugin: Plugin; }
