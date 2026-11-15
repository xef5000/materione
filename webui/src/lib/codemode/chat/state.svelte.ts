/**
 * The assistant panel's session (issue 348): the conversation, the provider in use, and the wiring of the agent loop to the
 * editor: the type-check Worker, the sandbox, the read-only op host over the session's channel, and the AGENT PORT of the
 * review session's proposal store.
 *
 * Session memory only (D1): the conversation is not content and is not persisted anywhere, in the page or outside it; a
 * disconnect or a new channel ends it. Nothing here approves, applies or reverts: this module never sees the proposal store,
 * only `reviewAgentPort()`, which has propose, declarePlan and reads. The owner's buttons are the review components', inline
 * in the panel, and they act through `ReviewActions` with the click that caused them.
 *
 * No provider is connected by default in this build: the model proxy is a later issue. `installChatProvider` is the seam
 * for tests, the `?codemode` browser fixture, and the future cloud proxy provider.
 */
import type { RpcChannel } from '../../api';
import type { ReadReply } from '../core/types';
import type { SurfaceDocument } from '../generate/surface';
import { reviewAgentPort, reviewAvailable } from '../review/state.svelte';
import { createOpHost, run as runInSandbox } from '../sandbox';
import { fetchSurface } from '../surface-source';
import { check } from '../typecheck';
import { agentContext } from './context';
import { runAgent, stopText } from './loop';
import { addUsage, refusalText, type ChatMessage, type ModelProvider, type ModelUsage, type ProviderInfo } from './provider';
import { runProgram, type ToolOutcome } from './tool';

export type ChatItem =
  | { id: number; kind: 'user'; text: string }
  | { id: number; kind: 'assistant'; text: string; streaming: boolean }
  | { id: number; kind: 'tool'; program: string | null; running: boolean; outcome: ToolOutcome | null }
  | { id: number; kind: 'notice'; tone: 'info' | 'warn' | 'error'; text: string };

class ChatState {
  /** The connected server advertises code mode: the assistant can exist. False: the panel says why it does not. */
  capable = $state(false);
  open = $state(false);
  /** Set by the rail button and spent by the panel: only then does opening move focus into it. */
  focusOnOpen = $state(false);
  tab = $state<'conversation' | 'log'>('conversation');
  items = $state<ChatItem[]>([]);
  busy = $state(false);
  /** The provider in use, or null: no model is connected and no demonstration was started. */
  provider = $state.raw<ProviderInfo | null>(null);
  /** With a recording: what the user says next, in order (a recording answers only what was recorded). */
  prompts = $state.raw<readonly string[]>([]);
  /** How many user messages this conversation has. */
  sent = $state(0);
  usage = $state.raw<ModelUsage>({});
  /** A polite status line for assistive technology. */
  announcement = $state('');
}

export const chat = new ChatState();

interface Handle {
  channel: RpcChannel;
  provider: ModelProvider | null;
  history: ChatMessage[];
  abort: AbortController | null;
}

let handle: Handle | null = null;
let nextItem = 1;

function clearConversation(): void {
  handle?.abort?.abort();
  if (handle !== null) {
    handle.history = [];
    handle.abort = null;
  }
  chat.items = [];
  chat.busy = false;
  chat.sent = 0;
  chat.usage = {};
  chat.announcement = '';
}

/** Starts (or keeps) the assistant session of this channel. A different channel is a different session: the conversation ends. */
export function connectChat(channel: RpcChannel | null): void {
  if (channel === null) {
    disposeChat();
    return;
  }
  chat.capable = reviewAvailable(channel);
  if (handle?.channel === channel) return;
  clearConversation();
  handle = { channel, provider: null, history: [], abort: null };
  chat.provider = null;
  chat.prompts = [];
}

export function disposeChat(): void {
  clearConversation();
  handle = null;
  chat.capable = false;
  chat.open = false;
  chat.focusOnOpen = false;
  chat.provider = null;
  chat.prompts = [];
}

/**
 * Connects a provider and starts a new conversation. The seam the model proxy's provider will use, and the browser harness.
 * `prompts` are a recording's user messages.
 */
export function installChatProvider(provider: ModelProvider | null, prompts: readonly string[] = []): void {
  if (handle === null) return;
  clearConversation();
  handle.provider = provider;
  chat.provider = provider?.info ?? null;
  chat.prompts = prompts;
}

/** A new conversation with the same provider. What was applied stays applied (it is in the change log). */
export function newConversation(): void {
  clearConversation();
}

export function stopChat(): void {
  handle?.abort?.abort();
}

type NewItem = ChatItem extends infer T ? (T extends { id: number } ? Omit<T, 'id'> : never) : never;

function add(item: NewItem): number {
  const id = nextItem++;
  chat.items.push({ ...item, id } as ChatItem);
  return id;
}

function patch(id: number, change: (item: ChatItem) => void): void {
  const found = chat.items.find((item) => item.id === id);
  if (found !== undefined) change(found);
}

/** One read-only editor op for the sandbox's host. The op is one of the SDK's operation table: `createOpHost` allows no other. */
function reader(channel: RpcChannel): (op: string, payload: Record<string, unknown>) => Promise<ReadReply> {
  // Bound once: the ops this sends are named, literally, in the SDK's operation table (core/ops.ts), which the release compatibility gate reads.
  const send = channel.call.bind(channel);
  return async (op, payload) => {
    try {
      const reply = await send(op, payload);
      return reply.ok ? { ok: true, data: reply.data } : { ok: false, error: { code: reply.error.code, message: reply.error.message } };
    } catch (error) {
      return { ok: false, error: { message: error instanceof Error ? error.message : String(error) } };
    }
  };
}

const ANNOUNCE: Partial<Record<ToolOutcome['status'], string>> = {
  applied: 'A program was applied. It is in the change log.',
  waiting: 'A change waits for your approval.',
  'type-error': 'A program did not type-check.',
  'run-error': 'A program failed in the sandbox.',
  refused: 'The server refused a program in its dry run.',
  stale: 'A program was stale and must be run again.',
  failed: 'A program could not be sent to the server.',
};

/** Sends the user's message and runs the agent to the end of the request. Resolves when the request ended; never throws. */
export async function sendMessage(text: string): Promise<void> {
  const current = handle;
  const said = text.trim();
  if (current === null || current.provider === null || !chat.capable || chat.busy || said === '') return;
  const agent = reviewAgentPort();
  if (agent === null) {
    add({ kind: 'notice', tone: 'error', text: 'The review session is not connected, so nothing can be proposed. Reconnect the editor.' });
    return;
  }
  chat.busy = true;
  chat.sent++;
  add({ kind: 'user', text: said });
  current.history.push({ role: 'user', text: said });
  const abort = new AbortController();
  current.abort = abort;

  let surface: SurfaceDocument;
  try {
    // read per request: a reload that changed a registry gives the next request the new surface (codemode.md section 7)
    surface = await fetchSurface(current.channel);
  } catch (error) {
    if (handle === current) {
      add({ kind: 'notice', tone: 'error', text: `The server's description could not be read, so no program can be checked: ${error instanceof Error ? error.message : String(error)}` });
      chat.busy = false;
      current.abort = null;
    }
    return;
  }

  const host = createOpHost(reader(current.channel));
  let speaking: number | null = null;
  const running = new Map<string, number>();
  const result = await runAgent(current.history, {
    provider: current.provider,
    system: agentContext(surface),
    signal: abort.signal,
    tool: (input) => runProgram(input, { surface, check, run: (program, document) => runInSandbox(program, document, host), agent }),
    onEvent(event) {
      if (handle !== current) return;
      if (event.type === 'turn-start') {
        speaking = null;
      } else if (event.type === 'text') {
        if (speaking === null) speaking = add({ kind: 'assistant', text: '', streaming: true });
        patch(speaking, (item) => {
          if (item.kind === 'assistant') item.text += event.delta;
        });
      } else if (event.type === 'tool-start') {
        if (speaking !== null) patch(speaking, (item) => item.kind === 'assistant' && (item.streaming = false));
        speaking = null;
        running.set(event.call.id, add({ kind: 'tool', program: event.program, running: true, outcome: null }));
      } else if (event.type === 'tool-end') {
        const id = running.get(event.call.id);
        if (id !== undefined) {
          patch(id, (item) => {
            if (item.kind === 'tool') {
              item.running = false;
              item.outcome = event.outcome;
            }
          });
        }
        chat.announcement = ANNOUNCE[event.outcome.status] ?? '';
      } else {
        chat.usage = addUsage(chat.usage, event.usage);
      }
    },
  });
  if (handle !== current || current.abort !== abort) return;
  for (const item of chat.items) if (item.kind === 'assistant') item.streaming = false;
  current.history.push(...result.added);
  if (result.stop === 'refused' && result.refusal !== undefined) {
    add({ kind: 'notice', tone: result.refusal.code === 'script_ended' ? 'info' : 'warn', text: refusalText(result.refusal) });
  } else {
    const why = stopText(result);
    if (why !== '') add({ kind: 'notice', tone: result.stop === 'aborted' ? 'info' : 'warn', text: why });
  }
  chat.busy = false;
  current.abort = null;
}
