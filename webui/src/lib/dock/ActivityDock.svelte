<script lang="ts">
  import { activityLog } from '../state.svelte';
  import type { ActivityKind } from '../state.svelte';

  let collapsed = $state(false);

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

<section>
  <button class="head" onclick={() => (collapsed = !collapsed)} aria-expanded={!collapsed}>
    <span class="title">Pipeline log</span>
    <span class="count">{activityLog.entries.length}</span>
    <span class="spacer"></span>
    <span class="toggle">{collapsed ? '▲' : '▼'}</span>
  </button>

  {#if !collapsed}
    {#if activityLog.entries.length > 0}
      <button class="clear" onclick={() => (activityLog.entries = [])}>clear</button>
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
        <li class="empty">nothing yet — saves, reloads and errors land here.</li>
      {/if}
    </ul>
  {/if}
</section>

<style>
  section {
    position: relative;
    border-top: 1px solid var(--border);
    background: var(--bg-panel);
    max-height: 180px;
    display: flex;
    flex-direction: column;
  }
  .head {
    display: flex;
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
  .count {
    background: var(--bg-raised);
    border-radius: 8px;
    padding: 0 7px;
    font-size: 10.5px;
  }
  .spacer,
  .toggle {
    flex: 1;
  }
  .toggle {
    flex: none;
  }
  .clear {
    position: absolute;
    top: 3px;
    right: 30px;
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
