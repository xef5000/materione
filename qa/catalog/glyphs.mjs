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
];
