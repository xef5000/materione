<script lang="ts">
  /**
   * The display + modules studio body (issue 152): the resource's display
   * card (name, lore, the texture/model slot) plus one row per module the
   * schema and the server's registries describe. Rows are titles and markers
   * only; the selected module's settings open in the panel on the right
   * (issue 174), and only that one module is shown there at a time. Adding and
   * removing modules stages the same lossless field edits as the classic
   * sections — nothing here writes around the one pipeline (D1/D6).
   */
  import Field from './Field.svelte';
  import Icon from '../components/Icon.svelte';
  import BehaviorRulesEditor from './BehaviorRulesEditor.svelte';
  import ModulePicker from './ModulePicker.svelte';
  import ItemAppearance from './ItemAppearance.svelte';
  import { fade, fly } from 'svelte/transition';
  import { flip } from 'svelte/animate';
  import { tick } from 'svelte';
  import { motionMs, scrollBehavior } from '../motion';
  import type { DiagnosticEntry, ResourceRef, TypeInfo, TypeParam } from '../api';
  import type { BehaviorVocabulary, MechanicEntry } from '../behavior';
  import {
    addMechanic,
    authoredMechanicName,
    fullMechanicId,
    isBuiltInMechanic,
    mechanicHint,
    mechanicInfo,
    mechanicEntries,
    mechanicOrigin,
    removeAt,
    setMechanicParam,
  } from '../behavior';
  import {
    advancedFieldsOf,
    defaultFor,
    displayFieldsOf,
    extraFieldsOf,
    inheritedModules,
    moduleCatalog,
    presenceOf,
    type AppearanceHost,
    type InheritedModule,
    type ModuleDescriptor,
  } from '../modules';
  import type { Snippet } from 'svelte';
  import type { IconName } from '../components/icons';

  interface Props {
    resource: ResourceRef;
    schema: TypeInfo;
    vocabulary: BehaviorVocabulary | null;
    /** The draft's root values — the same record a classic save diffs. */
    values: Record<string, unknown>;
    host: string;
    /** The classic studio's per-field inherited/overrides line. */
    provenance: Snippet<[string]>;
    set: (field: string, value: unknown) => void;
    removeField: (field: string) => void;
    onError: (message: string) => void;
    focusField: (fieldId: string) => void;
    actionFor: (field: TypeParam) => { label: string; run: () => void } | undefined;
    missingTexture: (field: TypeParam) => string | null;
    fieldIssue: (fieldId: string) => DiagnosticEntry | undefined;
    /** What the template chain supplies per key, and which template supplies it (read-only). */
    inherited?: Record<string, unknown>;
    inheritedSources?: Record<string, string>;
    appearance?: AppearanceHost;
  }

  let {
    resource, schema, vocabulary, values, host, provenance,
    set, removeField, onError, focusField, actionFor, missingTexture, fieldIssue,
    inherited = {}, inheritedSources = {}, appearance,
  }: Props = $props();

  const fullCatalog = $derived(moduleCatalog(schema, vocabulary));
  /**
   * With the Appearance component, the appearance-category field modules (the
   * model style) are its options menu, not module rows.
   */
  const appearanceOptions = $derived(appearance
    ? fullCatalog.flatMap((module) => module.kind === 'field' && module.category === 'appearance' && module.field
      ? [module.field] : [])
    : []);
  const catalog = $derived(fullCatalog.filter((module) =>
    !(module.field && appearanceOptions.some((option) => option.id === module.field!.id))));
  const display = $derived(displayFieldsOf(schema));
  const extraFields = $derived(extraFieldsOf(schema));
  const advancedFields = $derived(advancedFieldsOf(schema));
  const fromTemplate = $derived(inheritedModules(catalog, values, inherited, inheritedSources));

  function overrideHere(field: string, value: unknown): void {
    run(() => set(field, plain(value)));
  }

  function shortId(id: string): string {
    return id.slice(id.lastIndexOf(':') + 1);
  }

  /** A detached copy of a draft value, safe to keep for an undo. */
  function plain<T>(value: T): T {
    return structuredClone($state.snapshot(value)) as T;
  }

  let pickerOpen = $state(false);
  /** Modules opened without a value yet (no key written until one is). */
  let expanded = $state<string[]>([]);
  /**
   * The module whose settings the panel shows: a row key or an inherited
   * module's key. Only one is ever selected, so only one module's settings
   * are rendered.
   */
  let selectedKey = $state<string | null>(null);
  /** The last removal, undoable from the list until the next add or removal. */
  let undoable = $state<{ label: string; undo: () => void } | null>(null);
  let listElement: HTMLUListElement | undefined = $state();
  let panel: HTMLElement | undefined = $state();

  type Card =
    | { kind: 'field' | 'rules'; key: string; descriptor: ModuleDescriptor }
    | { kind: 'mechanic'; key: string; descriptor: ModuleDescriptor; entry: MechanicEntry }
    | { kind: 'unknown'; key: string; sourceField: string; entry: MechanicEntry };

  /**
   * One row per present module instance. Authored mechanic entries the
   * server does not register (and unreadable entries) keep their rows with
   * their settings untouched — present is decided by the file, not the
   * registry.
   */
  const cards = $derived.by(() => {
    const out: Card[] = [];
    for (const descriptor of catalog) {
      const presence = presenceOf(descriptor, values);
      const opened = presence.present || expanded.includes(descriptor.id);
      if (descriptor.kind === 'mechanic') {
        if (!opened) continue;
        for (const entry of presence.entries) {
          out.push({ kind: 'mechanic', key: `${descriptor.id}#${entry.index}`, descriptor, entry });
        }
        continue;
      }
      if (opened) out.push({ kind: descriptor.kind, key: descriptor.id, descriptor });
    }
    for (const descriptor of catalog) {
      if (descriptor.kind !== 'mechanic' || !descriptor.sourceField) continue;
      const known = (id: string) =>
        catalog.some((other) => other.kind === 'mechanic' && other.mechanic?.id === id);
      for (const entry of mechanicEntries(values[descriptor.sourceField])) {
        const id = entry.name ? fullMechanicId(entry.name) : '';
        if (!id || !known(id)) {
          out.push({
            kind: 'unknown',
            key: `${descriptor.sourceField}#unknown#${entry.index}`,
            sourceField: descriptor.sourceField,
            entry,
          });
        }
      }
    }
    return out;
  });

  const selectedCard = $derived(cards.find((card) => card.key === selectedKey) ?? null);
  const selectedInherited = $derived(
    selectedCard ? null : fromTemplate.find((module) => module.key === selectedKey) ?? null,
  );

  function select(key: string): void {
    selectedKey = key;
  }

  /**
   * A row the user chose: when the panel is stacked below the list (narrow studios) and its top is
   * past the visible part of the modules area, bring it into view. Wide studios keep it beside the list.
   */
  function choose(key: string): void {
    select(key);
    void tick().then(() => {
      const area = panel?.parentElement;
      if (!panel || !area || panel.getBoundingClientRect().top < area.getBoundingClientRect().bottom - 80) return;
      panel.scrollIntoView({ block: 'start', behavior: scrollBehavior() });
    });
  }

  function isAdded(module: ModuleDescriptor): boolean {
    return presenceOf(module, values).present;
  }

  function run(change: () => void): void {
    try {
      change();
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e));
    }
  }

  /** Moves focus to the first control of the settings panel, once the selected module has rendered. */
  function focusPanel(): void {
    void tick().then(() => {
      const control = panel?.querySelector<HTMLElement>(
        'input:not([type="hidden"]), select, textarea, button, [contenteditable="true"]',
      );
      (control ?? panel)?.focus();
    });
  }

  /**
   * Problems → Fix and the repair links name a field: the module that owns it
   * is selected so its control can be focused. Returns false when no module
   * owns the field (display-card fields, the Appearance component, extras).
   */
  export function reveal(fieldId: string): boolean {
    const owner = ownerOf(fieldId);
    if (!owner) return false;
    selectedKey = owner;
    return true;
  }

  function ownerOf(fieldId: string): string | null {
    for (const card of cards) {
      if (card.kind === 'unknown') {
        if (card.sourceField === fieldId) return card.key;
      } else if (card.descriptor.kind === 'mechanic') {
        if (card.descriptor.sourceField === fieldId) return card.key;
      } else if (card.descriptor.field?.id === fieldId) {
        return card.key;
      }
    }
    return fromTemplate.find((module) => module.field === fieldId)?.key ?? null;
  }

  function addModule(module: ModuleDescriptor): void {
    pickerOpen = false;
    undoable = null;
    if (module.kind === 'mechanic' && module.mechanic) {
      const source = module.sourceField ?? 'mechanics';
      run(() => {
        const next = addMechanic(values[source], module.mechanic!);
        set(source, next);
        // The new ability is the last entry; its settings open in the panel.
        select(`${module.id}#${next.length - 1}`);
      });
      focusPanel();
      return;
    }
    const field = module.field!;
    const value = defaultFor(field);
    if (value === undefined) {
      // Nothing honest to write yet (a reference the author has not chosen):
      // open the module as an empty one; the first value sets the key.
      expanded = [...expanded, module.id];
      select(module.id);
      focusPanel();
      return;
    }
    run(() => {
      set(field.id, value);
      select(module.id);
    });
    focusPanel();
  }

  /** What undoing a removal of `field` writes: its value as it was. */
  function restorerFor(field: string): () => void {
    if (!Object.hasOwn(values, field)) return () => removeField(field);
    const before = plain(values[field]);
    return () => set(field, before);
  }

  function undo(): void {
    const last = undoable;
    undoable = null;
    if (last) run(last.undo);
  }

  function removeCard(card: Card): void {
    const label = cardLabel(card);
    if (card.kind === 'unknown') {
      const restore = restorerFor(card.sourceField);
      const current = values[card.sourceField];
      run(() => set(card.sourceField, removeAt(current, card.entry.index)));
      undoable = { label, undo: restore };
      // Entry keys carry the entry's position, so the siblings after it renumber.
      if (selectedKey !== null && isAbilityKey(selectedKey)) selectedKey = null;
      return;
    }
    if (card.kind === 'mechanic') {
      const source = card.descriptor.sourceField ?? 'mechanics';
      const restore = restorerFor(source);
      const next = removeAt(values[source], card.entry.index);
      // Removing the last ability removes the key — `mechanics: []` is noise.
      run(() => (next.length === 0 ? removeField(source) : set(source, next)));
      undoable = { label, undo: restore };
      if (selectedKey !== null && isAbilityKey(selectedKey)) selectedKey = null;
      return;
    }
    expanded = expanded.filter((id) => id !== card.descriptor.id);
    if (selectedKey === card.key) selectedKey = null;
    if (presenceOf(card.descriptor, values).present) {
      const restore = restorerFor(card.descriptor.id);
      run(() => removeField(card.descriptor.id));
      undoable = { label, undo: restore };
    }
  }

  function isAbilityKey(key: string): boolean {
    return key.startsWith('mechanic:') || key.includes('#unknown#');
  }

  function cardLabel(card: Card): string {
    if (card.kind !== 'unknown') return card.descriptor.label;
    return card.entry.name ? authoredMechanicName(fullMechanicId(card.entry.name)) : 'Unreadable entry';
  }

  function cardIcon(card: Card): IconName {
    return (card.kind === 'unknown' ? 'circle' : card.descriptor.icon) as IconName;
  }

  /**
   * The row's error marker: a diagnostic names a field module's field, or its
   * texture is missing. A diagnostic on the abilities list names no entry, so
   * abilities carry no row marker (the module cards this replaced never showed one either).
   */
  function hasProblem(card: Card): boolean {
    if (card.kind !== 'field' && card.kind !== 'rules') return false;
    const field = card.descriptor.field;
    return field ? Boolean(fieldIssue(field.id) || missingTexture(field)) : false;
  }

  /** The field an ability's settings answer to: a diagnostic's `mechanics` target focuses the first ability. */
  function entryField(card: Card): string {
    return card.kind === 'unknown' ? card.sourceField : card.descriptor.sourceField ?? 'mechanics';
  }

  function paramValue(params: Record<string, unknown>, param: TypeParam): unknown {
    return Object.hasOwn(params, param.id) ? params[param.id] : undefined;
  }

  /** Rows: arrows move (and select) through the list, Enter opens the settings in the panel. */
  function rowButtons(): HTMLButtonElement[] {
    return Array.from(listElement?.querySelectorAll<HTMLButtonElement>('[data-row-key]') ?? []);
  }

  function onRowKey(event: KeyboardEvent, key: string): void {
    if (event.defaultPrevented || event.isComposing) return;
    if (event.key === 'Enter') {
      event.preventDefault();
      select(key);
      focusPanel();
      return;
    }
    const rows = rowButtons();
    const index = rows.findIndex((row) => row.dataset.rowKey === key);
    let target = -1;
    if (event.key === 'ArrowDown') target = Math.min(index + 1, rows.length - 1);
    else if (event.key === 'ArrowUp') target = Math.max(index - 1, 0);
    else if (event.key === 'Home') target = 0;
    else if (event.key === 'End') target = rows.length - 1;
    const next = rows[target];
    if (!next || target === index) return;
    event.preventDefault();
    next.focus();
    select(next.dataset.rowKey ?? key);
  }

  /** Escape in the panel returns focus to the selected row (the studio's own Escape stays off). */
  function onPanelKey(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    event.preventDefault();
    const rows = rowButtons();
    (rows.find((row) => row.dataset.rowKey === selectedKey) ?? rows[0])?.focus();
  }
</script>

<section class="modules" aria-label="Display and modules">
  <div class="list">
    {#if appearance}
      <ItemAppearance
        pack={resource.pack}
        {values}
        {inherited}
        name={display.name}
        lore={display.lore}
        texture={display.slots.find((slot) => slot.uiHint === 'texture')}
        model={display.slots.find((slot) => slot.uiHint === 'model')}
        options={appearanceOptions}
        visual={appearance.visual}
        render={appearance.render}
        material={appearance.material}
        version={appearance.version}
        {set}
        {removeField}
        {provenance}
        {missingTexture}
        {fieldIssue}
        onDrawTexture={appearance.onDrawTexture}
        onEditModel={appearance.onEditModel}
      />
    {:else}
    <article class="card display" aria-label="Display">
      {#if display.name}
        <Field field={display.name} value={values[display.name.id]} pack={resource.pack}
          onChange={(next) => set(display.name!.id, next)} />
        {@render provenance(display.name.id)}
      {/if}
      {#if display.lore}
        <Field field={display.lore} value={values[display.lore.id]} pack={resource.pack}
          onChange={(next) => set(display.lore!.id, next)} />
        {@render provenance(display.lore.id)}
      {/if}
      {#each display.slots as field (field.id)}
        <Field {field} value={values[field.id]} pack={resource.pack}
          onChange={(next) => set(field.id, next)} action={actionFor(field)} />
        {@render provenance(field.id)}
        {@const missing = missingTexture(field)}
        {#if missing}
          {@const issue = fieldIssue(field.id)}
          <div class="repair" role="group" aria-label={`Repair ${field.label || field.id}`}>
            <p>
              <strong>Missing texture.</strong> {missing} does not exist in this pack{issue ? ` (${issue.code})` : ''}, so
              the game shows a missing-texture pattern.
            </p>
            <button type="button" onclick={() => actionFor(field)?.run()}>Draw it now</button>
            <button type="button" onclick={() => focusField(field.id)}>Choose another texture</button>
          </div>
        {/if}
      {/each}
    </article>
    {/if}

    <ul class="rows" aria-label="Modules" bind:this={listElement}>
      {#each cards as card (card.key)}
        {@const removable = card.kind === 'unknown' || card.descriptor.kind === 'mechanic' || card.descriptor.required !== true}
        <li
          animate:flip={{ duration: motionMs('base') }}
          in:fly={{ y: -4, duration: motionMs('base') }}
          out:fade={{ duration: motionMs('fast') }}
          class="row-item"
          data-module={card.kind === 'unknown' ? card.entry.name || 'unknown' : card.descriptor.id}
        >
          <div class="row" class:selected={selectedKey === card.key}>
            <button
              type="button"
              class="row-main"
              data-row-key={card.key}
              aria-current={selectedKey === card.key ? 'true' : undefined}
              onclick={() => choose(card.key)}
              onkeydown={(event) => onRowKey(event, card.key)}
            >
              <Icon name={cardIcon(card)} size={16} />
              <span class="row-label">{cardLabel(card)}</span>
              {#if hasProblem(card)}
                <span class="marker problem" role="img" aria-label="Has a problem"><Icon name="triangle-alert" size={14} /></span>
              {/if}
            </button>
            {#if removable}
              <button type="button" class="row-remove" title={`Remove ${cardLabel(card)}`}
                aria-label={`Remove ${cardLabel(card)}`} onclick={() => removeCard(card)}>
                <Icon name="trash-2" size={15} />
              </button>
            {/if}
          </div>
        </li>
      {/each}

      {#each fromTemplate as module (module.key)}
        <li class="row-item" data-inherited-module={module.key}>
          <div class="row inherited" class:selected={selectedKey === module.key}>
            <button
              type="button"
              class="row-main"
              data-row-key={module.key}
              aria-current={selectedKey === module.key ? 'true' : undefined}
              onclick={() => choose(module.key)}
              onkeydown={(event) => onRowKey(event, module.key)}
            >
              <Icon name={module.icon} size={16} />
              <span class="row-label">{module.label}</span>
              <span class="marker">Inherited</span>
            </button>
          </div>
        </li>
      {/each}
    </ul>

    <button type="button" class="add" onclick={() => (pickerOpen = true)}>
      <Icon name="plus" size={16} />
      Add module
    </button>
    {#if undoable}
      <div class="undo" role="status">
        <span>Removed {undoable.label}</span>
        <button type="button" onclick={undo}>Undo</button>
      </div>
    {/if}

    {#if extraFields.length > 0}
      <section class="extras" aria-label="More fields">
        {#each extraFields as field (field.id)}
          <Field {field} value={values[field.id]} pack={resource.pack}
            onChange={(next) => set(field.id, next)} action={actionFor(field)} />
          {@render provenance(field.id)}
        {/each}
      </section>
    {/if}

    {#if advancedFields.length > 0}
      <details class="advanced">
        <summary>Advanced ({advancedFields.length})</summary>
        {#each advancedFields as field (field.id)}
          <Field {field} value={values[field.id]} pack={resource.pack}
            onChange={(next) => set(field.id, next)} />
          {@render provenance(field.id)}
        {/each}
      </details>
    {/if}
  </div>

  <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
  <section class="panel" class:empty={!selectedCard && !selectedInherited} aria-label="Module settings" tabindex="-1" bind:this={panel} onkeydown={onPanelKey}>
    {#key selectedKey}
      <div class="settings" in:fade={{ duration: motionMs('fast') }}>
        {#if selectedCard}
          {@const card = selectedCard}
          {@const info = card.kind === 'mechanic' && card.entry.name ? mechanicInfo(vocabulary, card.entry.name) : null}
          <div class="ability" data-field={card.kind === 'field' || card.kind === 'rules' ? undefined : entryField(card)}>
            <header>
              <Icon name={cardIcon(card)} size={16} />
              <strong title={card.kind === 'unknown' ? undefined : mechanicHint(info)}>{cardLabel(card)}</strong>
              {#if info}
                {#if !isBuiltInMechanic(info)}<span class="origin">{mechanicOrigin(info)}</span>{/if}
              {:else if card.kind === 'unknown' && card.entry.name}
                <span class="origin">{fullMechanicId(card.entry.name)} · not registered on this server</span>
              {:else if card.kind === 'unknown'}
                <span class="origin">not a single ability mapping</span>
              {/if}
            </header>
            {#if card.kind === 'mechanic'}
              {#if info}
                {#each info.parameters ?? [] as param (param.id)}
                  <Field field={param} value={paramValue(card.entry.params, param)} pack={resource.pack}
                    onChange={(next) => run(() => set(card.descriptor.sourceField ?? 'mechanics',
                      setMechanicParam(values[card.descriptor.sourceField ?? 'mechanics'], card.entry.index, param.id, next)))} />
                {/each}
                {#if Object.keys(card.entry.params).some((key) => !(info.parameters ?? []).some((param) => param.id === key))}
                  <p class="note">Other settings on this ability are kept as written; edit them in Files.</p>
                {/if}
              {:else}
                <p class="note">Install or enable the plugin that provides it; its settings are kept as written and can be edited in Files.</p>
              {/if}
            {:else if card.kind === 'unknown'}
              <p class="note">
                {card.entry.name
                  ? 'Install or enable the plugin that provides it; its settings are kept as written and can be edited in Files.'
                  : 'Each entry must be one ability name with its settings; edit this one in Files.'}
              </p>
            {:else if card.descriptor.kind === 'rules' && card.descriptor.field}
              <BehaviorRulesEditor field={card.descriptor.field} value={values[card.descriptor.field.id]}
                pack={resource.pack} {host} vocabulary={vocabulary}
                onChange={(next) => set(card.descriptor.field!.id, next)} />
            {:else if card.descriptor.field}
              <Field field={card.descriptor.field} value={values[card.descriptor.field.id]} pack={resource.pack} hideLabel
                onChange={(next) => set(card.descriptor.field!.id, next)} action={actionFor(card.descriptor.field)} />
              {@render provenance(card.descriptor.field.id)}
              {@const issue = fieldIssue(card.descriptor.field.id)}
              {#if issue}
                <p class="field-issue" role="note">{issue.code}: {issue.message}{issue.hint ? ` — ${issue.hint}` : ''}</p>
              {/if}
            {/if}
          </div>
        {:else if selectedInherited}
          {@const module = selectedInherited}
          <header>
            <Icon name={module.icon} size={16} />
            <strong>{module.label}</strong>
            <span class="origin">Inherited from {shortId(module.source)}</span>
          </header>
          <p class="note" data-inherited-summary>{module.summary}</p>
          <button type="button" class="action" onclick={() => overrideHere(module.field, module.value)}
            title="Copy the template's value into this resource so you can change it">
            Override here
          </button>
        {/if}
      </div>
    {/key}
  </section>
</section>

{#if pickerOpen}
  <ModulePicker {catalog} {isAdded} onClose={() => (pickerOpen = false)} onPick={addModule} />
{/if}

<style>
  /* Two columns: the list (Appearance on top, then the modules) and the settings panel. Each scrolls on its own. */
  .modules {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(300px, 320px);
    grid-template-rows: minmax(0, 1fr);
    gap: var(--space-5);
    flex: 1 1 0;
    min-width: 0;
    min-height: 0;
  }
  .list {
    display: grid;
    align-content: start;
    gap: var(--space-3);
    min-width: 0;
    min-height: 0;
    overflow-y: auto;
    padding-right: var(--space-2);
  }
  .panel {
    min-width: 0;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-3) var(--space-4);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--bg-panel);
  }
  .panel:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  /* Nothing selected: the column stays reserved, but draws no empty box. */
  .panel.empty {
    border-color: transparent;
    background: none;
  }
  .settings,
  .ability {
    display: grid;
    align-content: start;
    gap: var(--space-2);
    min-width: 0;
  }
  /* Narrow studios stack the panel under the list; the whole area scrolls instead. A media query, not a
     container query: a container ancestor would also contain the fixed rich-text toolbars. */
  @media (max-width: 1100px) {
    .modules {
      grid-template-columns: minmax(0, 1fr);
      grid-template-rows: auto;
      overflow-y: auto;
    }
    .list,
    .panel {
      overflow: visible;
      min-height: auto;
    }
  }
  .card {
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: var(--space-3) var(--space-4);
    min-width: 0;
    background: var(--bg-panel);
  }
  .display {
    display: grid;
    gap: var(--space-2);
    background: var(--bg-raised);
    border-color: var(--border-strong);
  }
  .panel header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    margin-bottom: var(--space-1);
  }
  .panel header :global(svg) {
    color: var(--muted);
    flex: none;
  }
  .panel header strong {
    font-size: var(--text-md);
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .origin {
    min-width: 0;
    color: var(--faint);
    font-size: var(--text-xs);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .rows {
    display: grid;
    gap: var(--space-1);
    margin: 0;
    padding: 0;
    list-style: none;
    min-width: 0;
  }
  .row-item {
    min-width: 0;
  }
  .row {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    min-width: 0;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--bg-panel);
    transition:
      background-color var(--motion-fast) var(--ease-standard),
      border-color var(--motion-fast) var(--ease-standard);
  }
  .row.inherited {
    border-style: dashed;
  }
  .row:hover {
    background: var(--bg-hover);
  }
  .row.selected {
    border-color: var(--accent);
    background: var(--accent-low);
  }
  .row-main {
    flex: 1 1 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    padding: var(--space-2) var(--space-3);
    border: none;
    background: none;
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
  }
  .row-main :global(svg) {
    color: var(--muted);
    flex: none;
  }
  .row.selected .row-main :global(svg) {
    color: var(--fg);
  }
  .row-main:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: -2px;
    border-radius: var(--radius);
  }
  .row-label {
    min-width: 0;
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .marker {
    flex: none;
    color: var(--faint);
    font-size: var(--text-xs);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    padding: 0 var(--space-2);
    white-space: nowrap;
  }
  .marker.problem {
    display: inline-grid;
    place-items: center;
    padding: 0 var(--space-1);
    color: var(--warn);
    border-color: var(--warn);
  }
  .row-remove {
    flex: none;
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    margin-right: var(--space-2);
    border: 1px solid transparent;
    border-radius: var(--radius);
    background: none;
    color: var(--muted);
    cursor: pointer;
  }
  .row-remove:hover {
    border-color: var(--border-strong);
    background: var(--bg-hover);
    color: var(--fg);
  }
  .row-remove:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 1px;
  }
  .note {
    margin: 0 0 var(--space-2);
    font-size: var(--text-sm);
    color: var(--muted);
  }
  .field-issue {
    margin: 0;
    font-size: var(--text-xs);
    color: var(--warn);
  }
  .repair {
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--warn);
    border-radius: var(--radius);
    font-size: var(--text-sm);
  }
  .repair p {
    margin: 0 0 var(--space-2);
    overflow-wrap: anywhere;
  }
  .repair button:not(.btn) {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
    margin-right: var(--space-2);
  }
  .action,
  .undo button {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-half) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
    justify-self: start;
  }
  .action:hover,
  .undo button:hover {
    background: var(--bg-hover);
  }
  .undo {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-sm);
    color: var(--muted);
    min-width: 0;
  }
  .add {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    border: 1px dashed var(--control-line);
    background: none;
    color: var(--muted);
    border-radius: var(--radius);
    padding: var(--space-2);
    font-size: var(--text-base);
    cursor: pointer;
  }
  .add:hover {
    background: var(--bg-hover);
    color: inherit;
  }
  .extras {
    display: grid;
    gap: var(--space-2);
    padding: var(--space-2) 0 0;
    border-top: 1px solid var(--border);
    min-width: 0;
  }
  .advanced {
    border-top: 1px solid var(--border);
    padding-top: var(--space-2);
    min-width: 0;
  }
  .advanced summary {
    color: var(--faint);
    font-size: var(--text-sm);
    cursor: pointer;
    margin-bottom: var(--space-2);
  }
  .advanced[open] summary {
    color: var(--muted);
  }
</style>
