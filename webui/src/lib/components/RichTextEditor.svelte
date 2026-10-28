<script lang="ts">
  /**
   * The rich text editor (issue 151, web.md "Rich text"): Minecraft text edited
   * in place, in the game's look, with a floating format toolbar. The document
   * model in src/lib/rich-text is the source of truth: browser input is
   * intercepted and turned into model edits, and the DOM is redrawn from the
   * model. The stored form (MiniMessage) never shows here; an untouched value
   * is never rewritten (master §5.6, §5.7).
   */
  import { onMount, untrack } from 'svelte';
  import Icon from './Icon.svelte';
  import ResourceThumbnail from './ResourceThumbnail.svelte';
  import type { IconName } from './icons';
  import { DEFAULT_CUSTOM_COLOR, DEFAULT_GRADIENT, hexOf, NAMED_COLORS } from '../rich-text/colors';
  import { domFromOffset, offsetFromDom, renderDocument } from '../rich-text/dom';
  import {
    activeStyle, cellsFromFragment, cellsFromText, deleteContent, format, History, insertGlyph, insertText,
    replaceSelection, selectedFragment, withSelection,
    type DeleteUnit, type EditorState, type FormatOp, type Selection,
  } from '../rich-text/edit';
  import { glyphImages, glyphResources, requestGlyphImages } from '../rich-text/glyphs.svelte';
  import { documentOf, storedValue, type Cell, type Decoration, type TextStyle } from '../rich-text/model';

  interface Props {
    id: string;
    label: string;
    value: unknown;
    /** Lore: Enter starts a new line and the value is a list of lines. */
    multiline?: boolean;
    /** The game's default color for this text: a name's white or lore's dark purple. */
    tone?: 'name' | 'lore';
    placeholder?: string;
    /** Read-only. A surrounding disabled `<fieldset>` disables it too, like a native input. */
    disabled?: boolean;
    /**
     * No tooltip frame of its own: a parent draws one tooltip around several
     * editors (the item studio's name and lore), so the text is edited in place.
     */
    bare?: boolean;
    onChange: (value: unknown) => void;
  }
  let { id, label, value, multiline = false, tone, placeholder = '', disabled = false, bare = false, onChange }: Props = $props();

  const RICH_MIME = 'application/x-materione-rich-text';
  const surfaceTone = $derived(tone ?? (multiline ? 'lore' : 'name'));

  let root = $state<HTMLDivElement>();
  let wrapper = $state<HTMLDivElement>();
  let toolbar = $state<HTMLDivElement>();

  let cells: Cell[] = $state.raw([]);
  let selection: Selection = $state.raw({ anchor: 0, focus: 0 });
  let pending: TextStyle | undefined = $state.raw(undefined);
  let supported = $state(true);
  /** A disabled ancestor fieldset (studio and Files lock their forms while a save is unresolved). */
  let fieldsetDisabled = $state(false);
  const locked = $derived(disabled || fieldsetDisabled);
  let focusWithin = $state(false);
  let popover = $state<'' | 'color' | 'gradient' | 'glyph'>('');
  let toolbarPosition = $state({ top: 0, left: 0, below: false });
  let customColor = $state(DEFAULT_CUSTOM_COLOR);
  let customHex = $state('');
  let stops = $state<string[]>([...DEFAULT_GRADIENT]);

  let history = new History();
  /** The value as loaded: the bytes kept wherever the look did not change. */
  let original: unknown;
  /** The last value sent up, so its echo through `value` is not a reload. */
  let emitted: unknown;
  let loaded = false;
  let composing = false;
  let compositionBase: EditorState | null = null;

  const current = (): EditorState => ({ cells, selection, pending });
  const active = $derived(activeStyle({ cells, selection, pending }));
  const glyphs = $derived(glyphResources());

  const sameValue = (a: unknown, b: unknown) => a === b || JSON.stringify(a) === JSON.stringify(b);

  function load(next: unknown) {
    const document = documentOf(next, multiline);
    supported = document !== null;
    original = next;
    emitted = next;
    loaded = true;
    history = new History();
    cells = document ?? [];
    selection = { anchor: cells.length, focus: cells.length };
    pending = undefined;
  }

  $effect(() => {
    const incoming = value;
    untrack(() => {
      if (loaded && sameValue(incoming, emitted)) return;
      load(incoming);
    });
  });

  function emit() {
    const next = storedValue(cells, multiline, original);
    if (sameValue(next, emitted)) return;
    emitted = next;
    onChange(next);
  }

  // --- drawing --------------------------------------------------------------

  $effect(() => {
    if (!root) return;
    renderDocument(root, cells, { glyphImage: (glyph) => glyphImages.get(glyph) });
    untrack(() => {
      scrambleObfuscated(true);
      if (document.activeElement === root && !composing) placeSelection();
    });
  });

  // --- obfuscated text ----------------------------------------------------------
  // Drawn scrambled, as the client does. A run the caret is in (or that the
  // selection touches) shows its real characters so it can be edited; leaving
  // it scrambles it again. The model never holds scrambled text — only the
  // drawn text nodes are swapped, with the same number of characters, so the
  // DOM <-> offset mapping is unaffected.
  const SCRAMBLE = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;

  function revealed(start: number, length: number): boolean {
    if (!focusWithin) return false;
    const low = Math.min(selection.anchor, selection.focus);
    const high = Math.max(selection.anchor, selection.focus);
    return high >= start && low <= start + length;
  }

  /** `reroll` redraws every scrambled run; without it only reveal/hide transitions change. */
  function scrambleObfuscated(reroll: boolean) {
    if (!root) return;
    let moved = false;
    for (const run of root.querySelectorAll<HTMLElement>('.mc-obf')) {
      const node = run.firstChild;
      if (!(node instanceof Text)) continue;
      const start = Number(run.dataset.start ?? 0);
      const length = Number(run.dataset.length ?? 0);
      const real = cells.slice(start, start + length).map((cell) => (cell.kind === 'char' ? cell.ch : '')).join('');
      const open = revealed(start, length);
      if (open) {
        run.dataset.revealed = 'true';
        run.removeAttribute('title');
        if (node.data !== real) {
          node.data = real;
          moved = true;
        }
      } else {
        delete run.dataset.revealed;
        run.title = real;
        const hidden = run.dataset.hidden === 'true';
        if (reroll || !hidden) {
          node.data = [...real].map((ch) => (/\s/.test(ch) ? ch : SCRAMBLE[Math.floor(Math.random() * SCRAMBLE.length)]!)).join('');
          if (!hidden) moved = true;
        }
        run.dataset.hidden = 'true';
      }
      if (open) delete run.dataset.hidden;
    }
    // Writing into the node that holds the caret moves it; put it back where the model says it is.
    if (moved && focusWithin && document.activeElement === root && !composing) placeSelection();
  }

  // The client rerolls every frame; a few times a second reads the same. Reduced motion keeps one static scramble.
  $effect(() => {
    if (!root || !cells.some((cell) => cell.style.obfuscated)) return;
    if (reducedMotion()) return;
    const timer = window.setInterval(() => scrambleObfuscated(true), 90);
    return () => window.clearInterval(timer);
  });

  $effect(() => {
    requestGlyphImages(cells.flatMap((cell) => (cell.kind === 'glyph' ? [cell.id] : [])));
  });

  function placeSelection() {
    if (!root) return;
    const domSelection = window.getSelection();
    if (!domSelection) return;
    const anchor = domFromOffset(root, selection.anchor);
    const focus = domFromOffset(root, selection.focus);
    domSelection.setBaseAndExtent(anchor.node, anchor.offset, focus.node, focus.offset);
  }

  function readSelection(): Selection | null {
    const domSelection = window.getSelection();
    if (!root || !domSelection || domSelection.rangeCount === 0) return null;
    const anchor = offsetFromDom(root, domSelection.anchorNode, domSelection.anchorOffset);
    const focus = offsetFromDom(root, domSelection.focusNode, domSelection.focusOffset);
    return anchor === null || focus === null ? null : { anchor, focus };
  }

  function syncSelection() {
    const read = readSelection();
    if (!read || (read.anchor === selection.anchor && read.focus === selection.focus)) return;
    const next = withSelection(current(), read);
    selection = next.selection;
    pending = next.pending;
    history.seal();
  }

  /** Commits an edit: history, model, value, and the caret when the text did not change. */
  function apply(next: EditorState, kind: string | null) {
    if (kind) history.record(current(), kind);
    const changed = next.cells !== cells;
    cells = next.cells;
    selection = next.selection;
    pending = next.pending;
    if (changed) emit();
    else placeSelection();
    positionToolbar();
  }

  // --- input ------------------------------------------------------------------

  const deletions: Record<string, [-1 | 1, DeleteUnit]> = {
    deleteContentBackward: [-1, 'char'],
    deleteContentForward: [1, 'char'],
    deleteWordBackward: [-1, 'word'],
    deleteWordForward: [1, 'word'],
    deleteSoftLineBackward: [-1, 'line'],
    deleteHardLineBackward: [-1, 'line'],
    deleteSoftLineForward: [1, 'line'],
    deleteHardLineForward: [1, 'line'],
  };
  const formats: Record<string, Decoration> = {
    formatBold: 'bold', formatItalic: 'italic', formatUnderline: 'underline', formatStrikeThrough: 'strikethrough',
  };

  function onbeforeinput(event: InputEvent) {
    if (event.inputType === 'insertCompositionText') return;
    // every other mutation goes through the model; the browser never edits the DOM itself
    event.preventDefault();
    if (!supported || locked) return;
    syncSelection();
    const type = event.inputType;
    if (type === 'insertText' || type === 'insertReplacementText') {
      const text = event.data ?? event.dataTransfer?.getData('text/plain') ?? '';
      if (text) apply(insertText(current(), text, multiline), 'type');
    } else if (type === 'insertParagraph' || type === 'insertLineBreak') {
      if (multiline) apply(insertText(current(), '\n', true), 'line');
    } else if (deletions[type]) {
      const [direction, unit] = deletions[type]!;
      const base = current();
      const next = deleteContent(base, direction, unit);
      if (next !== base) apply(next, 'delete');
    } else if (type === 'deleteByCut' || type === 'deleteContent' || type === 'deleteByDrag') {
      apply(replaceSelection(current(), []), 'delete');
    } else if (type === 'historyUndo') undo();
    else if (type === 'historyRedo') redo();
    else if (formats[type]) applyFormat({ kind: 'toggle', decoration: formats[type]! });
  }

  function oncompositionstart() {
    syncSelection();
    composing = true;
    compositionBase = current();
  }

  function oncompositionend(event: CompositionEvent) {
    composing = false;
    const base = compositionBase ?? current();
    compositionBase = null;
    if (event.data) apply(insertText(base, event.data, multiline), 'type');
    else cells = [...base.cells]; // redraw over whatever the composition left
  }

  function onkeydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && popover) {
      event.preventDefault();
      popover = '';
      return;
    }
    if (event.key === 'Enter' && !multiline) {
      event.preventDefault();
      return;
    }
    if (!(event.ctrlKey || event.metaKey) || event.altKey || !supported || locked) return;
    const key = event.key.toLowerCase();
    const shortcut: Record<string, FormatOp> = event.shiftKey
      ? { x: { kind: 'toggle', decoration: 'strikethrough' }, k: { kind: 'toggle', decoration: 'obfuscated' } }
      : {
          b: { kind: 'toggle', decoration: 'bold' },
          i: { kind: 'toggle', decoration: 'italic' },
          u: { kind: 'toggle', decoration: 'underline' },
          '\\': { kind: 'clear' },
        };
    if (shortcut[key]) {
      event.preventDefault();
      syncSelection();
      applyFormat(shortcut[key]!);
    } else if (key === 'z') {
      event.preventDefault();
      if (event.shiftKey) redo();
      else undo();
    } else if (key === 'y' && !event.shiftKey) {
      event.preventDefault();
      redo();
    }
  }

  function undo() {
    const previous = history.undo(current());
    if (previous) apply(previous, null);
  }

  function redo() {
    const next = history.redo(current());
    if (next) apply(next, null);
  }

  function onpaste(event: ClipboardEvent) {
    event.preventDefault();
    if (!supported || locked || !event.clipboardData) return;
    syncSelection();
    const rich = event.clipboardData.getData(RICH_MIME);
    const pasted = rich
      ? cellsFromFragment(rich, multiline)
      : cellsFromText(event.clipboardData.getData('text/plain'), pending ?? activeStyle({ cells, selection }), multiline);
    if (pasted.length) apply(replaceSelection(current(), pasted), 'paste');
  }

  function oncopy(event: ClipboardEvent) {
    syncSelection();
    if (selection.anchor === selection.focus || !event.clipboardData) return;
    event.preventDefault();
    const fragment = selectedFragment(current());
    event.clipboardData.setData('text/plain', fragment.plain);
    event.clipboardData.setData(RICH_MIME, fragment.rich);
  }

  function oncut(event: ClipboardEvent) {
    oncopy(event);
    if (event.defaultPrevented && supported && !locked) apply(replaceSelection(current(), []), 'delete');
  }

  // --- toolbar ------------------------------------------------------------------

  function applyFormat(op: FormatOp) {
    apply(format(current(), op), 'format');
    root?.focus();
    placeSelection();
  }

  function pickColor(color: string) {
    applyFormat({ kind: 'color', color });
    popover = '';
  }

  function applyCustomHex() {
    const raw = customHex.trim();
    const hex = hexOf(raw.startsWith('#') ? raw : `#${raw}`);
    if (hex) pickColor(hex);
  }

  function openGradient() {
    const existing = active.gradient?.map((stop) => hexOf(stop) ?? stop);
    stops = existing && existing.length >= 2 ? existing : [...DEFAULT_GRADIENT];
    popover = popover === 'gradient' ? '' : 'gradient';
  }

  function applyGradient() {
    applyFormat({ kind: 'gradient', stops: [...stops] });
    popover = '';
  }

  function addGlyph(glyphId: string) {
    apply(insertGlyph(current(), glyphId), 'glyph');
    popover = '';
    root?.focus();
    placeSelection();
  }

  function positionToolbar() {
    if (!root || !toolbar) return;
    const box = root.getBoundingClientRect();
    let left = box.left;
    const domSelection = window.getSelection();
    if (domSelection && domSelection.rangeCount && root.contains(domSelection.anchorNode)) {
      const rect = domSelection.getRangeAt(0).getBoundingClientRect();
      if (rect.width || rect.height) left = rect.left;
    }
    const width = toolbar.offsetWidth || 320;
    left = Math.max(8, Math.min(left, window.innerWidth - width - 8));
    const below = box.top - toolbar.offsetHeight - 8 < 8;
    toolbarPosition = { top: below ? box.bottom + 6 : box.top - 6, left, below };
  }

  function onfocusin() {
    focusWithin = true;
  }

  $effect(() => {
    if (toolbar) untrack(positionToolbar);
  });

  function onfocusout(event: FocusEvent) {
    if (wrapper?.contains(event.relatedTarget as Node | null)) return;
    focusWithin = false;
    popover = '';
    history.seal();
    scrambleObfuscated(false);
  }

  onMount(() => {
    const fieldsets: HTMLFieldSetElement[] = [];
    for (let element = wrapper?.parentElement; element; element = element.parentElement) {
      if (element instanceof HTMLFieldSetElement) fieldsets.push(element);
    }
    const updateLock = () => {
      fieldsetDisabled = fieldsets.some((fieldset) => fieldset.disabled);
    };
    const lockObserver = new MutationObserver(updateLock);
    for (const fieldset of fieldsets) lockObserver.observe(fieldset, { attributes: true, attributeFilter: ['disabled'] });
    updateLock();
    const onSelection = () => {
      if (composing || document.activeElement !== root) return;
      syncSelection();
      positionToolbar();
      scrambleObfuscated(false);
    };
    const reposition = () => focusWithin && positionToolbar();
    document.addEventListener('selectionchange', onSelection);
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    return () => {
      lockObserver.disconnect();
      document.removeEventListener('selectionchange', onSelection);
      window.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
    };
  });

  const decorationTools: { decoration: Decoration; label: string; icon: IconName; keys: string }[] = [
    { decoration: 'bold', label: 'Bold', icon: 'bold', keys: 'Ctrl+B' },
    { decoration: 'italic', label: 'Italic', icon: 'italic', keys: 'Ctrl+I' },
    { decoration: 'underline', label: 'Underline', icon: 'underline', keys: 'Ctrl+U' },
    { decoration: 'strikethrough', label: 'Strikethrough', icon: 'strikethrough', keys: 'Ctrl+Shift+X' },
    { decoration: 'obfuscated', label: 'Obfuscated', icon: 'shuffle', keys: 'Ctrl+Shift+K' },
  ];
  const keep = (event: Event) => event.preventDefault();
</script>

<div class="rich-text-editor" bind:this={wrapper} {onfocusin} {onfocusout}>
  {#if !supported}
    <p class="unsupported">This value cannot be edited as plain lines without changing its structure. Use Files; the original value is preserved.</p>
  {:else}
    <div class="surface" class:mc-tooltip={!bare} class:bare>
      <div
        {id}
        bind:this={root}
        class="mc-text editable"
        class:mc-lore={surfaceTone === 'lore'}
        class:multiline={multiline && !bare}
        contenteditable={locked ? 'false' : 'true'}
        role="textbox"
        tabindex="0"
        aria-label={label}
        aria-disabled={locked}
        aria-multiline={multiline}
        spellcheck="false"
        data-field-control
        {onbeforeinput}
        {onkeydown}
        {onpaste}
        {oncopy}
        {oncut}
        {oncompositionstart}
        {oncompositionend}
        ondrop={keep}
        ondragstart={keep}
      ></div>
      {#if cells.length === 0 && placeholder}
        <span class="placeholder" aria-hidden="true">{placeholder}</span>
      {/if}
    </div>
    {#if focusWithin && !locked}
      <div
        class="toolbar"
        class:below={toolbarPosition.below}
        bind:this={toolbar}
        role="toolbar"
        aria-label={`${label} formatting`}
        style:top={`${toolbarPosition.top}px`}
        style:left={`${toolbarPosition.left}px`}
      >
        {#each decorationTools as tool (tool.decoration)}
          <button type="button" class="tool" aria-label={`${label}: ${tool.label}`} title={`${tool.label} (${tool.keys})`}
            aria-pressed={active[tool.decoration] === true} onpointerdown={keep}
            onclick={() => applyFormat({ kind: 'toggle', decoration: tool.decoration })}>
            <Icon name={tool.icon} size={15} />
          </button>
        {/each}
        <span class="divider" aria-hidden="true"></span>
        <button type="button" class="tool" aria-label={`${label}: Color`} title="Color" aria-expanded={popover === 'color'}
          onpointerdown={keep} onclick={() => { popover = popover === 'color' ? '' : 'color'; }}>
          <Icon name="palette" size={15} />
          <span class="swatch-mark" style:background={active.color ? hexOf(active.color) : undefined}></span>
        </button>
        <button type="button" class="tool" aria-label={`${label}: Gradient`} title="Gradient" aria-expanded={popover === 'gradient'}
          aria-pressed={active.gradient !== undefined} onpointerdown={keep} onclick={openGradient}>
          <Icon name="blend" size={15} />
        </button>
        {#if glyphs.length > 0}
          <button type="button" class="tool" aria-label={`${label}: Insert glyph`} title="Insert glyph" aria-expanded={popover === 'glyph'}
            onpointerdown={keep} onclick={() => { popover = popover === 'glyph' ? '' : 'glyph'; }}>
            <Icon name="sticker" size={15} />
          </button>
        {/if}
        <button type="button" class="tool" aria-label={`${label}: Clear formatting`} title="Clear formatting (Ctrl+\)"
          onpointerdown={keep} onclick={() => applyFormat({ kind: 'clear' })}>
          <Icon name="remove-formatting" size={15} />
        </button>

        {#if popover === 'color'}
          <div class="popover" role="group" aria-label={`${label} colors`}>
            <div class="swatches">
              {#each NAMED_COLORS as color (color.name)}
                <button type="button" class="swatch" aria-label={`${label}: ${color.label}`} title={color.label}
                  aria-pressed={active.color === color.name} style:background={`var(--legacy-${color.code})`}
                  onpointerdown={keep} onclick={() => pickColor(color.name)}></button>
              {/each}
            </div>
            <div class="custom">
              <input type="color" aria-label={`${label}: custom color`} bind:value={customColor}
                onchange={() => pickColor(customColor)} />
              <input type="text" class="hex" aria-label={`${label}: hex color`} placeholder="#rrggbb" bind:value={customHex}
                onkeydown={(event) => { if (event.key === 'Enter') { event.preventDefault(); applyCustomHex(); } }} />
              <button type="button" class="btn btn-sm" onclick={applyCustomHex}>Apply</button>
            </div>
          </div>
        {:else if popover === 'gradient'}
          <div class="popover" role="group" aria-label={`${label} gradient`}>
            <div class="preview" style:background={`linear-gradient(90deg, ${stops.join(', ')})`}></div>
            <div class="stops">
              {#each stops as stop, index (index)}
                <span class="stop">
                  <input type="color" aria-label={`Gradient stop ${index + 1}`} value={stop}
                    oninput={(event) => { stops[index] = event.currentTarget.value; }} />
                  {#if stops.length > 2}
                    <button type="button" class="tool" aria-label={`Remove stop ${index + 1}`}
                      onclick={() => { stops = stops.filter((_, at) => at !== index); }}><Icon name="minus" size={13} /></button>
                  {/if}
                </span>
              {/each}
              {#if stops.length < 8}
                <button type="button" class="tool" aria-label="Add gradient stop"
                  onclick={() => { stops = [...stops, stops.at(-1) ?? DEFAULT_GRADIENT[1]!]; }}><Icon name="plus" size={13} /></button>
              {/if}
            </div>
            <button type="button" class="btn btn-sm btn-primary" onclick={applyGradient}>Apply gradient</button>
          </div>
        {:else if popover === 'glyph'}
          <div class="popover glyph-list" role="group" aria-label={`${label} glyphs`}>
            {#each glyphs as glyph (`${glyph.pack}:${glyph.id}`)}
              <button type="button" class="menu-item" onpointerdown={keep} onclick={() => addGlyph(glyph.id)}>
                <ResourceThumbnail visual={glyph.visual} label={glyph.id} size={20} />
                <span>{glyph.id}</span>
              </button>
            {/each}
          </div>
        {/if}
      </div>
    {/if}
  {/if}
</div>

<style>
  .rich-text-editor {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
  }
  .surface {
    position: relative;
    min-width: 0;
  }
  .editable {
    min-height: 1.25em;
    outline: none;
    cursor: text;
  }
  .editable.multiline {
    min-height: 3.75em;
  }
  .editable[aria-disabled='true'] {
    cursor: default;
    opacity: 0.7;
  }
  .surface:focus-within {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  .surface.bare:focus-within {
    outline-offset: 1px;
    border-radius: var(--radius-sm);
  }
  .surface.bare .placeholder {
    inset: 0;
  }
  .placeholder {
    position: absolute;
    inset: var(--space-2) var(--space-3);
    font-family: var(--font-minecraft);
    font-size: var(--text-minecraft);
    line-height: 1.25;
    color: var(--faint);
    pointer-events: none;
  }
  .toolbar {
    position: fixed;
    z-index: 40;
    display: flex;
    align-items: center;
    gap: var(--space-half);
    padding: var(--space-1);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-2);
    transform: translateY(-100%);
  }
  .toolbar.below {
    transform: none;
  }
  .tool {
    position: relative;
    display: inline-grid;
    place-items: center;
    min-width: 28px;
    height: 28px;
    padding: 0 var(--space-1);
    background: transparent;
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    color: var(--fg);
    cursor: pointer;
  }
  .tool:hover {
    background: var(--bg-hover);
  }
  .tool[aria-pressed='true'],
  .tool[aria-expanded='true'] {
    background: var(--accent-low);
    border-color: var(--accent);
  }
  .swatch-mark {
    position: absolute;
    left: var(--space-1);
    right: var(--space-1);
    bottom: 3px;
    height: 3px;
    border-radius: var(--radius-sm);
  }
  .divider {
    width: 1px;
    align-self: stretch;
    margin: 0 var(--space-1);
    background: var(--border-strong);
  }
  .popover {
    position: absolute;
    top: calc(100% + var(--space-1));
    left: 0;
    display: grid;
    gap: var(--space-2);
    min-width: 220px;
    padding: var(--space-2);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-2);
  }
  .swatches {
    display: grid;
    grid-template-columns: repeat(8, 22px);
    gap: var(--space-1);
  }
  .swatch {
    width: 22px;
    height: 22px;
    padding: 0;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    cursor: pointer;
  }
  .swatch[aria-pressed='true'] {
    outline: 2px solid var(--focus-ring);
    outline-offset: 1px;
  }
  .custom,
  .stops {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    flex-wrap: wrap;
  }
  .stop {
    display: inline-flex;
    align-items: center;
  }
  input[type='color'] {
    width: 32px;
    height: 26px;
    padding: 0;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: none;
    cursor: pointer;
  }
  .hex {
    width: 90px;
    font-family: var(--mono);
    font-size: var(--text-sm);
  }
  .preview {
    height: 14px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border-strong);
  }
  .glyph-list {
    max-height: 220px;
    overflow: auto;
  }
  .unsupported {
    color: var(--muted);
    font-size: var(--text-xs);
    line-height: 1.4;
    margin: 0;
  }
</style>
