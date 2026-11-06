<script lang="ts">
  /**
   * A sound parameter (logic.md section 7.3): a sound key, with the packs' own sound events offered from the catalog
   * (the Sounds content type). A vanilla key (`entity.player.levelup`, `minecraft:block.chest.open`) is typed: the
   * editor has no list of the game's sounds, and a template (`${...}`) is allowed as for any text parameter.
   */
  import { workspace } from '../../../../state.svelte';
  import type { NodeFlag } from '../../../../logic/verdicts';
  import TemplateField from './TemplateField.svelte';

  interface Props {
    value: unknown;
    id?: string;
    label: string;
    disabled?: boolean;
    flags?: NodeFlag[];
    onchange: (value: unknown) => void;
    ondraft?: (value: unknown) => void;
  }

  let { value, id, label, disabled = false, flags = [], onchange, ondraft }: Props = $props();

  /** The packs' sound events, written `pack:id`, as the dev command and the sound action name them. */
  const catalog = $derived(
    workspace.resources
      .filter((ref) => ref.path.startsWith('sounds/'))
      .map((ref) => `${ref.pack}:${ref.id.slice(ref.id.lastIndexOf(':') + 1)}`),
  );
</script>

<TemplateField
  {id}
  {label}
  value={typeof value === 'string' ? value : ''}
  {disabled}
  {flags}
  suggestions={catalog}
  placeholder="entity.player.levelup, or pick a pack sound"
  onchange={(next) => onchange(next === '' ? undefined : next)}
  ondraft={(next) => ondraft?.(next)}
/>
{#if catalog.length > 0}
  <p class="note">{catalog.length} pack sound{catalog.length === 1 ? '' : 's'}: start typing a pack name to pick one.</p>
{/if}

<style>
  .note {
    margin: var(--space-1) 0 0;
    color: var(--faint);
    font-size: var(--text-xs);
  }
</style>
