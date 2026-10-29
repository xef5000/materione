<script lang="ts">
  /**
   * The appearance of a customized item layer, inline (gui.md section 2, 6). The item is an
   * ordinary resource (`items/gui/<gui-path>/<layer-id>.yml`), so this is the item studio's own
   * Appearance component on that item's document draft: the same texture and model pickers, the
   * same name and lore editing, the same one-field-edit-per-change rule (D6). Nothing about it is
   * GUI-specific. Its edits stay in the item's draft until the studio saves (Ctrl+S saves the
   * layout and every customized item together).
   */
  import ItemAppearance from '../ItemAppearance.svelte';
  import { displayFieldsOf } from '../../modules';
  import type { InheritanceView, ResourceRef } from '../../api';
  import {
    ensureDocumentDraft,
    getDocumentDraft,
    removeDraftField,
    retainDocumentDraft,
    setDraftField,
  } from '../../drafts.svelte';
  import { localStudioVisual } from '../../studio-visual';
  import { rootIdForResource } from '../../studio';
  import { revealInStudio, session, workspace } from '../../state.svelte';

  let { resource }: { resource: ResourceRef } = $props();

  // Created once, at mount (the parent keys this component by the item's path): `getDocumentDraft` adds
  // the draft to the shared store when it is new, which Svelte forbids inside a `$derived`.
  // svelte-ignore state_referenced_locally
  const draft = getDocumentDraft(resource.pack, resource.path);
  // svelte-ignore state_referenced_locally
  const rootId = rootIdForResource(resource.id);
  $effect(() => retainDocumentDraft(draft));
  $effect(() => {
    const current = draft;
    const channel = session.channel;
    if (channel && !current.loaded && !current.loading && !current.error) void ensureDocumentDraft(current, channel);
  });

  const values = $derived((draft.values[rootId] ?? {}) as Record<string, unknown>);
  const loaded = $derived((draft.base[rootId] ?? {}) as Record<string, unknown>);
  const inheritance = $derived((draft.computed.inheritance as Record<string, InheritanceView> | undefined)?.[rootId]);
  const inherited = $derived(inheritance?.inherited ?? {});
  const effective = $derived({ ...inherited, ...values } as Record<string, unknown>);
  const schema = $derived(workspace.types.find((type) => type.directory === 'items'));
  const display = $derived(displayFieldsOf(schema));
  const summary = $derived(workspace.summaries.find((entry) => entry.pack === resource.pack && entry.path === resource.path && entry.id === resource.id));
  const unchanged = $derived(values.texture === loaded.texture && values.model === loaded.model);
  const visual = $derived(
    unchanged && summary?.visual
      ? summary.visual
      : localStudioVisual(resource.pack, String(effective.texture ?? ''), String(effective.model ?? ''), workspace.assets),
  );
  const render = $derived(unchanged ? summary?.render : undefined);
  const editable = $derived(draft.loaded && !draft.saving && !draft.stale && !draft.uncertain);

  function set(field: string, value: unknown): void {
    if (editable) setDraftField(draft, [rootId, field], value);
  }

  function removeField(field: string): void {
    if (editable) removeDraftField(draft, [rootId, field]);
  }
</script>

{#snippet provenance(_field: string)}{/snippet}

<div class="inline" data-testid="gui-item-appearance">
  {#if !draft.loaded}
    <p class="note">{draft.error || 'Opening the item…'}</p>
  {:else}
    <ItemAppearance
      pack={resource.pack}
      {values}
      {inherited}
      name={display.name}
      lore={display.lore}
      texture={display.slots.find((slot) => slot.uiHint === 'texture')}
      model={display.slots.find((slot) => slot.uiHint === 'model')}
      options={[]}
      {visual}
      {render}
      material={String(effective.material ?? '')}
      version={workspace.visualVersion}
      {set}
      {removeField}
      {provenance}
      missingTexture={() => null}
      fieldIssue={() => undefined}
      onDrawTexture={() => void revealInStudio(resource)}
      onEditModel={() => void revealInStudio(resource)}
    />
    {#if draft.error}<p class="note error" role="alert">{draft.error}</p>{/if}
  {/if}
  <button type="button" class="link" onclick={() => void revealInStudio(resource)}>Open in the item studio</button>
</div>

<style>
  .inline {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    min-width: 0;
  }
  /* the Appearance card is built for a wide column; in the inspector it stacks */
  .inline :global(.stage) {
    grid-template-columns: minmax(0, 1fr) !important;
  }
  .inline :global(.appearance) {
    padding: var(--space-2);
  }
  .note {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .note.error {
    color: var(--error);
  }
  .link {
    align-self: flex-start;
    background: none;
    border: 0;
    padding: 0;
    color: var(--accent);
    font-size: var(--text-xs);
    cursor: pointer;
    text-decoration: underline;
  }
</style>
