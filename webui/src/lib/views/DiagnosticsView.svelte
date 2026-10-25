<script lang="ts">
  import { diagnostics, revealInAdvanced, workspace } from '../state.svelte';
  import type { DiagnosticEntry } from '../api';
  import { diagnosticSource } from '../diagnostics';

  let filter = $state<'all' | 'error' | 'warn'>('all');

  function severityOf(entry: DiagnosticEntry): string {
    return entry.severity.toLowerCase();
  }

  const shown = $derived(
    filter === 'all'
      ? diagnostics.entries
      : diagnostics.entries.filter((entry) => severityOf(entry) === filter),
  );
  const errors = $derived(diagnostics.entries.filter((entry) => severityOf(entry) === 'error').length);
  const warnings = $derived(diagnostics.entries.filter((entry) => severityOf(entry) === 'warn').length);

</script>

<p class="summary">
  <button type="button" class:active={filter === 'all'} aria-pressed={filter === 'all'} onclick={() => (filter = 'all')}>
    all {diagnostics.entries.length}
  </button>
  <button type="button" class:active={filter === 'error'} aria-pressed={filter === 'error'} onclick={() => (filter = 'error')}>
    <span class="err">{errors} errors</span>
  </button>
  <button type="button" class:active={filter === 'warn'} aria-pressed={filter === 'warn'} onclick={() => (filter = 'warn')}>
    <span class="warn">{warnings} warnings</span>
  </button>
</p>
<ul>
  {#each shown as entry (entry.code + '/' + entry.message)}
    {@const target = diagnosticSource(entry, workspace.packs, workspace.resources)}
    <li class={severityOf(entry)}>
      <code>{entry.code}</code>
      <span class="msg">{entry.message}</span>
      {#if entry.location}
        {#if target}
          <button
            class="loc"
            type="button"
            title={`Open ${target.resource.path} in Files`}
            aria-label={`Open ${target.resource.path} in Files at ${entry.location}`}
            onclick={() => void revealInAdvanced(target.resource, target.source)}>at {entry.location}</button
          >
        {:else}
          <span class="loc">at {entry.location}</span>
        {/if}
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
    flex-wrap: wrap;
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
    overflow-wrap: anywhere;
  }
  .loc {
    display: inline-block;
    max-width: 100%;
    overflow-wrap: anywhere;
    background: none;
    border: none;
    color: var(--faint);
    font-size: 11px;
    padding: 0;
    cursor: pointer;
    text-decoration: underline dotted;
  }
  span.loc {
    cursor: default;
    text-decoration: none;
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
