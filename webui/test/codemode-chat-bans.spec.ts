/**
 * Ban tests for the assistant (issue 348; D11, D13; codemode.md sections 4.5, 5.6, 12.1), kept by tests and not by review goodwill:
 *
 *  - nothing in `chat/` makes a network call or touches storage: the only provider is the scripted one, and there is no new endpoint;
 *  - nothing in `chat/` can approve, apply, reject or revert: no file names the proposal store, a gesture or the owner's actions, and
 *    the tool reaches the store only through the agent port's `propose`;
 *  - the provider interface cannot name a model or carry a credential;
 *  - the read-only op host is given the channel without a call shape the compatibility gate cannot read.
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const CHAT = fileURLToPath(new URL('../src/lib/codemode/chat', import.meta.url));

function sources(dir: string, prefix = ''): { name: string; text: string }[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? sources(join(dir, entry.name), `${prefix}${entry.name}/`)
      : /\.(ts|svelte)$/.test(entry.name)
        ? [{ name: `${prefix}${entry.name}`, text: readFileSync(join(dir, entry.name), 'utf8') }]
        : [],
  );
}

/** Code only: comments out, and the prose of string and template literals out (the instructions talk about approving). */
function code(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/`(?:\\[\s\S]|[^`\\])*`/g, '``')
    .replace(/'(?:\\.|[^'\\\n])*'/g, "''")
    .replace(/"(?:\\.|[^"\\\n])*"/g, '""');
}

const files = sources(CHAT);
const file = (name: string): string => files.find((entry) => entry.name === name)?.text ?? '';
const imports = (text: string): string[] => [...text.matchAll(/from\s+'([^']+)'/g)].map((match) => match[1]!);

describe('the assistant makes no network call and keeps nothing', () => {
  it('scans the chat directory', () => {
    expect(files.map((entry) => entry.name).sort()).toEqual(
      ['ChatPanel.svelte', 'context.ts', 'disclosure.ts', 'loop.ts', 'provider.ts', 'scripted.ts', 'scripts/bloodfang.ts', 'state.svelte.ts', 'tool.ts'].sort(),
    );
  });

  it.each(['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'sendBeacon', 'RTCPeerConnection', 'importScripts', 'localStorage', 'sessionStorage', 'indexedDB', 'cookie'])(
    'no file uses %s',
    (name) => {
      const pattern = new RegExp(`\\b${name}\\b`);
      expect(files.filter((entry) => pattern.test(code(entry.text))).map((entry) => entry.name)).toEqual([]);
    },
  );

  it('no file holds a URL, so there is no endpoint to call', () => {
    expect(files.filter((entry) => /https?:\/\/|wss?:\/\//.test(entry.text)).map((entry) => entry.name)).toEqual([]);
  });

  it("imports only the editor's own modules", () => {
    for (const entry of files) {
      expect(imports(entry.text).filter((specifier) => !specifier.startsWith('.') && specifier !== 'svelte'), entry.name).toEqual([]);
    }
  });
});

describe('the assistant cannot approve, apply or revert', () => {
  const BANNED = /\bgestureFromEvent\b|\bReviewActions\b|\breviewActions\b|\bcreateProposalStore\b|\bProposalStore\b|\bGrantSet\b|\bApprovalGesture\b|\.approve\w*\(|\.revert\w*\(|\.reject\(|\.applyReady\(|\.previewRevert\(|\.confirmRevert\(/;

  it('no file of the chat names the store, a gesture, a grant or an owner action', () => {
    expect(files.filter((entry) => BANNED.test(code(entry.text))).map((entry) => entry.name)).toEqual([]);
  });

  it('the loop, the provider, the context and the script import nothing of the host, the review session or the channel', () => {
    for (const name of ['loop.ts', 'provider.ts', 'scripted.ts', 'context.ts', 'disclosure.ts', 'scripts/bloodfang.ts']) {
      expect(imports(file(name)).filter((specifier) => /host|review|api|state/.test(specifier)), name).toEqual([]);
    }
  });

  it('the tool takes the host as a type only, and of the agent port only `propose`', () => {
    const lines = file('tool.ts').split('\n').filter((line) => line.includes("from '../host'"));
    expect(lines).toHaveLength(1);
    expect(lines[0]!.startsWith('import type ')).toBe(true);
    expect(file('tool.ts')).toContain("agent: Pick<AgentPort, 'propose'>");
  });

  it('the session wires the tool to the agent port and to nothing else of the review session', () => {
    const state = file('state.svelte.ts');
    const fromReview = /import \{([^}]*)\} from '\.\.\/review\/state\.svelte'/.exec(state)?.[1]?.split(',').map((name) => name.trim()).sort();
    expect(fromReview).toEqual(['reviewAgentPort', 'reviewAvailable']);
    expect(imports(state).filter((specifier) => specifier.includes('/host'))).toEqual([]);
  });

  it('the panel reuses the review components and does not fork them', () => {
    const panel = file('ChatPanel.svelte');
    for (const component of ['ProposalCard', 'LogEntry', 'ProgramView']) expect(panel).toContain(`import ${component} from '../review/${component}.svelte'`);
    expect(panel).not.toMatch(/onclick=\{[^}]*(approve|revert|reject)/i);
  });
});

describe('the provider interface is neutral', () => {
  const source = ts.createSourceFile('provider.ts', file('provider.ts'), ts.ScriptTarget.ES2022, true);
  const membersOf = (name: string): string[] => {
    const found: string[] = [];
    source.forEachChild((node) => {
      if (ts.isInterfaceDeclaration(node) && node.name.text === name) for (const member of node.members) if (member.name !== undefined) found.push(member.name.getText(source));
    });
    return found;
  };

  it('a request is the system text, the messages, the tools and an abort signal: no model, no key, no URL', () => {
    expect(membersOf('ModelRequest')).toEqual(['system', 'messages', 'tools', 'signal']);
    expect(membersOf('ModelProvider')).toEqual(['info', 'stream']);
    expect(membersOf('ProviderInfo')).toEqual(['id', 'label', 'remote']);
    expect(code(file('provider.ts'))).not.toMatch(/\b(apiKey|api_key|token|secret|model(Name|Id)?)\s*[?:]/);
  });

  it('a refusal and usage are part of the stream', () => {
    expect(membersOf('ModelRefusal')).toEqual(['code', 'message', 'retryAfterSeconds']);
    expect(membersOf('ModelUsage')).toEqual(['inputTokens', 'outputTokens', 'cachedInputTokens', 'credits']);
    expect(file('provider.ts')).toContain("'allowance_exhausted'");
  });
});

describe('the compatibility gate can still read every op', () => {
  it('no chat file has a call shape the gate would read as an op', () => {
    for (const entry of files) expect([...entry.text.matchAll(/\.call\(/g)], entry.name).toEqual([]);
  });
});
