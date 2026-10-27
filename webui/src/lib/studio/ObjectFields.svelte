<script lang="ts">
  /**
   * A mapping edited as the labelled fields its schema declares (a sound's
   * music-disc section): each nested parameter gets the control its own
   * editor metadata asks for. Keys the schema does not declare stay in the map
   * exactly as written; clearing a text field removes its key instead of
   * writing an empty string.
   */
  import Field from './Field.svelte';
  import type { TypeParam } from '../api';

  interface Props {
    field: TypeParam;
    value: unknown;
    pack: string;
    onChange: (value: unknown) => void;
  }

  let { field, value, pack, onChange }: Props = $props();

  const map = $derived(
    value !== null && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, unknown>
      : {},
  );
  const extra = $derived(
    Object.keys(map).filter((key) => !(field.nested ?? []).some((param) => param.id === key)),
  );

  function set(param: TypeParam, next: unknown) {
    const copy = { ...map };
    if (next === '' || next === undefined) delete copy[param.id];
    else copy[param.id] = next;
    onChange(copy);
  }
</script>

<div class="object" data-object={field.id}>
  {#each field.nested ?? [] as param (param.id)}
    <Field field={param} value={map[param.id]} {pack} onChange={(next) => set(param, next)} />
  {/each}
  {#if extra.length > 0}
    <p class="note">Other settings ({extra.join(', ')}) are kept as written; edit them in Files.</p>
  {/if}
</div>

<style>
  .object {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
  }
  .note {
    margin: 0;
    font-size: var(--text-xs);
    color: var(--muted);
  }
</style>
