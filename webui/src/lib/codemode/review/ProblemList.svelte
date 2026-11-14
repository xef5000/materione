<script lang="ts">
  /**
   * The dry run's findings, by the operation that caused them. An error blocks (it is never approvable); a warning is shown;
   * a finding that was already there before this change is shown as that, and never blocks. Each says what it is in words.
   */
  import Chip from '../../ui/Chip.svelte';
  import type { ProblemGroup } from './model';
  import { problemLabel } from './model';

  interface Props {
    groups: readonly ProblemGroup[];
    /** Called with a program line when the owner asks to see the line that staged the operation. */
    onShowLine?: (line: number) => void;
  }

  let { groups, onShowLine }: Props = $props();
</script>

<ul class="groups">
  {#each groups as group, index (index)}
    <li>
      <p class="where">
        {#if group.operation === null}
          About the whole change
        {:else}
          Operation {group.operation + 1}{group.kind ? `: ${group.kind}` : ''}{group.target ? ` ${group.target}` : ''}
        {/if}
        {#if group.line !== null && onShowLine}
          <button type="button" class="line" onclick={() => onShowLine?.(group.line as number)}>program line {group.line}</button>
        {:else if group.line !== null}
          <span class="line-text">program line {group.line}</span>
        {/if}
      </p>
      <ul class="problems">
        {#each group.problems as problem, at (at)}
          <li class={problem.severity === 'error' ? 'error' : problem.preexisting ? 'old' : 'warn'}>
            <Chip tone={problem.severity === 'error' ? 'error' : problem.preexisting ? 'neutral' : 'warn'}>{problemLabel(problem)}</Chip>
            {#if problem.code}<code class="code">{problem.code}</code>{/if}
            <span class="msg">{problem.message}</span>
            {#if problem.hint}<span class="hint">{problem.hint}</span>{/if}
            {#if problem.resource}<span class="hint">{problem.resource}</span>{/if}
          </li>
        {/each}
      </ul>
    </li>
  {/each}
</ul>

<style>
  .groups,
  .problems {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-2);
  }
  .where {
    margin: 0 0 var(--space-1);
    color: var(--muted);
    font-size: var(--text-sm);
    overflow-wrap: anywhere;
  }
  .line,
  .line-text {
    margin-left: var(--space-2);
    font-size: var(--text-xs);
  }
  .line {
    border: none;
    background: none;
    padding: 0;
    color: var(--accent);
    cursor: pointer;
    text-decoration: underline;
  }
  .problems li {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: var(--space-1) var(--space-2);
    padding: var(--space-1) var(--space-2);
    border-left: 3px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--bg-panel);
    font-size: var(--text-base);
  }
  .problems li.error {
    border-left-color: var(--error);
    background: var(--error-bg);
  }
  .problems li.warn {
    border-left-color: var(--warn);
    background: var(--warn-bg);
  }
  .code {
    font-family: var(--mono);
    font-size: var(--text-xs);
    color: var(--muted);
  }
  .msg {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .hint {
    flex: 1 0 100%;
    color: var(--muted);
    font-size: var(--text-sm);
    overflow-wrap: anywhere;
  }
</style>
