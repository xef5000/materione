<script lang="ts">
  /**
   * The inspector. Nothing here knows a component by name: the fields come from
   * the registry's description of the selected layer's component (the same rule
   * as the item studio's modules, D5), rendered with bespoke controls where a
   * plain input would be a poor fit: scrub numbers for geometry, color fields
   * with hex for styles, segmented choices for small enums. What the registry
   * does not describe is reported, never silently dropped or rewritten.
   */
  import Icon from '../../components/Icon.svelte';
  import type { TypeParam } from '../../api';
  import { isRecord, isHidden, isLocked, layerLabel, type GuiLayer, type Json } from '../../gui/model';
  import * as ops from '../../gui/ops';
  import { transformability, componentIcon } from '../../gui/registry';
  import type { GuiStudioState } from '../../gui/studio.svelte';
  import { unionOf } from '../../gui/transform';
  import { MAX_ROWS, MIN_ROWS, canvasSize } from '../../gui/chest';
  import { formatColor } from '../../gui/raster/color';
  import { RAISED_DEFAULT, SLOT_DEFAULT, bareType } from '../../gui/raster/flatten';
  import ColorField from './ColorField.svelte';
  import ScrubNumber from './ScrubNumber.svelte';

  interface Props {
    studio: GuiStudioState;
    /** Texture references the pack offers (assets/textures/**.png), for image fields. */
    textures: string[];
  }

  let { studio, textures }: Props = $props();

  const GEOMETRY = ['x', 'y', 'width', 'height'];
  const selected = $derived(studio.selected);
  const layer = $derived(selected.length === 1 ? selected[0]! : null);
  const info = $derived(layer ? studio.infoOf(layer) : undefined);
  const can = $derived(layer ? transformability(info, layer) : null);
  const bounds = $derived(layer ? studio.boundsOf(layer) : null);
  const target = $derived(layer ? (info?.target ?? 'raster') : 'raster');
  const rows = $derived(studio.doc.rows);
  const locked = $derived(layer ? isLocked(layer) : false);
  const disabled = $derived(studio.readOnly || locked);

  const groupBounds = $derived(
    selected.length > 1
      ? unionOf(selected.map((entry) => studio.boundsOf(entry)).filter((rect) => rect !== null))
      : null,
  );
  const groupMovable = $derived(selected.length > 1 && selected.every((entry) => transformability(studio.infoOf(entry), entry).movable && !transformability(studio.infoOf(entry), entry).slotBound));

  const params = $derived.by<TypeParam[]>(() => {
    if (!layer || !info) return [];
    const geometryShown = can?.movable && !can.slotBound;
    return info.parameters
      .filter((param) => !(geometryShown && GEOMETRY.includes(param.id)))
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
  });

  const unknownKeys = $derived.by<string[]>(() => {
    if (!layer) return [];
    const described = new Set([...(info?.parameters.map((param) => param.id) ?? []), 'id', 'type', 'name', 'visible', 'locked', 'opacity']);
    return Object.keys(layer).filter((key) => !described.has(key));
  });

  function geometry(field: 'x' | 'y' | 'w' | 'h'): number {
    const rect = bounds;
    return rect ? rect[field] : 0;
  }

  function setGeometry(field: 'x' | 'y' | 'width' | 'height', value: number): void {
    if (!layer || !bounds) return;
    studio.setField(layer.id, field, value);
  }

  function moveGroup(axis: 'x' | 'y', value: number): void {
    if (!groupBounds) return;
    const delta = value - groupBounds[axis];
    if (delta === 0) return;
    studio.run(ops.nudge(studio.doc, studio.scene, studio.selection, axis === 'x' ? delta : 0, axis === 'y' ? delta : 0));
  }

  function opacityOf(l: GuiLayer): number {
    return typeof l.opacity === 'number' ? l.opacity : 100;
  }

  function setOpacity(value: number): void {
    const next = Math.min(100, Math.max(0, Math.round(value)));
    for (const id of studio.selection) studio.setField(id, 'opacity', next === 100 ? undefined : next, 'opacity');
  }

  function styleOf(l: GuiLayer, id: string): Json {
    const value = l[id];
    return isRecord(value) ? value : {};
  }

  function setStyle(l: GuiLayer, id: string, key: string, value: string | undefined): void {
    const next: Json = { ...styleOf(l, id), [key]: value };
    if (value === undefined) delete next[key];
    studio.setField(l.id, id, Object.keys(next).length ? next : undefined);
  }

  function choices(param: TypeParam): { value: string; label: string }[] {
    return param.options?.length
      ? param.options.map((option) => ({ value: option.value, label: option.label }))
      : (param.allowedValues ?? []).map((value) => ({ value, label: value }));
  }

  function kindOf(param: TypeParam): 'style' | 'color' | 'choice' | 'number' | 'texture' | 'toggle' | 'text' {
    // the bevel style is a raw map in the schema; its four colors are spec (gui.md section 4), not schema
    if (param.id === 'style') return 'style';
    if (param.uiHint === 'color' || param.id === 'color') return 'color';
    if (param.uiHint === 'select' || param.allowedValues?.length) return 'choice';
    if (param.type === 'integer' || param.type === 'decimal' || param.uiHint === 'number') return 'number';
    if (param.uiHint === 'texture') return 'texture';
    if (param.type === 'boolean' || param.uiHint === 'toggle') return 'toggle';
    return 'text';
  }

  /** The bevel style's colors and the vanilla look an unset one falls back to (gui.md section 4). */
  function styleParts(l: GuiLayer): { id: string; label: string; fallback: string }[] {
    const slots = bareType(l) === 'slots';
    const base = slots ? SLOT_DEFAULT : RAISED_DEFAULT;
    const parts = [['fill', 'Fill'], ['highlight', 'Highlight'], ['shadow', 'Shadow'], ['outline', 'Outline']] as const;
    return parts
      .filter(([id]) => !(slots && id === 'outline'))
      .map(([id, label]) => ({ id, label, fallback: formatColor(base[id]) }));
  }

  const rowCounts = Array.from({ length: MAX_ROWS - MIN_ROWS + 1 }, (_, index) => MIN_ROWS + index);
  const sizeText = $derived(`${canvasSize(rows).width} x ${canvasSize(rows).height} px`);
</script>

<aside class="inspector" aria-label="Inspector">
  {#if selected.length === 0}
    <section class="group">
      <h3>Screen</h3>
      <label class="field">
        <span>Title</span>
        <input
          type="text"
          value={studio.doc.title}
          disabled={studio.readOnly}
          spellcheck="false"
          placeholder="Shown to screen readers"
          oninput={(event) => studio.setTitle(event.currentTarget.value)}
        />
      </label>
      <div class="field">
        <span>Rows</span>
        <div class="segmented" role="group" aria-label="Rows">
          {#each rowCounts as count (count)}
            <button type="button" class:on={rows === count} disabled={studio.readOnly} aria-pressed={rows === count} onclick={() => studio.setRows(count)}>{count}</button>
          {/each}
        </div>
      </div>
      <p class="readout"><Icon name="ruler" size={14} /> {sizeText}</p>
      <p class="hint">Select a layer on the canvas or in the list to edit it.</p>
    </section>
  {:else if layer}
    <header class="head">
      <span class="glyph"><Icon name={componentIcon(info)} size={18} /></span>
      <div class="titles">
        <strong>{layerLabel(layer)}</strong>
        <code>{layer.id} · {info?.displayName ?? layer.type}</code>
      </div>
      <button type="button" class="icon" disabled={studio.readOnly} title={isHidden(layer) ? 'Show' : 'Hide'} aria-label="{isHidden(layer) ? 'Show' : 'Hide'} layer"
        onclick={() => studio.run(ops.setVisible(studio.doc, layer.id, isHidden(layer)))}><Icon name={isHidden(layer) ? 'eye-off' : 'eye'} size={16} /></button>
      <button type="button" class="icon" class:on={locked} disabled={studio.readOnly} title={locked ? 'Unlock' : 'Lock'} aria-label="{locked ? 'Unlock' : 'Lock'} layer"
        onclick={() => studio.run(ops.setLocked(studio.doc, layer.id, !locked))}><Icon name={locked ? 'lock' : 'lock-open'} size={16} /></button>
    </header>
    {#if locked}<p class="note"><Icon name="lock" size={14} /> Locked: unlock to edit.</p>{/if}

    {#if can?.movable && !can.slotBound}
      <section class="group">
        <h3>Transform</h3>
        <div class="grid">
          <ScrubNumber label="X" value={geometry('x')} {disabled} onchange={(value) => setGeometry('x', value)} />
          <ScrubNumber label="Y" value={geometry('y')} {disabled} onchange={(value) => setGeometry('y', value)} />
          {#if can.resizable}
            <ScrubNumber label="W" value={geometry('w')} min={1} {disabled} onchange={(value) => setGeometry('width', value)} />
            <ScrubNumber label="H" value={geometry('h')} min={1} {disabled} onchange={(value) => setGeometry('height', value)} />
          {/if}
        </div>
      </section>
    {/if}

    {#each params as param (param.id)}
      {@const kind = kindOf(param)}
      {@const raw = layer[param.id]}
      <section class="group">
        {#if kind === 'style'}
          <div class="titled">
            <h3>{param.label ?? 'Style'}</h3>
            <button type="button" class="link" disabled={disabled || raw === undefined} onclick={() => studio.setField(layer.id, param.id, undefined)}>Reset</button>
          </div>
          {#each styleParts(layer) as part (part.id)}
            <ColorField
              label={part.label}
              value={typeof styleOf(layer, param.id)[part.id] === 'string' ? (styleOf(layer, param.id)[part.id] as string) : undefined}
              fallback={part.fallback}
              {disabled}
              onchange={(value) => setStyle(layer, param.id, part.id, value)}
            />
          {/each}
        {:else if kind === 'color'}
          <ColorField
            label={param.label ?? param.id}
            value={typeof raw === 'string' ? raw : undefined}
            fallback={param.default ?? ''}
            {disabled}
            onchange={(value) => studio.setField(layer.id, param.id, value)}
          />
        {:else if kind === 'choice'}
          <div class="field">
            <span>{param.label ?? param.id}</span>
            <div class="segmented" role="group" aria-label={param.label ?? param.id}>
              {#each choices(param) as choice (choice.value)}
                {@const on = (typeof raw === 'string' ? raw : (param.default ?? '')) === choice.value}
                <button type="button" class:on {disabled} aria-pressed={on} onclick={() => studio.setField(layer.id, param.id, choice.value)}>{choice.label}</button>
              {/each}
            </div>
          </div>
        {:else if kind === 'number'}
          <ScrubNumber
            wide
            label={param.label ?? param.id}
            value={typeof raw === 'number' ? raw : Number(param.default ?? 0)}
            min={param.min ?? -9999}
            max={param.max ?? 9999}
            {disabled}
            onchange={(value) => studio.setField(layer.id, param.id, value)}
          />
        {:else if kind === 'texture'}
          <label class="field">
            <span>{param.label ?? param.id}</span>
            <input
              type="text"
              list="gui-textures"
              spellcheck="false"
              value={typeof raw === 'string' ? raw : ''}
              disabled={disabled || bareType(layer) === 'paint'}
              placeholder="gui/example"
              oninput={(event) => studio.setField(layer.id, param.id, event.currentTarget.value)}
            />
          </label>
        {:else if kind === 'toggle'}
          <label class="check">
            <input type="checkbox" checked={raw === true} {disabled} onchange={(event) => studio.setField(layer.id, param.id, event.currentTarget.checked)} />
            <span>{param.label ?? param.id}</span>
          </label>
        {:else}
          <label class="field">
            <span>{param.label ?? param.id}</span>
            <input
              type="text"
              spellcheck="false"
              value={typeof raw === 'string' ? raw : ''}
              {disabled}
              placeholder={param.example ?? ''}
              oninput={(event) => studio.setField(layer.id, param.id, event.currentTarget.value)}
            />
          </label>
        {/if}
      </section>
    {/each}

    {#if target === 'raster'}
      <section class="group">
        <h3>Opacity</h3>
        <div class="opacity">
          <input type="range" min="0" max="100" aria-label="Opacity" value={opacityOf(layer)} {disabled} oninput={(event) => setOpacity(Number(event.currentTarget.value))} />
          <ScrubNumber label="%" value={opacityOf(layer)} min={0} max={100} {disabled} onchange={setOpacity} />
        </div>
      </section>
    {/if}

    {#if unknownKeys.length}
      <p class="note"><Icon name="info" size={14} /> {unknownKeys.length} field{unknownKeys.length === 1 ? '' : 's'} ({unknownKeys.join(', ')}) only in Advanced Files. Kept as they are.</p>
    {/if}
    {#if !info}
      <p class="note"><Icon name="triangle-alert" size={14} /> The server does not know the component "{layer.type}". The layer is kept as it is.</p>
    {/if}
  {:else}
    <header class="head">
      <span class="glyph"><Icon name="layers" size={18} /></span>
      <div class="titles">
        <strong>{selected.length} layers</strong>
        <code>multiple selection</code>
      </div>
    </header>
    {#if groupBounds && groupMovable}
      <section class="group">
        <h3>Transform</h3>
        <div class="grid">
          <ScrubNumber label="X" value={groupBounds.x} disabled={studio.readOnly} onchange={(value) => moveGroup('x', value)} />
          <ScrubNumber label="Y" value={groupBounds.y} disabled={studio.readOnly} onchange={(value) => moveGroup('y', value)} />
        </div>
      </section>
    {/if}
    <section class="group">
      <h3>Opacity</h3>
      <div class="opacity">
        <input type="range" min="0" max="100" aria-label="Opacity" value={opacityOf(selected[0]!)} disabled={studio.readOnly} oninput={(event) => setOpacity(Number(event.currentTarget.value))} />
        <ScrubNumber label="%" value={opacityOf(selected[0]!)} min={0} max={100} disabled={studio.readOnly} mixed={new Set(selected.map(opacityOf)).size > 1} onchange={setOpacity} />
      </div>
    </section>
    <div class="actions">
      <button type="button" class="btn" disabled={studio.readOnly} onclick={() => studio.duplicateSelection()}><Icon name="copy" size={14} /> Duplicate</button>
      <button type="button" class="btn danger" disabled={studio.readOnly} onclick={() => studio.deleteSelection()}><Icon name="trash-2" size={14} /> Delete</button>
    </div>
  {/if}
  <datalist id="gui-textures">
    {#each textures as reference (reference)}<option value={reference}></option>{/each}
  </datalist>
</aside>

<style>
  .inspector {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    min-width: 0;
    height: 100%;
    overflow-y: auto;
    overflow-x: hidden;
  }
  .head {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  .glyph {
    flex: none;
    display: inline-grid;
    place-items: center;
    width: 32px;
    height: 32px;
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--accent);
  }
  .titles {
    flex: 1 1 0;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .titles strong,
  .titles code {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .titles strong {
    font-size: var(--text-md);
  }
  .titles code {
    color: var(--faint);
    font-family: var(--mono);
    font-size: var(--text-xs);
  }
  .icon {
    flex: none;
    display: inline-grid;
    place-items: center;
    width: 28px;
    height: 28px;
    background: transparent;
    border: 0;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .icon:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .icon.on {
    color: var(--accent);
  }
  .group {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  h3 {
    margin: 0;
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }
  .titled {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .link {
    background: transparent;
    border: 0;
    color: var(--accent);
    font-size: var(--text-xs);
    cursor: pointer;
  }
  .link:disabled {
    color: var(--faint);
    cursor: default;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-2);
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    min-width: 0;
  }
  .field > span {
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .field input[type='text'] {
    width: 100%;
    min-width: 0;
    box-sizing: border-box;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
    outline: none;
  }
  .field input[type='text']:focus {
    border-color: var(--accent);
  }
  .segmented {
    display: flex;
    min-width: 0;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    overflow: hidden;
  }
  .segmented button {
    flex: 1 1 0;
    min-width: 0;
    padding: var(--space-1) var(--space-2);
    background: var(--bg-input);
    border: 0;
    border-right: 1px solid var(--border-strong);
    color: var(--muted);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .segmented button:last-child {
    border-right: 0;
  }
  .segmented button:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .segmented button.on {
    background: var(--accent-low);
    color: var(--accent-hover);
    font-weight: 600;
  }
  .opacity {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 88px);
    align-items: center;
    gap: var(--space-3);
  }
  .opacity input[type='range'] {
    min-width: 0;
    width: 100%;
    accent-color: var(--accent);
  }
  .check {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-sm);
  }
  .readout {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
    font-variant-numeric: tabular-nums;
  }
  .hint {
    margin: 0;
    color: var(--faint);
    font-size: var(--text-sm);
  }
  .note {
    display: flex;
    align-items: flex-start;
    gap: var(--space-2);
    margin: 0;
    padding: var(--space-2) var(--space-3);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .actions {
    display: flex;
    gap: var(--space-2);
    padding: var(--space-3);
  }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .btn:hover:not(:disabled) {
    background: var(--bg-hover);
  }
  .btn.danger:hover:not(:disabled) {
    border-color: var(--error);
    color: var(--error);
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
