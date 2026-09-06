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
  import TextureEditor from './TextureEditor.svelte';
  import ModelWorkbench from './ModelWorkbench.svelte';
  import { ops, unwrap } from '../api';
  import type { ResourceRef, TypeInfo, TypeParam } from '../api';
  import { changedFields, sectionsOf, unrenderableKeys } from '../studio';
  import {
    clearDocStale,
    diagnostics,
    errorMessage,
    logActivity,
    refreshPack,
    revealInAdvanced,
    session,
    workspace,
  } from '../state.svelte';

  interface Props {
    resource: ResourceRef;
    onClose: () => void;
  }

  let { resource, onClose }: Props = $props();

  let rev = $state(0);
  let loaded = $state<Record<string, unknown>>({});
  let draft = $state<Record<string, unknown>>({});
  let rootId = $state('');
  /** Server-computed read-only metadata (a glyph's allocated codepoint). */
  let computed = $state<Record<string, Record<string, { codepoint: number; count: number }>>>({});
  let error = $state('');
  let saving = $state(false);
  /** The texture slot currently open in the pixel editor, if any. */
  let texturing = $state<{ field: string; reference: string } | null>(null);
  /** Bumped when the pixel editor writes, so the preview re-reads the bytes. */
  let textureVersion = $state(0);
  /** The model field currently open in the Blockbench workbench, if any. */
  let modelling = $state<string | null>(null);
  const modelField = $derived(modelling ?? 'model');

  const typeDirectory = $derived(resource.path.split('/')[0] ?? '');
  const schema = $derived(
    workspace.types.find((type: TypeInfo) => type.directory === typeDirectory) ??
      workspace.types.find((type: TypeInfo) => type.name === resource.type),
  );
  const glyphMeta = $derived(computed.glyphs?.[rootId] ?? null);
  const sections = $derived(sectionsOf(schema));
  const advancedOnly = $derived(unrenderableKeys(loaded, schema));
  const dirty = $derived(changedFields(loaded, draft).length > 0);
  const folder = $derived(resource.path.split('/').slice(0, -1).join('/'));
  const name = $derived(String(draft.display_name ?? '') || resource.id);
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

  $effect(() => {
    // Re-read whenever the browser opens a different entry.
    const key = `${resource.pack}/${resource.path}`;
    void load(key);
  });

  async function load(_key: string) {
    try {
      const payload = unwrap(await ops.get(session.channel!, resource.pack, resource.path));
      rev = Number(payload.rev ?? 0);
      const parsed = (payload.parsed as Record<string, unknown>) ?? {};
      rootId = String(Object.keys(parsed)[0] ?? resource.id);
      const body = (parsed[rootId] as Record<string, unknown>) ?? {};
      loaded = { ...body };
      draft = structuredClone(body);
      computed = (payload.computed as typeof computed) ?? {};
      error = '';
      clearDocStale(`${resource.pack}/${resource.path}`);
    } catch (e) {
      error = errorMessage(e);
    }
  }

  function valueOf(field: TypeParam): unknown {
    return draft[field.id];
  }

  function set(field: string, value: unknown) {
    draft = { ...draft, [field]: value };
  }

  async function save() {
    const changed = changedFields(loaded, draft);
    if (changed.length === 0) return;
    saving = true;
    try {
      const result = unwrap(
        await ops.setFields(
          session.channel!,
          resource.pack,
          resource.path,
          rev,
          changed.map((key) => ({ field: [rootId, key], value: draft[key] })),
        ),
      );
      rev = Number(result.rev ?? rev);
      logActivity('save', `${resource.path} saved (${changed.length} fields)`);
      await load(`${resource.pack}/${resource.path}`);
      await refreshPack();
    } catch (e) {
      const message = errorMessage(e);
      error = message.includes('MTR-0034')
        ? 'This file changed on disk since it was opened. Reload to see the new version.'
        : message;
    } finally {
      saving = false;
    }
  }

  function editModel(field: TypeParam) {
    modelling = field.id;
  }

  async function modelSaved(reference: string) {
    modelling = null;
    if (draft[modelField] !== reference) {
      draft = { ...draft, [modelField]: reference };
    }
    await load(`${resource.pack}/${resource.path}`);
  }

  /**
   * Editing a texture that does not exist yet is the common case for a new
   * item, so the slot proposes a predictable path rather than asking; the
   * owning field is written the moment the PNG lands, so the entry is never
   * left pointing at nothing.
   */
  function editTexture(field: TypeParam) {
    const reference = String(draft[field.id] ?? '') || `${typeDirectory}/${resource.id}`;
    texturing = { field: field.id, reference };
  }

  async function textureSaved() {
    if (!texturing) return;
    textureVersion += 1;
    const { field, reference } = texturing;
    if (draft[field] !== reference) {
      draft = { ...draft, [field]: reference };
      await save();
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
    if (texturing || modelling) return; // the overlay owns the keyboard
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
      title="open this file in Advanced Files"
    >
      Open as YAML
    </button>
    <button type="button" class="save" disabled={!dirty || saving} onclick={save}>
      {saving ? 'Saving…' : 'Save'}
    </button>
  </header>

  {#if error}
    <p class="error">{error}</p>
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
      {#if !schema}
        <p class="error">No registered type owns {typeDirectory}/ — edit this file as YAML.</p>
      {/if}
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
                  draft = { ...draft, shape: next.shape, ingredients: next.ingredients };
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
  </footer>

  {#if texturing}
    <div class="overlay">
      <TextureEditor
        pack={resource.pack}
        path={`assets/textures/${texturing.reference}.png`}
        onClose={() => (texturing = null)}
        onSaved={textureSaved}
      />
    </div>
  {/if}

  {#if modelling}
    <div class="overlay">
      <ModelWorkbench
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
  @media (max-width: 720px) {
    .body {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
