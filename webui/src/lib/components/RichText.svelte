<script lang="ts">
  /**
   * Player-visible text drawn the way the game draws it (web.md, "Rich text"):
   * the stored form parsed by the same model the editor uses, in the
   * Minecraft-style face, with obfuscated text scrambling like the client.
   */
  import { renderDocument } from '../rich-text/dom';
  import { parseLine, plainText } from '../rich-text/model';
  import { glyphImages, requestGlyphImages } from '../rich-text/glyphs.svelte';

  interface Props {
    text: string;
    /** `name` draws white by default, `lore` dark purple, like the game's tooltip. */
    tone?: 'name' | 'lore';
  }
  let { text, tone = 'name' }: Props = $props();
  let root = $state<HTMLSpanElement>();
  const cells = $derived(parseLine(text));
  const obfuscated = $derived(cells.some((cell) => cell.style.obfuscated));
  const plain = $derived(plainText(text));

  $effect(() => {
    if (!root) return;
    renderDocument(root, cells, { glyphImage: (id) => glyphImages.get(id) });
  });

  $effect(() => {
    requestGlyphImages(cells.flatMap((cell) => (cell.kind === 'glyph' ? [cell.id] : [])));
  });

  // Obfuscated characters change every frame in game; a few times a second reads the same.
  const SCRAMBLE = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  $effect(() => {
    if (!root || !obfuscated) return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    const timer = window.setInterval(() => {
      for (const run of root?.querySelectorAll<HTMLElement>('.mc-obf') ?? []) {
        const textNode = run.firstChild;
        if (!textNode) continue;
        textNode.textContent = [...(textNode.textContent ?? '')]
          .map((ch) => (/\s/.test(ch) ? ch : SCRAMBLE[Math.floor(Math.random() * SCRAMBLE.length)]))
          .join('');
      }
    }, 90);
    return () => window.clearInterval(timer);
  });
</script>

<span class="mc-text rich-text" class:mc-lore={tone === 'lore'} bind:this={root}
  title={obfuscated ? plain : undefined}></span>

<style>
  .rich-text {
    display: block;
  }
</style>
