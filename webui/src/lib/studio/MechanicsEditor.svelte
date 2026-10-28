<script lang="ts">
  /**
   * An item's `mechanics:` list as ability cards. Every card is generated
   * from the mechanic's registered self-description (plugin.md §3 — no
   * hand-written per-mechanic forms); entries the server does not register
   * are still shown, with their parameters preserved untouched.
   */
  import Field from './Field.svelte';
  import type { TypeParam } from '../api';
  import {
    addMechanic,
    canAddMechanic,
    fullMechanicId,
    isBuiltInMechanic,
    mechanicEntries,
    mechanicHint,
    mechanicInfo,
    mechanicOrigin,
    removeAt,
    setMechanicParam,
    type BehaviorVocabulary,
  } from '../behavior';

  interface Props {
    field: TypeParam;
    value: unknown;
    pack: string;
    vocabulary: BehaviorVocabulary | null;
    onChange: (value: unknown) => void;
    onError: (message: string) => void;
  }

  let { field, value, pack, vocabulary, onChange, onError }: Props = $props();
  let choice = $state('');

  const entries = $derived(mechanicEntries(value));
  const registered = $derived(vocabulary?.mechanics ?? []);
  const chosen = $derived(registered.find((mechanic) => mechanic.id === choice));

  function run(change: () => unknown) {
    try {
      onChange(change());
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e));
    }
  }

  function add() {
    if (!chosen) return;
    run(() => addMechanic(value, chosen));
    choice = '';
  }

  function paramValue(params: Record<string, unknown>, param: TypeParam): unknown {
    return Object.hasOwn(params, param.id) ? params[param.id] : undefined;
  }
</script>

<section class="abilities" data-field={field.id} aria-label={field.label || field.id}>
  <h3>{field.label || 'Abilities'}</h3>
  {#if !vocabulary}
    <p class="note">This server does not describe its abilities, so they can only be edited in Files.</p>
  {/if}
  {#each entries as entry (entry.index)}
    {@const info = mechanicInfo(vocabulary, entry.name)}
    <article class="card" data-mechanic={entry.name || `#${entry.index}`}>
      <header>
        <strong title={mechanicHint(info)}>{info?.displayName ?? (entry.name || 'Unreadable entry')}</strong>
        {#if info}
          {#if !isBuiltInMechanic(info)}<span class="origin">{mechanicOrigin(info)}</span>{/if}
        {:else}
          <span class="origin">{entry.name ? `${fullMechanicId(entry.name)} · not registered on this server` : 'not a single ability mapping'}</span>
        {/if}
        <button type="button" class="remove" aria-label={`Remove ${info?.displayName ?? entry.name}`}
          onclick={() => run(() => removeAt(value, entry.index))}>Remove</button>
      </header>
      {#if info}
        {#each info.parameters ?? [] as param (param.id)}
          <Field field={param} value={paramValue(entry.params, param)} {pack}
            onChange={(next) => run(() => setMechanicParam(value, entry.index, param.id, next))} />
        {/each}
        {#if Object.keys(entry.params).some((key) => !(info.parameters ?? []).some((param) => param.id === key))}
          <p class="note">Other settings on this ability are kept as written; edit them in Files.</p>
        {/if}
      {:else}
        <p class="note">
          {entry.name
            ? 'Install or enable the plugin that provides it; its settings are kept as written and can be edited in Files.'
            : 'Each entry must be one ability name with its settings; edit this one in Files.'}
        </p>
      {/if}
    </article>
  {/each}
  {#if entries.length === 0}
    <p class="note">No abilities yet.</p>
  {/if}
  {#if registered.length > 0}
    <div class="add">
      <label>
        <span>Add an ability</span>
        <select bind:value={choice} title={mechanicHint(chosen)}>
          <option value="">Choose…</option>
          {#each registered as mechanic (mechanic.id)}
            <option value={mechanic.id} disabled={!canAddMechanic(value, mechanic)}>
              {mechanic.displayName}{mechanic.id.startsWith('materione:') ? '' : ` (${mechanic.registeredBy})`}
            </option>
          {/each}
        </select>
      </label>
      <button type="button" disabled={!chosen} onclick={add}>Add</button>
    </div>
  {/if}
</section>

<style>
  .abilities {
    display: grid;
    gap: var(--space-2);
    margin-bottom: var(--space-4);
    min-width: 0;
  }
  h3 {
    margin: 0;
    font-size: var(--text-sm);
    font-weight: normal;
    color: var(--muted);
  }
  .card {
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-3);
    min-width: 0;
  }
  header {
    display: flex;
    align-items: baseline;
    gap: var(--space-2);
    min-width: 0;
  }
  header strong {
    font-size: var(--text-base);
  }
  .origin {
    min-width: 0;
    color: var(--faint);
    font-size: var(--text-xs);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  header .remove {
    margin-left: auto;
  }
  .remove,
  .add button:not(.btn) {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-half) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .note {
    margin: var(--space-1) 0 var(--space-2);
    font-size: var(--text-sm);
    color: var(--muted);
  }
  .add {
    display: flex;
    align-items: end;
    gap: var(--space-2);
  }
  .add label {
    display: grid;
    gap: var(--space-1);
    flex: 1;
    min-width: 0;
    font-size: var(--text-sm);
    color: var(--muted);
  }
  select {
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-base);
    min-width: 0;
  }
</style>
