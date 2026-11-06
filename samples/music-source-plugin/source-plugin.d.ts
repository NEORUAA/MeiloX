/** MeiloX source plugin API 1. JS runs in a fresh isolated process for each operation. */
export interface Context {
  readonly apiVersion: 1;
  readonly config: Readonly<Record<string, string>>;
  readonly storage: Readonly<Record<string, string>>;
}
export interface Track {
  trackId: string;
  title: string;
  artists: Array<string | { name: string }>;
  album?: string | { title: string };
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
  request: { url: string; method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD'; headers?: Record<string, string>; body?: string };
  state?: unknown;
}
export type ErrorCode = 'AUTH_REQUIRED' | 'AUTH_EXPIRED' | 'UNAVAILABLE' | 'NOT_FOUND' | 'NETWORK' | 'TIMEOUT' | 'INVALID_RESPONSE' | 'PLUGIN' | 'UNSUPPORTED';
export type Result<T> = T | { result: T; storage?: Record<string, string> } | HostRequest | { error: ErrorCode };
export interface Plugin {
  'catalog.search'?(params: { query: string; offset: number; limit: number }, context: Context): Result<{ items: Track[]; hasMore: boolean }>;
  'catalog.track'?(params: { trackId: string }, context: Context): Result<Track | null>;
  'playback.resolve'?(params: { trackId: string; quality: string }, context: Context): Result<AudioResource>;
  'lyrics.get'?(params: { trackId: string }, context: Context): Result<{ text: string; translation?: string } | null>;
  onResponse?(operation: string, response: { status: number; headers: Record<string, string>; body: string }, state: unknown, context: Context): Result<unknown>;
}
declare global { var sourcePlugin: Plugin; }
