/** MeiloX source plugin APIs 1 and 2. Each operation runs in a fresh isolated process. */
export interface Context {
  readonly apiVersion: 1 | 2;
  readonly config: Readonly<Record<string, string>>;
  readonly storage: Readonly<Record<string, string>>;
  /** API 2: fresh standard Base64 of 256 CSPRNG bytes for every operation and onResponse evaluation.
   * Consume bytes once for nonces/IVs, never persist the pool, and return a typed error if unavailable or exhausted. */
  readonly secureRandomBase64: string;
}
export interface Track {
  trackId: string;
  title: string;
  artists: Array<string | { name: string; artistId?: string }>;
  album?: string | { title: string; albumId?: string };
  durationMs: number;
  artworkUrl?: string;
  isrc?: string;
  version?: string;
  explicit?: boolean;
}
export interface AudioResource {
  uri: string;
  quality: string;
  headers?: Record<string, string>;
  expiresAtMs?: number;
  contentLength?: number;
  contentId?: string;
  mimeType?: string;
  cachePolicy?: 'ALLOW' | 'NO_STORE';
}
export interface HostRequest {
  request: { url: string; method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD'; headers?: Record<string, string>; body?: string;
    /** Defaults to utf8. Base64 preserves bounded raw response bytes and leaves response.body empty. */
    responseEncoding?: 'utf8' | 'base64' };
  state?: unknown;
}
export interface HostResponse {
  status: number; headers: Record<string, string>; body: string;
  /** Present only when request.responseEncoding is base64; contains the original HTTP response bytes. */
  bodyBase64?: string;
}
export type ErrorCode = 'AUTH_REQUIRED' | 'AUTH_EXPIRED' | 'UNAVAILABLE' | 'NOT_FOUND' | 'NETWORK' | 'TIMEOUT' | 'INVALID_RESPONSE' | 'PLUGIN' | 'UNSUPPORTED';
export type Result<T> = T | { result: T; storage?: Record<string, string> } | HostRequest | { error: ErrorCode };
export type ContentType = 'track' | 'playlist' | 'album' | 'artist';
export interface PageRequest { offset: number; limit: number; cursor?: string }
export interface Page<T> { items: T[]; hasMore: boolean; nextCursor?: string }
export type PlaylistEditAction = 'create' | 'update' | 'delete' | 'addTracks' | 'removeTracks' | 'reorderTracks';
export interface Playlist {
  playlistId: string; title: string; artworkUrl?: string; creatorName?: string;
  trackCount?: number; description?: string; editableActions?: PlaylistEditAction[];
}
export interface Album {
  albumId: string; title: string; artworkUrl?: string;
  artists?: Array<string | { name: string; artistId?: string }>; trackCount?: number; description?: string;
}
export interface Artist { artistId: string; name: string; artworkUrl?: string; description?: string }
export type SourceItem = { type: 'track'; track: Track } | { type: 'playlist'; playlist: Playlist }
  | { type: 'album'; album: Album } | { type: 'artist'; artist: Artist };
export interface FeedPage {
  sections: Array<{ id: string; title: string; layout: 'hero' | 'playlists' | 'tracks' | 'albums' | 'artists' | 'grid'; items: SourceItem[] }>;
  categories?: Array<{ id: string; title: string }>; hasMore?: boolean; nextCursor?: string;
}
export interface Account { accountId: string; displayName: string; avatarUrl?: string; sessionRevision?: string }
export interface CollectionRef { type: 'playlist' | 'album' | 'artist'; id: string }
export type Operation = 'catalog.search' | 'catalog.track' | 'playback.resolve' | 'lyrics.get'
  | 'feed.home' | 'feed.discover' | 'album.get' | 'album.tracks' | 'artist.get' | 'artist.tracks' | 'artist.albums'
  | 'playlist.get' | 'playlist.tracks' | 'account.get' | 'library.likedTracks' | 'library.createdPlaylists'
  | 'library.savedPlaylists' | 'library.savedAlbums' | 'library.followedArtists' | 'track.liked' | 'track.like'
  | 'collection.saved' | 'collection.save' | 'playlist.create' | 'playlist.update' | 'playlist.delete'
  | 'playlist.addTracks' | 'playlist.removeTracks' | 'playlist.reorderTracks';
export interface Manifest {
  id: string; providerId?: string; name: string; author: string; version: string; apiVersion: 1 | 2;
  entry?: 'index.js'; capabilities: Operation[]; description?: string;
  permissions?: { network?: string[]; storage?: boolean };
  config?: Array<{ key: string; label: string; secret?: boolean; required?: boolean }>;
  searchTypes?: ContentType[]; collectionTypes?: Array<CollectionRef['type']>;
}
export interface Plugin {
  /** API 1 returns bare tracks; API 2 requires tagged items matching params.type. */
  'catalog.search'?(params: PageRequest & { query: string; type?: ContentType }, context: Context): Result<Page<Track> | Page<SourceItem>>;
  'catalog.track'?(params: { trackId: string }, context: Context): Result<Track | null>;
  'playback.resolve'?(params: { trackId: string; quality: string }, context: Context): Result<AudioResource>;
  'lyrics.get'?(params: { trackId: string }, context: Context): Result<{ text: string; translation?: string } | null>;
  'feed.home'?(params: PageRequest & { categoryId?: string }, context: Context): Result<FeedPage>;
  'feed.discover'?(params: PageRequest & { categoryId?: string }, context: Context): Result<FeedPage>;
  'album.get'?(params: { albumId: string }, context: Context): Result<Album>;
  'album.tracks'?(params: PageRequest & { albumId: string }, context: Context): Result<Page<Track>>;
  'artist.get'?(params: { artistId: string }, context: Context): Result<Artist>;
  'artist.tracks'?(params: PageRequest & { artistId: string }, context: Context): Result<Page<Track>>;
  'artist.albums'?(params: PageRequest & { artistId: string }, context: Context): Result<Page<Album>>;
  'playlist.get'?(params: { playlistId: string }, context: Context): Result<Playlist>;
  'playlist.tracks'?(params: PageRequest & { playlistId: string }, context: Context): Result<Page<Track>>;
  'account.get'?(params: Record<string, never>, context: Context): Result<Account | null>;
  'library.likedTracks'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'library.createdPlaylists'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'library.savedPlaylists'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'library.savedAlbums'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'library.followedArtists'?(params: PageRequest, context: Context): Result<Page<SourceItem>>;
  'track.liked'?(params: { trackId: string }, context: Context): Result<boolean>;
  'track.like'?(params: { trackId: string; liked: boolean }, context: Context): Result<true>;
  'collection.saved'?(params: CollectionRef, context: Context): Result<boolean>;
  'collection.save'?(params: CollectionRef & { saved: boolean }, context: Context): Result<true>;
  'playlist.create'?(params: { title: string; description?: string }, context: Context): Result<Playlist>;
  'playlist.update'?(params: { playlistId: string; title?: string; description?: string }, context: Context): Result<true>;
  'playlist.delete'?(params: { playlistId: string }, context: Context): Result<true>;
  'playlist.addTracks'?(params: { playlistId: string; trackIds: string[] }, context: Context): Result<true>;
  'playlist.removeTracks'?(params: { playlistId: string; trackIds: string[] }, context: Context): Result<true>;
  'playlist.reorderTracks'?(params: { playlistId: string; trackIds: string[] }, context: Context): Result<true>;
  onResponse?(operation: string, response: HostResponse, state: unknown, context: Context): Result<unknown>;
}
/** Packages may include root LICENSE and NOTICE files, each at most 64 KiB of UTF-8 plain text.
 * Only index.js is evaluated; all existing script, asset and package size limits remain in effect. */
declare global { var sourcePlugin: Plugin; }
