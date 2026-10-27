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
      'platform/src/main/java/dev/materione/platform/text/MaterioneText.java',
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

      // --- the authored colon-less placeholder "<3" is tab-completed AND
      // resolves: plain placeholders match as exact substrings of literal
      // runs (#79), landing on the same codepoint as :heart:
      await ctx.exec(`mc.getConnection().sendChat('plain3 <3')
return 'sent'`, 15_000);
      const deadline2 = Date.now() + 10_000;
      let hearts = null;
      for (;;) {
        const lines = await ctx.chat(12);
        hearts = lines.find((line) => line.includes('plain3')) ?? null;
        if (hearts) break;
        if (Date.now() > deadline2) break;
        await ctx.sleep(400);
      }
      ctx.expect(hearts).toBeTruthy();
      const plainHigh = [...hearts].map((ch) => ch.codePointAt(0)).filter((cp) => cp >= 0x2000);
      ctx.expect(plainHigh.length).toBe(1); // exactly the heart, no literal residue
      ctx.expect(plainHigh[0]).toBe(cp);
      ctx.expect(hearts.includes('<3')).toBe(false);
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
      'platform/src/main/java/dev/materione/platform/text/MaterioneText.java',
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
      'platform/src/main/java/dev/materione/platform/text/MaterioneText.java',
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
      'platform/src/main/java/dev/materione/platform/text/MaterioneText.java',
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
      'platform/src/main/java/dev/materione/platform/text/MaterioneText.java',
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

      // --- 0: the tag is consumed, nothing renders (marker word: older
      // shift-free lines from predecessor tests linger in the history)
      await send('zero<shift:0>LR');
      const zero = await findLiteral('zeroLR');
      if (!zero) ctx.expect('(no zero-shift line in chat history)').toBe('a zeroLR line');
      const zeroShifts = [...zero].map((ch) => ch.codePointAt(0)).filter(isShift);
      ctx.expect(zeroShifts.length).toBe(0);
      ctx.expect(zero.includes('<shift:')).toBe(false);

      // --- invalid offset: stays literal
      await send('L<shift:abc>R');
      const bad = await findLiteral('<shift:abc>');
      if (!bad) ctx.expect('(no literal tag line in chat history)').toBe('the literal tag');
      ctx.expect(bad.includes('<shift:abc>')).toBe(true);
    },
  }),

  test({
    id: 'glyphs.emoji',
    title: 'emoji/chat integration end to end: :heart:, :crown: and the alias placeholder :bigheart: resolve in one real chat line (the alias landing on its target\'s codepoint); a per-glyph permission gates :crown: live per message (literal while deopped, resolving after re-op, :heart: unaffected); and the join-time custom chat completions carry exactly the placeholders a player may use — :heart: and <3 for everyone, :crown: only when permitted, :bigheart: never (tab_complete false)',
    mode: 'auto',
    covers: [
      'core/src/main/java/dev/materione/core/runtime/RuntimeGlyphs.java',
      'platform/src/main/java/dev/materione/platform/text/GlyphText.java',
      'platform/src/main/java/dev/materione/platform/text/MaterioneText.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/text/**',
      'fixtures/packs/sample/glyphs/**',
    ],
    dependsOn: ['glyphs.shift'],
    timeoutMs: 420_000,
    steps: async (ctx) => {
      const isGlyphCp = (cp) => cp > 41999 && cp < 42100;
      const send = (text) => ctx.exec(`mc.getConnection().sendChat('${text}')
return 'sent'`, 15_000);
      const findLine = async (marker, glyphCount, timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const lines = await ctx.chat(12);
          for (const line of lines) {
            const glyphs = [...line].map((ch) => ch.codePointAt(0)).filter(isGlyphCp);
            if (glyphs.length === glyphCount && line.includes(marker)) {
              return { line, glyphs };
            }
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
      // the client stores the join-time custom chat completions in its
      // suggestion provider (connection.suggestionsProvider.
      // customCompletionSuggestions on 26.1.2 — a string set)
      const completions = async () => {
        const out = await ctx.exec(`def v = mc.getConnection().suggestionsProvider.customCompletionSuggestions
def out = []
for (def e : v) { out.add(e.toString()) }
return out.join(' | ')`, 15_000);
        return String(out);
      };

      await ctx.reset();
      // Completion suggestions are sent only at join. The alias test may
      // promote Probe after a deopped join without refreshing that packet.
      await ctx.rcon('op Probe');
      await ctx.rejoin();
      await ctx.sleep(5_000); // settle the pack and operator-gated completions

      // --- resolution matrix in one line: two regular emoji + the alias
      // placeholder (resolves through its target's bitmaps)
      await send(':heart: :crown: :bigheart:');
      const all = await findLine('Probe', 3);
      if (!all) ctx.expect('(no emoji line in chat history)').toBe('three glyph codepoints');
      ctx.expect(all.line.includes(':crown:')).toBe(false);
      ctx.expect(all.line.includes(':bigheart:')).toBe(false);
      const H = all.glyphs[0]; // :heart:
      const C = all.glyphs[1]; // :crown:
      ctx.expect(C === H).toBe(false); // distinct glyphs, distinct codepoints
      ctx.expect(all.glyphs[2]).toBe(H); // :bigheart: -> heart_alias -> heart cell 1
      for (const cp of all.glyphs) {
        ctx.expect(cp).toBeGreaterThan(41999);
        ctx.expect(cp).toBeLessThan(42100);
      }
      ctx.expect(await ctx.shot('glyph-emoji')).toBeTruthy();

      // --- the join-time completion list (this session joined as op):
      // every placeholder the player may use, and nothing else
      const opCompletions = await completions();
      ctx.expect(opCompletions.includes(':heart:')).toBe(true);
      ctx.expect(opCompletions.includes('<3')).toBe(true);
      ctx.expect(opCompletions.includes(':crown:')).toBe(true); // tab_complete: true
      ctx.expect(opCompletions.includes(':bigheart:')).toBe(false); // tab_complete: false

      // --- permission gates :crown: live per message; :heart: has none
      await ctx.rcon('deop Probe');
      await send('denied :crown: but :heart: works');
      await ctx.sleep(2_500); // the async decorate settles while still deopped
      await ctx.rcon('op Probe');
      const denied = await findLine('denied', 1);
      if (!denied) ctx.expect('(no denied line in chat history)').toBe('one heart, literal crown');
      ctx.expect(denied.line.includes(':crown:')).toBe(true); // literal for a denied viewer
      ctx.expect(denied.glyphs[0]).toBe(H); // :heart: unaffected by crown's permission
      await send('allowed :crown:');
      const allowed = await findLine('allowed', 1);
      if (!allowed) ctx.expect('(no allowed line in chat history)').toBe('the resolved crown');
      ctx.expect(allowed.glyphs[0]).toBe(C);

      // --- the completion list is built at JOIN: rejoin deopped and :crown:
      // drops out (permission-filtered), while :heart: and <3 stay
      await ctx.rcon('deop Probe');
      await ctx.rejoin();
      await ctx.sleep(3_000);
      const deniedCompletions = await completions();
      ctx.expect(deniedCompletions.includes(':heart:')).toBe(true);
      ctx.expect(deniedCompletions.includes('<3')).toBe(true);
      ctx.expect(deniedCompletions.includes(':crown:')).toBe(false);
      ctx.expect(deniedCompletions.includes(':bigheart:')).toBe(false);
      // restore the permitted baseline (op + fresh join rebuilds the list)
      await ctx.rcon('op Probe');
      await ctx.rejoin();
      await ctx.sleep(3_000);
      const restored = await completions();
      ctx.expect(restored.includes(':crown:')).toBe(true);
      ctx.expect(restored.includes(':heart:')).toBe(true);
    },
  }),

  test({
    id: 'glyphs.effects',
    title: 'GLSL text effects live: the client\'s active resource manager serves the version-matched injected rendertype_text vertex shader under the vanilla name (materione marker present, the client\'s own pack format baked into the generated include) with exactly four signal-keyed animation branches — shake/wave in pre-transform, pulse/rainbow in post-transform; <e:…> spans resolve in real chat; the two-frame evidence shows the four effect words animating differently between frames',
    mode: 'visual',
    covers: [
      'content/src/main/java/dev/materione/content/effects/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeEffects.java',
      'pack/src/main/java/dev/materione/pack/compile/PackCompiler.java',
      'pack/src/main/java/dev/materione/pack/compile/TextEffectShaders.java',
      'pack/src/main/resources/shaders/**',
      'pack/src/test/java/dev/materione/pack/compile/PackCompilerTest.java',
      'platform/src/main/java/dev/materione/platform/text/GlyphText.java',
      'platform/src/main/java/dev/materione/platform/text/MaterioneText.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/text/**',
      'fixtures/packs/sample/effects/**',
    ],
    // Shared chat decoration is the prerequisite; emoji's join-time completion list is orthogonal.
    dependsOn: ['glyphs.alias'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot settle; the pack loads at join

      // the client resource manager serves the INJECTED shader under the
      // vanilla name — the overlay is active for this client's format, not
      // the shader-free base layer
      const shader = await ctx.exec(`def rm = mc.getResourceManager()
def idc = Class.forName('net.minecraft.resources.Identifier')
def rl = idc.fromNamespaceAndPath('minecraft', 'shaders/core/rendertype_text.vsh')
def opt = rm.getResource(rl)
if (!opt.isPresent()) return 'NOT PRESENT'
return new String(opt.get().open().readAllBytes().@target, java.nio.charset.StandardCharsets.UTF_8)`, 20_000);
      const source = String(shader);
      ctx.expect(source).toContain('#version 330');
      ctx.expect(source).toContain('#moj_import <minecraft:materione_text_effects.glsl>');
      ctx.expect(source).toContain('materionePreTransform(mtrPosition);');
      ctx.expect(source).toContain('materionePostTransform(mtrPosition);');

      // the generated include: the client's own pack format baked (84 = the
      // 26.1 rung this probe client resolves to), one branch per effect keyed
      // on its signal color — shake + wave pre-transform, pulse + rainbow post
      const include = await ctx.exec(`def rm = mc.getResourceManager()
def idc = Class.forName('net.minecraft.resources.Identifier')
def rl = idc.fromNamespaceAndPath('minecraft', 'shaders/include/materione_text_effects.glsl')
return new String(rm.getResource(rl).get().open().readAllBytes().@target, java.nio.charset.StandardCharsets.UTF_8)`, 20_000);
      const inc = String(include);
      ctx.expect(inc).toContain('MATERIONE_PACK_FORMAT 84');
      ctx.expect(inc).toContain('materionePreTransform');
      ctx.expect(inc).toContain('materionePostTransform');
      ctx.expect(inc).toContain('mtrHash'); // shake's jitter
      ctx.expect(inc).toContain('MTR_TAU'); // wave/pulse timing
      ctx.expect(inc).toContain('0.166667'); // the rainbow gradient chain
      let branchCount = 0;
      let at = 0;
      while ((at = inc.indexOf('if (mtrSignal(', at)) >= 0) {
        branchCount += 1;
        at += 1;
      }
      ctx.expect(branchCount).toBe(4); // one per effect (the helper definition doesn't match)

      // the spans resolve in real chat (the decorate path; the colors
      // themselves are the shader's business — judged by eyes below).
      // Close tags are </e>/</effect> — the id lives on the open tag only
      await ctx.exec(`mc.getConnection().sendChat('A <e:rainbow>rainbow</e> <e:wave>wave</e> <e:shake>shake</e> <e:pulse>pulse</e> B')
return 'sent'`, 15_000);
      const deadline = Date.now() + 15_000;
      let line = null;
      for (;;) {
        const lines = await ctx.chat(12);
        line = lines.find((l) => l.includes('rainbow') && l.includes('pulse')) ?? null;
        if (line) break;
        if (Date.now() > deadline) break;
        await ctx.sleep(400);
      }
      ctx.expect(line).toBeTruthy();
      ctx.expect(line.includes('<e:')).toBe(false); // tags consumed
      ctx.expect(line.includes('</e')).toBe(false);

      // the animation itself: two frames 700ms apart — judged by eyes
      await ctx.sleep(700);
      await ctx.shot('effects-frame-a');
      await ctx.sleep(700);
      await ctx.shot('effects-frame-b');
      ctx.needsEyes('the four effect words (rainbow, wave, shake, pulse) render animated by the injected text shader — rainbow cycling hues, wave rippling vertically, shake jittering, pulse fading — visibly DIFFERENT between the two frames while the plain A/B words stay static, and no unshaded fallback (the effect words are not plain solid magenta-family signal text)');
    },
  }),
];
