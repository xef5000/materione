// Glyphs suite: the pack-font surface as seen by a real player on a real
// server. Auto mode — the runner judges pass/fail from assertions; the
// chat screenshot is archived evidence (the bitmap itself is judged by the
// pack compile acceptance and the font-engine readback, not by eyes here).

import { test } from './_lib.mjs';

export default [
  test({
    id: 'glyphs.basic',
    title: 'sample:heart (texture gui/heart, placeholder ":heart:", no authored codepoint) reaches a real client as unicode: a player chat ":heart:" comes back decorated as exactly one high codepoint inside the allocation window (42000+, the compiler table run — never authored), the client font engine binds that codepoint with the compiled bitmap advance (9, not the 6-wide missing-glyph box), an unknown placeholder stays literal, and the colon-less authored placeholder "<3" never resolves in chat',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/glyphs/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeGlyphs.java',
      'core/src/main/java/dev/materione/core/text/GlyphCodepoints.java',
      'platform/src/main/java/dev/materione/platform/text/GlyphText.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/text/**',
      'fixtures/packs/sample/glyphs/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot settle; the pack loads at join

      // the client font engine: the bound bitmap's advance vs the missing-
      // glyph box the same engine renders for an unbound codepoint
      const widthOf = (cp) => ctx.exec(`String s = new String(Character.toChars(${cp}))
return mc.font.width(s)`, 15_000);
      const missingWidth = Number(await widthOf(0xF8FF)); // unbound private-use

      // --- the emoji path: real player chat through the decorate event
      await ctx.exec(`mc.getConnection().sendChat(':heart:')
return 'sent'`, 15_000);
      const findDecorated = async (timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const lines = await ctx.chat(12);
          for (const line of lines) {
            const high = [...line].map((ch) => ch.codePointAt(0)).filter((cp) => cp >= 0x2000);
            if (high.length > 0) return { line, high };
          }
          if (Date.now() > deadline) return null;
          await ctx.sleep(400);
        }
      };
      const found = await findDecorated();
      if (!found) ctx.expect('(no decorated line in chat history)').toBe('a line with the glyph codepoint');
      // exactly one substituted codepoint, inside the allocation window —
      // the compiler's table run, never an authored character
      ctx.expect(found.high.length).toBe(1);
      const cp = found.high[0];
      ctx.expect(cp).toBeGreaterThan(41999);
      ctx.expect(cp).toBeLessThan(42100);
      ctx.expect(found.line.includes(':heart:')).toBe(false); // placeholder is gone
      ctx.expect(await ctx.shot('glyph-chat')).toBeTruthy();

      // --- the client font engine binds the codepoint: the bitmap advance
      // the compiled provider carries (9 for the heart), not the 6-wide
      // missing-glyph box an unbound codepoint would render
      ctx.expect(Number(await widthOf(cp))).toBe(9);
      ctx.expect(missingWidth).toBe(6);

      // --- an unknown placeholder stays literal (no resolution, no leak)
      await ctx.exec(`mc.getConnection().sendChat(':definitely_not_a_glyph:')
return 'sent'`, 15_000);
      const deadline = Date.now() + 10_000;
      let literal = null;
      for (;;) {
        const lines = await ctx.chat(12);
        literal = lines.find((line) => line.includes(':definitely_not_a_glyph:')) ?? null;
        if (literal) break;
        if (Date.now() > deadline) break;
        await ctx.sleep(400);
      }
      ctx.expect(literal).toBeTruthy();

      // --- the authored colon-less placeholder "<3" is registered for tab
      // completion but never resolves: the emoji scanner only matches
      // colon-delimited placeholders. Documented live; tracked separately.
      await ctx.exec(`mc.getConnection().sendChat('<3')
return 'sent'`, 15_000);
      const deadline2 = Date.now() + 10_000;
      let hearts = null;
      for (;;) {
        const lines = await ctx.chat(12);
        hearts = lines.find((line) => line.includes('<3')) ?? null;
        if (hearts) break;
        if (Date.now() > deadline2) break;
        await ctx.sleep(400);
      }
      ctx.expect(hearts).toBeTruthy(); // stays literal
      ctx.expect(hearts.includes('<3')).toBe(true);
    },
  }),

  test({
    id: 'glyphs.sheet',
    title: 'one sheet definition (sample:color_sheet, texture gui/color_sheet, rows 2 × columns 2) becomes four live client codepoints: the sheet\'s own <glyph:> tag renders cell 1 and reference aliases with index 2/3/4 render cells 2/3/4 as one consecutive row-major allocation run, every cell binds in the client font engine with the same advance (the sliced cell\'s compiled width, never the missing-glyph box), and the four cells render visually distinct (screenshot: red green / blue yellow quadrants)',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/glyphs/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeGlyphs.java',
      'core/src/main/java/dev/materione/core/text/GlyphCodepoints.java',
      'platform/src/main/java/dev/materione/platform/text/GlyphText.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/text/**',
      'fixtures/packs/sample/glyphs/**',
    ],
    dependsOn: ['glyphs.basic'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot settle; the pack loads at join

      const widthOf = (cp) => ctx.exec(`String s = new String(Character.toChars(${cp}))
return mc.font.width(s)`, 15_000);
      const missingWidth = Number(await widthOf(0xF8FF)); // unbound private-use
      ctx.expect(missingWidth).toBe(6);

      // the sheet's own tag (renders its first cell) plus three reference
      // aliases selecting cells 2, 3, 4 — one line, four live codepoints.
      // Tags carry bare ids: the tag parser splits on ':' and the second
      // slot is the optional "colorable" flag, so namespaced ids in a tag
      // never resolve (the reference FIELD takes full ids, as the fixtures do)
      await ctx.exec(`mc.getConnection().sendChat('<glyph:color_sheet> <glyph:sheet_cell_2> <glyph:sheet_cell_3> <glyph:sheet_cell_4>')
return 'sent'`, 15_000);
      const findDecorated = async (timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const lines = await ctx.chat(12);
          for (const line of lines) {
            const high = [...line].map((ch) => ch.codePointAt(0)).filter((cp) => cp >= 0x2000);
            if (high.length >= 4) return { line, high };
          }
          if (Date.now() > deadline) return null;
          await ctx.sleep(400);
        }
      };
      const found = await findDecorated();
      if (!found) ctx.expect('(no decorated line in chat history)').toBe('a line with four glyph codepoints');

      // one sheet → many: four substituted codepoints on one line, inside
      // the compiler's allocation window (never authored characters)
      ctx.expect(found.high.length).toBe(4);
      for (const cp of found.high) {
        ctx.expect(cp).toBeGreaterThan(41999);
        ctx.expect(cp).toBeLessThan(42100);
      }
      // row-major consecutive run: the sheet's own tag took cell 1 (the run
      // base), the index references took cells 2..4 in tag order
      ctx.expect(found.high[1]).toBe(found.high[0] + 1);
      ctx.expect(found.high[2]).toBe(found.high[1] + 1);
      ctx.expect(found.high[3]).toBe(found.high[2] + 1);
      // the tags are consumed — no literal residue reaches the client
      ctx.expect(found.line.includes('<glyph:')).toBe(false);
      ctx.expect(await ctx.shot('glyph-sheet-cells')).toBeTruthy();

      // every cell binds in the client font engine with the SAME advance —
      // one sheet slices into equal cells — and never the missing-glyph box
      const widths = [];
      for (const cp of found.high) {
        widths.push(Number(await widthOf(cp)));
      }
      ctx.expect(widths[0]).toBeGreaterThan(missingWidth);
      ctx.expect(widths[1]).toBe(widths[0]);
      ctx.expect(widths[2]).toBe(widths[0]);
      ctx.expect(widths[3]).toBe(widths[0]);
    },
  }),

  test({
    id: 'glyphs.grid',
    title: 'grid glyph — a wide texture across chars (sample:banner_strip, texture gui/banner_strip 32×8, rows 1 × columns 4): the four cells compile as ONE single-row chars string over one allocation run, and chained with no separators in real chat (the sheet\'s tag then three index references) they render flush as one 32px-wide image — four consecutive ascending codepoints with no characters between them, each bound in the client font engine with the same cell advance, and the screenshot shows the four color bands butted left-to-right in row-major order (red green blue yellow)',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/glyphs/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeGlyphs.java',
      'core/src/main/java/dev/materione/core/text/GlyphCodepoints.java',
      'platform/src/main/java/dev/materione/platform/text/GlyphText.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/text/**',
      'fixtures/packs/sample/glyphs/**',
    ],
    dependsOn: ['glyphs.sheet'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot settle; the pack loads at join

      const widthOf = (cp) => ctx.exec(`String s = new String(Character.toChars(${cp}))
return mc.font.width(s)`, 15_000);
      const missingWidth = Number(await widthOf(0xF8FF)); // unbound private-use
      ctx.expect(missingWidth).toBe(6);

      // the grid composition: the sheet's own tag (cell 1, the leftmost
      // slice) then three index references, chained with NO separators —
      // composition in text is chaining cell aliases (one tag = one
      // codepoint; single-tag full-canvas emission is M6 HUD/menu scope)
      await ctx.exec(`mc.getConnection().sendChat('<glyph:banner_strip><glyph:strip_cell_2><glyph:strip_cell_3><glyph:strip_cell_4>')
return 'sent'`, 15_000);
      const findDecorated = async (timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const lines = await ctx.chat(12);
          for (const line of lines) {
            const high = [...line].map((ch) => ch.codePointAt(0)).filter((cp) => cp >= 0x2000);
            if (high.length >= 4) return { line, high };
          }
          if (Date.now() > deadline) return null;
          await ctx.sleep(400);
        }
      };
      const found = await findDecorated();
      if (!found) ctx.expect('(no decorated line in chat history)').toBe('a line with four glyph codepoints');

      // one wide texture across four chars, inside the compiler's
      // allocation window (never authored characters)
      ctx.expect(found.high.length).toBe(4);
      for (const cp of found.high) {
        ctx.expect(cp).toBeGreaterThan(41999);
        ctx.expect(cp).toBeLessThan(42100);
      }
      // row-major consecutive run in composition order: cell 1 base, then
      // cells 2..4 left to right
      ctx.expect(found.high[1]).toBe(found.high[0] + 1);
      ctx.expect(found.high[2]).toBe(found.high[1] + 1);
      ctx.expect(found.high[3]).toBe(found.high[2] + 1);
      // flush: nothing (not even a space) sits between the cells — they
      // butt into one image
      const chars = [...found.line];
      const at = chars.findIndex((ch) => ch.codePointAt(0) >= 0x2000);
      const flush = chars.slice(at, at + 4).map((ch) => ch.codePointAt(0));
      ctx.expect(flush).toEqual([found.high[0], found.high[1], found.high[2], found.high[3]]);
      // the tags are consumed — no literal residue reaches the client
      ctx.expect(found.line.includes('<glyph:')).toBe(false);
      ctx.expect(await ctx.shot('glyph-grid-banner')).toBeTruthy();

      // every cell binds with the SAME advance — equal cell widths are what
      // makes the bands butt together instead of overlapping — and never
      // the missing-glyph box
      const widths = [];
      for (const cp of found.high) {
        widths.push(Number(await widthOf(cp)));
      }
      ctx.expect(widths[0]).toBeGreaterThan(missingWidth);
      ctx.expect(widths[1]).toBe(widths[0]);
      ctx.expect(widths[2]).toBe(widths[0]);
      ctx.expect(widths[3]).toBe(widths[0]);
    },
  }),

  test({
    id: 'glyphs.alias',
    title: 'reference glyphs (aliases) resolve through their target\'s bitmaps without allocating anything: three aliases of sample:heart (the M4 heart_big at index 1, an alias with no index, an alias with an out-of-range index 99) all render the SAME codepoint as a :heart: chat, with the same font binding; and an alias\'s own permission gates it independently of its target (denied while deopped, resolving after re-op). Dangling targets quarantine at pipeline validate and alias→alias chains are rejected there too (PipelineRunnerTest) — neither reaches the runtime',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/glyphs/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeGlyphs.java',
      'platform/src/main/java/dev/materione/platform/text/GlyphText.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/text/**',
      'fixtures/packs/sample/glyphs/**',
    ],
    dependsOn: ['glyphs.grid'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot settle; the pack loads at join

      const widthOf = (cp) => ctx.exec(`String s = new String(Character.toChars(${cp}))
return mc.font.width(s)`, 15_000);
      const missingWidth = Number(await widthOf(0xF8FF)); // unbound private-use
      ctx.expect(missingWidth).toBe(6);
      const send = (text) => ctx.exec(`mc.getConnection().sendChat('${text}')
return 'sent'`, 15_000);
      // find a chat line whose high-codepoint count matches (glyph tags
      // consumed); earlier glyph tests' lines stay in history, so both the
      // count and the no-residue shape discriminate
      const findLine = async (highCount, timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const lines = await ctx.chat(12);
          for (const line of lines) {
            const high = [...line].map((ch) => ch.codePointAt(0)).filter((cp) => cp >= 0x2000);
            if (high.length === highCount && !line.includes('<glyph:')) return { line, high };
          }
          if (Date.now() > deadline) return null;
          await ctx.sleep(400);
        }
      };
      const findLiteral = async (marker, timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const lines = await ctx.chat(12);
          const line = lines.find((l) => l.includes(marker));
          if (line) return line;
          if (Date.now() > deadline) return null;
          await ctx.sleep(400);
        }
      };

      // --- the target's codepoint, live: the :heart: echo
      await send(':heart:');
      const target = await findLine(1);
      if (!target) ctx.expect('(no :heart: line in chat history)').toBe('one codepoint');
      const H = target.high[0];
      ctx.expect(H).toBeGreaterThan(41999);
      ctx.expect(H).toBeLessThan(42100);
      ctx.expect(Number(await widthOf(H))).toBe(9);

      // --- alias identity: index 1 (heart_big), no index, out-of-range 99 —
      // all resolve to the target's first cell, the same codepoint, bound
      await send('<glyph:heart_big><glyph:heart_alias><glyph:heart_edge>');
      const aliased = await findLine(3);
      if (!aliased) ctx.expect('(no alias line in chat history)').toBe('three codepoints');
      ctx.expect(aliased.high[0]).toBe(H);
      ctx.expect(aliased.high[1]).toBe(H);
      ctx.expect(aliased.high[2]).toBe(H);
      ctx.expect(Number(await widthOf(aliased.high[0]))).toBe(9);
      ctx.expect(await ctx.shot('glyph-alias')).toBeTruthy();

      // --- the alias's OWN permission gates it independently of its target
      // (heart has none): restore op before any assert can fail
      await ctx.rcon('deop Probe');
      await send('restricted_alias <glyph:restricted_alias>');
      await ctx.sleep(2_500); // the async decorate settles while still deopped
      await ctx.rcon('op Probe');
      const denied = await findLiteral('restricted_alias');
      if (!denied) ctx.expect('(no restricted_alias line in chat history)').toBe('the literal tag');
      ctx.expect(denied.includes('<glyph:restricted_alias>')).toBe(true);
      const deniedHigh = [...denied].map((ch) => ch.codePointAt(0)).filter((cp) => cp >= 0x2000);
      ctx.expect(deniedHigh.length).toBe(0); // no unicode leak for a denied viewer
      // permitted again: the same alias resolves (found by its marker word,
      // so a stale one-codepoint line can't stand in for the re-check)
      await send('alias_recheck <glyph:restricted_alias>');
      const recheck = await findLiteral('alias_recheck');
      if (!recheck) ctx.expect('(no alias_recheck line in chat history)').toBe('the resolved alias');
      const recheckHigh = [...recheck].map((ch) => ch.codePointAt(0)).filter((cp) => cp >= 0x2000);
      ctx.expect(recheckHigh.length).toBe(1);
      ctx.expect(recheckHigh[0]).toBe(H);
      ctx.expect(recheck.includes('<glyph:restricted_alias>')).toBe(false);
    },
  }),

  test({
    id: 'glyphs.shift',
    title: 'pixel-shift tags (<shift:N>) compose invisible U+E101+ space-provider characters that move text by exactly N pixels on a real client: <shift:7> renders as the greedy +4/+2/+1 run whose font-engine width measures exactly 7 (each character measuring its own advance — the minecraft:default → materione:shift space provider, never the missing-glyph box), <shift:-5> measures −5, <shift:600> composes +512/+64/+16/+8 to exactly 600, <shift:0> consumes the tag with no characters, and <shift:abc> stays literal',
    mode: 'auto',
    covers: [
      'core/src/main/java/dev/materione/core/text/ShiftMath.java',
      'platform/src/main/java/dev/materione/platform/text/GlyphText.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/text/**',
    ],
    dependsOn: ['glyphs.alias'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot settle; the pack loads at join

      const cpText = (cps) => `new String(new int[] {${cps.join(', ')}}, 0, ${cps.length})`;
      const widthOfExpr = (expr) => ctx.exec(`String s = ${expr}
return mc.font.width(s)`, 15_000);
      const widthOfCp = (cp) => ctx.exec(`String s = new String(Character.toChars(${cp}))
return mc.font.width(s)`, 15_000);
      // shift characters live at U+E101..U+E11A — disjoint from the glyph
      // codepoint window (42000+, well below U+E101)
      const isShift = (cp) => cp >= 0xe101 && cp <= 0xe11a;
      const send = (text) => ctx.exec(`mc.getConnection().sendChat('${text}')
return 'sent'`, 15_000);
      const findShift = async (count, timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const lines = await ctx.chat(12);
          for (const line of lines) {
            const shifts = [...line].map((ch) => ch.codePointAt(0)).filter(isShift);
            if (shifts.length === count && !line.includes('<shift:')) return { line, shifts };
          }
          if (Date.now() > deadline) return null;
          await ctx.sleep(400);
        }
      };
      const findLiteral = async (marker, timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const lines = await ctx.chat(12);
          const line = lines.find((l) => l.includes(marker));
          if (line) return line;
          if (Date.now() > deadline) return null;
          await ctx.sleep(400);
        }
      };

      // --- +7: the greedy decomposition +4/+2/+1, measured by the client
      await send('L<shift:7>R');
      const plus = await findShift(3);
      if (!plus) ctx.expect('(no shifted line in chat history)').toBe('three shift chars');
      ctx.expect(plus.shifts[0]).toBe(0xe113); // +4, largest power first
      ctx.expect(plus.shifts[1]).toBe(0xe112); // +2
      ctx.expect(plus.shifts[2]).toBe(0xe111); // +1
      ctx.expect(Number(await widthOfExpr(cpText(plus.shifts)))).toBe(7);
      // each character measures its own space-provider advance — invisible,
      // bound, never the 6-wide missing-glyph box
      ctx.expect(Number(await widthOfCp(0xe113))).toBe(4);
      ctx.expect(Number(await widthOfCp(0xe112))).toBe(2);
      ctx.expect(Number(await widthOfCp(0xe111))).toBe(1);
      ctx.expect(plus.line.includes('<shift:')).toBe(false);
      ctx.expect(await ctx.shot('glyph-shift')).toBeTruthy();

      // --- −5: negative advances compose the same way
      await send('L<shift:-5>R');
      const minus = await findShift(2);
      if (!minus) ctx.expect('(no negative-shift line in chat history)').toBe('two shift chars');
      ctx.expect(minus.shifts[0]).toBe(0xe103); // −4
      ctx.expect(minus.shifts[1]).toBe(0xe101); // −1
      ctx.expect(Number(await widthOfExpr(cpText(minus.shifts)))).toBe(-5);

      // --- 600: beyond one power-of-two, still exact
      await send('L<shift:600>R');
      const wide = await findShift(4);
      if (!wide) ctx.expect('(no wide-shift line in chat history)').toBe('four shift chars');
      ctx.expect(wide.shifts[0]).toBe(0xe11a); // +512
      ctx.expect(wide.shifts[1]).toBe(0xe117); // +64
      ctx.expect(wide.shifts[2]).toBe(0xe115); // +16
      ctx.expect(wide.shifts[3]).toBe(0xe114); // +8
      ctx.expect(Number(await widthOfExpr(cpText(wide.shifts)))).toBe(600);

      // --- 0: the tag is consumed, nothing renders
      await send('L<shift:0>R');
      const zero = await findShift(0);
      if (!zero) ctx.expect('(no zero-shift line in chat history)').toBe('a plain LR line');
      ctx.expect(zero.line.includes('LR')).toBe(true);

      // --- invalid offset: stays literal
      await send('L<shift:abc>R');
      const bad = await findLiteral('<shift:abc>');
      if (!bad) ctx.expect('(no literal tag line in chat history)').toBe('the literal tag');
      ctx.expect(bad.includes('<shift:abc>')).toBe(true);
    },
  }),
];
