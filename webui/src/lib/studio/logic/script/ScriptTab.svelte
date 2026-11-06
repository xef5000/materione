<script lang="ts">
  /**
   * The Script tab (logic.md section 7.1, 33): the resource's `open:`, `behavior:` and `flows:` as YAML, for the author who
   * would rather type it. It edits the same document as the Design and Logic tabs through their history, so Undo, Redo,
   * the dirty marker and Save are shared. A draft that parses is committed once typing pauses; one that does not stays in
   * the editor with the error on its line, and nothing reaches the document until it is fixed. The compiler checks every
   * draft that parses, and its diagnostics are drawn on the lines their step paths name.
   */
  import { onDestroy, untrack } from 'svelte';
  import Icon from '../../../components/Icon.svelte';
  import type { ScriptState } from '../../../logic/script/script.svelte';
  import { revealInAdvanced, session } from '../../../state.svelte';
  import type { ResourceRef } from '../../../api';

  let { script, resource }: { script: ScriptState; resource: ResourceRef } = $props();

  let area = $state<HTMLTextAreaElement>();
  let gutter = $state<HTMLDivElement>();

  // The text follows the document whenever something else changes it (a Design or Logic edit, an undo).
  $effect(() => {
    void script.docKey;
    untrack(() => script.follow());
  });
  $effect(() => {
    script.connect(session.channel);
  });
  onDestroy(() => script.flush());

  const count = $derived(Math.max(1, script.text.split('\n').length));
  /** The worst thing said about each line: the draft's error, else the compiler's. */
  const marks = $derived.by(() => {
    const out = new Map<number, { severity: 'error' | 'warn' | 'info'; text: string[] }>();
    const add = (line: number, severity: 'error' | 'warn' | 'info', text: string): void => {
      const rank = { error: 3, warn: 2, info: 1 };
      const current = out.get(line);
      if (!current) out.set(line, { severity, text: [text] });
      else out.set(line, { severity: rank[severity] > rank[current.severity] ? severity : current.severity, text: [...current.text, text] });
    };
    if (script.error) add(script.error.line, 'error', script.error.message);
    for (const diagnostic of script.diagnostics) add(diagnostic.line, diagnostic.severity, `${diagnostic.code}: ${diagnostic.message}`);
    return out;
  });
  const summary = $derived.by(() => {
    if (script.error) return { kind: 'error', text: `Not applied: line ${script.error.line}: ${script.error.message}` };
    if (script.stale) return { kind: 'warn', text: 'The logic changed in another tab since you started this draft.' };
    if (script.drafting) return { kind: 'warn', text: 'Applying…' };
    return { kind: 'ok', text: 'In sync with the file' };
  });

  function sync(): void {
    if (gutter && area) gutter.scrollTop = area.scrollTop;
  }

  function input(event: Event): void {
    script.edit((event.currentTarget as HTMLTextAreaElement).value);
  }

  function keys(event: KeyboardEvent): void {
    const target = event.currentTarget as HTMLTextAreaElement;
    if (event.key === 'Escape') {
      target.blur(); // Tab then moves focus on, as everywhere else
      event.stopPropagation();
    } else if (event.key === 'Tab' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey) {
      event.preventDefault();
      target.setRangeText('  ', target.selectionStart, target.selectionEnd, 'end');
      script.edit(target.value);
    }
  }

  function jump(line: number): void {
    if (!area) return;
    const lines = script.text.split('\n');
    const start = lines.slice(0, line - 1).reduce((offset, each) => offset + each.length + 1, 0);
    area.focus();
    area.setSelectionRange(start, start + (lines[line - 1]?.length ?? 0));
    area.scrollTop = Math.max(0, (line - 4) * 20);
    sync();
  }
</script>

<section class="script" aria-label="Script" data-testid="script-tab">
  <div class="tools" role="toolbar" aria-label="Script tools">
    <button type="button" class="tool" title={script.studio.undoLabel ? `Undo ${script.studio.undoLabel} (Ctrl+Z)` : 'Undo (Ctrl+Z)'} aria-label="Undo" disabled={!script.studio.canUndo || script.studio.readOnly} onclick={() => script.undo()}>
      <Icon name="undo-2" size={16} />
    </button>
    <button type="button" class="tool" title={script.studio.redoLabel ? `Redo ${script.studio.redoLabel} (Ctrl+Shift+Z)` : 'Redo (Ctrl+Shift+Z)'} aria-label="Redo" disabled={!script.studio.canRedo || script.studio.readOnly} onclick={() => script.redo()}>
      <Icon name="redo-2" size={16} />
    </button>
    <span class="state {summary.kind}" role="status" data-testid="script-state" data-state={script.error ? 'error' : script.stale ? 'stale' : script.drafting ? 'drafting' : 'synced'}>{summary.text}</span>
    <span class="spacer"></span>
    {#if script.checking}<span class="checking" role="status">Checking…</span>{/if}
    {#if script.stale}
      <button type="button" class="act" onclick={() => script.commit(true)} data-testid="script-apply-anyway">Apply my draft</button>
      <button type="button" class="act" onclick={() => script.discard()} data-testid="script-discard">Discard my draft</button>
    {:else if script.drafting}
      <button type="button" class="act" onclick={() => script.discard()} data-testid="script-discard">Discard draft</button>
    {/if}
  </div>

  {#if script.logic.status === 'unavailable'}
    <p class="banner notice" role="status" data-testid="script-unavailable">
      This connection does not advertise the <code>logic</code> capability (an older Materione plugin, or the in-browser demo), so the script is shown but
      not edited here, and the rules in the file are untouched.
    </p>
  {:else if script.logic.problems.length > 0}
    <p class="banner error" role="alert">
      This file's logic cannot be edited faithfully ({script.logic.problems[0]}), so it is shown but not edited here;
      <button type="button" class="link" onclick={() => revealInAdvanced(resource)}>edit it in Files</button>.
    </p>
  {:else if script.studio.readOnly}
    <p class="banner warn" role="status">This file is read-only in the studio, so its script is too.</p>
  {/if}

  <div class="editor">
    <div class="gutter" bind:this={gutter} aria-hidden="true">
      {#each { length: count } as _, index (index)}
        {@const line = index + 1}
        {@const mark = marks.get(line)}
        <div class="ln {mark?.severity ?? ''}" data-line={line} data-mark={mark?.severity} title={mark?.text.join('\n')}>
          {#if mark}<Icon name={mark.severity === 'info' ? 'info' : 'triangle-alert'} size={11} />{/if}{line}
        </div>
      {/each}
    </div>
    <textarea
      bind:this={area}
      class="code"
      aria-label="Script (YAML)"
      aria-describedby="script-help"
      data-testid="script-editor"
      spellcheck="false"
      autocomplete="off"
      wrap="off"
      readonly={!script.editable}
      value={script.text}
      oninput={input}
      onkeydown={keys}
      onscroll={sync}
    ></textarea>
  </div>
  <p id="script-help" class="help">
    open:, behavior: and flows: of this GUI. Edits apply when you pause and share Undo with the other tabs; a draft that does not parse is kept here, never saved.
    Tab indents; Escape then Tab leaves the editor.
  </p>

  {#if script.error || script.diagnostics.length > 0}
    <ul class="problems" aria-label="Problems" data-testid="script-problems">
      {#if script.error}
        <li class="error" data-testid="script-error">
          <button type="button" onclick={() => jump(script.error!.line)}>
            <Icon name="triangle-alert" size={12} /> <strong>Line {script.error.line}</strong> {script.error.message}
          </button>
        </li>
      {/if}
      {#each script.diagnostics as diagnostic, index (index)}
        <li class={diagnostic.severity} data-testid="script-diagnostic" data-line={diagnostic.line}>
          <button type="button" onclick={() => jump(diagnostic.line)}>
            <Icon name={diagnostic.severity === 'info' ? 'info' : 'triangle-alert'} size={12} />
            <strong>Line {diagnostic.line}</strong> {diagnostic.code}: {diagnostic.message}
            {#if diagnostic.path}<code>{diagnostic.path}</code>{/if}
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .script {
    display: flex;
    flex: 1 1 0;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
  }
  .tools {
    display: flex;
    flex: none;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    border-bottom: 1px solid var(--border);
    background: var(--bg-panel);
    min-width: 0;
  }
  .tool {
    display: inline-grid;
    place-items: center;
    width: 30px;
    height: 28px;
    background: transparent;
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .tool:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .tool:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .state {
    min-width: 0;
    overflow: hidden;
    font-size: var(--text-xs);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .state.ok {
    color: var(--muted);
  }
  .state.warn {
    color: var(--warn);
  }
  .state.error {
    color: var(--error);
  }
  .spacer {
    flex: 1;
  }
  .checking {
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .act {
    height: 26px;
    padding: 0 var(--space-3);
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: inherit;
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .act:hover {
    background: var(--bg-hover);
  }
  .banner {
    margin: 0;
    flex: none;
    padding: var(--space-2) var(--space-3);
    font-size: var(--text-sm);
  }
  .banner.error {
    background: var(--error-bg);
    color: var(--error);
  }
  .banner.warn {
    background: var(--warn-bg);
    color: var(--warn);
  }
  .banner.notice {
    background: var(--ok-bg);
    color: var(--ok);
  }
  .link {
    background: none;
    border: 0;
    color: inherit;
    text-decoration: underline;
    cursor: pointer;
    font: inherit;
    padding: 0;
  }
  .editor {
    display: flex;
    flex: 1 1 0;
    min-height: 0;
    background: var(--bg-input);
  }
  .gutter {
    flex: none;
    width: 56px;
    overflow: hidden;
    padding: var(--space-2) 0;
    background: var(--bg-panel);
    border-right: 1px solid var(--border);
    color: var(--faint);
    font: var(--text-xs) / 20px var(--mono);
    text-align: right;
    user-select: none;
  }
  .ln {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 2px;
    height: 20px;
    padding-right: var(--space-2);
    font-variant-numeric: tabular-nums;
  }
  .ln.error {
    background: var(--error-bg);
    color: var(--error);
  }
  .ln.warn {
    background: var(--warn-bg);
    color: var(--warn);
  }
  .ln.info {
    color: var(--muted);
  }
  .code {
    flex: 1 1 0;
    min-width: 0;
    box-sizing: border-box;
    padding: var(--space-2) var(--space-3);
    resize: none;
    background: transparent;
    border: 0;
    color: var(--fg);
    font: var(--text-sm) / 20px var(--mono);
    tab-size: 2;
    white-space: pre;
    overflow: auto;
    outline: none;
  }
  .code:focus-visible {
    box-shadow: inset 0 0 0 2px var(--focus-ring);
  }
  .help {
    flex: none;
    margin: 0;
    padding: var(--space-1) var(--space-3);
    border-top: 1px solid var(--border);
    background: var(--bg-panel);
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .problems {
    flex: none;
    max-height: 28%;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
    border-top: 1px solid var(--border);
    background: var(--bg-panel);
  }
  .problems button {
    display: flex;
    align-items: baseline;
    gap: var(--space-2);
    width: 100%;
    padding: var(--space-1) var(--space-3);
    background: none;
    border: 0;
    color: var(--fg);
    font-size: var(--text-xs);
    text-align: left;
    cursor: pointer;
  }
  .problems button:hover {
    background: var(--bg-hover);
  }
  .problems li.error :global(svg) {
    color: var(--error);
  }
  .problems li.warn :global(svg) {
    color: var(--warn);
  }
  code {
    color: var(--faint);
    font: var(--text-xs) var(--mono);
  }
</style>
