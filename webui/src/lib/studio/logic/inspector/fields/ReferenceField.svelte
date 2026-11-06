<script lang="ts">
  /**
   * A reference to another resource (logic.md section 7.3): `gui.open`'s `gui`, say. A text field with the matching
   * resources offered as suggestions. What a `reference` points at is the parameter's `referenceKind` when the
   * self-description gives one, else its name (`gui` points at the GUIs); with neither, every resource is offered.
   */
  import { workspace } from '../../../../state.svelte';
  import type { NodeFlag } from '../../../../logic/verdicts';
  import type { ParamInfo } from '../../../../logic/vocabulary';
  import TemplateField from './TemplateField.svelte';

  interface Props {
    param: ParamInfo;
    value: unknown;
    id?: string;
    label: string;
    disabled?: boolean;
    flags?: NodeFlag[];
    onchange: (value: unknown) => void;
    ondraft?: (value: unknown) => void;
  }

  let { param, value, id, label, disabled = false, flags = [], onchange, ondraft }: Props = $props();

  const DIRECTORIES: Record<string, string> = { gui: 'guis', item: 'items', block: 'blocks', furniture: 'furnitures', sound: 'sounds' };

  const choices = $derived.by(() => {
    const kind = (param.referenceKind ?? param.id).toLowerCase().replace(/^pack-/, '');
    const directory = DIRECTORIES[kind];
    return workspace.resources
      .filter((ref) => !directory || ref.path.startsWith(`${directory}/`))
      .map((ref) => (ref.id.includes(':') ? ref.id : `${ref.pack}:${ref.id}`));
  });
</script>

<TemplateField
  {id}
  {label}
  value={typeof value === 'string' ? value : ''}
  {disabled}
  {flags}
  suggestions={choices}
  placeholder={param.example ?? 'pack:name'}
  onchange={(next) => onchange(next === '' ? undefined : next)}
  ondraft={(next) => ondraft?.(next)}
/>
