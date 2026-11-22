<script lang="ts">
  /**
   * A reference to another resource (logic.md section 7.3): `view.open`'s `view`, say. A text field with the matching
   * resources offered as suggestions. What a `reference` points at is the parameter's `referenceKind` when the
   * self-description gives one, else its name (`gui` points at the GUIs); with neither, every resource is offered. The kind
   * `view` points at every view kind at once: the types whose self-description carries the view facet, so a dialog or
   * a HUD is offered as soon as the server has one.
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
    // a view reference is any view kind: the directories of the types that declare the view facet
    const directories =
      kind === 'view' && !workspace.types.some((type) => type.name === 'view')
        ? workspace.types.filter((type) => type.view !== undefined && type.directory !== undefined).map((type) => type.directory!)
        : DIRECTORIES[kind]
          ? [DIRECTORIES[kind]]
          : [];
    return workspace.resources
      .filter((ref) => directories.length === 0 || directories.some((directory) => ref.path.startsWith(`${directory}/`)))
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
