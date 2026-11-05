<script lang="ts">
  /**
   * A value no typed field can hold (a mapping, a parameter the self-description calls `raw`, a value of an unexpected
   * shape): shown as JSON and editable as JSON, so nothing in the file is ever out of reach and nothing is dropped
   * (master section 5.6). Plain text that is not JSON is kept as the text it is.
   */
  import { jsonLine } from '../../../../logic/params';

  interface Props {
    value: unknown;
    id?: string;
    label: string;
    disabled?: boolean;
    onchange: (value: unknown) => void;
  }

  let { value, id, label, disabled = false, onchange }: Props = $props();

  function show(authored: unknown): string {
    if (authored === undefined) return '';
    return typeof authored === 'string' ? authored : jsonLine(authored);
  }

  let text = $state('');
  let focused = $state(false);
  let problem = $state('');
  $effect(() => {
    const authored = value;
    if (!focused) text = show(authored);
  });

  function commit(): void {
    focused = false;
    problem = '';
    const trimmed = text.trim();
    if (trimmed === '') {
      if (value !== undefined) onchange(undefined);
      return;
    }
    let next: unknown = trimmed;
    if (/^[[{]/.test(trimmed) || /^(true|false|null|-?\d+(\.\d+)?)$/.test(trimmed)) {
      try {
        next = JSON.parse(trimmed);
      } catch {
        problem = 'That is not valid JSON, so it was not applied.';
        return;
      }
    }
    if (JSON.stringify(next) !== JSON.stringify(value)) onchange(next);
    text = show(value);
  }
</script>

<textarea
  {id}
  rows="2"
  aria-label={label}
  spellcheck="false"
  {disabled}
  value={text}
  oninput={(event) => (text = event.currentTarget.value)}
  onfocus={() => (focused = true)}
  onblur={commit}
></textarea>
{#if problem}<p class="problem" role="alert">{problem}</p>{/if}

<style>
  textarea {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    padding: var(--space-1) var(--space-2);
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--mono);
    resize: vertical;
  }
  textarea:focus {
    border-color: var(--accent);
    outline: none;
  }
  .problem {
    margin: var(--space-1) 0 0;
    color: var(--error);
    font-size: var(--text-xs);
  }
</style>
