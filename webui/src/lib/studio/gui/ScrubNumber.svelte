<script lang="ts">
  /**
   * A number you can type into or drag: press the label and move sideways to
   * scrub it (4 px per step, Shift for 10x), or focus the field and use the
   * arrow keys. Every change is reported as it happens so the canvas follows
   * the drag; the studio folds the stream into one undo step.
   */
  interface Props {
    label: string;
    value: number | null;
    min?: number;
    max?: number;
    unit?: string;
    disabled?: boolean;
    /** The selection disagrees on this value: shown as a dash until edited. */
    mixed?: boolean;
    /** A full-width row: a word label ("Slot") instead of a one-letter handle. */
    wide?: boolean;
    onchange: (value: number) => void;
  }

  let { label, value, min = -9999, max = 9999, unit, disabled = false, mixed = false, wide = false, onchange }: Props = $props();
  let draft = $state('');
  let editing = $state(false);
  let scrubbing = $state(false);

  const shown = $derived(editing ? draft : mixed || value === null ? '' : String(value));

  function clamp(n: number): number {
    return Math.min(max, Math.max(min, Math.round(n)));
  }

  function commit(): void {
    editing = false;
    const parsed = Number(draft.trim());
    if (draft.trim() !== '' && Number.isFinite(parsed) && clamp(parsed) !== value) onchange(clamp(parsed));
  }

  let scrubStart: { x: number; value: number } | null = null;

  function down(event: PointerEvent): void {
    if (disabled || event.button !== 0) return;
    scrubStart = { x: event.clientX, value: value ?? 0 };
    scrubbing = true;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    event.preventDefault();
  }

  function move(event: PointerEvent): void {
    if (!scrubStart) return;
    const steps = Math.trunc((event.clientX - scrubStart.x) / 4) * (event.shiftKey ? 10 : 1);
    const next = clamp(scrubStart.value + steps);
    if (next !== value) onchange(next);
  }

  function up(): void {
    scrubStart = null;
    scrubbing = false;
  }

  function keys(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      commit();
      (event.currentTarget as HTMLInputElement).blur();
    } else if (event.key === 'Escape') {
      editing = false;
      (event.currentTarget as HTMLInputElement).blur();
    } else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault();
      const step = (event.shiftKey ? 10 : 1) * (event.key === 'ArrowUp' ? 1 : -1);
      const next = clamp((value ?? 0) + step);
      draft = String(next);
      if (next !== value) onchange(next);
    }
  }
</script>

<label class="num" class:disabled class:scrubbing class:wide>
  <span
    class="scrub"
    role="presentation"
    onpointerdown={down}
    onpointermove={move}
    onpointerup={up}
    onpointercancel={up}
  >{label}</span>
  <input
    type="text"
    inputmode="numeric"
    aria-label={label}
    autocomplete="off"
    spellcheck="false"
    placeholder={mixed ? 'mixed' : ''}
    {disabled}
    value={shown}
    onfocus={(event) => {
      editing = true;
      draft = mixed || value === null ? '' : String(value);
      (event.currentTarget as HTMLInputElement).select();
    }}
    oninput={(event) => (draft = event.currentTarget.value)}
    onblur={commit}
    onkeydown={keys}
  />
  {#if unit}<em>{unit}</em>{/if}
</label>

<style>
  .num {
    display: flex;
    align-items: center;
    min-width: 0;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    transition: border-color var(--motion-fast) var(--ease-standard);
  }
  .num:focus-within {
    border-color: var(--accent);
  }
  .num.disabled {
    opacity: 0.5;
  }
  .scrub {
    flex: none;
    width: 22px;
    text-align: center;
    color: var(--muted);
    font-size: var(--text-xs);
    font-weight: 600;
    cursor: ew-resize;
    user-select: none;
    touch-action: none;
    padding: var(--space-1) 0;
  }
  .wide .scrub {
    width: 72px;
    padding-left: var(--space-2);
    padding-right: var(--space-2);
    text-align: left;
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .wide input {
    text-align: right;
  }
  .scrubbing .scrub,
  .scrub:hover {
    color: var(--accent);
  }
  input {
    flex: 1 1 0;
    min-width: 0;
    width: 100%;
    background: transparent;
    border: 0;
    padding: var(--space-1) var(--space-1) var(--space-1) 0;
    font-size: var(--text-sm);
    font-variant-numeric: tabular-nums;
    outline: none;
  }
  input::placeholder {
    color: var(--faint);
  }
  em {
    flex: none;
    padding-right: var(--space-2);
    color: var(--faint);
    font-style: normal;
    font-size: var(--text-xs);
  }
</style>
