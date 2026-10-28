<script lang="ts">
  /**
   * The display + modules studio body (issue 152): the resource's display
   * card (name, lore, the texture/model slot) plus one card per module the
   * schema and the server's registries describe. Adding and removing modules
   * stages the same lossless field edits as the classic sections — nothing
   * here writes around the one pipeline (D1/D6).
   */
  import Field from './Field.svelte';
  import Icon from '../components/Icon.svelte';
  import BehaviorRulesEditor from './BehaviorRulesEditor.svelte';
  import ModulePicker from './ModulePicker.svelte';
  import { fade, fly } from 'svelte/transition';
  import { flip } from 'svelte/animate';
  import { motionMs } from '../motion';
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
    showInFile: () => void;
    /** What the template chain supplies per key, and which template supplies it (read-only). */
    inherited?: Record<string, unknown>;
    inheritedSources?: Record<string, string>;
  }

  let {
    resource, schema, vocabulary, values, host, provenance,
    set, removeField, onError, focusField, actionFor, missingTexture, fieldIssue, showInFile,
    inherited = {}, inheritedSources = {},
  }: Props = $props();

  const catalog = $derived(moduleCatalog(schema, vocabulary));
  const display = $derived(displayFieldsOf(schema));
  const extraFields = $derived(extraFieldsOf(schema));
  const advancedFields = $derived(advancedFieldsOf(schema));
  const fromTemplate = $derived(inheritedModules(catalog, values, inherited, inheritedSources));

  function overrideHere(field: string, value: unknown): void {
    run(() => set(field, structuredClone($state.snapshot(value))));
  }

  function shortId(id: string): string {
    return id.slice(id.lastIndexOf(':') + 1);
  }

  let pickerOpen = $state(false);
  /** Modules opened without a value yet (no key written until one is). */
  let expanded = $state<string[]>([]);

  type Card =
    | { kind: 'field' | 'rules'; key: string; descriptor: ModuleDescriptor }
    | { kind: 'mechanic'; key: string; descriptor: ModuleDescriptor; entry: MechanicEntry }
    | { kind: 'unknown'; key: string; sourceField: string; entry: MechanicEntry };

  /**
   * One card per present module instance. Authored mechanic entries the
   * server does not register (and unreadable entries) keep their cards with
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

  function addModule(module: ModuleDescriptor): void {
    pickerOpen = false;
    if (module.kind === 'mechanic' && module.mechanic) {
      const source = module.sourceField ?? 'mechanics';
      run(() => set(source, addMechanic(values[source], module.mechanic!)));
      return;
    }
    const field = module.field!;
    const value = defaultFor(field);
    if (value === undefined) {
      // Nothing honest to write yet (a reference the author has not chosen):
      // open the card; the first value sets the key.
      expanded = [...expanded, module.id];
      queueMicrotask(() => focusField(field.id));
      return;
    }
    run(() => set(field.id, value));
    queueMicrotask(() => focusField(field.id));
  }

  function removeCard(card: Card): void {
    if (card.kind === 'unknown') {
      const current = values[card.sourceField];
      run(() => set(card.sourceField, removeAt(current, card.entry.index)));
      return;
    }
    if (card.kind === 'mechanic') {
      const source = card.descriptor.sourceField ?? 'mechanics';
      const next = removeAt(values[source], card.entry.index);
      // Removing the last ability removes the key — `mechanics: []` is noise.
      run(() => (next.length === 0 ? removeField(source) : set(source, next)));
      return;
    }
    expanded = expanded.filter((id) => id !== card.descriptor.id);
    if (presenceOf(card.descriptor, values).present) {
      run(() => removeField(card.descriptor.id));
    }
  }

  function cardLabel(card: Card): string {
    if (card.kind !== 'unknown') return card.descriptor.label;
    return card.entry.name ? authoredMechanicName(fullMechanicId(card.entry.name)) : 'Unreadable entry';
  }

  function cardIcon(card: Card): IconName {
    return (card.kind === 'unknown' ? 'circle' : card.descriptor.icon) as IconName;
  }

  function paramValue(params: Record<string, unknown>, param: TypeParam): unknown {
    return Object.hasOwn(params, param.id) ? params[param.id] : undefined;
  }
</script>

<section class="modules" aria-label="Display and modules">
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

  {#each cards as card (card.key)}
    {@const descriptor = card.kind === 'unknown' ? null : card.descriptor}
    {@const info = card.kind === 'mechanic' && card.entry.name
      ? mechanicInfo(vocabulary, card.entry.name)
      : null}
    {@const removable = card.kind === 'unknown' || descriptor?.kind === 'mechanic' || descriptor?.required !== true}
    <article
      animate:flip={{ duration: motionMs('base') }}
      in:fly={{ y: -4, duration: motionMs('base') }}
      out:fade={{ duration: motionMs('fast') }}
      class="card module"
      data-module={card.kind === 'unknown' ? card.entry.name || 'unknown' : descriptor!.id}
      data-field={card.kind === 'unknown' ? card.sourceField : descriptor!.kind === 'mechanic'
        ? descriptor!.sourceField : descriptor!.field!.id}
    >
      <header>
        <Icon name={cardIcon(card)} size={16} />
        <strong title={mechanicHint(info)}>{cardLabel(card)}</strong>
        {#if info}
          {#if !isBuiltInMechanic(info)}<span class="origin">{mechanicOrigin(info)}</span>{/if}
        {:else if card.kind === 'unknown' && card.entry.name}
          <span class="origin">{fullMechanicId(card.entry.name)} · not registered on this server</span>
        {:else if card.kind === 'unknown'}
          <span class="origin">not a single ability mapping</span>
        {/if}
        <span class="spacer"></span>
        <button type="button" class="ghost" onclick={showInFile} title="open this module's file">
          Show in file
        </button>
        {#if removable}
          <button type="button" class="ghost remove" aria-label={`Remove ${cardLabel(card)}`}
            onclick={() => removeCard(card)}>Remove</button>
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
      {:else if descriptor!.kind === 'rules' && descriptor!.field}
        <BehaviorRulesEditor field={descriptor!.field} value={values[descriptor!.field.id]}
          pack={resource.pack} {host} vocabulary={vocabulary}
          onChange={(next) => set(descriptor!.field!.id, next)} />
      {:else if descriptor!.field}
        <Field field={descriptor!.field} value={values[descriptor!.field.id]} pack={resource.pack} hideLabel
          onChange={(next) => set(descriptor!.field!.id, next)} action={actionFor(descriptor!.field)} />
        {@render provenance(descriptor!.field.id)}
        {@const issue = fieldIssue(descriptor!.field.id)}
        {#if issue}
          <p class="field-issue" role="note">{issue.code}: {issue.message}{issue.hint ? ` — ${issue.hint}` : ''}</p>
        {/if}
      {/if}
    </article>
  {/each}

  {#each fromTemplate as module (module.key)}
    <article class="card module inherited" data-inherited-module={module.key} aria-label={`${module.label} (inherited)`}>
      <header>
        <Icon name={module.icon} size={16} />
        <strong>{module.label}</strong>
        <span class="origin">Inherited from {shortId(module.source)}</span>
        <span class="spacer"></span>
        <button type="button" class="ghost" onclick={() => overrideHere(module.field, module.value)}
          title="Copy the template's value into this resource so you can change it">
          Override here
        </button>
      </header>
      <p class="note" data-inherited-summary>{module.summary}</p>
    </article>
  {/each}

  <button type="button" class="add" onclick={() => (pickerOpen = true)}>
    <Icon name="plus" size={16} />
    Add module
  </button>

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
</section>

{#if pickerOpen}
  <ModulePicker {catalog} {isAdded} onClose={() => (pickerOpen = false)} onPick={addModule} />
{/if}

<style>
  .modules {
    display: grid;
    gap: var(--space-3);
    min-width: 0;
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
  .module header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    margin-bottom: var(--space-2);
  }
  .module header :global(svg) {
    color: var(--muted);
    flex: none;
  }
  .module header strong {
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
  /* Pushes the header's buttons to the end, whether or not an origin tag is shown. */
  .spacer {
    flex: 1 1 0;
    min-width: 0;
  }
  .ghost {
    border: 1px solid var(--border-strong);
    background: none;
    color: var(--muted);
    border-radius: var(--radius);
    padding: var(--space-half) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
    white-space: nowrap;
    flex: none;
  }
  .ghost:hover {
    background: var(--bg-hover);
    color: inherit;
  }
  .inherited {
    border-style: dashed;
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
