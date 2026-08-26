<script lang="ts">
  import { diagnostics, openDoc, workspace } from '../state.svelte';
  import type { DiagnosticEntry } from '../api';

  let filter = $state<'all' | 'error' | 'warn'>('all');

  const shown = $derived(
    filter === 'all'
      ? diagnostics.entries
      : diagnostics.entries.filter((entry) => entry.severity === filter),
  );
  const errors = $derived(diagnostics.entries.filter((d) => d.severity === 'error').length);
  const warnings = $derived(diagnostics.entries.filter((d) => d.severity === 'warn').length);

  /**
   * Best-effort click-through: when the diagnostic's source location names a
   * file of a known pack, open it. Runtime diagnostics (no file) stay inert.
   */
  function locationFile(entry: DiagnosticEntry): { pack: string; path: string } | null {
    if (!entry.location) return null;
    const file = entry.location.split(':')[0]!.replace('\\', '/');
    for (const pack of workspace.packs) {
      if (file.startsWith(pack + '/')) {
        return { pack, path: file.slice(pack.length + 1) };
      }
    }
    return null;
  }

  function open(entry: DiagnosticEntry) {
    const target = locationFile(entry);
    if (!target || !workspace.activePack) return;
    if (!target.path.endsWith('.yml') && !target.path.endsWith('.yaml')) return;
    openDoc({ pack: target.pack, type: target.path.split('/')[0], path: target.path, id: '' });
  }
</script>

<p class="summary">
  <button class:active={filter === 'all'} onclick={() => (filter = 'all')}>
    all {diagnostics.entries.length}
  </button>
  <button class:active={filter === 'error'} onclick={() => (filter = 'error')}>
    <span class="err">{errors} errors</span>
  </button>
  <button class:active={filter === 'warn'} onclick={() => (filter = 'warn')}>
    <span class="warn">{warnings} warnings</span>
  </button>
</p>
<ul>
  {#each shown as entry (entry.code + '/' + entry.message)}
    {@const target = locationFile(entry)}
    <li class={entry.severity}>
      <code>{entry.code}</code>
      <span class="msg">{entry.message}</span>
      {#if entry.location}
        <button
          class="loc"
          disabled={!target}
          title={target ? 'open ' + target.path : ''}
          onclick={() => open(entry)}>at {entry.location}</button
        >
      {/if}
      {#if entry.hint}<span class="hint">{entry.hint}</span>{/if}
    </li>
  {/each}
  {#if shown.length === 0}
    <li class="empty">all clear</li>
  {/if}
</ul>

<style>
  .summary {
    display: flex;
    gap: 6px;
    font-size: 12px;
    margin: 4px 0 10px;
  }
  .summary button {
    background: none;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    color: var(--muted);
    font-size: 11.5px;
    padding: 2px 8px;
    cursor: pointer;
  }
  .summary button.active {
    border-color: var(--accent);
    color: var(--fg);
  }
  .err {
    color: var(--error);
  }
  .warn {
    color: var(--warn);
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  li {
    font-size: 12.5px;
    padding: 6px;
    border-left: 3px solid var(--border-strong);
    margin-bottom: 6px;
    background: var(--bg-hover);
    border-radius: 0 var(--radius) var(--radius) 0;
  }
  li.error {
    border-left-color: var(--error);
  }
  li.warn {
    border-left-color: var(--warn);
  }
  code {
    color: var(--accent);
    font-size: 11.5px;
  }
  .msg {
    display: block;
  }
  .loc {
    background: none;
    border: none;
    color: var(--faint);
    font-size: 11px;
    padding: 0;
    cursor: pointer;
    text-decoration: underline dotted;
  }
  .loc:hover:not(:disabled) {
    color: var(--accent);
  }
  .loc:disabled {
    cursor: default;
    text-decoration: none;
  }
  .hint {
    display: block;
    font-size: 11px;
    color: var(--faint);
  }
  .empty {
    color: var(--faint);
    font-style: italic;
  }
</style>
