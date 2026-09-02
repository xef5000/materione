<script lang="ts">
  import AssetPicker from './AssetPicker.svelte';
  import { ops, unwrap } from '../api';
  import type { ResourceRef, TypeInfo, TypeParam } from '../api';
  import { clearDocStale, logActivity, session, setTabDirty, workspace } from '../state.svelte';

  interface Props {
    resource: ResourceRef;
    tabKey: string;
  }

  let { resource, tabKey }: Props = $props();

  let raw = $state('');
  let rev = $state(0);
  let data = $state<Record<string, unknown>>({});
  let error = $state('');
  let dirty = $state(false);
  let saving = $state(false);
  // Form is the default surface; YAML is the dev-tool view. Files without a
  // schema (pack.yml) start in YAML directly.
  let mode = $state<'form' | 'yaml'>('form');
  let advancedOpen = $state(false);

  const rootId = $derived(String(Object.keys(data)[0] ?? resource.id));
  // Schemas are keyed by the type's DIRECTORY (where the file lives) — the
  // registry name ("item") never equals the path prefix ("items").
  const typeDirectory = $derived(resource.path.split('/')[0] ?? '');
  const schema = $derived(
    workspace.types.find((t: TypeInfo) => t.directory === typeDirectory) ??
      workspace.types.find((t: TypeInfo) => t.name === resource.type) ??
      workspace.types.find((t: TypeInfo) => t.name === typeDirectory),
  );
  const fields = $derived(schema?.parameters ?? []);
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

  function markDirty(value: boolean) {
    dirty = value;
    setTabDirty(tabKey, value);
  }

  function isScalarListField(key: string): boolean {
    const current = (data[rootId] as Record<string, unknown>)?.[key];
    return Array.isArray(current)
      ? current.every((item) => typeof item !== 'object' || item === null)
      : true; // empty/absent lists are safe to create as scalars-per-line
  }

  async function load() {
    try {
      const payload = unwrap(await ops.get(session.channel!, resource.pack, resource.path));
      raw = String(payload.raw ?? '');
      rev = Number(payload.rev ?? 0);
      data = (payload.parsed as Record<string, unknown>) ?? {};
      error = '';
      clearDocStale(tabKey);
      markDirty(false);
      if (!schema) mode = 'yaml';
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      if (message.includes('MTR-0009')) {
        raw = skeleton();
        rev = 0;
        data = {};
        error = '';
        markDirty(true);
        mode = 'yaml';
      } else {
        raw = '';
        rev = 0;
        data = {};
        error = message;
      }
    }
  }

  const staleOnDisk = $derived(workspace.staleDocs[tabKey] === true);

  // External edits reach open tabs as a flag. A clean tab re-fetches the
  // authoritative bytes right away; a dirty tab keeps its edits and shows
  // the banner — the watcher must never clobber unsaved work.
  $effect(() => {
    if (staleOnDisk && !dirty) {
      void load();
    }
  });

  function skeleton(): string {
    const id =
      resource.id === '<parse error>' || resource.id.includes(' ') ? 'new_entry' : resource.id;
    const lines = [`# ${resource.pack}:${id}`, `${id}:`];
    for (const field of fields) {
      lines.push(`  ${field.id}: ${field.default ?? ''}`);
    }
    return lines.join('\n') + '\n';
  }

  /** Full-document save (YAML view / advanced pane / new files). */
  async function saveRaw() {
    saving = true;
    try {
      const result = unwrap(await ops.put(session.channel!, resource.pack, resource.path, rev, raw));
      rev = Number(result.rev);
      data = (result.parsed as Record<string, unknown>) ?? {};
      markDirty(false);
      error = '';
      logActivity(
        'save',
        `saved ${resource.pack}/${resource.path} → rev ${rev}; targeted reload queued`,
      );
    } catch (e) {
      error = describe(e);
    } finally {
      saving = false;
    }
  }

  /**
   * Form edits patch ONE node through the lossless layer — comments and
   * untouched lines survive. The response re-read keeps the JSON view
   * authoritative instead of re-parsing YAML client-side.
   */
  async function patch(fieldId: string, value: string | number | boolean | string[]) {
    try {
      const result = unwrap(
        await ops.setScalar(session.channel!, resource.pack, resource.path, rev, [rootId, fieldId], value),
      );
      rev = Number(result.rev);
      const fresh = unwrap(await ops.get(session.channel!, resource.pack, resource.path));
      raw = String(fresh.raw);
      data = (fresh.parsed as Record<string, unknown>) ?? {};
      logActivity('save', `${resource.pack}:${rootId} · ${fieldId} updated → rev ${rev}`);
    } catch (e) {
      error = describe(e);
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

  // Text-like fields commit on BLUR (or Enter), not `change`: blur does not
  // depend on the browser's dirty-value flag, so every edit path — typing,
  // paste, programmatic — commits exactly once when focus leaves.
  function commitText(field: TypeParam, element: HTMLInputElement, rendered: string): void {
    if (element.value !== rendered) {
      void patch(field.id, element.value);
    }
  }

  function commitNumber(field: TypeParam, element: HTMLInputElement, rendered: string): void {
    if (element.value !== rendered) {
      void patch(field.id, Number(element.value));
    }
  }

  function commitList(field: TypeParam, element: HTMLTextAreaElement, rendered: string): void {
    const items = element.value.split('\n').map((line) => line.trim()).filter(Boolean);
    const before = rendered.split('\n').map((line) => line.trim()).filter(Boolean);
    if (JSON.stringify(items) !== JSON.stringify(before)) {
      void patch(field.id, items);
    }
  }

  async function validate() {
    try {
      const payload = unwrap(await ops.dryRun(session.channel!, raw));
      error = payload.ok ? '' : `syntax: ${payload.error}`;
    } catch (e) {
      error = describe(e);
    }
  }

  $effect(() => {
    void load();
  });
</script>

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
          onclick={() => (mode = 'form')}>form</button>
        <button
          role="tab"
          aria-selected={mode === 'yaml'}
          class:active={mode === 'yaml'}
          title="the underlying file — a dev tool"
          onclick={() => (mode = 'yaml')}>yaml</button>
      </div>
    {/if}
    <button onclick={() => void load()}>reload</button>
    <button class="primary" disabled={saving || !dirty} onclick={() => void saveRaw()}>
      {saving ? 'saving…' : 'save'}
    </button>
  </header>

  {#if staleOnDisk}
    <div class="stale-banner">
      changed on disk — your unsaved edits are kept
      <button onclick={() => {
        if (dirty && !confirm('Reload this file from disk and discard unsaved changes?')) {
          return;
        }
        clearDocStale(tabKey);
        void load();
      }}>reload</button>
    </div>
  {/if}

  {#if error}
    <p class="error">{error}</p>
  {/if}

  {#if mode === 'form' && schema}
    <div class="form">
      {#each fields.filter((field) => !advancedKeys.includes(field.id)) as field (field.id)}
        {@const value = textValue(field)}
        <label class="field">
          <span class="fid">
            {field.id}
            {#if field.required}<em title="required">*</em>{/if}
          </span>

          {#if field.uiHint === 'texture' || field.uiHint === 'model'}
            <AssetPicker
              pack={resource.pack}
              hint={field.uiHint}
              value={value}
              onChange={(next) => void patch(field.id, next)}
            />
          {:else if field.allowedValues && field.allowedValues.length > 0}
            <select
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
              type="checkbox"
              checked={boolValue(field)}
              onchange={(event) => void patch(field.id, event.currentTarget.checked)}
            />
          {:else if field.type === 'integer' || field.type === 'decimal'}
            <input
              type="number"
              step={field.type === 'decimal' ? 'any' : '1'}
              value={value}
              onblur={(event) => commitNumber(field, event.currentTarget, value)}
              onkeydown={(event) => {
                if (event.key === 'Enter') {
                  event.currentTarget.blur();
                }
              }}
            />
          {:else if field.type === 'list'}
            <textarea
              class="list"
              rows="3"
              spellcheck="false"
              value={listValue(field)}
              placeholder="one entry per line"
              onblur={(event) => commitList(field, event.currentTarget, listValue(field))}
            ></textarea>
          {:else}
            <input
              value={value}
              placeholder={field.default ?? ''}
              title={field.description ?? ''}
              onblur={(event) => commitText(field, event.currentTarget, value)}
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
        </label>
      {/each}

      {#if advancedKeys.length > 0}
        <details class="advanced" bind:open={advancedOpen}>
          <summary>
            advanced ({advancedKeys.length}) — edited as YAML:
            <span class="keys">{advancedKeys.join(', ')}</span>
          </summary>
          <textarea
            class="code"
            bind:value={raw}
            oninput={() => markDirty(true)}
            rows="14"
            spellcheck="false"
            aria-label="advanced YAML"
          ></textarea>
          <div class="advrow">
            <button disabled={!dirty} onclick={() => void saveRaw()}>
              apply advanced changes
            </button>
          </div>
        </details>
      {/if}
    </div>
  {:else}
    <textarea
      class="code full"
      bind:value={raw}
      oninput={() => markDirty(true)}
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
  article {
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 14px 18px;
    height: 100%;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
  }
  header {
    display: flex;
    align-items: baseline;
    gap: 12px;
  }
  h2 {
    margin: 0 auto 0 0;
    font-size: 16px;
  }
  .meta {
    color: var(--muted);
    font-size: 12px;
    margin-right: auto;
  }
  .modes {
    display: flex;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .modes button {
    background: none;
    border: none;
    color: var(--muted);
    font-size: 12px;
    padding: 3px 10px;
    cursor: pointer;
  }
  .modes button.active {
    background: var(--bg-raised);
    color: var(--accent);
  }
  button {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 4px 12px;
    cursor: pointer;
    font-size: 13px;
  }
  button.primary {
    background: var(--primary);
    border-color: var(--primary);
    color: white;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .form {
    margin-top: 12px;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 4px 20px;
    align-content: start;
  }
  .field {
    display: block;
    margin: 6px 0;
  }
  .fid {
    display: block;
    font-size: 12.5px;
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
    padding: 6px 8px;
    font: 13px var(--mono);
  }
  textarea.code {
    resize: vertical;
    line-height: 1.55;
  }
  textarea.full {
    margin-top: 12px;
    flex: 1;
    min-height: 380px;
  }
  textarea.list {
    font-size: 12.5px;
  }
  small {
    color: var(--muted);
    display: block;
    font-size: 11px;
  }
  .advanced {
    grid-column: 1 / -1;
    margin-top: 10px;
    border-top: 1px dotted var(--border-strong);
    padding-top: 8px;
  }
  .advanced summary {
    cursor: pointer;
    font-size: 12.5px;
    color: var(--muted);
  }
  .advanced .keys {
    color: var(--faint);
    font-family: var(--mono);
    font-size: 11.5px;
  }
  .advanced textarea {
    margin-top: 8px;
  }
  .advrow {
    display: flex;
    justify-content: flex-end;
    margin-top: 6px;
  }
  .error {
    background: var(--error-bg);
    color: var(--error);
    border-radius: 8px;
    padding: 8px 12px;
    white-space: pre-wrap;
  }
  .stale-banner {
    margin-top: 10px;
    display: flex;
    align-items: center;
    gap: 10px;
    background: var(--warn-bg, rgba(255, 193, 7, 0.12));
    color: var(--warn);
    border-radius: 8px;
    padding: 6px 12px;
    font-size: 13px;
  }
  .stale-banner button {
    margin-left: auto;
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 2px 10px;
    cursor: pointer;
    font-size: 12.5px;
  }
  footer {
    margin-top: 10px;
    display: flex;
    justify-content: flex-end;
  }
</style>
