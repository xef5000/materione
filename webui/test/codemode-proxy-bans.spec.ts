/**
 * Ban tests for the cloud model proxy client (`webui/src/lib/codemode/proxy/`):
 *
 *  - auth is strictly HttpOnly cookie via `credentials: 'include'`: zero browser storage or `document.cookie` usage;
 *  - nothing in `proxy/` can approve, apply, reject, or revert proposals;
 *  - no dynamic `.call(` shape that the release compatibility gate would read as an editor RPC op.
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PROXY = fileURLToPath(new URL('../src/lib/codemode/proxy', import.meta.url));

function sources(dir: string, prefix = ''): { name: string; text: string }[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? sources(join(dir, entry.name), `${prefix}${entry.name}/`)
      : /\.(ts|svelte)$/.test(entry.name)
        ? [{ name: `${prefix}${entry.name}`, text: readFileSync(join(dir, entry.name), 'utf8') }]
        : [],
  );
}

function code(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/`(?:\\[\s\S]|[^`\\])*`/g, '``')
    .replace(/'(?:\\.|[^'\\\n])*'/g, "''")
    .replace(/"(?:\\.|[^"\\\n])*"/g, '""');
}

const files = sources(PROXY);

describe('the cloud model proxy client stores no browser credentials and cannot approve or revert', () => {
  it('scans the proxy directory', () => {
    expect(files.map((entry) => entry.name).sort()).toEqual([
      'CloudAccountBar.svelte',
      'CloudSignInCard.svelte',
      'account.svelte.ts',
      'config.ts',
      'provider.ts',
    ]);
  });

  it.each(['localStorage', 'sessionStorage', 'indexedDB', 'cookie'])(
    'no file uses %s',
    (name) => {
      const pattern = new RegExp(`\\b${name}\\b`);
      expect(files.filter((entry) => pattern.test(code(entry.text))).map((entry) => entry.name)).toEqual([]);
    },
  );

  it('no file names ProposalStore, ReviewActions, ApprovalGesture, approve*, revert*, reject, or .call(', () => {
    const BANNED = /\bProposalStore\b|\bReviewActions\b|\bApprovalGesture\b|\.approve\w*|\.revert\w*|\.reject\b|\.call\(/;
    expect(files.filter((entry) => BANNED.test(code(entry.text))).map((entry) => entry.name)).toEqual([]);
  });
});
