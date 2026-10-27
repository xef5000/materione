<script lang="ts">
  /**
   * Revision-checked all-or-none authoring from the Content catalog: set one
   * field on the marked resources, or make variants of one resource. Every
   * run is previewed first (`authoring.bulk` with dryRun) and applied with the
   * revisions the preview was read at, so a document that changed in between
   * refuses the whole change. Nothing here loops independent writes.
   */
  import { onMount } from 'svelte';
  import { ops, unwrap } from '../api';
  import type { TypeParam } from '../api';
  import {
    bulkHeadline,
    fieldOperations,
    formatValue,
    parseBulkResult,
    recipeOperation,
    variantPlan,
    MAX_BULK_OPERATIONS,
    type BulkOperation,
    type BulkResult,
    type BulkTarget,
  } from '../bulk';
  import { readRevisions } from '../bulk-io';
  import { errorMessage, logActivity, refreshPack, session, workspace } from '../state.svelte';
  import Dialog from '../ui/Dialog.svelte';

  type Mode =
    | { kind: 'field'; type: string; targets: (BulkTarget & { name: string })[] }
    | { kind: 'variants'; type: string; source: BulkTarget & { name: string }; folder: string };

  interface Props {
    mode: Mode;
    onClose: () => void;
    onDone?: (firstPath: string | null) => void;
  }

  let { mode, onClose, onDone }: Props = $props();

  const pack = workspace.activePack!;
  const typeInfo = $derived(workspace.types.find((entry) => (entry.directory ?? entry.name) === mode.type));
  const fields = $derived(
    (typeInfo?.parameters ?? []).filter((param) =>
      !['list', 'raw'].includes(param.type) && (mode.kind === 'variants' || param.id !== 'template')),
  );

  let fieldId = $state('');
  let value = $state('');
  let remove = $state(false);
  let names = $state('');
  let withRecipes = $state(false);
  let preview = $state<BulkResult | null>(null);
  // Plain, not $state: the operations are sent over the channel and must stay structured-cloneable.
  let planned: BulkOperation[] = [];
  let busy = $state(false);
  let error = $state('');
  let problems = $state<string[]>([]);
  let firstInput = $state<HTMLElement>();

  const field = $derived(fields.find((param) => param.id === fieldId));
  const canRecipe = $derived(mode.kind === 'variants' && mode.type === 'items');

  onMount(() => firstInput?.focus());

  function invalidate(): void {
    preview = null;
    error = '';
  }

  function typedValue(param: TypeParam): unknown {
    if (param.type === 'integer') {
      if (!/^-?\d+$/.test(value.trim())) throw new Error(`${param.label ?? param.id} needs a whole number`);
      return Number(value.trim());
    }
    if (param.type === 'decimal') {
      if (value.trim() === '' || Number.isNaN(Number(value))) throw new Error(`${param.label ?? param.id} needs a number`);
      return Number(value);
    }
    if (param.type === 'boolean') return value === 'true';
    return value;
  }

  async function build(): Promise<{ operations: BulkOperation[]; revisions: Map<string, number> }> {
    if (mode.kind === 'field') {
      if (!field) throw new Error('Choose the field to change.');
      const targets = mode.targets;
      if (targets.length > MAX_BULK_OPERATIONS) {
        throw new Error(`Change at most ${MAX_BULK_OPERATIONS} documents at a time (${targets.length} marked).`);
      }
      const revisions = await readRevisions(pack, [...new Set(targets.map((target) => target.path))]);
      const edit = remove ? { remove: true as const } : { value: typedValue(field) };
      return { operations: fieldOperations(targets, [field.id], edit, revisions), revisions };
    }
    const existing = new Set(workspace.resources.map((resource) => resource.id));
    const sourceDoc = unwrap(await ops.get(session.channel!, pack, mode.source.path));
    const parsed = (sourceDoc.parsed ?? {}) as Record<string, Record<string, unknown>>;
    const hasDisplayName = typeof parsed[mode.source.id]?.display_name === 'string';
    const plan = variantPlan(
      { path: mode.source.path, id: mode.source.id, hasDisplayName },
      names, field ? [field.id] : null, existing, mode.folder,
    );
    problems = plan.problems;
    if (plan.problems.length || plan.operations.length === 0) {
      throw new Error(plan.problems.length ? 'Fix the highlighted names first.' : 'Add at least one variant name.');
    }
    const operations = [...plan.operations];
    if (withRecipes) {
      for (const op of plan.operations) {
        if (op.kind === 'duplicate') operations.push(recipeOperation(op.id));
      }
    }
    if (operations.length > MAX_BULK_OPERATIONS) {
      throw new Error(`Make at most ${MAX_BULK_OPERATIONS} documents at a time.`);
    }
    return { operations, revisions: new Map() };
  }

  async function run(apply: boolean): Promise<void> {
    busy = true;
    error = '';
    problems = [];
    try {
      let operations = planned;
      if (!apply || !preview) {
        const built = await build();
        operations = built.operations;
        planned = operations;
      }
      const result = parseBulkResult(unwrap(await ops.bulk(session.channel!, pack, operations, !apply)));
      if (apply) {
        await refreshPack();
        logActivity('save', `bulk change applied to ${result.documents.filter((doc) => !doc.unchanged).length} documents; preview is building`);
        onDone?.(result.documents.find((doc) => !doc.unchanged)?.path ?? null);
        onClose();
      } else {
        preview = result;
      }
    } catch (e) {
      error = errorMessage(e);
      if (apply) preview = null;
    } finally {
      busy = false;
    }
  }


  const labelOf = (path: string): string => {
    if (mode.kind === 'field') {
      const hit = mode.targets.find((target) => target.path === path);
      if (hit) return hit.name;
    }
    return path.slice(path.lastIndexOf('/') + 1).replace(/\.ya?ml$/i, '');
  };
  const canPreview = $derived(
    mode.kind === 'field' ? Boolean(field) && (remove || value !== '' || field?.type === 'string') : names.trim() !== '',
  );
  const changedCount = $derived(preview?.documents.filter((doc) => !doc.unchanged).length ?? 0);
  const headline = $derived(
    mode.kind === 'field'
      ? `Change a field on ${mode.targets.length} ${mode.targets.length === 1 ? 'resource' : 'resources'}`
      : `Variants of ${mode.source.name}`,
  );
</script>

<Dialog title={headline} onClose={onClose} width="560px" dismissOnScrim={false}>

    {#if mode.kind === 'field'}
      <label class="field">
        <span class="field-label">Field</span>
        <select bind:value={fieldId} bind:this={firstInput} onchange={invalidate}>
          <option value="" disabled>choose a field…</option>
          {#each fields as param (param.id)}
            <option value={param.id}>{param.label ?? param.id}</option>
          {/each}
        </select>
      </label>
      {#if field}
        <label class="check">
          <input type="checkbox" bind:checked={remove} onchange={invalidate} />
          <span>Remove the field instead (resources fall back to what they inherit, or the default)</span>
        </label>
        {#if !remove}
          <label class="field">
            <span class="field-label">New value</span>
            {#if field.allowedValues?.length}
              <select bind:value={value} onchange={invalidate}>
                <option value="" disabled>choose…</option>
                {#each field.allowedValues as option (option)}<option value={option}>{option}</option>{/each}
              </select>
            {:else if field.type === 'boolean'}
              <select bind:value={value} onchange={invalidate}>
                <option value="" disabled>choose…</option>
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
            {:else}
              <input bind:value={value} oninput={invalidate} spellcheck="false" />
            {/if}
          </label>
        {/if}
      {/if}
    {:else}
      <label class="field">
        <span class="field-label">One variant per line — “Name” or “Name = value”</span>
        <textarea
          rows="5"
          bind:value={names}
          bind:this={firstInput}
          oninput={invalidate}
          spellcheck="false"
          placeholder={'Ruby Blade\nSapphire Blade = item/sapphire_blade'}
        ></textarea>
      </label>
      <label class="field">
        <span class="field-label">Field set by “= value” (optional)</span>
        <select bind:value={fieldId} onchange={invalidate}>
          <option value="">none — copies keep the source's values</option>
          {#each fields as param (param.id)}
            <option value={param.id}>{param.label ?? param.id}</option>
          {/each}
        </select>
      </label>
      {#if canRecipe}
        <label class="check">
          <input type="checkbox" bind:checked={withRecipes} onchange={invalidate} />
          <span>Also create a recipe for each variant (same change)</span>
        </label>
      {/if}
      {#if problems.length}
        <ul class="problems" role="alert">
          {#each problems as problem (problem)}<li>{problem}</li>{/each}
        </ul>
      {/if}
    {/if}

    {#if error}<p class="error" role="alert">{error}</p>{/if}

    {#if preview}
      <section class="impact" aria-label="impact summary">
        <p class="headline"><strong>{bulkHeadline(preview)}</strong>. All of it is written together, or none of it.</p>
        <ul>
          {#each preview.documents as doc (doc.path)}
            <li class:same={doc.unchanged}>
              <span class="doc">{labelOf(doc.path)}{doc.kind === 'set' ? '' : ' (new)'}</span>
              {#if doc.unchanged}
                <span class="muted">already as requested</span>
              {:else}
                {#each doc.changes.slice(0, 4) as change, index (index)}
                  <span class="diff">{change.field.slice(1).join('.') || change.field[0]}:
                    <del>{formatValue(change.before)}</del> → <ins>{formatValue(change.after)}</ins></span>
                {/each}
                {#if doc.changes.length > 4}<span class="muted">+{doc.changes.length - 4} more fields</span>{/if}
              {/if}
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    <div class="dialog-actions">
      <button type="button" class="btn" onclick={onClose}>Cancel</button>
      <button type="button" class="btn" disabled={busy || !canPreview} onclick={() => void run(false)}>
        Preview changes
      </button>
      <button
        type="button"
        class="btn btn-primary"
        disabled={busy || !preview || changedCount === 0}
        onclick={() => void run(true)}
      >
        Apply to {changedCount} {changedCount === 1 ? 'document' : 'documents'}
      </button>
    </div>
</Dialog>

<style>
  label.field {
    margin-bottom: var(--space-3);
  }
  .field > input:not([type='checkbox']),
  .field > select,
  .field > textarea {
    width: 100%;
  }
  textarea {
    resize: vertical;
  }
  label.check {
    display: flex;
    gap: var(--space-2);
    align-items: flex-start;
    margin-bottom: var(--space-3);
  }
  .error,
  .problems {
    margin: var(--space-2) 0;
    color: var(--error);
    font-size: var(--text-sm);
  }
  .problems {
    padding-left: var(--space-5);
  }
  .impact {
    margin-top: var(--space-2);
    padding-top: var(--space-2);
    border-top: 1px solid var(--border);
  }
  .headline {
    margin: 0 0 var(--space-2);
    font-size: var(--text-sm);
  }
  .impact ul {
    max-height: 220px;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
  }
  .impact li {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-3);
    min-width: 0;
    padding: var(--space-1) 0;
    font-size: var(--text-sm);
  }
  .impact li.same {
    opacity: 0.6;
  }
  .doc {
    font-weight: 600;
  }
  .diff {
    font-family: var(--mono);
    overflow-wrap: anywhere;
  }
  .muted {
    color: var(--faint);
  }
  del {
    color: var(--muted);
  }
  ins {
    color: var(--accent);
    text-decoration: none;
  }
</style>
