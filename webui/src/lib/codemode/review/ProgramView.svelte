<script lang="ts">
  /**
   * The program text, collapsed: the witness of how the operation list was produced. Each line that staged an operation
   * says which one, from the position the sandbox recorded (not from anything the model wrote). The program is what the
   * agent wrote; what it does is the documents above, from the dry run.
   */
  import type { SourcePosition, StagedOperation } from '../core/types';
  import { programRows } from './model';

  interface Props {
    program: string;
    operations?: readonly StagedOperation[];
    sources?: readonly (SourcePosition | null)[];
    /** For an audit row: the digest, and whether the server kept only the start of the text. */
    digest?: string | null;
    truncatedFrom?: number | null;
    /** A line to point at (a finding's source), one-based. */
    highlight?: number | null;
    /** Whether the text is shown; the review opens it when a finding points at a line. */
    open?: boolean;
  }

  let { program, operations = [], sources = [], digest = null, truncatedFrom = null, highlight = null, open = $bindable(false) }: Props = $props();

  const rows = $derived(programRows(program, operations, sources));
  const staged = $derived(rows.reduce((total, row) => total + row.staged.length, 0));
</script>

<details class="program" bind:open>
  <summary>
    Program ({rows.length} {rows.length === 1 ? 'line' : 'lines'}{staged > 0 ? `, ${staged} ${staged === 1 ? 'operation' : 'operations'} staged` : ''})
  </summary>
  {#if truncatedFrom !== null}
    <p class="note">The server kept the first {program.length} of {truncatedFrom} characters. The digest still covers all of it.</p>
  {/if}
  <!-- a scrollable region must be reachable by keyboard -->
  <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
  <div class="code" role="region" aria-label="Program text" tabindex="0">
    {#each rows as row (row.line)}
      <div class="line" class:hit={highlight === row.line} id={`program-line-${row.line}`}>
        <span class="no" aria-hidden="true">{row.line}</span>
        <code>{row.text === '' ? ' ' : row.text}</code>
        {#each row.staged as stage (stage.index)}
          <span class="staged">staged operation {stage.index + 1}: {stage.kind}</span>
        {/each}
      </div>
    {/each}
  </div>
  {#if digest !== null}
    <p class="note digest">Digest <code>{digest}</code></p>
  {/if}
</details>

<style>
  .program {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-panel);
  }
  summary {
    cursor: pointer;
    padding: var(--space-1) var(--space-2);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  summary:hover {
    color: var(--fg);
  }
  .code {
    border-top: 1px solid var(--border);
    background: var(--bg-input);
    font-family: var(--mono);
    font-size: var(--text-xs);
    line-height: 1.5;
    overflow-x: auto;
    max-height: 320px;
    overflow-y: auto;
  }
  .line {
    display: flex;
    align-items: baseline;
    gap: var(--space-2);
    min-width: max-content;
    padding: 0 var(--space-2);
  }
  .line.hit {
    background: var(--warn-bg);
  }
  .no {
    flex: none;
    min-width: 3ch;
    text-align: right;
    color: var(--faint);
    user-select: none;
  }
  code {
    white-space: pre;
    color: var(--fg-code);
  }
  .staged {
    flex: none;
    margin-left: auto;
    padding: 0 var(--space-2);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    color: var(--muted);
    font-family: var(--font-sans);
  }
  .note {
    margin: 0;
    padding: var(--space-1) var(--space-2);
    color: var(--muted);
    font-size: var(--text-xs);
    overflow-wrap: anywhere;
  }
</style>
