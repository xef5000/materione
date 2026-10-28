<script lang="ts">
  /**
   * A color control for GUI styles: a swatch (the native picker) and a hex
   * field that takes `#RRGGBB` or `#RRGGBBAA`. A color the layer does not set
   * shows the component's default, dimmed, and "reset" removes the key again so
   * the file stays as small as the author's intent.
   */
  import Icon from '../../components/Icon.svelte';
  import { alphaOf, argb, blueOf, cssColor, formatColor, greenOf, parseColor, pickerValue, redOf } from '../../gui/raster/color';

  interface Props {
    label: string;
    /** The layer's own value, or undefined when it inherits the default. */
    value: string | undefined;
    /** What an unset color looks like (the component's default). */
    fallback: string;
    onchange: (value: string | undefined) => void;
    disabled?: boolean;
  }

  let { label, value, fallback, onchange, disabled = false }: Props = $props();
  let draft = $state('');
  let editing = $state(false);
  let invalid = $state(false);

  const effective = $derived(parseColor(value ?? fallback) ?? parseColor(fallback) ?? 0);
  const unset = $derived(value === undefined);
  const text = $derived(editing ? draft : formatColor(effective));

  function commit(): void {
    editing = false;
    const parsed = parseColor(draft);
    invalid = parsed === null;
    if (parsed !== null && formatColor(parsed) !== formatColor(effective)) onchange(formatColor(parsed));
    else if (parsed === null) draft = formatColor(effective);
  }

  function picked(event: Event): void {
    const rgb = parseColor((event.currentTarget as HTMLInputElement).value);
    if (rgb === null) return;
    // the native picker has no alpha: keep whatever transparency the color already had
    onchange(formatColor(argb(alphaOf(effective) || 255, redOf(rgb), greenOf(rgb), blueOf(rgb))));
  }
</script>

<div class="color" class:unset class:invalid>
  <span class="name">{label}</span>
  <label class="swatch" title="Pick {label.toLowerCase()}">
    <span class="chip" style:background={cssColor(effective)}></span>
    <input type="color" aria-label="{label} picker" {disabled} value={pickerValue(effective)} oninput={picked} />
  </label>
  <input
    class="hex"
    type="text"
    aria-label="{label} hex"
    spellcheck="false"
    autocomplete="off"
    maxlength="9"
    {disabled}
    value={text}
    onfocus={(event) => {
      editing = true;
      draft = formatColor(effective);
      (event.currentTarget as HTMLInputElement).select();
    }}
    oninput={(event) => (draft = event.currentTarget.value)}
    onblur={commit}
    onkeydown={(event) => {
      if (event.key === 'Enter') (event.currentTarget as HTMLInputElement).blur();
      if (event.key === 'Escape') {
        editing = false;
        draft = formatColor(effective);
        (event.currentTarget as HTMLInputElement).blur();
      }
    }}
  />
  <button class="reset" type="button" title="Back to the default" aria-label="Reset {label}" disabled={disabled || unset} onclick={() => onchange(undefined)}>
    <Icon name="x" size={12} />
  </button>
</div>

<style>
  .color {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
  }
  .name {
    flex: none;
    width: 64px;
    font-size: var(--text-sm);
    color: var(--muted);
  }
  .swatch {
    position: relative;
    flex: none;
    width: 28px;
    height: 24px;
    border: 1px solid var(--control-line);
    border-radius: var(--radius-sm);
    overflow: hidden;
    cursor: pointer;
    background:
      conic-gradient(var(--checker-a) 25%, var(--checker-b) 0 50%, var(--checker-a) 0 75%, var(--checker-b) 0) 0 0 / 8px 8px;
  }
  .swatch:focus-within {
    border-color: var(--accent);
  }
  .chip {
    position: absolute;
    inset: 0;
  }
  .swatch input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    opacity: 0;
    cursor: pointer;
  }
  .hex {
    flex: 1 1 0;
    min-width: 0;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    padding: var(--space-1) var(--space-2);
    font-family: var(--mono);
    font-size: var(--text-sm);
    outline: none;
  }
  .hex:focus {
    border-color: var(--accent);
  }
  .unset .hex {
    color: var(--faint);
  }
  .invalid .hex {
    border-color: var(--error);
  }
  .reset {
    flex: none;
    display: inline-grid;
    place-items: center;
    width: 22px;
    height: 22px;
    background: transparent;
    border: 0;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .reset:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .reset:disabled {
    visibility: hidden;
  }
</style>
