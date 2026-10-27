<script lang="ts">
  /**
   * "Save as template" (issue 154): pick the fields a resource sets that other
   * things should share, name the template, and the server writes a new
   * `templates/<id>.yml` from the resource's own authored values. The resource
   * itself is left alone.
   */
  import Button from '../ui/Button.svelte';
  import Dialog from '../ui/Dialog.svelte';
  import Field from '../ui/Field.svelte';
  import { ops, unwrap } from '../api';
  import type { ResourceRef } from '../api';
  import { humanise, idFromName } from '../browser';
  import { rootIdForResource } from '../studio';
  import { extractableFields, valuePreview } from '../templates';
  import { errorMessage, session, workspace } from '../state.svelte';

  interface Props {
    resource: ResourceRef;
    onDone: (created: { pack: string; path: string; id: string }) => void;
    onCancel: () => void;
  }

  let { resource, onDone, onCancel }: Props = $props();

  let name = $state('');
  let loading = $state(true);
  let busy = $state(false);
  let error = $state('');
  let body = $state<Record<string, unknown>>({});
  let chosen = $state<Record<string, boolean>>({});

  const rootId = $derived(rootIdForResource(resource.id));
  const id = $derived(idFromName(name));
  const taken = $derived(
    id !== '' && workspace.templates.some((entry) => entry.pack === resource.pack && entry.id === id),
  );
  const fields = $derived(Object.keys(body));
  const picked = $derived(fields.filter((field) => chosen[field]));

  $effect(() => {
    const channel = session.channel;
    if (!channel) return;
    void (async () => {
      try {
        const doc = unwrap(await ops.get(channel, resource.pack, resource.path));
        const root = (doc.parsed as Record<string, Record<string, unknown>>)[rootId] ?? {};
        body = root;
        chosen = Object.fromEntries(extractableFields(root).map((entry) => [entry.field, entry.selected]));
      } catch (e) {
        error = errorMessage(e);
      } finally {
        loading = false;
      }
    })();
  });

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const channel = session.channel;
    if (!channel || busy || !id || taken || picked.length === 0) return;
    busy = true;
    error = '';
    try {
      const created = unwrap(await ops.saveAsTemplate(
        channel, resource.pack, resource.path, rootId, id, picked,
      ));
      onDone({ pack: resource.pack, path: String(created.path), id });
    } catch (e) {
      error = errorMessage(e);
      busy = false;
    }
  }
</script>

<Dialog title="Save as template" onClose={onCancel} width="520px">
  <form onsubmit={submit} class="save">
    <Field
      label="Template name"
      hint={id ? `id: ${id}` : `shared by everything based on it, taken from ${resource.id}`}
      error={taken ? `A template with the id "${id}" already exists.` : ''}
    >
      <!-- svelte-ignore a11y_autofocus -->
      <input bind:value={name} autofocus spellcheck="false" autocomplete="off" />
    </Field>

    <fieldset>
      <legend class="field-label">Fields to put in the template</legend>
      {#if loading}
        <p class="hint">Reading {resource.id}…</p>
      {:else if fields.length === 0}
        <p class="hint">{resource.id} sets nothing yet.</p>
      {:else}
        <ul>
          {#each fields as field (field)}
            <li>
              <label>
                <input type="checkbox" bind:checked={chosen[field]} data-field={field} />
                <span class="label">{humanise(field)}</span>
                <span class="value">{valuePreview(body[field])}</span>
              </label>
            </li>
          {/each}
        </ul>
        <p class="hint">
          {resource.id} keeps its own copy. Base new things on the template to share these values;
          change them there to change every one.
        </p>
      {/if}
    </fieldset>

    {#if error}<p class="error" role="alert">{error}</p>{/if}

    <div class="dialog-actions">
      <Button onclick={onCancel}>Cancel</Button>
      <Button type="submit" variant="primary" disabled={busy || !id || taken || picked.length === 0}>
        {busy ? 'Saving…' : 'Save template'}
      </Button>
    </div>
  </form>
</Dialog>

<style>
  .save {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }
  fieldset {
    border: none;
    margin: 0;
    padding: 0;
  }
  ul {
    list-style: none;
    margin: var(--space-1) 0 0;
    padding: 0;
    max-height: 240px;
    overflow-y: auto;
  }
  li label {
    display: grid;
    grid-template-columns: auto minmax(0, 10em) minmax(0, 1fr);
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-2);
    border-radius: var(--radius-sm);
    cursor: pointer;
  }
  li label:hover {
    background: var(--bg-hover);
  }
  .value {
    color: var(--faint);
    font-size: var(--text-xs);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .hint {
    margin: var(--space-2) 0 0;
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .error {
    margin: 0;
    color: var(--error);
    font-size: var(--text-sm);
  }
</style>
