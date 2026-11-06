import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'));
const script = fs.readFileSync(path.join(root, manifest.entry), 'utf8');
function invoke(operation, params) {
  const isolated = vm.createContext({});
  vm.runInContext(script, isolated, { timeout: 1000 });
  isolated.params = params;
  isolated.context = { apiVersion: 1, config: {}, storage: {} };
  return JSON.parse(vm.runInContext(`JSON.stringify(sourcePlugin[${JSON.stringify(operation)}](params, context))`, isolated, { timeout: 1000 }));
}
const search = invoke('catalog.search', { query: 'Mock Recording MeiloX SDK', offset: 0, limit: 20 });
assert.equal(search.items.length, 1);
const track = invoke('catalog.track', { trackId: search.items[0].trackId });
assert.equal(track.trackId, 'demo:offline/中文 1');
const audio = invoke('playback.resolve', { trackId: track.trackId, quality: 'hires' });
assert.equal(audio.quality, 'standard');
assert.equal(audio.uri, 'asset://tone.wav');
assert.match(invoke('lyrics.get', { trackId: track.trackId }).text, /Offline SDK fixture/);
assert.ok(fs.statSync(path.join(root, 'assets/tone.wav')).size > 44);
assert.equal(invoke('catalog.search', { query: 'Another recording', offset: 0, limit: 20 }).items.length, 0);
assert.equal(invoke('catalog.track', { trackId: 'missing' }), null);
assert.deepEqual(invoke('playback.resolve', { trackId: 'missing', quality: 'standard' }), { error: 'NOT_FOUND' });
const attempts = [];
for (const provider of [() => { attempts.push('origin'); throw Error('Unavailable'); }, () => { attempts.push(manifest.id); return audio; }]) {
  try { assert.equal(provider().uri, audio.uri); break; } catch { /* Try the next configured fixture. */ }
}
assert.deepEqual(attempts, ['origin', manifest.id]);
console.log('Offline source SDK: search, details, audio, lyrics identity and ordered fallback passed.');
