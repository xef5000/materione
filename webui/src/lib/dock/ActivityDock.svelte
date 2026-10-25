<script lang="ts">
  import { activityLog } from '../state.svelte';
  import type { ActivityKind } from '../state.svelte';

  interface Props {
    open?: boolean;
  }

  let { open = $bindable(false) }: Props = $props();

  const errors = $derived(activityLog.entries.filter((entry) => entry.kind === 'error').length);

  const glyph: Record<ActivityKind, string> = {
    save: '✎',
    reload: '↻',
    error: '⚠',
    info: '·',
  };

  function time(ts: number): string {
    return new Date(ts).toLocaleTimeString();
  }
</script>

<section id="activity-dock" class:expanded={open} aria-label="Activity log">
  <button
    class="head"
    type="button"
    onclick={() => (open = !open)}
    aria-expanded={open}
    aria-controls="activity-entries"
  >
    <span class="title">Activity</span>
    <span class="count" aria-label={`${activityLog.entries.length} activity entries`}>
      {activityLog.entries.length}
    </span>
    {#if errors > 0}
      <span class="error-count">{errors} {errors === 1 ? 'error' : 'errors'}</span>
    {/if}
    <span class="spacer"></span>
    <span class="toggle" aria-hidden="true">{open ? '▼' : '▲'}</span>
  </button>

  <div id="activity-entries" class="body" hidden={!open}>
    {#if activityLog.entries.length > 0}
      <button
        class="clear"
        type="button"
        aria-label="Clear activity log"
        onclick={() => (activityLog.entries = [])}
      >clear</button>
    {/if}
    <ul>
      {#each activityLog.entries as entry, index (entry.ts + '-' + index)}
        <li class={entry.kind}>
          <span class="glyph">{glyph[entry.kind]}</span>
          <span class="ts">{time(entry.ts)}</span>
          <span class="text">{entry.text}</span>
        </li>
      {/each}
      {#if activityLog.entries.length === 0}
        <li class="empty">No activity yet — saves, reloads and errors appear here.</li>
      {/if}
    </ul>
  </div>
</section>

<style>
  section {
    position: relative;
    flex: none;
    border-top: 1px solid var(--border);
    background: var(--bg-panel);
    max-height: 180px;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .head {
    display: flex;
    flex: none;
    align-items: center;
    gap: 8px;
    background: none;
    border: none;
    color: var(--muted);
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    padding: 5px 12px;
    cursor: pointer;
    text-align: left;
  }
  .head:hover {
    color: var(--fg);
  }
  .count {
    background: var(--bg-raised);
    border-radius: 8px;
    padding: 0 7px;
    font-size: 10.5px;
  }
  .error-count {
    color: var(--error);
    font-size: 10.5px;
    font-weight: 600;
    text-transform: none;
    letter-spacing: 0;
  }
  .spacer,
  .toggle {
    flex: 1;
  }
  .toggle {
    flex: none;
  }
  .body {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
    overflow: hidden;
  }
  .body[hidden] {
    display: none;
  }
  .clear {
    align-self: flex-end;
    margin: 0 12px 3px;
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--faint);
    font-size: 10.5px;
    padding: 1px 8px;
    cursor: pointer;
  }
  .clear:hover {
    color: var(--fg);
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0 12px 8px;
    overflow-y: auto;
    min-height: 0;
    font-family: var(--mono);
    font-size: 11.5px;
  }
  li {
    display: flex;
    gap: 8px;
    padding: 2px 0;
    border-bottom: 1px dotted var(--border);
    align-items: baseline;
  }
  .glyph {
    width: 12px;
    text-align: center;
    flex: none;
  }
  li.save .glyph {
    color: var(--ok);
  }
  li.reload .glyph {
    color: var(--accent);
  }
  li.error .glyph {
    color: var(--error);
  }
  li.info .glyph {
    color: var(--muted);
  }
  .ts {
    color: var(--faint);
    flex: none;
  }
  .text {
    white-space: pre-wrap;
    word-break: break-word;
  }
  .empty {
    color: var(--faint);
    font-style: italic;
    border: none;
  }
</style>
