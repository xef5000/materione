<script lang="ts">
  import { parseLegacyText } from '../legacy-text';
  let { text }: { text: string } = $props();
  const segments = $derived(parseLegacyText(text));
</script>

{#each segments as segment}
  <span
    style:color={segment.color?.startsWith('#') ? segment.color : segment.color ? `var(--legacy-${segment.color})` : undefined}
    class:bold={segment.bold}
    class:italic={segment.italic}
    class:underline={segment.underline}
    class:strike={segment.strikethrough}
    title={segment.obfuscated ? 'Obfuscation is not simulated in this preview.' : undefined}
  >{segment.text}</span>
{/each}

<style>
  span { white-space: pre-wrap; overflow-wrap: anywhere; }
  .bold { font-weight: 700; }
  .italic { font-style: italic; }
  .underline { text-decoration-line: underline; }
  .strike { text-decoration-line: line-through; }
  .underline.strike { text-decoration-line: underline line-through; }
</style>
