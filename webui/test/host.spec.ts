import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { HOST_HEADER, HOST_MARKER_PATH, detectHost, startIntent } from '../src/lib/host';

function respond(headers: Record<string, string>): typeof fetch {
  return async () => new Response('{}', { status: 200, headers });
}

describe('editor host detection (web.md §6, cloud landing)', () => {
  it('is cloud only when the marker header says so', async () => {
    expect(await detectHost(respond({ [HOST_HEADER]: 'cloud' }))).toBe('cloud');
    expect(await detectHost(respond({ [HOST_HEADER]: ' Cloud ' }))).toBe('cloud');
    expect(await detectHost(respond({}))).toBe('plugin');
    expect(await detectHost(respond({ [HOST_HEADER]: 'plugin' }))).toBe('plugin');
  });

  it('falls back to the plugin behaviour when the probe fails', async () => {
    expect(await detectHost(async () => { throw new TypeError('offline'); })).toBe('plugin');
  });

  it('ships the marker file and attaches the header only through Cloudflare _headers', () => {
    expect(JSON.parse(readFileSync('public/host.json', 'utf8'))).toEqual({});
    const headers = readFileSync('public/_headers', 'utf8');
    expect(headers.replaceAll('\r', '')).toContain(`\n${HOST_MARKER_PATH}\n  ${HOST_HEADER}: cloud\n`);
  });
});

describe('start intent', () => {
  it('prefers a capability URL over everything else', () => {
    expect(startIntent('?s=abc&demo', '#key')).toEqual({ kind: 'session', code: 'abc', key: 'key' });
  });

  it('reads the demo flag', () => {
    expect(startIntent('?demo', '')).toEqual({ kind: 'demo' });
    expect(startIntent('?s=abc&demo', '')).toEqual({ kind: 'demo' });
  });

  it('is idle without a code and key', () => {
    expect(startIntent('', '')).toEqual({ kind: 'idle' });
    expect(startIntent('?s=abc', '#')).toEqual({ kind: 'idle' });
  });
});
