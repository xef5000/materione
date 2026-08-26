<script lang="ts">
  import type { EditorChannel } from '../protocol/session';
  import type { Response } from '../protocol/wire';
  import { ops, unwrap } from '../api';
  import type { ResourceRef, TypeInfo } from '../api';

  interface Props {
    channel: EditorChannel;
    resource: ResourceRef;
    schema?: TypeInfo;
    onSaved: (message: string) => void;
  }

  let { channel, resource, schema, onSaved }: Props = $props();

  let raw = $state('');
  let rev = $state(0);
  let data = $state<Record<string, unknown>>({});
  let error = $state('');
  let dirty = $state(false);
  let saving = $state(false);

  // The first top-level key of the file is the resource this editor targets.
  const rootId = $derived(Object.keys(data)[0] ?? resource.id);
  const fields = $derived(schema?.parameters ?? []);

  async function load() {
    try {
      const payload = unwrap(await ops.get(channel, resource.pack, resource.path));
      raw = String(payload.raw ?? '');
      rev = Number(payload.rev ?? 0);
      // The wire carries `parsed` as a JSON object already — no re-parsing.
      data = (payload.parsed as Record<string, unknown>) ?? {};
      error = '';
      dirty = false;
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      if (message.includes('MTR-0009')) {
        // Genuinely absent: offer a starter skeleton built from the schema.
        raw = skeleton();
        rev = 0;
        data = {};
        error = '';
        dirty = true;
      } else {
        raw = '';
        rev = 0;
        data = {};
        error = message;
      }
    }
  }

  function skeleton(): string {
    const id = resource.id === '<parse error>' || resource.id.includes(' ') ? 'new_entry' : resource.id;
    const lines = [`# ${resource.pack}:${id}`, `${id}:`];
    for (const field of fields) {
      lines.push(`  ${field.id}: ${field.default ?? ''}${field.required ? '' : ''}`);
    }
    return lines.join('\n') + '\n';
  }

  async function saveRaw() {
    saving = true;
    try {
      const result = unwrap(await ops.put(channel, resource.pack, resource.path, rev, raw));
      rev = Number(result.rev);
      data = (result.parsed as Record<string, unknown>) ?? {};
      dirty = false;
      error = '';
      onSaved(`saved ${resource.pack}/${resource.path} → rev ${rev}; targeted reload queued`);
    } catch (e) {
      error = describe(e);
    } finally {
      saving = false;
    }
  }

  /** Form edits patch ONE scalar through the lossless layer — comments survive. */
  async function setScalar(fieldId: string, value: string | number | boolean) {
    try {
      const result = unwrap(
        await ops.setScalar(channel, resource.pack, resource.path, rev, [rootId, fieldId], value),
      );
      rev = Number(result.rev);
      raw = String(result.raw);
      data = JSON.parse(JSON.stringify(parseYamlView(raw)));
      onSaved(`${fieldId} updated → rev ${rev}`);
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

  /**
   * A forgiving view of the YAML for form bindings: scalars only, via a
   * regex-free line walk that understands "key: value" at two levels. The
   * authoritative parse lives server-side; this only pre-fills inputs.
   */
  function parseYamlView(text: string): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    let current: Record<string, unknown> | null = null;
    for (const line of text.split('\n')) {
      const match = /^(\s*)([A-Za-z_][\w .\-/]*)\s*:\s*(.*?)\s*(?:#.*)?$/.exec(line);
      if (!match) continue;
      const [, indent, key, value] = match;
      if ((indent?.length ?? 0) === 0) {
        current = {};
        out[key!] = current;
      } else if (current && value !== '') {
        const unquoted = value!.replace(/^"(.*)"$/, '$1');
        current[key!] =
          /^\d+$/.test(unquoted) ? Number(unquoted) : unquoted === 'true' ? true : unquoted;
      }
    }
    return out;
  }

  function currentValue(fieldId: string): string {
    const root = data[rootId];
    if (root && typeof root === 'object' && fieldId in (root as Record<string, unknown>)) {
      return String((root as Record<string, unknown>)[fieldId]);
    }
    return schema?.parameters.find((f) => f.id === fieldId)?.default ?? '';
  }

  async function validate() {
    try {
      const response: Response = await channel.call('validate.dryRun', { content: raw });
      const payload = unwrap(response);
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
    <button onclick={() => void load()}>reload</button>
    <button class="primary" disabled={saving || !dirty} onclick={() => void saveRaw()}>
      {saving ? 'saving…' : 'save'}
    </button>
  </header>

  {#if error}
    <p class="error">{error}</p>
  {/if}

  <div class="columns">
    <textarea
      bind:value={raw}
      oninput={() => (dirty = true)}
      rows="20"
      spellcheck="false"
      aria-label="raw YAML"
    ></textarea>

    {#if schema}
      <div class="form">
        <h3>{schema.displayName} · form</h3>
        <p class="hint">edits patch single values losslessly — comments stay</p>
        {#each fields as field (field.id)}
          {@const value = currentValue(field.id)}
          <label>
            <span class="fid">
              {field.id}
              {#if field.required}<em title="required">*</em>{/if}
            </span>
            {#if field.allowedValues && field.allowedValues.length > 0}
              <select
                value={value}
                onchange={(event) => void setScalar(field.id, event.currentTarget.value)}
              >
                <option value=""></option>
                {#each field.allowedValues as option (option)}
                  <option value={option}>{option}</option>
                {/each}
              </select>
            {:else}
              <input
                value={value}
                placeholder={field.default ?? ''}
                title={field.description ?? ''}
                onchange={(event) => {
                  const input = event.currentTarget.value;
                  if (input !== value) {
                    void setScalar(field.id, input);
                  }
                }}
              />
            {/if}
            {#if field.description}
              <small>{field.description}</small>
            {/if}
          </label>
        {/each}
        <p class="hint">
          lists & nested structures are edited in the YAML pane; forms cover scalars.
        </p>
      </div>
    {:else}
      <div class="form muted">
        <h3>no schema loaded</h3>
      </div>
    {/if}
  </div>

  <footer>
    <button onclick={() => void validate()}>check syntax</button>
  </footer>
</article>

<style>
  article {
    background: #181c24;
    border: 1px solid #262c37;
    border-radius: 12px;
    padding: 14px 18px;
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
    color: #8b93a5;
    font-size: 12px;
    margin-right: auto;
  }
  button {
    border: 1px solid #3b4453;
    background: none;
    color: inherit;
    border-radius: 6px;
    padding: 4px 12px;
    cursor: pointer;
  }
  button.primary {
    background: #2f6fed;
    border-color: #2f6fed;
    color: white;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .columns {
    display: grid;
    grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
    gap: 14px;
    margin-top: 10px;
  }
  textarea {
    width: 100%;
    box-sizing: border-box;
    min-height: 380px;
    background: #0e1116;
    color: #c8d3f5;
    border: 1px solid #262c37;
    border-radius: 8px;
    font: 13px/1.55 ui-monospace, monospace;
    padding: 10px;
    resize: vertical;
  }
  .form h3 {
    margin: 0 0 2px;
    font-size: 13px;
  }
  .form .hint,
  .muted {
    color: #586074;
    font-size: 12px;
  }
  label {
    display: block;
    margin: 10px 0;
  }
  .fid {
    display: block;
    font-size: 12.5px;
    font-weight: 600;
  }
  .fid em {
    color: #e0af68;
    font-style: normal;
  }
  input,
  select {
    width: 100%;
    box-sizing: border-box;
    background: #0e1116;
    color: inherit;
    border: 1px solid #3b4453;
    border-radius: 6px;
    padding: 6px 8px;
    font: 13px ui-monospace, monospace;
  }
  small {
    color: #8b93a5;
    display: block;
  }
  .error {
    background: #2a1620;
    color: #f7768e;
    border-radius: 8px;
    padding: 8px 12px;
    white-space: pre-wrap;
  }
  footer {
    margin-top: 10px;
    display: flex;
    justify-content: flex-end;
  }
</style>
