<script lang="ts">
  import { onMount, tick, untrack } from 'svelte';
  import AssetPicker from './AssetPicker.svelte';
  import RichTextEditor from './RichTextEditor.svelte';
  import MaterialPicker from './MaterialPicker.svelte';
  import { ops, unwrap } from '../api';
  import type { ResourceRef, TypeInfo, TypeParam } from '../api';
  import {
    connection, logActivity, openBlockbench, registerEditorGuard, revealInStudio,
    session, setTabDirty, workspace, type DocTab,
  } from '../state.svelte';
  import {
    discardDocumentDraft, draftDirty, ensureDocumentDraft, getDocumentDraft,
    rawDraftDirty, reconcileDocumentDraft, reloadDocumentDraft,
    retainDocumentDraft, saveDocumentDraft, setDraftField,
  } from '../drafts.svelte';

  interface Props {
    resource: ResourceRef;
    tabKey: string;
    active?: boolean;
    source?: DocTab['source'];
  }

  let { resource, tabKey, active = true, source }: Props = $props();
  const draft = untrack(() => getDocumentDraft(resource.pack, resource.path));
  const data = $derived(draft.values);
  const rev = $derived(draft.rev);
  const dirty = $derived(draftDirty(draft));
  const saving = $derived(draft.saving);
  const error = $derived(draft.error);
  let mode = $state<'form' | 'yaml'>('form');
  let advancedOpen = $state(false);
  let sourceArea: HTMLTextAreaElement | undefined = $state();
  let numericInputs = $state<Record<string, string>>({});
  let numericErrors = $state<Record<string, string>>({});
  const invalidNumbers = $derived(Object.values(numericErrors).filter(Boolean));

  $effect(() => registerEditorGuard(tabKey, () => {
    if (!invalidNumbers.length) return true;
    if (!confirm('Discard incomplete number inputs? Other pending fields are kept.')) return false;
    numericInputs = {};
    numericErrors = {};
    return true;
  }, () => invalidNumbers.length > 0));

  $effect(() => {
    if (!active || !source || !draft.loaded || !sourceArea) return;
    const target = source;
    const area = sourceArea;
    const raw = draft.savedRaw;
    void tick().then(() => {
      if (!active || source !== target || sourceArea !== area) return;
      const lines = raw.replace(/\r\n?/g, '\n').split('\n');
      const row = Math.min(target.line - 1, lines.length - 1);
      const start = lines.slice(0, row).reduce((offset, line) => offset + line.length + 1, 0);
      const column = Math.min(target.column - 1, lines[row]!.length);
      area.focus();
      area.setSelectionRange(start + column, start + lines[row]!.length);
      area.scrollTop = Math.max(0, row * 20 - area.clientHeight / 2);
    });
  });

  const rootIds = $derived(Object.keys(data));
  const rootId = $derived(
    Object.hasOwn(data, resource.id) ? resource.id :
      Object.hasOwn(data, resource.id.split(':').pop() ?? '') ? resource.id.split(':').pop()! :
        rootIds[0] ?? resource.id,
  );
  // Schemas are keyed by the type's DIRECTORY (where the file lives) — the
  // registry name ("item") never equals the path prefix ("items").
  const typeDirectory = $derived(resource.path.split('/')[0] ?? '');
  const schema = $derived(
    workspace.types.find((t: TypeInfo) => t.directory === typeDirectory) ??
      workspace.types.find((t: TypeInfo) => t.name === resource.type) ??
      workspace.types.find((t: TypeInfo) => t.name === typeDirectory),
  );
  const fields = $derived(schema?.parameters ?? []);
  const sections = $derived([...new Set(
    fields.filter((field) => !field.advanced).map((field) => field.section ?? 'general'),
  )]);
  /** The document's own keys inside the root mapping. */
  const presentKeys = $derived(
    data[rootId] && typeof data[rootId] === 'object'
      ? Object.keys(data[rootId] as Record<string, unknown>)
      : [],
  );
  /** Fields the form cannot render safely — they stay in the YAML pane. */
  const advancedKeys = $derived(
    presentKeys.filter((key) => {
      const field = fields.find((candidate) => candidate.id === key);
      if (!field) return true; // unknown to the schema
      return field.type === 'raw' || (field.type === 'list' && !isScalarListField(key));
    }),
  );

  $effect(() => { setTabDirty(tabKey, dirty || invalidNumbers.length > 0); });
  const blocked = $derived(draft.loading || saving || draft.uncertain || draft.stale || !connection.connected);

  function isScalarListField(key: string): boolean {
    const current = (data[rootId] as Record<string, unknown>)?.[key];
    return Array.isArray(current)
      ? current.every((item) => typeof item !== 'object' || item === null)
      : true; // empty/absent lists are safe to create as scalars-per-line
  }

  async function load() {
    if (!session.channel) return;
    if ((dirty || draft.uncertain) &&
        !confirm('Discard this document draft and reload server files? Cancel to return and Save & apply first.')) return;
    if (invalidNumbers.length && !confirm('Discard incomplete number inputs and reload?')) return;
    numericInputs = {};
    numericErrors = {};
    await reloadDocumentDraft(draft, session.channel, true);
    if (!schema) mode = 'yaml';
  }

  const staleOnDisk = $derived(draft.stale);

  // External edits reach open tabs as a flag. A clean tab re-fetches the
  // authoritative bytes right away; a dirty tab keeps its edits and shows
  // the banner — the watcher must never clobber unsaved work.
  $effect(() => {
    if (staleOnDisk && !dirty && !saving && !draft.loading && !draft.uncertain && !draft.error && session.channel) {
      const channel = session.channel;
      untrack(() => { void reloadDocumentDraft(draft, channel, true); });
    }
  });

  function setMode(next: 'form' | 'yaml') {
    if (next === mode) return;
    if (invalidNumbers.length) {
      if (!confirm('Discard incomplete number inputs before changing representation?')) return;
      numericInputs = {};
      numericErrors = {};
    }
    if ((next === 'yaml' && draft.edits.length) || (next === 'form' && rawDraftDirty(draft))) {
      if (!confirm('Discard pending edits before changing representation? Cancel to return and Save & apply first.')) return;
      discardDocumentDraft(draft);
    }
    mode = next;
  }

  async function save() {
    if (!session.channel || invalidNumbers.length) return;
    if (await saveDocumentDraft(draft, session.channel)) {
      logActivity('save', `${resource.pack}/${resource.path} saved to files; reload requested`);
    }
  }

  function patch(fieldId: string, value: unknown) {
    try {
      setDraftField(draft, [rootId, fieldId], value);
    } catch (e) {
      draft.error = describe(e);
    }
  }

  function describe(e: unknown): string {
    if (e instanceof Error) {
      return e.message.includes('MTR-0034')
        ? 'This file changed elsewhere (conflict). Reload it and re-apply your edit.'
        : e.message;
    }
    return String(e);
  }

  function textValue(field: TypeParam): string {
    const current = (data[rootId] as Record<string, unknown> | undefined)?.[field.id];
    if (current === undefined || current === null) return field.default ?? '';
    return String(current);
  }

  function boolValue(field: TypeParam): boolean {
    const current = (data[rootId] as Record<string, unknown> | undefined)?.[field.id];
    if (typeof current === 'boolean') return current;
    return (field.default ?? '').toLowerCase() === 'true';
  }

  function listValue(field: TypeParam): string {
    const current = (data[rootId] as Record<string, unknown> | undefined)?.[field.id];
    if (current === undefined || current === null) return '';
    return Array.isArray(current) ? current.map((item) => String(item)).join('\n') : String(current);
  }

  function commitText(field: TypeParam, element: HTMLInputElement, rendered: string): void {
    if (element.value !== rendered) {
      patch(field.id, element.value);
    }
  }

  function commitNumber(field: TypeParam, element: HTMLInputElement, rendered: string): void {
    const key = JSON.stringify([rootId, field.id]);
    numericInputs[key] = element.value;
    const number = Number(element.value);
    if (!element.value.trim() || !Number.isFinite(number) ||
        (field.type === 'integer' && !Number.isInteger(number))) {
      numericErrors[key] = `Enter a valid ${field.type === 'integer' ? 'whole number' : 'number'} for ${field.label || field.id}.`;
      return;
    }
    delete numericErrors[key];
    if (element.value !== rendered) {
      patch(field.id, number);
    }
  }

  function commitList(field: TypeParam, element: HTMLTextAreaElement, rendered: string): void {
    const items = element.value === '' ? [] : element.value.split('\n');
    const before = rendered === '' ? [] : rendered.split('\n');
    if (JSON.stringify(items) !== JSON.stringify(before)) {
      void patch(field.id, items);
    }
  }

  async function validate() {
    try {
      const payload = unwrap(await ops.dryRun(session.channel!, draft.raw));
      draft.error = payload.ok ? '' : `syntax: ${payload.error}`;
    } catch (e) {
      draft.error = describe(e);
    }
  }

  onMount(() => {
    if (session.channel) void ensureDocumentDraft(draft, session.channel);
    return retainDocumentDraft(draft);
  });

  function onKeydown(event: KeyboardEvent) {
    if (!active || blocked) return;
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      void save();
    }
  }
</script>

<svelte:window onkeydown={onKeydown} />

<article>
  <header>
    <h2>{resource.pack}:{rootId}</h2>
    <span class="meta">{resource.path} · rev {rev}{dirty ? ' · unsaved changes' : ''}</span>
    {#if schema}
      <div class="modes" role="tablist" aria-label="editor mode">
        <button
          role="tab"
          aria-selected={mode === 'form'}
          class:active={mode === 'form'}
          onclick={() => setMode('form')}>form</button>
        <button
          role="tab"
          aria-selected={mode === 'yaml'}
          class:active={mode === 'yaml'}
          title="the underlying file — a dev tool"
          onclick={() => setMode('yaml')}>yaml</button>
      </div>
    {/if}
    {#if schema}
      <button onclick={() => void revealInStudio({ ...resource, id: rootId })}>Open in Studio</button>
    {/if}
    <button disabled={saving || draft.loading || !connection.connected} onclick={() => void load()}>reload</button>
    <button class="btn btn-primary" disabled={blocked || !dirty || invalidNumbers.length > 0} onclick={() => void save()}>
      {saving ? 'Saving...' : 'Save & apply'}
    </button>
  </header>
  <p class="impact">Saving applies gameplay changes server-wide. Publish pack releases the resource pack to other players.</p>
  {#if source && draft.loaded}
    <section class="source">
      <p class="impact">
        Saved source · line {source.line}, column {source.column}.
        Read-only; pending edits below are preserved. Locations refer to the last saved version.
        <button onclick={() => {
          const tab = workspace.tabs.find((entry) => entry.key === tabKey);
          if (tab?.kind === 'doc') tab.source = undefined;
        }}>Close source</button>
      </p>
      <textarea class="code source-code" rows="6" readonly spellcheck="false"
        aria-label="Saved source" value={draft.savedRaw} bind:this={sourceArea}></textarea>
    </section>
  {/if}
  {#if rootIds.length > 1 && mode === 'form'}
    <label>Resource
      <select value={rootId} onchange={(event) => {
        const tab = workspace.tabs.find((item) => item.key === tabKey);
        if (tab?.kind === 'doc') tab.id = event.currentTarget.value;
      }}>
        {#each rootIds as id}<option value={id}>{id}</option>{/each}
      </select>
    </label>
  {/if}

  {#if staleOnDisk}
    <div class="stale-banner">
      changed on disk — your unsaved edits are kept
      <button onclick={() => void load()}>discard and reload</button>
    </div>
  {/if}

  {#if error}
    <p class="error">{error}</p>
  {/if}
  {#each invalidNumbers as message}<p class="error">{message}</p>{/each}
  {#if draft.notice}<p class="impact" role="status">{draft.notice}</p>{/if}
  {#if draft.edits.length}
    <p class="impact">
      {draft.edits.length} pending fields in this file
      ({[...new Set(draft.edits.map((edit) => edit.field[0]))].join(', ')}).
    </p>
  {/if}
  {#if draft.uncertain}
    <button disabled={!connection.connected || draft.loading} onclick={() => {
      if (session.channel) void reconcileDocumentDraft(draft, session.channel);
    }}>Reconcile server files</button>
  {/if}

  {#if mode === 'form' && schema}
    {#if rawDraftDirty(draft)}
      <p class="stale-banner">Pending YAML edits are preserved. <button onclick={() => (mode = 'yaml')}>View pending YAML</button></p>
    {/if}
    <fieldset disabled={blocked || rawDraftDirty(draft)}>
    <div class="form">
      {#each sections as section (section)}
        <section class="studio-section">
          <h3>{section}</h3>
          {#each fields.filter((field) => !field.advanced && !advancedKeys.includes(field.id) && (field.section ?? 'general') === section).sort((a, b) => (a.order ?? 0) - (b.order ?? 0)) as field (field.id)}
        {@const value = textValue(field)}
        {@const controlId = `file-${tabKey}-${rootId}-${field.id}`}
        <div class="field">
          <label class="fid" for={controlId}>
            {field.label || field.id}
            {#if field.required}<em title="required">*</em>{/if}
          </label>

          {#if field.uiHint === 'material'}
            <MaterialPicker id={controlId} label={field.label || field.id} {value}
              choices={field.allowedValues} required={field.required} onChange={(next) => patch(field.id, next)} />
          {:else if field.uiHint === 'formatted-text' || field.uiHint === 'formatted-lines'}
            <RichTextEditor id={controlId} label={field.label || field.id} placeholder={field.example ?? ''}
              value={(data[rootId] as Record<string, unknown> | undefined)?.[field.id]}
              multiline={field.uiHint === 'formatted-lines'} onChange={(next) => patch(field.id, next)} />
          {:else if field.uiHint === 'texture' || field.uiHint === 'model'}
            <AssetPicker
              id={controlId}
              pack={resource.pack}
              hint={field.uiHint}
              value={value}
              onChange={(next) => patch(field.id, next)}
            />
            {#if field.uiHint === 'model'}
              <button
                class="model-action"
                type="button"
                onclick={() => openBlockbench({
                  pack: resource.pack,
                  path: resource.path,
                  rev,
                  modelRef: value,
                  rootId,
                })}
              >
                {value ? 'Edit model' : 'Create model'}
              </button>
            {/if}
          {:else if field.allowedValues && field.allowedValues.length > 0}
            <select
              id={controlId}
              value={value}
              onchange={(event) => void patch(field.id, event.currentTarget.value)}
            >
              <option value=""></option>
              {#each field.allowedValues as option (option)}
                <option value={option}>{option}</option>
              {/each}
            </select>
          {:else if field.type === 'boolean'}
            <input
              id={controlId}
              type="checkbox"
              checked={boolValue(field)}
              onchange={(event) => void patch(field.id, event.currentTarget.checked)}
            />
          {:else if field.type === 'integer' || field.type === 'decimal'}
            <input
              id={controlId}
              type="text"
              inputmode={field.type === 'decimal' ? 'decimal' : 'numeric'}
              value={numericInputs[JSON.stringify([rootId, field.id])] ?? value}
              oninput={(event) => commitNumber(field, event.currentTarget, value)}
              onblur={() => {
                const key = JSON.stringify([rootId, field.id]);
                if (!numericErrors[key]) delete numericInputs[key];
              }}
              onkeydown={(event) => {
                if (event.key === 'Enter') {
                  event.currentTarget.blur();
                }
              }}
            />
          {:else if field.type === 'list'}
            <textarea
              id={controlId}
              class="list"
              rows="3"
              spellcheck="false"
              value={listValue(field)}
              placeholder="one entry per line"
              oninput={(event) => commitList(field, event.currentTarget, listValue(field))}
            ></textarea>
          {:else}
            <input
              id={controlId}
              value={value}
              placeholder={field.default ?? ''}
              title={field.description ?? field.example ?? ''}
              oninput={(event) => commitText(field, event.currentTarget, value)}
              onkeydown={(event) => {
                if (event.key === 'Enter') {
                  event.currentTarget.blur();
                }
              }}
            />
          {/if}
          {#if field.description}
            <small>{field.description}</small>
          {/if}
        </div>
          {/each}
        </section>
      {/each}

      {#if advancedKeys.length > 0}
        <details class="advanced" bind:open={advancedOpen}>
          <summary>
            advanced ({advancedKeys.length}) — edited as YAML:
            <span class="keys">{advancedKeys.join(', ')}</span>
          </summary>
          <div class="advrow">
            <button onclick={() => setMode('yaml')}>
              Open YAML
            </button>
          </div>
        </details>
      {/if}
    </div>
    </fieldset>
  {:else}
    <textarea
      class="code full"
      bind:value={draft.raw}
      disabled={blocked}
      rows="22"
      spellcheck="false"
      aria-label="raw YAML"
    ></textarea>
    <footer>
      <button onclick={() => void validate()}>check syntax</button>
    </footer>
  {/if}
</article>

<style>
  .source-code {
    width: 100%;
    box-sizing: border-box;
    line-height: 20px;
    resize: vertical;
  }
  article {
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: var(--space-4) var(--space-5);
    height: 100%;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
  }
  header {
    display: flex;
    align-items: baseline;
    gap: var(--space-3);
    flex-wrap: wrap;
  }
  h2 {
    margin: 0 auto 0 0;
    font-size: var(--text-lg);
  }
  .meta {
    color: var(--muted);
    font-size: var(--text-sm);
    margin-right: auto;
  }
  .modes {
    display: flex;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .modes button:not(.btn) {
    background: none;
    border: none;
    color: var(--muted);
    font-size: var(--text-sm);
    padding: var(--space-1) var(--space-3);
    cursor: pointer;
  }
  .modes button:not(.btn).active {
    background: var(--bg-raised);
    color: var(--accent);
  }
  button:not(.btn) {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-3);
    cursor: pointer;
    font-size: var(--text-base);
  }
  button:not(.btn):disabled {
    opacity: 0.5;
    cursor: default;
  }
  .form {
    margin-top: var(--space-3);
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: var(--space-1) var(--space-5);
    align-content: start;
  }
  fieldset { border: 0; padding: 0; margin: 0; min-width: 0; overflow: auto; }
  .impact { color: var(--muted); font-size: var(--text-sm); margin: var(--space-2) 0; }
  .studio-section { display: contents; }
  .studio-section h3 { grid-column: 1 / -1; margin: var(--space-4) 0 var(--space-half); color: var(--muted); font-size: var(--text-xs); letter-spacing: .08em; text-transform: uppercase; }
  .studio-section:first-child h3 { margin-top: 0; }
  .field {
    display: block;
    margin: var(--space-2) 0;
  }
  .fid {
    display: block;
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .fid em {
    color: var(--warn);
    font-style: normal;
  }
  input:not([type='checkbox']),
  select,
  textarea.list,
  textarea.code {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-2);
    font: var(--text-base) var(--mono);
  }
  textarea.code {
    resize: vertical;
    line-height: 1.55;
  }
  textarea.full {
    margin-top: var(--space-3);
    flex: 1;
    min-height: 380px;
  }
  textarea.list {
    font-size: var(--text-sm);
  }
  small {
    color: var(--muted);
    display: block;
    font-size: var(--text-xs);
  }
  .advanced {
    grid-column: 1 / -1;
    margin-top: var(--space-3);
    border-top: 1px dotted var(--border-strong);
    padding-top: var(--space-2);
  }
  .advanced summary {
    cursor: pointer;
    font-size: var(--text-sm);
    color: var(--muted);
  }
  .advanced .keys {
    color: var(--faint);
    font-family: var(--mono);
    font-size: var(--text-xs);
  }
  .advrow {
    display: flex;
    justify-content: flex-end;
    margin-top: var(--space-2);
  }
  .error {
    background: var(--error-bg);
    color: var(--error);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-3);
    white-space: pre-wrap;
  }
  .stale-banner {
    margin-top: var(--space-3);
    display: flex;
    align-items: center;
    gap: var(--space-3);
    background: var(--warn-bg);
    color: var(--warn);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-3);
    font-size: var(--text-base);
  }
  .stale-banner button:not(.btn) {
    margin-left: auto;
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-half) var(--space-3);
    cursor: pointer;
    font-size: var(--text-sm);
  }
  footer {
    margin-top: var(--space-3);
    display: flex;
    justify-content: flex-end;
  }
</style>
