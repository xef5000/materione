// The M5 editor surface (roadmap M5): the /materione editor command family —
// cloud default with the local fallback, the always-available local mode, the
// status view, trust answers, and revocation. The probe judges from console
// replies and server markers; no browser client is involved here (the
// transport itself is gated live by :harness integration tests, incl. the
// relay gate over the fake mailbox).

import { test } from './_lib.mjs';

const TRANSPORT = 'plugin/src/main/java/dev/materione/plugin/runtime/EditorTransportService.java';
const COMMANDS = 'plugin/src/main/java/dev/materione/plugin/command/EditorCommands.java';

export default [
  test({
    id: 'editor.local-url',
    title: '/materione editor local hands out a loopback capability URL (always-available direct mode)',
    covers: [TRANSPORT, COMMANDS],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione editor local');
      // Boolean form on purpose: the reply embeds the capability URL and its
      // secret fragment — assert the shape, never quote the value into a
      // dossier or the ledger.
      ctx.expect(reply.includes('Editor session ready (local)')).toBeTruthy();
      await ctx.waitForServerLog(/MATERIONE-EDITOR http:\/\/127\.0\.0\.1:\d+\/\?s=/);
    },
  }),

  test({
    id: 'editor.default-surface',
    title: '/materione editor (cloud default) hands out an editor URL — cloud when the relay answers, local with MTR-0050 when it does not',
    covers: [
      TRANSPORT,
      COMMANDS,
      'web/src/main/java/dev/materione/web/relay/RelaySessions.java',
      'web/src/main/java/dev/materione/web/relay/RelayClient.java',
    ],
    dependsOn: ['editor.local-url'],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione editor');
      // Cloud when the relay answers; the local fallback with the MTR-0050
      // diagnostic when it does not. Either way the admin gets a working URL.
      ctx.expect(/Editor session ready \((cloud|local)\)/.test(reply)).toBeTruthy();
      await ctx.waitForServerLog(/MATERIONE-EDITOR http/);
      if (reply.includes('cloud editor unreachable')) {
        await ctx.waitForServerLog(/MTR-0050/);
      }
    },
  }),

  test({
    id: 'editor.status',
    title: '/materione editor status reports the running transport with its mode',
    covers: [TRANSPORT, COMMANDS],
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
    covers: [TRANSPORT, COMMANDS],
    dependsOn: ['editor.local-url'],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione editor approve deadbeef');
      ctx.expect(reply).toContain('no pending editor request with nonce deadbeef');
    },
  }),

  test({
    id: 'editor.revoke',
    title: 'revoking answers on every selector — bare (sessions), workstations, and an unknown prefix',
    covers: [TRANSPORT, COMMANDS, 'web/src/main/java/dev/materione/web/EditorWorkstations.java'],
    dependsOn: ['editor.default-surface'],
    steps: async (ctx) => {
      // Runs after default-surface so the session that test opened is swept.
      ctx.expect(
        (await ctx.rcon('materione editor revoke')).includes('all editor sessions revoked'),
      ).toBeTruthy();
      ctx.expect(
        (await ctx.rcon('materione editor revoke workstations'))
          .includes('all trusted workstations revoked'),
      ).toBeTruthy();
      ctx.expect(
        (await ctx.rcon('materione editor revoke zz'))
          .includes('no trusted workstation starts with zz'),
      ).toBeTruthy();
    },
  }),
];
