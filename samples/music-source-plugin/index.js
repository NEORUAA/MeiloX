/* An offline SDK fixture. This does not connect to QQ Music or Kugou. */
(function () {
  'use strict';
  const track = Object.freeze({
    trackId: 'demo:offline/中文 1',
    title: 'Mock Recording',
    artists: ['MeiloX SDK'],
    album: 'Offline Fixtures',
    durationMs: 1000,
    explicit: false,
    version: 'studio'
  });
  globalThis.sourcePlugin = Object.freeze({
    'catalog.search': function (params) {
      const query = params.query.toLowerCase();
      const matches = query.includes('mock') || query.includes('meilox sdk');
      return { items: matches && params.offset === 0 ? [track] : [], hasMore: false };
    },
    'catalog.track': function (params) {
      return params.trackId === track.trackId ? track : null;
    },
    'playback.resolve': function (params) {
      if (params.trackId !== track.trackId) return { error: 'NOT_FOUND' };
      return {
        uri: 'asset://tone.wav',
        quality: 'standard',
        mimeType: 'audio/wav',
        contentId: 'mock-tone-pcm16-mono-16000-v1',
        cachePolicy: 'ALLOW'
      };
    },
    'lyrics.get': function (params) {
      return params.trackId === track.trackId ? { text: '[00:00.00]Offline SDK fixture' } : null;
    }
  });
})();
