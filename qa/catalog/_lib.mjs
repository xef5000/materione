// Catalog authoring library for the in-game QA suite (issue #38).
//
// A catalog file (`qa/catalog/<suite>.mjs`) default-exports an array of tests
// built with `test()`. The runner (`qa/runner.mjs`) loads every catalog file,
// validates the set (unique ids, resolvable acyclic deps, id/suite match) and
// executes selected tests through the probe agent. Structural validation
// lives in the runner so there is one source of truth; this module only gives
// authors a named constructor and the ctx contract.
//
// The ctx API a test's `steps(ctx)` receives (implemented in qa/runner.mjs):
//
//   ctx.rcon(cmd)                 → server-console command reply (string)
//   ctx.exec(groovy, timeoutMs?)  → client-side Groovy result, unwrapped
//   ctx.command(cmd)              → run a command AS THE PLAYER
//   ctx.rejoin()                  → disconnect, relaunch the probe client and
//                                   rejoin (join-time behaviors rerun; returns
//                                   once the player is in-world again)
//   ctx.chat(limit?)              → recent client chat lines (strings)
//   ctx.waitForChat(regex, opts?) → poll chat until a line matches (throws
//                                   AssertionError on timeout)
//   ctx.state()                   → client snapshot (position, screen, …)
//   ctx.shot(name)                -> Minecraft client screenshot in this test's
//                                   evidence dir; returns the absolute path
//   ctx.browser.openEditor()      -> optional real local plugin App using the
//                                   normal capability handshake and approval
//   ctx.browser.page              -> current Playwright page after openEditor()
//   ctx.browser.browserShot(name) -> scoped App screenshot, recorded separately
//   ctx.browser.close()           -> close this test's isolated browser
//   ctx.serverLogLine(regex)      → last server-log line matching since this
//                                   test started (boot markers are stable)
//   ctx.waitForServerLog(regex)   → poll the server log until it matches
//   ctx.reset()                   → clear inventory/effects/drops, teleport to
//                                   the scene anchor (fresh baseline per test)
//   ctx.expect(actual)            → assertions: toBe, toEqual, toContain,
//                                   toMatch, toBeGreaterThan, toBeLessThan,
//                                   toBeTruthy — failures carry
//                                   {operator, expected, actual} into the
//                                   failure dossier
//   ctx.sleep(ms), ctx.log(msg)   — pacing + transcript narration
//   ctx.needsEyes(what)           → declare what a human/vision pass must
//                                   confirm in the screenshots (required for
//                                   mode:'visual' tests)
//
// Modes: 'auto' (default) — the runner judges pass/fail from the assertions;
// 'visual' — steps capture evidence and call ctx.needsEyes(); the runner
// records needs-eyes and `runner mark <id> pass|fail` closes the loop.

export function test(def) {
  for (const key of ['id', 'title', 'steps']) {
    if (!(key in def)) {
      throw new Error(`test definition is missing "${key}": ${JSON.stringify(def).slice(0, 160)}`);
    }
  }
  return def;
}
