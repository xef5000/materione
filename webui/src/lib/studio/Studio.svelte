<script lang="ts">
  /**
   * The studio shell: one entry, edited through purpose-built controls that
   * the registry describes. It is a client of the document ops like any other
   * surface — a save is one `authoring.setFields` transaction, so the file on
   * disk stays the single source of truth (D1) and the round-trip stays
   * lossless: fields the studio cannot render are reported, never rewritten.
   */
  import Field from './Field.svelte';
  import Icon from '../components/Icon.svelte';
  import StructuredList from './StructuredList.svelte';
  import RecipeGrid from './RecipeGrid.svelte';
  import StudioPreview from './StudioPreview.svelte';
  import ItemPreview from './ItemPreview.svelte';
  import GlyphPreview from './GlyphPreview.svelte';
  import AudioPreview from './AudioPreview.svelte';
  import PaintingPreview from './PaintingPreview.svelte';
  import TextureEditor from './TextureEditor.svelte';
  import ModelWorkbench from './ModelWorkbench.svelte';
  import MechanicsEditor from './MechanicsEditor.svelte';
  import BehaviorRulesEditor from './BehaviorRulesEditor.svelte';
  import ModulesArea from './ModulesArea.svelte';
  import BasedOn from './BasedOn.svelte';
  import SaveAsTemplateDialog from '../components/SaveAsTemplateDialog.svelte';
  import { ops, unwrap } from '../api';
  import type { InheritanceView, ResourceRef, TypeInfo, TypeParam } from '../api';
  import { diagnosticNames } from '../diagnostics';
  import { usesModules, type AppearanceHost } from '../modules';
  import { freshTextureReference } from '../asset-choice';
  import { scrollBehavior } from '../motion';
  import {
    acceptsTemplates,
    selectableTemplates,
    servedSchema,
    studioSchema,
    usersLabel,
  } from '../templates';
  import { tick } from 'svelte';
  import {
    draftDirty,
    ensureDocumentDraft,
    fieldValue,
    getDocumentDraft,
    pendingRemoval,
    rawDraftDirty,
    reconcileDocumentDraft,
    removeDraftField,
    retainDocumentDraft,
    revertDraftField,
    saveDocumentDraft,
    setDraftField,
  } from '../drafts.svelte';
  import { rootIdForResource, sectionsOf, unrenderableKeys } from '../studio';
  import { localStudioVisual } from '../studio-visual';
  import { documentOf, plainText } from '../rich-text/model';
  import { invalidateThumbnails } from '../thumbnails';
  import {
    content,
    diagnostics,
    connection,
    errorMessage,
    logActivity,
    openStudio,
    registerEditorGuard,
    refreshPack,
    revealInAdvanced,
    revealInStudio,
    session,
    workspace,
  } from '../state.svelte';

  interface Props {
    resource: ResourceRef;
    onClose: () => void;
    active?: boolean;
  }

  let { resource, onClose, active = true }: Props = $props();
  let formElement: HTMLDivElement | undefined = $state();

  const documentDraft = $derived(getDocumentDraft(resource.pack, resource.path));
  const rootId = $derived(rootIdForResource(resource.id));
  const loaded = $derived(recordOf(documentDraft.base[rootId]));
  const draft = $derived(recordOf(documentDraft.values[rootId]));
  /** Read-only template view from the canonical resolver; reflects the saved `template:`. */
  const inheritance = $derived(
    (documentDraft.computed.inheritance as Record<string, InheritanceView> | undefined)?.[rootId],
  );
  /** What the previews draw: the draft over what the saved template chain supplies. */
  const effective = $derived<Record<string, unknown>>({ ...(inheritance?.inherited ?? {}), ...draft });
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
  /** A template is a partial definition of the type it serves, edited as that type (issue 154). */
  const isTemplate = $derived(typeDirectory === 'templates');
  const templateEntry = $derived(
    isTemplate
      ? workspace.templates.find((entry) => entry.pack === resource.pack && entry.id === rootId)
      : undefined,
  );
  /** Pinned on first sight: saving a template must not switch the studio it is open in. */
  let pinnedServes = $state('');
  $effect(() => {
    if (isTemplate && !pinnedServes && templateEntry?.serves[0]) pinnedServes = templateEntry.serves[0];
  });
  const servedDirectory = $derived(isTemplate ? pinnedServes || templateEntry?.serves[0] || '' : typeDirectory);
  /** The registered type that describes this document's fields. */
  const ownSchema = $derived(
    isTemplate
      ? servedSchema({ serves: servedDirectory ? [servedDirectory] : [] }, workspace.types)
      : workspace.types.find((type: TypeInfo) => type.directory === typeDirectory) ??
        workspace.types.find((type: TypeInfo) => type.name === resource.type),
  );
  /** What the form renders: no raw `template` control anywhere, and nothing required of a template. */
  const schema = $derived(studioSchema(ownSchema, isTemplate));
  const sections = $derived(sectionsOf(schema));
  /** The module layout (display card or Appearance, list, settings panel) fills the form and scrolls by pane. */
  const modulesLayout = $derived(usesModules(schema));
  const advancedOnly = $derived(unrenderableKeys(loaded, ownSchema));
  const takesTemplates = $derived(acceptsTemplates(ownSchema));
  const authoredTemplate = $derived(typeof draft.template === 'string' ? draft.template : '');
  const parents = $derived(
    selectableTemplates(workspace.templates, servedDirectory,
      isTemplate ? { pack: resource.pack, id: rootId } : undefined),
  );
  let savingAsTemplate = $state(false);
  const folder = $derived(resource.path.split('/').slice(0, -1).join('/'));
  const name = $derived(plainText(String(effective.display_name ?? '')).trim() || resource.id);
  const savedSummary = $derived(workspace.summaries.find((entry) =>
    entry.pack === resource.pack && entry.path === resource.path && entry.id === resource.id,
  ));
  const savedVisual = $derived(savedSummary?.visual);
  /** The saved model render applies only while every field that shapes it is unedited. */
  const render = $derived(
    ['texture', 'model', 'technique', 'model_parent'].every((field) => draft[field] === loaded[field])
      ? savedSummary?.render
      : undefined,
  );
  const visual = $derived(
    draft.texture === loaded.texture && draft.model === loaded.model && savedVisual
      ? savedVisual
      : localStudioVisual(resource.pack, String(effective.texture ?? ''), String(effective.model ?? ''), workspace.assets),
  );
  /** Items (and the templates that serve them) draw their look with the one Appearance component (issue 173). */
  const itemAppearance = $derived(servedDirectory === 'items' && usesModules(schema));
  const appearanceHost = $derived<AppearanceHost>({
    visual,
    render,
    material: String(effective.material ?? ''),
    version: textureVersion,
    onDrawTexture: (field, fresh) => editTexture(field, fresh),
    onEditModel: (field) => editModel(field),
  });
  const fieldsEditable = $derived(documentDraft.loaded && !loading && !saving &&
    !rawDraftDirty(documentDraft) && !documentDraft.uncertain && !documentDraft.stale);
  const canFocusName = $derived(fieldsEditable && documentOf(draft.display_name, false) !== null &&
    sections.some((section) => section.fields.some((field) => field.id === 'display_name')));
  const canFocusLore = $derived(fieldsEditable && documentOf(draft.lore, true) !== null &&
    sections.some((section) => section.fields.some((field) => field.id === 'lore')));

  /** The module studio's list and panel: asked to select the module that owns a field (issue 174). */
  let modules = $state<{ reveal: (fieldId: string) => boolean } | undefined>();

  /** Focuses a field, selecting the module that owns it first so its control is on screen. */
  function focusField(fieldId: string) {
    modules?.reveal(fieldId);
    void tick().then(() => focusControl(fieldId));
  }

  function focusControl(fieldId: string) {
    const field = [...(formElement?.querySelectorAll<HTMLElement>('[data-field]') ?? [])]
      .find((element) => element.dataset.field === fieldId);
    const control = field?.querySelector<HTMLElement>('[data-field-control]') ??
      field?.querySelector<HTMLElement>('input, textarea, select');
    control?.focus();
    control?.scrollIntoView({ block: 'nearest', behavior: scrollBehavior() });
  }
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
    diagnostics.entries.filter((entry) => diagnosticNames(entry, resource)),
  );

  function templateLabel(id: string): string {
    return id.slice(id.lastIndexOf(':') + 1);
  }

  function shortValue(value: unknown): string {
    const text = typeof value === 'string' ? value : JSON.stringify(value);
    return text === undefined ? '' : text.length > 48 ? `${text.slice(0, 47)}…` : text;
  }

  /** A template opens in its own studio (display + modules), like the type it serves. */
  function openTemplate(id: string) {
    const location = inheritance?.templates[id]
      ?? (() => {
        const entry = workspace.templates.find((candidate) => candidate.id === templateLabel(id));
        return entry ? { pack: entry.pack, path: entry.path } : undefined;
      })();
    if (!location) return;
    void revealInStudio({ pack: location.pack, path: location.path, type: 'templates', id: templateLabel(id) });
  }

  const detachBlocked = $derived(
    dirty ? 'Save or discard your changes first: detaching rewrites the saved file'
      : inheritance?.failure ? 'The template does not resolve' : '',
  );

  /** Copies the inherited values into this resource and drops the link: one revision, then reconcile. */
  async function detach() {
    const channel = session.channel;
    if (!channel || detachBlocked || !inheritance) return;
    const was = templateLabel(inheritance.template);
    try {
      unwrap(await ops.detachTemplate(channel, resource.pack, resource.path, documentDraft.rev, rootId));
      await reconcileDocumentDraft(documentDraft, channel);
      await refreshPack();
      logActivity('save', `${resource.path} detached from ${was}`);
    } catch (e) {
      documentDraft.error = errorMessage(e);
    }
  }

  function resetToInherited(fieldId: string) {
    try {
      removeDraftField(documentDraft, [rootId, fieldId]);
    } catch (e) {
      documentDraft.error = errorMessage(e);
    }
  }

  function undoReset(fieldId: string) {
    try {
      revertDraftField(documentDraft, [rootId, fieldId]);
    } catch (e) {
      documentDraft.error = errorMessage(e);
    }
  }

  /** The texture a slot names but the pack does not contain (only for this pack's own textures). */
  function missingTexture(field: TypeParam): string | null {
    if (field.uiHint !== 'texture' || !workspace.assets.length) return null;
    const raw = String(draft[field.id] ?? '').trim();
    if (!raw) return null;
    const colon = raw.indexOf(':');
    if (colon >= 0 && raw.slice(0, colon) !== resource.pack) return null;
    const reference = colon >= 0 ? raw.slice(colon + 1) : raw;
    const path = `assets/textures/${reference}.png`;
    return workspace.assets.some((asset) => asset.path === path) ? null : path;
  }

  function fieldIssue(fieldId: string) {
    return issues.find((entry) => entry.target?.field?.[0] === fieldId);
  }

  /** Problems → Fix: once this entry's form is on screen, bring the named field into view. */
  $effect(() => {
    const request = content.focus;
    if (!active || !request || !documentDraft.loaded || !formElement) return;
    if (request.pack !== resource.pack || request.path !== resource.path || request.id !== resource.id) return;
    content.focus = null;
    void tick().then(() => focusField(request.field));
  });

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
    invalidateThumbnails();
    modelling = null;
    const channel = session.channel;
    if (channel) {
      await reconcileDocumentDraft(documentDraft, channel, [committedField]);
      await refreshPack();
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
  function editTexture(field: TypeParam, fresh = false) {
    if (!canEditAsset()) return;
    const reference = fresh
      ? freshTextureReference(workspace.assets, servedDirectory, resource.id)
      : String(draft[field.id] ?? '') || `${servedDirectory}/${resource.id}`;
    textureDirty = false;
    texturing = { field: field.id, reference };
  }

  async function textureSaved() {
    if (!texturing) return;
    textureVersion += 1;
    invalidateThumbnails(resource.pack);
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
    if (!active || event.defaultPrevented || event.isComposing || texturing || modelling) return;
    // An open dialog (the asset picker) owns Escape: it closes the dialog, not the studio.
    if (event.key === 'Escape' && !dirty && !document.querySelector('[role="dialog"][aria-modal="true"]')) onClose();
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      void save();
    }
  }
</script>

<svelte:window onkeydown={onKeydown} />

{#snippet provenance(fieldId: string)}
  {#if inheritance && fieldId !== 'template'}
    {@const source = inheritance.sources[fieldId]}
    {@const removing = pendingRemoval(documentDraft, [rootId, fieldId])}
    {#if removing && source}
      <p class="provenance" data-provenance={fieldId}>
        Resets to the value from {templateLabel(source)} on Save & apply ({shortValue(inheritance.inherited[fieldId])}).
        <button type="button" class="link" onclick={() => undoReset(fieldId)}>Undo</button>
      </p>
    {:else if source && Object.hasOwn(draft, fieldId)}
      <p class="provenance" data-provenance={fieldId}>
        Overrides {templateLabel(source)} ({shortValue(inheritance.inherited[fieldId])}).
        <button type="button" class="link" onclick={() => resetToInherited(fieldId)}>Reset to inherited</button>
      </p>
    {:else if source}
      <p class="provenance inherited" data-provenance={fieldId}>
        Inherited from {templateLabel(source)}: {shortValue(inheritance.inherited[fieldId])}
      </p>
    {/if}
  {/if}
{/snippet}

<section class="studio view-enter">
  <header>
    <button type="button" class="back" onclick={onClose} title="back to {typeDirectory}" aria-label="back to {typeDirectory}">
      <Icon name="arrow-left" size={16} />
    </button>
    <h2 class="id" title="revision {rev}">{resource.id}</h2>
    {#if takesTemplates}
      <BasedOn
        authored={authoredTemplate}
        {inheritance}
        candidates={parents}
        detachBlocked={detachBlocked}
        editable={fieldsEditable}
        onChange={(ref) => set('template', ref)}
        onOpen={openTemplate}
        onDetach={detach}
      />
    {/if}
    {#if isTemplate}
      <span class="chip chip-accent" data-template-badge>
        Template for {ownSchema?.displayName ?? 'nothing yet'}
      </span>
    {/if}
    <span class="spacer"></span>
    {#if takesTemplates && !isTemplate}
      <button
        type="button"
        class="yaml"
        disabled={!fieldsEditable || dirty}
        title={dirty ? 'Save your changes first: a template is made from the saved values' : 'Share some of these fields as a template'}
        onclick={() => (savingAsTemplate = true)}
        data-save-as-template
      >
        Save as template…
      </button>
    {/if}
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
  {#if templateEntry}
    <p class="used-by" data-used-by>
      <strong>{usersLabel(templateEntry.usedBy)}</strong>
      {#each templateEntry.usedBy as user (user.path + user.id)}
        <button type="button" class="link" onclick={() => openStudio(user)}>{user.id}</button>
      {/each}
    </p>
  {/if}

  <div class="body" class:appearance={itemAppearance}>
    {#if !itemAppearance}
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
      {:else if servedDirectory === 'items'}
        <ItemPreview name={effective.display_name} lore={effective.lore} material={String(effective.material ?? '')}
          {visual} {render} version={textureVersion} {canFocusName} {canFocusLore} onFocus={focusField} />
      {:else}
        <StudioPreview
          pack={resource.pack}
          texture={String(effective.texture ?? '')}
          model={String(effective.model ?? '')}
          label={name}
          nonce={textureVersion}
          {visual}
          {render}
        />
      {/if}
    </aside>
    {/if}

    <div class="form" class:modules-form={modulesLayout} bind:this={formElement}>
      {#if rawDraftDirty(documentDraft)}
        <p class="pending-yaml">
          Advanced Files YAML changes are pending. Apply or discard them there before editing structured fields.
        </p>
      {/if}
      <fieldset
        class="field-controls"
        disabled={!fieldsEditable}
      >
      {#if !schema}
        <p class="error">No registered type owns {typeDirectory}/ — edit this file as YAML.</p>
      {:else if !documentDraft.loaded}
        <p class="loading">Loading document…</p>
      {:else if usesModules(schema)}
        <!-- The module studio (issues 152, 174): display card or Appearance, one row per module
             and the settings panel, derived from the same self-description as the sections below. -->
        <ModulesArea
          bind:this={modules}
          {resource}
          {schema}
          vocabulary={workspace.behavior}
          values={draft}
          host={schema.name}
          {provenance}
          {set}
          removeField={resetToInherited}
          onError={(message) => (documentDraft.error = message)}
          {focusField}
          {actionFor}
          {missingTexture}
          {fieldIssue}
          inherited={inheritance?.inherited ?? {}}
          inheritedSources={inheritance?.sources ?? {}}
          appearance={itemAppearance ? appearanceHost : undefined}
        />
      {:else}
        {#each sections as section (section.id)}
          <fieldset>
            <legend>{section.label}</legend>
            {#each section.fields as field (field.id)}
              {#if field.uiHint === 'mechanics'}
                <MechanicsEditor {field} value={valueOf(field)} pack={resource.pack}
                  vocabulary={workspace.behavior} onChange={(v) => set(field.id, v)}
                  onError={(message) => (documentDraft.error = message)} />
              {:else if field.uiHint === 'behavior-rules'}
                <BehaviorRulesEditor {field} value={valueOf(field)} pack={resource.pack}
                  host={schema.name} vocabulary={workspace.behavior} onChange={(v) => set(field.id, v)} />
              {:else if field.uiHint === 'structured-list'}
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
              {@const missing = missingTexture(field)}
              {#if missing}
                {@const issue = fieldIssue(field.id)}
                <div class="repair" role="group" aria-label={`Repair ${field.label || field.id}`}>
                  <p>
                    <strong>Missing texture.</strong> {missing} does not exist in this pack{issue ? ` (${issue.code})` : ''}, so
                    the game shows a missing-texture pattern.
                  </p>
                  <button type="button" onclick={() => editTexture(field)}>Draw it now</button>
                  <button type="button" onclick={() => focusField(field.id)}>Choose another texture</button>
                </div>
              {:else if fieldIssue(field.id)}
                {@const issue = fieldIssue(field.id)!}
                <p class="field-issue" role="note">{issue.code}: {issue.message}{issue.hint ? ` — ${issue.hint}` : ''}</p>
              {/if}
              {@render provenance(field.id)}
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

  {#if savingAsTemplate}
    <SaveAsTemplateDialog
      {resource}
      onCancel={() => (savingAsTemplate = false)}
      onDone={async (created) => {
        savingAsTemplate = false;
        await refreshPack();
        logActivity('save', `saved ${created.id} as a template`);
        openStudio({ pack: created.pack, type: 'templates', path: created.path, id: created.id });
      }}
    />
  {/if}

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
        typeDirectory={servedDirectory}
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
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    min-width: 0;
  }
  .back {
    background: none;
    border: none;
    color: var(--muted);
    font-size: var(--text-lg);
    cursor: pointer;
    padding: 0 var(--space-1);
  }
  .back:hover {
    color: var(--fg);
  }
  /* The resource id is the header's title: an identifier, so it is set in the mono face. */
  .id {
    margin: 0;
    font: 600 var(--text-md) var(--mono);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
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
    padding: var(--space-1) var(--space-3);
    font-size: var(--text-sm);
    cursor: pointer;
    white-space: nowrap;
  }
  .save {
    background: var(--accent);
    border-color: transparent;
    color: var(--accent-ink);
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
    gap: var(--space-5);
    padding: var(--space-4);
    overflow-y: hidden;
    overflow-x: hidden;
    min-width: 0;
  }
  /* The Appearance component is the studio's centre: one column, no side preview (the settings panel sits beside the list). */
  .body.appearance {
    grid-template-columns: minmax(0, 1fr);
  }
  /* The modules layout (issue 174) fills the form: the list and the settings panel each scroll on their own.
     No container-type here: containment makes this box the containing block of the fixed rich-text toolbars. */
  .form.modules-form {
    display: flex;
    flex-direction: column;
    max-width: none;
    overflow: hidden;
  }
  .form.modules-form > .field-controls {
    display: flex;
    flex: 1 1 0;
    flex-direction: column;
    min-height: 0;
  }
  .preview {
    min-width: 0;
    min-height: 0;
    overflow-y: auto;
    padding-right: var(--space-1);
  }
  .form {
    min-width: 0;
    min-height: 0;
    overflow-y: auto;
    padding-right: var(--space-2);
    max-width: 620px;
  }
  fieldset {
    border: none;
    border-top: 1px solid var(--border);
    margin: 0 0 var(--space-5);
    padding: var(--space-3) 0 0;
    min-width: 0;
  }
  .field-controls {
    border: none;
    margin: 0;
    padding: 0;
  }
  legend {
    font-size: var(--text-xs);
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--faint);
    padding: 0 var(--space-2) 0 0;
  }
  footer {
    display: flex;
    flex: none;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-top: 1px solid var(--border);
    font-size: var(--text-xs);
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
    padding: var(--space-2) var(--space-3);
    background: var(--error-bg);
    color: var(--error);
    font-size: var(--text-sm);
  }
  .notice,
  .loading {
    margin: 0;
    padding: var(--space-2) var(--space-3);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .pending-yaml {
    margin: 0 0 var(--space-3);
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--warn);
    font-size: var(--text-sm);
  }
  .used-by {
    display: flex;
    flex: none;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin: 0;
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .provenance,
  .field-issue {
    margin: -6px 0 var(--space-3);
    font-size: var(--text-xs);
    color: var(--muted);
  }
  .provenance.inherited {
    color: var(--faint);
  }
  .field-issue {
    color: var(--warn);
  }
  .repair {
    margin: -4px 0 var(--space-4);
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
  .pending-summary {
    border-bottom: 1px solid var(--border);
    color: var(--warn);
  }
  @media (max-width: 720px) {
    .body {
      display: flex;
      flex-direction: column;
    }
    .preview {
      max-height: 42%;
      flex: none;
    }
    .form {
      flex: 1 1 0;
    }
  }
</style>
