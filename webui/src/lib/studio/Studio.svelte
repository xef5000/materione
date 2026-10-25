<script lang="ts">
  /**
   * The studio shell: one entry, edited through purpose-built controls that
   * the registry describes. It is a client of the document ops like any other
   * surface — a save is one `authoring.setFields` transaction, so the file on
   * disk stays the single source of truth (D1) and the round-trip stays
   * lossless: fields the studio cannot render are reported, never rewritten.
   */
  import Field from './Field.svelte';
  import StructuredList from './StructuredList.svelte';
  import RecipeGrid from './RecipeGrid.svelte';
  import StudioPreview from './StudioPreview.svelte';
  import GlyphPreview from './GlyphPreview.svelte';
  import AudioPreview from './AudioPreview.svelte';
  import PaintingPreview from './PaintingPreview.svelte';
  import TextureEditor from './TextureEditor.svelte';
  import ModelWorkbench from './ModelWorkbench.svelte';
  import { ops, unwrap } from '../api';
  import type { ResourceRef, TypeInfo, TypeParam } from '../api';
  import {
    draftDirty,
    ensureDocumentDraft,
    fieldValue,
    getDocumentDraft,
    rawDraftDirty,
    reconcileDocumentDraft,
    retainDocumentDraft,
    saveDocumentDraft,
    setDraftField,
  } from '../drafts.svelte';
  import { rootIdForResource, sectionsOf, unrenderableKeys } from '../studio';
  import {
    diagnostics,
    connection,
    errorMessage,
    logActivity,
    registerEditorGuard,
    refreshPack,
    revealInAdvanced,
    session,
    workspace,
  } from '../state.svelte';

  interface Props {
    resource: ResourceRef;
    onClose: () => void;
    active?: boolean;
  }

  let { resource, onClose, active = true }: Props = $props();

  const documentDraft = $derived(getDocumentDraft(resource.pack, resource.path));
  const rootId = $derived(rootIdForResource(resource.id));
  const loaded = $derived(recordOf(documentDraft.base[rootId]));
  const draft = $derived(recordOf(documentDraft.values[rootId]));
  const rev = $derived(documentDraft.rev);
  const dirty = $derived(draftDirty(documentDraft));
  const saving = $derived(documentDraft.saving);
  const loading = $derived(documentDraft.loading);
  const error = $derived(documentDraft.error);
  /** Server-computed read-only metadata (a glyph's allocated codepoint). */
  const computed = $derived(documentDraft.computed as Record<
    string,
    Record<string, { codepoint: number; count: number }>
  >);
  /** The texture slot currently open in the pixel editor, if any. */
  let texturing = $state<{ field: string; reference: string } | null>(null);
  let textureDirty = $state(false);
  let textureSaving = $state(false);
  /** Bumped when the pixel editor writes, so the preview re-reads the bytes. */
  let textureVersion = $state(0);
  /** The model field currently open in the Blockbench workbench, if any. */
  let modelling = $state<string | null>(null);
  let modelSaving = $state(false);
  const glyphMeta = $derived(computed.glyphs?.[rootId] ?? null);

  const typeDirectory = $derived(resource.path.split('/')[0] ?? '');
  const schema = $derived(
    workspace.types.find((type: TypeInfo) => type.directory === typeDirectory) ??
      workspace.types.find((type: TypeInfo) => type.name === resource.type),
  );
  const sections = $derived(sectionsOf(schema));
  const advancedOnly = $derived(unrenderableKeys(loaded, schema));
  const folder = $derived(resource.path.split('/').slice(0, -1).join('/'));
  const name = $derived(String(draft.display_name ?? '') || resource.id);
  const pendingRoots = $derived([
    ...new Set(
      documentDraft.edits
        .map((edit) => edit.field[0])
        .filter((root): root is string => typeof root === 'string' && root.length > 0),
    ),
  ]);
  const pendingSummary = $derived(
    !rawDraftDirty(documentDraft) && pendingRoots.some((root) => root !== rootId)
      ? `Save & apply will apply ${documentDraft.edits.length} pending field${documentDraft.edits.length === 1 ? '' : 's'} across ${pendingRoots.length} resource${pendingRoots.length === 1 ? '' : 's'} in this file (${pendingRoots.map(resourceIdForRoot).join(', ')}).`
      : '',
  );
  const issues = $derived(
    diagnostics.entries.filter((entry) => (entry.resource ?? '').includes(resource.id)),
  );

  /** Ingredient choices for the crafting grid: this pack's items, then materials. */
  const choices = $derived([
    ...workspace.resources
      .filter((entry) => entry.path.startsWith('items/'))
      .map((entry) => ({
        value: `${entry.pack}:${entry.id}`,
        label: entry.id,
        group: 'pack',
      })),
    ...materialChoices(),
  ]);

  function materialChoices(): { value: string; label: string; group: string }[] {
    const materials = schema?.parameters.find((field) => field.id === 'material')?.allowedValues;
    return (materials ?? []).map((value) => ({ value, label: value.toLowerCase(), group: 'vanilla' }));
  }

  function recordOf(value: unknown): Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, unknown>
      : {};
  }

  function resourceIdForRoot(root: string): string {
    return workspace.resources.find((entry) =>
      entry.pack === resource.pack &&
      entry.path === resource.path &&
      rootIdForResource(entry.id) === root,
    )?.id ?? root;
  }

  $effect(() => {
    const current = documentDraft;
    return retainDocumentDraft(current);
  });

  $effect(() => {
    const current = documentDraft;
    const channel = session.channel;
    if (
      channel &&
      !current.loaded &&
      !current.loading &&
      !current.saving &&
      !draftDirty(current) &&
      !current.error
    ) {
      void ensureDocumentDraft(current, channel);
    }
  });

  $effect(() => {
    return registerEditorGuard('studio', () => {
      if (textureSaving || modelSaving) return false;
      if (modelling) {
        return confirm('Leave the open model editor? Unsaved model work may be lost.');
      }
      if (texturing && textureDirty) {
        return confirm('Leave the texture editor without applying its unsaved pixels?');
      }
      return true;
    }, () => Boolean(texturing || modelling || textureSaving || modelSaving));
  });

  function valueOf(field: TypeParam): unknown {
    return draft[field.id];
  }

  function set(field: string, value: unknown) {
    if (rawDraftDirty(documentDraft)) {
      documentDraft.error = 'Apply or discard the Advanced Files YAML draft before editing fields here.';
      return;
    }
    try {
      setDraftField(documentDraft, [rootId, field], value);
    } catch (e) {
      documentDraft.error = errorMessage(e);
    }
  }

  function canEditAsset(): boolean {
    if (!connection.connected || !session.channel) {
      documentDraft.error = 'Connect to the server before opening immediate-write texture or model editors.';
      return false;
    }
    if (!documentDraft.loaded || documentDraft.saving || documentDraft.loading ||
      documentDraft.uncertain || documentDraft.stale) {
      documentDraft.error = 'Reconcile the document draft before saving texture or model assets.';
      return false;
    }
    if (rawDraftDirty(documentDraft)) {
      documentDraft.error = 'Apply or discard the Advanced Files YAML draft before saving texture or model changes.';
      return false;
    }
    return true;
  }

  async function save() {
    const channel = session.channel;
    if (!active || !draftDirty(documentDraft)) return;
    if (!channel) {
      documentDraft.error = 'Reconnect before applying this document.';
      return;
    }
    const changed = documentDraft.edits.length || (rawDraftDirty(documentDraft) ? 1 : 0);
    try {
      if (await saveDocumentDraft(documentDraft, channel)) {
        logActivity('save', `${resource.path} saved (${changed} pending changes)`);
        await refreshPack();
      }
    } catch (e) {
      documentDraft.error = errorMessage(e);
    }
  }

  function editModel(field: TypeParam) {
    if (!canEditAsset()) return;
    modelling = field.id;
  }

  async function modelSaved(_reference: string) {
    const field = modelling ?? 'model';
    const committedField = [rootId, field];
    textureVersion += 1;
    modelling = null;
    const channel = session.channel;
    if (channel) {
      await reconcileDocumentDraft(documentDraft, channel, [committedField]);
    } else {
      documentDraft.stale = true;
      documentDraft.error = 'Model assets were applied, but the document draft could not be reconciled while disconnected.';
    }
  }

  /**
   * Editing a texture that does not exist yet is the common case for a new
   * item, so the slot proposes a predictable path rather than asking; the
   * owning field is written the moment the PNG lands, so the entry is never
   * left pointing at nothing.
   */
  function editTexture(field: TypeParam) {
    if (!canEditAsset()) return;
    const reference = String(draft[field.id] ?? '') || `${typeDirectory}/${resource.id}`;
    textureDirty = false;
    texturing = { field: field.id, reference };
  }

  async function textureSaved() {
    if (!texturing) return;
    textureVersion += 1;
    const { field, reference } = texturing;
    const channel = session.channel;
    if (!channel) {
      try {
        setDraftField(documentDraft, [rootId, field], reference);
      } catch {
        // Keep the error below; an asset may have been saved just as the channel closed.
      }
      documentDraft.error = 'The texture was saved, but its document reference could not be applied while disconnected.';
      return;
    }
    await refreshPack();
    if (rawDraftDirty(documentDraft)) {
      documentDraft.error = 'The texture asset was saved, but its reference was not applied because an Advanced Files YAML draft is pending.';
      return;
    }
    const committedField = [rootId, field];
    const committedValue = fieldValue(documentDraft.base, committedField);
    if (committedValue !== reference) {
      if (documentDraft.saving || documentDraft.loading || documentDraft.uncertain || documentDraft.stale) {
        documentDraft.error = 'The texture asset was saved, but its reference needs draft reconciliation before it can be applied.';
        return;
      }
      try {
        unwrap(await ops.setFields(
          channel,
          resource.pack,
          resource.path,
          documentDraft.rev,
          [{ field: committedField, value: reference }],
        ));
        await reconcileDocumentDraft(documentDraft, channel, [committedField]);
      } catch (e) {
        const message = `The texture asset was saved, but its reference was not applied: ${errorMessage(e)}`;
        await reconcileDocumentDraft(documentDraft, channel);
        try {
          setDraftField(documentDraft, committedField, reference);
          documentDraft.error = `${message} The reference is staged and has not been applied.`;
        } catch {
          documentDraft.error = `${message} Reconcile the document draft before applying the reference.`;
        }
      }
    }
  }

  function actionFor(field: TypeParam) {
    if (field.uiHint === 'model') {
      return { label: 'Edit in Blockbench', run: () => editModel(field) };
    }
    if (field.uiHint === 'texture') {
      return { label: draft[field.id] ? 'Edit' : 'Draw', run: () => editTexture(field) };
    }
    return undefined;
  }

  function onKeydown(event: KeyboardEvent) {
    if (!active || texturing || modelling) return; // the active overlay owns the keyboard
    if (event.key === 'Escape' && !dirty) onClose();
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      void save();
    }
  }
</script>

<svelte:window onkeydown={onKeydown} />

<section class="studio">
  <header>
    <button type="button" class="back" onclick={onClose} title="back to {typeDirectory}">←</button>
    <span class="name">{name}</span>
    <span class="meta">{resource.id} · rev {rev}</span>
    <span class="spacer"></span>
    <button
      type="button"
      class="yaml"
      onclick={() => revealInAdvanced(resource)}
      title="open this resource in Files"
    >
      Open in Files
    </button>
    <button type="button" class="save" disabled={!active || !documentDraft.loaded || !dirty || saving || loading || documentDraft.uncertain || documentDraft.stale} onclick={save}>
      {saving ? 'Saving…' : 'Save & apply'}
    </button>
  </header>

  {#if error}
    <p class="error">{error}</p>
  {/if}
  {#if documentDraft.notice}
    <p class="notice">{documentDraft.notice}</p>
  {/if}
  {#if pendingSummary}
    <p class="notice pending-summary">{pendingSummary}</p>
  {/if}

  <div class="body">
    <aside class="preview">
      {#if typeDirectory === 'glyphs'}
        <GlyphPreview
          pack={resource.pack}
          texture={String(draft.texture ?? '')}
          frames={draft.frames}
          ascent={Number(draft.ascent ?? 8)}
          height={Number(draft.height ?? 8)}
          codepoint={glyphMeta?.codepoint ?? null}
          nonce={textureVersion}
        />
      {:else if typeDirectory === 'sounds'}
        <AudioPreview
          pack={resource.pack}
          sounds={draft.sounds}
          onChange={(value) => set('sounds', value)}
        />
      {:else if typeDirectory === 'paintings'}
        <PaintingPreview
          pack={resource.pack}
          texture={String(draft.texture ?? '')}
          width={Number(draft.width ?? 1)}
          height={Number(draft.height ?? 1)}
          nonce={textureVersion}
        />
      {:else}
        <StudioPreview
          pack={resource.pack}
          texture={String(draft.texture ?? '')}
          model={String(draft.model ?? '')}
          label={name}
          nonce={textureVersion}
        />
      {/if}
    </aside>

    <div class="form">
      {#if rawDraftDirty(documentDraft)}
        <p class="pending-yaml">
          Advanced Files YAML changes are pending. Apply or discard them there before editing structured fields.
        </p>
      {/if}
      <fieldset
        class="field-controls"
        disabled={documentDraft.loading || documentDraft.saving || rawDraftDirty(documentDraft) || documentDraft.uncertain || documentDraft.stale}
      >
      {#if !schema}
        <p class="error">No registered type owns {typeDirectory}/ — edit this file as YAML.</p>
      {:else if !documentDraft.loaded}
        <p class="loading">Loading document…</p>
      {:else}
        {#each sections as section (section.id)}
          <fieldset>
            <legend>{section.label}</legend>
            {#each section.fields as field (field.id)}
              {#if field.uiHint === 'structured-list'}
                <StructuredList {field} value={valueOf(field)} onChange={(v) => set(field.id, v)} />
              {:else if field.uiHint === 'recipe-grid' && field.id === 'shape'}
                <RecipeGrid
                  shape={draft.shape}
                  ingredients={draft.ingredients}
                  {choices}
                  onChange={(next) => {
                    set('shape', next.shape);
                    set('ingredients', next.ingredients);
                  }}
                />
              {:else if field.uiHint === 'recipe-grid'}
                <!-- ingredients ride along with the grid above -->
              {:else}
                <Field
                  {field}
                  value={valueOf(field)}
                  pack={resource.pack}
                  onChange={(v) => set(field.id, v)}
                  action={actionFor(field)}
                />
              {/if}
            {/each}
          </fieldset>
        {/each}
      {/if}
      </fieldset>
    </div>
  </div>

  <footer>
    {#if issues.length > 0}
      <span class="issue">{issues[0]!.code}: {issues[0]!.message}</span>
    {/if}
    {#if advancedOnly.length > 0}
      <span class="advanced">
        {advancedOnly.length}
        {advancedOnly.length === 1 ? 'field is' : 'fields are'} only editable in Advanced Files
        ({advancedOnly.join(', ')})
      </span>
      <button type="button" class="link" onclick={() => revealInAdvanced(resource)}>Open</button>
    {/if}
    {#if saving}<span>Applying…</span>{/if}
  </footer>

  {#if texturing}
    <div class="overlay">
      <TextureEditor
        active={active}
        pack={resource.pack}
        path={`assets/textures/${texturing.reference}.png`}
        onClose={() => (texturing = null)}
        onDirtyChange={(dirty) => (textureDirty = dirty)}
        onSavingChange={(saving) => (textureSaving = saving)}
        onSaved={textureSaved}
        draftError={documentDraft.error}
      />
    </div>
  {/if}

  {#if modelling}
    <div class="overlay">
      <ModelWorkbench
        active={active}
        pack={resource.pack}
        ownerPath={resource.path}
        ownerId={resource.id}
        ownerName={name}
        {rev}
        field={[rootId, modelling]}
        modelRef={String(draft[modelling] ?? '')}
        textureRef={String(draft.texture ?? '')}
        {typeDirectory}
        onClose={() => (modelling = null)}
        onSaved={modelSaved}
        onSavingChange={(saving) => (modelSaving = saving)}
      />
    </div>
  {/if}
</section>

<style>
  .studio {
    /* Flex, not grid tracks: the error line is optional, and positional rows
       would put the body in an `auto` track and stop it scrolling. */
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
    overflow: hidden;
    position: relative;
  }
  /* The pixel editor takes over the studio, not the app: the entry it belongs
     to is still the thing being edited. */
  .overlay {
    position: absolute;
    inset: 0;
    z-index: 5;
    background: var(--bg-app);
  }
  header {
    display: flex;
    flex: none;
    align-items: center;
    gap: 10px;
    padding: 8px 12px;
    border-bottom: 1px solid var(--border);
    min-width: 0;
  }
  .back {
    background: none;
    border: none;
    color: var(--muted);
    font-size: 16px;
    cursor: pointer;
    padding: 0 4px;
  }
  .back:hover {
    color: var(--fg);
  }
  .name {
    font-size: 14px;
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .meta {
    color: var(--faint);
    font: 11px var(--mono);
    white-space: nowrap;
  }
  .spacer {
    flex: 1;
    min-width: 0;
  }
  .yaml,
  .save,
  .link {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 4px 10px;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  .save {
    background: var(--primary);
    border-color: transparent;
    color: #fff;
  }
  .save:disabled {
    background: none;
    border-color: var(--border);
    color: var(--faint);
    cursor: default;
  }
  .yaml:hover,
  .link:hover {
    background: var(--bg-hover);
  }
  .body {
    display: grid;
    flex: 1 1 0;
    min-height: 0;
    grid-template-columns: minmax(0, 260px) minmax(0, 1fr);
    gap: 20px;
    padding: 16px;
    overflow-y: auto;
    overflow-x: hidden;
    min-width: 0;
  }
  .preview {
    min-width: 0;
  }
  .form {
    min-width: 0;
    max-width: 620px;
  }
  fieldset {
    border: none;
    border-top: 1px solid var(--border);
    margin: 0 0 18px;
    padding: 12px 0 0;
    min-width: 0;
  }
  .field-controls {
    border: none;
    margin: 0;
    padding: 0;
  }
  legend {
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--faint);
    padding: 0 6px 0 0;
  }
  footer {
    display: flex;
    flex: none;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    border-top: 1px solid var(--border);
    font-size: 11px;
    color: var(--muted);
    min-width: 0;
  }
  footer span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .issue {
    color: var(--warn);
  }
  .error {
    margin: 0;
    flex: none;
    padding: 6px 12px;
    background: var(--error-bg);
    color: var(--error);
    font-size: 12px;
  }
  .notice,
  .loading {
    margin: 0;
    padding: 6px 12px;
    color: var(--muted);
    font-size: 12px;
  }
  .pending-yaml {
    margin: 0 0 12px;
    padding: 8px 10px;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--warn);
    font-size: 12px;
  }
  .pending-summary {
    border-bottom: 1px solid var(--border);
    color: var(--warn);
  }
  @media (max-width: 720px) {
    .body {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
