<script lang="ts">
  /**
   * An item parameter (logic.md section 7.3): the studio's item browser, the one the item and recipe studios use. A
   * pick writes `pack:id` (a bare id would resolve in the `materione` namespace in game) or `minecraft:<material>`. A
   * value that is a template (`${args.item}`) is not a pick, so it is shown as the template it is, with a way back to
   * the browser.
   */
  import { storedItemRef } from '../../../../gui/items';
  import type { NodeFlag } from '../../../../logic/verdicts';
  import ItemPicker from '../../../ItemPicker.svelte';
  import { useInspector } from '../context';
  import TemplateField from './TemplateField.svelte';

  interface Props {
    value: unknown;
    id?: string;
    label: string;
    required?: boolean;
    disabled?: boolean;
    flags?: NodeFlag[];
    onchange: (value: unknown) => void;
    ondraft?: (value: unknown) => void;
  }

  let { value, id, label, required = false, disabled = false, flags = [], onchange, ondraft }: Props = $props();

  const inspector = useInspector();
  const text = $derived(typeof value === 'string' ? value : '');
  const templated = $derived(/\$\{|%/.test(text));
</script>

{#if templated}
  <TemplateField {id} {label} value={text} {disabled} {flags} onchange={(next) => onchange(next)} ondraft={(next) => ondraft?.(next)} />
  <button type="button" class="again" {disabled} onclick={() => onchange(undefined)}>Choose an item instead</button>
{:else}
  <ItemPicker
    {id}
    pack={inspector.canvas.gui.pack}
    {label}
    value={text}
    {required}
    placeholder="Choose an item"
    onPick={(picked) => onchange(picked === '' ? undefined : storedItemRef(picked, inspector.canvas.gui.pack))}
  />
{/if}

<style>
  .again {
    margin-top: var(--space-1);
    padding: 0;
    background: none;
    border: 0;
    color: var(--accent);
    font-size: var(--text-xs);
    cursor: pointer;
    text-decoration: underline;
  }
</style>
