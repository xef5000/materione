<script lang="ts">
  /**
   * The control for one parameter value (logic.md section 7.3, 31), chosen by the self-description: the item browser
   * for an item, the sound catalog for a sound, the colour picker, a number field, a toggle, a select for fixed values,
   * rows for a list, the variable picker, and the template field for any other text. A value a control cannot hold falls
   * back to the nearest one that can (`kindFor`), so a field never hides or rewrites what the file says.
   */
  import GuiColorField from '../../gui/ColorField.svelte';
  import type { FieldKind } from '../../../logic/params';
  import type { NodeFlag } from '../../../logic/verdicts';
  import type { ParamInfo } from '../../../logic/vocabulary';
  import BooleanField from './fields/BooleanField.svelte';
  import EnumField from './fields/EnumField.svelte';
  import InputMethodField from './fields/InputMethodField.svelte';
  import ItemField from './fields/ItemField.svelte';
  import ListField from './fields/ListField.svelte';
  import NumberField from './fields/NumberField.svelte';
  import RawField from './fields/RawField.svelte';
  import ReferenceField from './fields/ReferenceField.svelte';
  import SoundField from './fields/SoundField.svelte';
  import TemplateField from './fields/TemplateField.svelte';
  import VariableField from './fields/VariableField.svelte';

  interface Props {
    param: ParamInfo;
    kind: FieldKind;
    element?: FieldKind;
    value: unknown;
    id: string;
    label: string;
    disabled?: boolean;
    flags?: NodeFlag[];
    suggestions?: string[];
    onchange: (value: unknown) => void;
    ondraft?: (value: unknown) => void;
  }

  let { param, kind, element, value, id, label, disabled = false, flags = [], suggestions = [], onchange, ondraft }: Props = $props();

  const text = $derived(typeof value === 'string' ? value : typeof value === 'number' || typeof value === 'boolean' ? String(value) : '');
</script>

{#if kind === 'item'}
  <ItemField {id} {label} {value} required={param.required} {disabled} {flags} {onchange} {ondraft} />
{:else if kind === 'sound'}
  <SoundField {id} {label} {value} {disabled} {flags} {onchange} {ondraft} />
{:else if kind === 'variable'}
  <VariableField {id} {label} {value} {disabled} {flags} {onchange} />
{:else if kind === 'inputMethod'}
  <InputMethodField {param} {id} {label} {value} {disabled} {onchange} />
{:else if kind === 'color'}
  <GuiColorField {label} value={typeof value === 'string' ? value : undefined} fallback={param.default ?? ''} {disabled} onchange={(next) => onchange(next)} />
{:else if kind === 'reference'}
  <ReferenceField {param} {id} {label} {value} {disabled} {flags} {onchange} {ondraft} />
{:else if kind === 'boolean'}
  <BooleanField {id} {label} {value} {disabled} {onchange} />
{:else if kind === 'enum'}
  <EnumField {param} {id} {label} {value} {disabled} {onchange} />
{:else if kind === 'number'}
  <NumberField {param} {id} {label} {value} {disabled} invalid={flags.some((flag) => flag.severity === 'error')} {onchange} />
{:else if kind === 'list'}
  <ListField {id} {label} {value} element={element ?? 'template'} {disabled} {suggestions} {flags} {onchange} {ondraft} />
{:else if kind === 'raw'}
  <RawField {id} {label} {value} {disabled} {onchange} />
{:else}
  <TemplateField
    {id}
    {label}
    value={text}
    {disabled}
    {flags}
    {suggestions}
    placeholder={param.default ?? param.example ?? ''}
    onchange={(next) => onchange(next === '' && !param.required ? undefined : next)}
    ondraft={(next) => ondraft?.(next)}
  />
{/if}
