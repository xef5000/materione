<script lang="ts">
  import { diagnostics } from '../state.svelte';

  const errors = $derived(diagnostics.entries.filter((d) => d.severity === 'error'));
  const warnings = $derived(diagnostics.entries.filter((d) => d.severity === 'warn').length);
</script>

<p class="summary">
  <span class="err">{errors.length} errors</span> · <span class="warn">{warnings} warnings</span>
</p>
<ul>
  {#each diagnostics.entries as entry (entry.code + '/' + entry.message)}
    <li class={entry.severity}>
      <code>{entry.code}</code>
      <span class="msg">{entry.message}</span>
      {#if entry.resource}<span class="res">{entry.resource}</span>{/if}
      {#if entry.hint}<span class="hint">{entry.hint}</span>{/if}
    </li>
  {/each}
  {#if workspace.diagnostics.entries.length === 0}
    <li class="empty">all clear</li>
  {/if}
</ul>

<style>
  .summary {
    font-size: 12px;
    color: var(--muted);
    margin: 4px 0 10px;
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
  .res,
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
