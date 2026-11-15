<script lang="ts">
  /**
   * The assistant panel (issue 348, codemode.md D13): the conversation with the agent, and inline the review components of
   * the proposal store: the proposal each program made, as the owner reviews it, and the session's change log. A side panel
   * of the workbench. It adds no way to approve, apply or revert: those are the review components' own buttons, which act on
   * the click that pressed them. The agent's only tool runs a program.
   *
   * Before the first message it states what leaves the page, written from the provider in use (disclosure.ts). A server that
   * does not advertise code mode has no assistant, and the panel says so.
   */
  import { onMount, tick } from 'svelte';
  import type { EditorHost } from '../../host';
  import Button from '../../ui/Button.svelte';
  import Chip from '../../ui/Chip.svelte';
  import Icon from '../../components/Icon.svelte';
  import LogEntry from '../review/LogEntry.svelte';
  import ProgramView from '../review/ProgramView.svelte';
  import ProposalCard from '../review/ProposalCard.svelte';
  import { review } from '../review/state.svelte';
  import { disclosure } from './disclosure';
  import { REPAIR_BUDGET } from './loop';
  import { chat, newConversation, sendMessage, stopChat } from './state.svelte';
  import type { ToolStatus } from './tool';

  interface Props {
    host?: EditorHost;
    /** Closes the panel; the caller returns focus to the control that opened it. */
    onClose: () => void;
    /** Opens the Review panel (the full list of proposals and the server's audit). */
    onOpenReview: () => void;
  }

  let { host = 'plugin', onClose, onOpenReview }: Props = $props();

  let heading = $state<HTMLElement | null>(null);
  let scroller = $state<HTMLElement | null>(null);
  let draft = $state('');

  const stated = $derived(chat.provider === null ? null : disclosure(chat.provider));
  const recorded = $derived(chat.prompts.length > 0);
  const nextPrompt = $derived(recorded ? (chat.prompts[chat.sent] ?? null) : null);
  const log = $derived([...review.snapshot.log].reverse());
  const proposalOf = (id: string | null) => (id === null ? undefined : review.snapshot.proposals.find((proposal) => proposal.id === id));
  const planOf = (id: string | null) => review.snapshot.plans.find((plan) => plan.id === id) ?? null;
  const usageLine = $derived.by(() => {
    const usage = chat.usage;
    const parts: string[] = [];
    const count = (value: number): string => value.toLocaleString('en-US');
    if (usage.inputTokens !== undefined) parts.push(`${count(usage.inputTokens)} input tokens${usage.cachedInputTokens !== undefined ? ` (${count(usage.cachedInputTokens)} from cache)` : ''}`);
    if (usage.outputTokens !== undefined) parts.push(`${count(usage.outputTokens)} output tokens`);
    if (usage.credits !== undefined) parts.push(`${count(usage.credits)} credits`);
    return parts.join(', ');
  });

  /** Every state in words, never by colour alone. */
  const STATUS: Record<ToolStatus, { tone: 'neutral' | 'ok' | 'warn' | 'error'; chip: string; words: string }> = {
    invalid: { tone: 'error', chip: 'Not run', words: 'The assistant called its tool wrongly. Nothing ran.' },
    'type-error': { tone: 'error', chip: 'Type error', words: 'The program did not type-check, so it did not run.' },
    'run-error': { tone: 'error', chip: 'Run failed', words: 'The program failed in the sandbox. Nothing was staged.' },
    read: { tone: 'neutral', chip: 'Read only', words: 'The program only read. Nothing was changed.' },
    refused: { tone: 'error', chip: 'Refused', words: 'The server refused the change in its dry run. Nothing was written.' },
    stale: { tone: 'warn', chip: 'Stale', words: 'A document changed after the program read it. Nothing was written.' },
    failed: { tone: 'error', chip: 'Failed', words: 'The server could not be reached or its reply could not be read.' },
    waiting: { tone: 'ok', chip: 'Dry run clean', words: 'The server accepted the change in its dry run. It changes existing content, so it is yours to approve below.' },
    applied: { tone: 'ok', chip: 'Dry run clean', words: 'The server accepted the change in its dry run. It only creates new content.' },
  };

  onMount(() => {
    if (chat.focusOnOpen) {
      chat.focusOnOpen = false;
      void tick().then(() => heading?.focus());
    }
  });

  // Keep the end of the conversation in view as it grows.
  $effect(() => {
    const last = chat.items[chat.items.length - 1];
    void chat.items.length;
    void (last?.kind === 'assistant' ? last.text.length : last?.kind === 'tool' ? last.running : 0);
    void chat.tab;
    const box = scroller;
    if (box !== null) void tick().then(() => (box.scrollTop = box.scrollHeight));
  });

  function onKey(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    const active = document.activeElement;
    const inside = active !== null && document.getElementById('assistant-panel')?.contains(active) === true;
    if (inside || active === null || active === document.body) {
      event.preventDefault();
      onClose();
    }
  }

  function onTabKey(event: KeyboardEvent): void {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight' && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    chat.tab = event.key === 'ArrowLeft' || event.key === 'Home' ? 'conversation' : 'log';
    void tick().then(() => document.getElementById(`assistant-tab-${chat.tab}`)?.focus());
  }

  function send(): void {
    const text = draft;
    if (text.trim() === '' || chat.busy) return;
    draft = '';
    void sendMessage(text);
  }

  function onComposerKey(event: KeyboardEvent): void {
    // Enter sends; Shift+Enter is a new line. A composing input method keeps its Enter.
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      send();
    }
  }

  function useRecorded(): void {
    if (nextPrompt === null) return;
    draft = nextPrompt;
    void tick().then(() => document.getElementById('assistant-input')?.focus());
  }
</script>

<svelte:window onkeydown={onKey} />

<section id="assistant-panel" class="panel" aria-labelledby="assistant-title">
  <div class="head">
    <h2 id="assistant-title" tabindex="-1" bind:this={heading}>Assistant</h2>
    {#if chat.capable}
      <Chip tone="neutral">{chat.provider !== null ? (recorded ? 'Recorded demonstration' : chat.provider.label) : host !== 'cloud' ? 'editor.materione.com only' : 'No model connected'}</Chip>
    {/if}
    <span class="spacer"></span>
    {#if chat.items.length > 0}
      <Button size="sm" variant="ghost" disabled={chat.busy} onclick={newConversation}>New conversation</Button>
    {/if}
    <button type="button" class="close" aria-label="Close Assistant" title="Close Assistant" onclick={onClose}><Icon name="x" size={14} /></button>
  </div>

  <p class="sr" role="status" aria-live="polite">{chat.announcement}</p>

  {#if !chat.capable}
    <div class="content">
      <div class="box" role="note" aria-label="Assistant not available">
        <p class="lead">The assistant is not available on this server.</p>
        <p>
          It works by code mode: it writes a program, and the server checks and applies the program's changes as one transaction. This server's
          Materione does not support that (it does not advertise the <code>codemode</code> capability), so there is no assistant here. It does not fall
          back to editing files directly.
        </p>
        <p>Update the Materione plugin on the server to use the assistant. Everything else in the editor works as before.</p>
      </div>
    </div>
  {:else}
    <div class="tabs" role="tablist" aria-label="Assistant sections" tabindex="-1" onkeydown={onTabKey}>
      <button
        type="button"
        role="tab"
        id="assistant-tab-conversation"
        aria-selected={chat.tab === 'conversation'}
        aria-controls="assistant-tabpanel"
        tabindex={chat.tab === 'conversation' ? 0 : -1}
        onclick={() => (chat.tab = 'conversation')}
      >Conversation</button>
      <button
        type="button"
        role="tab"
        id="assistant-tab-log"
        aria-selected={chat.tab === 'log'}
        aria-controls="assistant-tabpanel"
        tabindex={chat.tab === 'log' ? 0 : -1}
        onclick={() => (chat.tab = 'log')}
      >Change log ({log.length})</button>
    </div>

    {#if chat.tab === 'conversation'}
      <div id="assistant-tabpanel" class="content" role="tabpanel" aria-labelledby="assistant-tab-conversation" bind:this={scroller}>
        {#if chat.provider === null}
          {#if host !== 'cloud'}
            <div class="box" role="note" aria-label="Available on editor.materione.com">
              <p class="lead">The assistant is available on editor.materione.com.</p>
              <p>The local editor stays entirely between your browser and your server and makes no external requests.</p>
              <p>Run <code>/materione editor</code> in game to open this server on editor.materione.com when you want the assistant. Everything else in the editor works the same here.</p>
            </div>
          {:else}
            <div class="box" role="note" aria-label="No model connected">
              <p class="lead">No model is connected in this version of the editor.</p>
              <p>The assistant's model proxy is not connected in this build yet, so nothing is sent anywhere.</p>
            </div>
          {/if}
        {:else if stated !== null}
          <section class="box" aria-label="What leaves this page">
            <p class="lead">{stated.headline}</p>
            {#if stated.sent.length > 0}
              <ul>{#each stated.sent as line (line)}<li>{line}</li>{/each}</ul>
            {/if}
            {#each stated.kept as line (line)}<p>{line}</p>{/each}
            <p class="hint">
              The assistant's only tool runs a program. A program that only creates content is applied at once; a change to anything that already exists
              waits for your approval here. The assistant cannot approve, apply or revert. After {REPAIR_BUDGET} failed programs in one request it stops and tells you.
            </p>
          </section>
        {/if}

        <div class="transcript" role="log" aria-label="Conversation" aria-live="off">
          {#each chat.items as item, index (item.id)}
            {#if item.kind === 'user'}
              <div class="msg user"><span class="who">You</span><p>{item.text}</p></div>
            {:else if item.kind === 'assistant'}
              <div class="msg assistant"><span class="who">Assistant{recorded ? ' (recorded)' : ''}</span><p>{item.text}{#if item.streaming}<span class="caret" aria-hidden="true"></span>{/if}</p></div>
            {:else if item.kind === 'notice'}
              <p class="notice {item.tone}" role={item.tone === 'info' ? 'status' : 'alert'}>{item.text}</p>
            {:else}
              {@const outcome = item.outcome}
              {@const proposal = proposalOf(outcome?.proposalId ?? null)}
              <div class="tool" role="group" aria-label={`Program ${chat.items.slice(0, index + 1).filter((entry) => entry.kind === 'tool').length}`}>
                {#if item.running || outcome === null}
                  <p class="tool-line" role="status"><Chip tone="neutral">Running</Chip> Checking the program, running it in the sandbox and asking the server for a dry run&hellip;</p>
                {:else}
                  <p class="tool-line"><Chip tone={STATUS[outcome.status].tone}>{STATUS[outcome.status].chip}</Chip> {STATUS[outcome.status].words}</p>
                  {#each outcome.missingPrimitives as gap (`${gap.catalog}.${gap.member}`)}
                    <p class="gap">The server has no <code>{gap.member}</code> on <code>{gap.catalog}</code>: a missing capability, not a typo.</p>
                  {/each}
                  {#if outcome.diagnostics.length > 0 && proposal === undefined}
                    <ul class="findings" aria-label="Findings">
                      {#each outcome.diagnostics as found, at (at)}
                        <li class={found.severity}>
                          <span class="sev">{found.severity === 'error' ? 'Error' : 'Warning'}</span>
                          {#if found.code}<code>{found.code}</code>{/if}
                          {#if found.line}<span class="where">line {found.line}</span>{/if}
                          <span>{found.message}</span>
                        </li>
                      {/each}
                    </ul>
                  {/if}
                {/if}
                {#if proposal !== undefined}
                  <ProposalCard {proposal} plan={planOf(proposal.plan)} expanded={proposal.state === 'waiting'} />
                {:else if item.program !== null}
                  <ProgramView program={item.program} />
                {/if}
              </div>
            {/if}
          {/each}
        </div>
      </div>

      {#if chat.provider !== null}
        <form class="composer" onsubmit={(event) => { event.preventDefault(); send(); }}>
          {#if recorded}
            <p class="hint">
              {#if nextPrompt !== null}
                This is a recording: it answers the recorded messages, in order.
                <button type="button" class="link" disabled={chat.busy} onclick={useRecorded}>Use the next recorded message</button>
              {:else}
                The recording has no further messages.
              {/if}
            </p>
          {/if}
          <label class="sr" for="assistant-input">Message to the assistant</label>
          <textarea
            id="assistant-input"
            rows="3"
            placeholder="Describe what you want to make or change"
            bind:value={draft}
            onkeydown={onComposerKey}
          ></textarea>
          <div class="row">
            {#if usageLine}<span class="usage" title="As the provider reported it">{recorded ? 'Recorded usage' : 'Usage'}: {usageLine}</span>{/if}
            <span class="spacer"></span>
            {#if chat.busy}
              <Button size="sm" onclick={stopChat}>Stop</Button>
            {/if}
            <Button size="sm" variant="primary" type="submit" disabled={chat.busy || draft.trim() === ''}>Send</Button>
          </div>
        </form>
      {/if}
    {:else}
      <div id="assistant-tabpanel" class="content" role="tabpanel" aria-labelledby="assistant-tab-log">
        <p class="hint">What was applied in this session, newest first. Each entry can be reverted after a preview.</p>
        {#if log.length === 0}
          <p class="empty">Nothing has been applied in this session yet.</p>
        {/if}
        <div class="list">
          {#each log as entry (entry.transactionId)}
            <LogEntry {entry} proposal={review.snapshot.proposals.find((proposal) => proposal.id === entry.proposal)} />
          {/each}
        </div>
        <div><Button size="sm" variant="ghost" onclick={onOpenReview}>Open Review: every proposal and the server's audit</Button></div>
      </div>
    {/if}
  {/if}
</section>

<style>
  .panel {
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    border-left: 1px solid var(--border);
    background: var(--bg-panel);
  }
  .head {
    display: flex;
    flex: none;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  h2 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: 600;
  }
  h2:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  .spacer {
    margin-left: auto;
  }
  .close {
    flex: none;
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--muted);
    padding: var(--space-half) var(--space-2);
    cursor: pointer;
  }
  .close:hover {
    color: var(--fg);
  }
  .sr {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
    margin: 0;
  }
  .tabs {
    display: flex;
    flex: none;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-3) 0;
    border-bottom: 1px solid var(--border);
  }
  [role='tab'] {
    border: 1px solid transparent;
    border-bottom: 2px solid transparent;
    border-radius: var(--radius-sm) var(--radius-sm) 0 0;
    background: none;
    color: var(--muted);
    padding: var(--space-1) var(--space-3);
    cursor: pointer;
    font-size: var(--text-base);
  }
  [role='tab']:hover {
    color: var(--fg);
  }
  [role='tab'][aria-selected='true'] {
    color: var(--accent);
    border-bottom-color: var(--accent);
    font-weight: 600;
  }
  .content {
    flex: 1 1 0;
    min-height: 0;
    overflow-y: auto;
    overflow-x: hidden;
    padding: var(--space-3);
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    align-content: start;
    gap: var(--space-3);
  }
  .box {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    justify-items: start;
    gap: var(--space-2);
    padding: var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-raised);
    font-size: var(--text-base);
  }
  .box p,
  .box ul {
    margin: 0;
  }
  .box ul {
    padding-left: var(--space-5);
  }
  .lead {
    font-weight: 600;
    color: var(--fg);
  }
  .hint,
  .empty {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .empty {
    font-size: var(--text-base);
  }
  code {
    font-family: var(--mono);
    font-size: var(--text-sm);
    color: var(--fg-code);
    overflow-wrap: anywhere;
  }
  .transcript,
  .list {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-3);
  }
  .msg {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-half);
  }
  .msg p {
    margin: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font-size: var(--text-md);
  }
  .msg.user p {
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-input);
  }
  .who {
    color: var(--muted);
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .caret {
    display: inline-block;
    width: var(--space-2);
    height: 1em;
    margin-left: var(--space-half);
    vertical-align: text-bottom;
    background: var(--accent);
  }
  .tool {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border);
    border-left: 2px solid var(--border-strong);
    border-radius: var(--radius);
  }
  .tool-line,
  .gap {
    margin: 0;
    font-size: var(--text-base);
  }
  .gap {
    padding: var(--space-1) var(--space-2);
    border: 1px solid var(--warn);
    border-radius: var(--radius-sm);
    background: var(--warn-bg);
  }
  .findings {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-1);
    font-size: var(--text-sm);
  }
  .findings li {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-2);
    overflow-wrap: anywhere;
  }
  .findings .sev {
    font-weight: 600;
  }
  .findings .error .sev {
    color: var(--error);
  }
  .findings .warning .sev {
    color: var(--warn);
  }
  .where {
    color: var(--muted);
  }
  .notice {
    margin: 0;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    font-size: var(--text-base);
  }
  .notice.warn {
    border-color: var(--warn);
    background: var(--warn-bg);
  }
  .notice.error {
    border-color: var(--error);
    background: var(--error-bg);
  }
  .composer {
    flex: none;
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3) var(--space-3);
    border-top: 1px solid var(--border);
  }
  textarea {
    width: 100%;
    box-sizing: border-box;
    resize: vertical;
    min-height: var(--space-10);
    padding: var(--space-2);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-input);
    color: var(--fg);
    font: var(--text-md) / 1.4 var(--font-sans);
  }
  .row {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .usage {
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .link {
    background: none;
    border: none;
    padding: 0;
    color: var(--accent);
    cursor: pointer;
    font: inherit;
    text-decoration: underline;
  }
  .link:disabled {
    color: var(--muted);
    cursor: default;
  }
</style>
