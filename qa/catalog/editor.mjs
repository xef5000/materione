// The M5 editor surface (roadmap M5): command checks use console replies and
// server markers; focused browser entries exercise the real local App through
// its normal trust flow. The transport protocol remains gated by :harness
// integration tests, including the relay gate over the fake mailbox.

import { test } from './_lib.mjs';

const TRANSPORT = 'plugin/src/main/java/dev/materione/plugin/runtime/EditorTransportService.java';
const COMMANDS = 'plugin/src/main/java/dev/materione/plugin/command/EditorCommands.java';
const ADMIN_API = 'plugin/src/main/java/dev/materione/plugin/runtime/api/AdminApiImpl.java';
const BROWSER_COVERS = [
  'webui/src/App.svelte',
  'webui/src/lib/protocol/session.ts',
  'webui/src/lib/components/AppHeader.svelte',
  'webui/src/lib/components/Workbench.svelte',
  'webui/src/lib/components/DocEditor.svelte',
  'webui/src/lib/views/AdvancedView.svelte',
  'webui/src/lib/views/ContentView.svelte',
  'webui/src/lib/views/FileTree.svelte',
  'webui/src/lib/state.svelte.ts',
  'webui/src/lib/drafts.svelte.ts',
  'webui/src/lib/diagnostics.ts',
  'webui/src/lib/views/DiagnosticsView.svelte',
  'webui/src/lib/components/AssetPreviewTab.svelte',
  'webui/src/lib/components/BlockbenchTab.svelte',
  'webui/src/lib/components/SettingsTab.svelte',
  'webui/src/lib/dock/ActivityDock.svelte',
  'webui/src/lib/studio/Studio.svelte',
  'webui/test-support/qa-browser.mjs',
  'qa/runner.mjs',
  'qa/evidence-redaction.mjs',
  'qa/evidence-redaction.test.mjs',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
  'core/src/main/java/dev/materione/core/authoring/AuthoringApiImpl.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/EditorTransportService.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/ItemAutoUpdater.java',
];

async function openSampleItemFile(page) {
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.locator('input.filter').fill('ruby_sword');
  const file = page.locator('button.label[title="items/ruby_sword.yml"]');
  await file.waitFor({ state: 'visible', timeout: 20_000 });
  await file.click();
  await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
    .waitFor({ state: 'visible', timeout: 20_000 });
  await page.locator('article').getByLabel('Display name').waitFor({ state: 'visible' });
  await waitForDisplayName(page, 'Ruby Sword');
}

async function waitForDisplayName(page, expected) {
  const field = page.locator('article').getByLabel('Display name');
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (await field.inputValue() === expected) return;
    await page.waitForTimeout(100);
  }
  throw new Error('sample:ruby_sword display name did not load as expected');
}

async function assertHeldRubySwordName(ctx, expected) {
  let actual = '';
  for (let attempt = 0; attempt < 12; attempt++) {
    await ctx.rcon('clear Probe');
    await ctx.rcon('materione give sample:ruby_sword Probe');
    await ctx.waitForChat(/Given sample:ruby_sword/);
    await ctx.exec('player.getInventory().selected = 0');
    actual = String(await ctx.exec('return player.getMainHandItem().getHoverName().getString()'));
    if (actual === expected) return;
    await ctx.sleep(750);
  }
  ctx.expect(actual).toBe(expected);
}

async function waitForFileApply(page) {
  const editor = page.locator('article');
  await editor.getByRole('button', { name: 'Save & apply' }).waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const button = [...document.querySelectorAll('article header button')]
      .find((candidate) => candidate.textContent.trim() === 'Save & apply');
    return button instanceof HTMLButtonElement && button.disabled;
  }, undefined, { timeout: 30_000 });
}

export default [
  test({
    id: 'editor.local-url',
    title: '/materione editor local hands out a loopback capability URL (always-available direct mode)',
    covers: [TRANSPORT, COMMANDS, ADMIN_API],
    steps: async (ctx) => {
      // AdminApiImpl resolves the console actor to Bukkit's global console
      // sender, so successful API messages do not come back in the RCON reply.
      // Assert the server-side readiness and URL markers instead; never put a
      // capability URL into assertion output.
      await ctx.rcon('materione editor local');
      await ctx.waitForServerLog(/Editor session ready \(local\)/);
      await ctx.waitForServerLog(/MATERIONE-EDITOR http:\/\/127\.0\.0\.1:\d+\/\?s=/);
    },
  }),

  test({
    id: 'editor.default-surface',
    title: '/materione editor (cloud default) hands out an editor URL — cloud when the relay answers, local with MTR-0050 when it does not',
    covers: [
      TRANSPORT,
      COMMANDS,
      ADMIN_API,
      'web/src/main/java/dev/materione/web/relay/RelaySessions.java',
      'web/src/main/java/dev/materione/web/relay/RelayClient.java',
    ],
    dependsOn: ['editor.local-url'],
    steps: async (ctx) => {
      await ctx.rcon('materione editor');
      const marker = await ctx.waitForServerLog(/MATERIONE-EDITOR https?:\/\//);
      const cloudUrl = /MATERIONE-EDITOR https:\/\/[^/\s]+\/\?s=/.test(marker);
      const localUrl = /MATERIONE-EDITOR http:\/\/127\.0\.0\.1:\d+\/\?s=/.test(marker);
      const ready = await ctx.waitForServerLog(/Editor session ready \((cloud|local)\)/);
      const localFallback = ready.includes('(local)');
      // Keep URL-bearing log values out of assertion output.
      ctx.expect(localFallback ? localUrl : cloudUrl).toBeTruthy();
      if (localFallback) {
        await ctx.waitForServerLog(/MTR-0050/);
      }
    },
  }),

  test({
    id: 'editor.status',
    title: '/materione editor status reports the running transport with its mode',
    covers: [TRANSPORT, COMMANDS, ADMIN_API],
    dependsOn: ['editor.local-url'],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione editor status');
      ctx.expect(reply.includes('editor transport: running (')).toBeTruthy();
      ctx.expect(reply.includes('mode cloud')).toBeTruthy();
    },
  }),

  test({
    id: 'editor.approve-unknown-nonce',
    title: 'approving an unknown nonce answers with a fail-soft refusal, not a crash',
    covers: [TRANSPORT, COMMANDS, ADMIN_API],
    dependsOn: ['editor.local-url'],
    steps: async (ctx) => {
      await ctx.rcon('materione editor approve deadbeef');
      await ctx.waitForServerLog(/no pending editor request with nonce deadbeef/);
    },
  }),

  test({
    id: 'editor.browser-files-draft-save',
    title: 'sample:ruby_sword Files draft survives Problems and Content navigation, then applies only on Save & apply',
    covers: BROWSER_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      let mayNeedRestore = false;
      let stepsError = null;
      try {
        await openSampleItemFile(page);
        const fileName = page.locator('article').getByLabel('Display name');

        await page.getByRole('button', { name: 'Open in Studio' }).click();
        const studio = page.locator('.studio');
        await studio.waitFor({ state: 'visible' });
        const studioMeta = await studio.locator('header .meta').innerText();
        ctx.expect(studioMeta.split(' · ')[0]).toBe('ruby_sword');
        ctx.expect(await studio.getByLabel('Display name').inputValue()).toBe('Ruby Sword');
        await page.getByRole('button', { name: /Open in Files/ }).click();
        await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
          .waitFor({ state: 'visible' });

        await page.locator('article').getByLabel('Display name').fill('Ruby Sword QA Draft');
        await page.locator('article').getByLabel('Display name').press('Tab');
        const save = page.locator('article').getByRole('button', { name: 'Save & apply' });
        ctx.expect(await save.isDisabled()).toBe(false);

        await page.getByRole('button', { name: /^Problems(?:, .+)?$/ }).click();
        await page.getByRole('heading', { name: 'Problems', exact: true }).waitFor({ state: 'visible' });
        await page.getByRole('button', { name: 'Files', exact: true }).click();
        await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
          .waitFor({ state: 'visible' });
        ctx.expect(await fileName.inputValue()).toBe('Ruby Sword QA Draft');

        await page.getByRole('button', { name: 'Content', exact: true }).click();
        await studio.waitFor({ state: 'visible' });
        ctx.expect((await studio.locator('header .meta').innerText()).split(' · ')[0])
          .toBe('ruby_sword');
        ctx.expect(await studio.getByLabel('Display name').inputValue()).toBe('Ruby Sword QA Draft');
        await page.getByRole('button', { name: /Open in Files/ }).click();
        await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
          .waitFor({ state: 'visible' });
        ctx.expect(await fileName.inputValue()).toBe('Ruby Sword QA Draft');

        await assertHeldRubySwordName(ctx, 'Ruby Sword');
        mayNeedRestore = true;
        await page.locator('article').getByRole('button', { name: 'Save & apply' }).click();
        await waitForFileApply(page);
        await ctx.waitForServerLog(/MTR-0012: applied: /, { timeoutMs: 45_000 });
        await assertHeldRubySwordName(ctx, 'Ruby Sword QA Draft');
      } catch (error) {
        stepsError = error;
        throw error;
      } finally {
        if (mayNeedRestore) {
          try {
            const currentPage = ctx.browser.page;
            const field = currentPage.locator('article').getByLabel('Display name');
            if (await field.isVisible() && (await field.inputValue()) !== 'Ruby Sword') {
              await field.fill('Ruby Sword');
              await field.press('Tab');
              await currentPage.locator('article').getByRole('button', { name: 'Save & apply' }).click();
              await waitForFileApply(currentPage);
            }
            await assertHeldRubySwordName(ctx, 'Ruby Sword');
          } catch {
            if (!stepsError) throw new Error('sample:ruby_sword could not be restored in the disposable probe copy');
          }
        }
      }
    },
  }),

  test({
    id: 'editor.browser-files-visual',
    title: 'sample:ruby_sword Files form presents a staged draft and distinct apply and publish actions',
    mode: 'visual',
    dependsOn: ['editor.browser-files-draft-save'],
    covers: BROWSER_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await openSampleItemFile(page);
      await page.locator('article').getByLabel('Display name').fill('Ruby Sword Visual Draft');
      await page.locator('article').getByLabel('Display name').press('Tab');
      await page.getByRole('button', { name: /^Problems(?:, .+)?$/ }).waitFor({ state: 'visible' });
      await page.getByRole('button', { name: 'Publish pack', exact: true }).waitFor({ state: 'visible' });
      await ctx.browser.browserShot('sample-ruby-sword-files-draft');
      ctx.needsEyes(
        'the real local Materione App shows sample:ruby_sword in Files with the staged Ruby Sword Visual Draft clearly unsaved; navigation reads Content, Files, Problems, the file header offers Open in Studio, Save & apply is distinct from Publish pack, and the screenshot contains no capability URL or session material',
      );
    },
  }),

  test({
    id: 'editor.revoke',
    title: 'revoking answers on every selector — bare (sessions), workstations, and an unknown prefix',
    covers: [
      TRANSPORT,
      COMMANDS,
      ADMIN_API,
      'web/src/main/java/dev/materione/web/EditorWorkstations.java',
    ],
    dependsOn: ['editor.default-surface'],
    steps: async (ctx) => {
      // Runs after default-surface so the session that test opened is swept.
      await ctx.rcon('materione editor revoke');
      await ctx.waitForServerLog(/all editor sessions revoked/);
      await ctx.rcon('materione editor revoke workstations');
      await ctx.waitForServerLog(/all trusted workstations revoked/);
      await ctx.rcon('materione editor revoke zz');
      await ctx.waitForServerLog(/no trusted workstation starts with zz/);
    },
  }),
];
