<script lang="ts">
  import { tick } from 'svelte';
  import { editableLegacyLines, editableLegacyText, formatLegacySelection, legacyLines } from '../legacy-text';

  interface Props {
    id: string;
    label: string;
    value: unknown;
    multiline?: boolean;
    onChange: (value: unknown) => void;
  }
  let { id, label, value, multiline = false, onChange }: Props = $props();
  const raw = $derived(multiline ? editableLegacyLines(value) : editableLegacyText(value));
  let area: HTMLInputElement | HTMLTextAreaElement | undefined = $state();
  const shortcuts: Record<string, string> = { b: 'l', i: 'o', u: 'n' };
  const colors = [
    ['0', 'Black'], ['1', 'Dark blue'], ['2', 'Dark green'], ['3', 'Dark aqua'],
    ['4', 'Dark red'], ['5', 'Dark purple'], ['6', 'Gold'], ['7', 'Gray'],
    ['8', 'Dark gray'], ['9', 'Blue'], ['a', 'Green'], ['b', 'Aqua'],
    ['c', 'Red'], ['d', 'Light purple'], ['e', 'Yellow'], ['f', 'White'],
  ];

  function change(text: string) {
    onChange(multiline ? legacyLines(text) : text);
  }

  async function format(code: string) {
    if (!area || raw === null) return;
    const target = area;
    const result = formatLegacySelection(raw, target.selectionStart ?? 0, target.selectionEnd ?? 0, code, multiline);
    change(result.text);
    await tick();
    if (target !== area) return;
    target.focus();
    target.setSelectionRange(result.start, result.end);
  }

  function keyboard(event: KeyboardEvent) {
    if ((event.ctrlKey || event.metaKey) && !event.altKey) {
      const code = shortcuts[event.key.toLowerCase()];
      if (code) {
        event.preventDefault();
        void format(code);
      }
    }
  }
</script>

<div class="formatted-editor">
  {#if raw === null}
    <p class="unsupported">This value cannot be edited as plain lines without changing its structure. Use Files; the original value is preserved.</p>
  {:else}
    <div class="tools" role="group" aria-label={`${label} formatting`}>
      <select aria-label={`${label} color`} value="" onchange={(event) => {
        void format(event.currentTarget.value);
        event.currentTarget.value = '';
      }}>
        <option value="" disabled>Color</option>
        {#each colors as [code, name]}
          <option value={code}>{name}</option>
        {/each}
      </select>
      {#each [{ code: 'l', label: 'Bold', text: 'B' }, { code: 'o', label: 'Italic', text: 'I' }, { code: 'n', label: 'Underline', text: 'U' }, { code: 'm', label: 'Strikethrough', text: 'S' }, { code: 'r', label: 'Reset formatting', text: 'Reset' }] as tool}
        <button type="button" title={tool.label} aria-label={`${label}: ${tool.label}`}
          onpointerdown={(event) => event.preventDefault()} onclick={() => void format(tool.code)}>{tool.text}</button>
      {/each}
    </div>
    {#if multiline}
      <textarea {id} bind:this={area} data-field-control rows="4" spellcheck="false" value={raw}
        oninput={(event) => change(event.currentTarget.value)} onkeydown={keyboard}></textarea>
      <div class="line-tools">
        <span>{Array.isArray(value) ? value.length : 0} lore lines, including blanks</span>
        <button type="button" onclick={() => onChange([...(Array.isArray(value) ? value : []), ''])}>Add blank line</button>
      </div>
    {:else}
      <input {id} bind:this={area} data-field-control type="text" spellcheck="false" value={raw}
        oninput={(event) => change(event.currentTarget.value)} onkeydown={keyboard} />
    {/if}
    <small>Formatting uses existing &amp; codes. Glyphs, effects and obfuscation are not simulated.</small>
  {/if}
</div>

<style>
  .formatted-editor { display: grid; gap: var(--space-1); min-width: 0; }
  .tools, .line-tools { display: flex; gap: var(--space-1); align-items: center; flex-wrap: wrap; }
  button:not(.btn), select { background: var(--bg-panel); border: 1px solid var(--border-strong); border-radius: var(--radius); font-size: var(--text-xs); padding: var(--space-1) var(--space-2); cursor: pointer; }
  button:not(.btn):hover { background: var(--bg-hover); }
  input, textarea { width: 100%; box-sizing: border-box; min-width: 0; background: var(--bg-input); border: 1px solid var(--border-strong); border-radius: var(--radius); padding: var(--space-2) var(--space-2); font-size: var(--text-base); }
  textarea { resize: vertical; }
  small, .line-tools, .unsupported { color: var(--muted); font-size: var(--text-xs); line-height: 1.4; margin: 0; }
  .line-tools { justify-content: space-between; }
</style>
