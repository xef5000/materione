// The M5 editor surface (roadmap M5): the /materione editor command family —
// cloud default with the local fallback, the always-available local mode, the
// status view, trust answers, and revocation. The probe judges from console
// replies and server markers; no browser client is involved here (the
// transport itself is gated live by :harness integration tests, incl. the
// relay gate over the fake mailbox).

import { test } from './_lib.mjs';

const TRANSPORT = 'plugin/src/main/java/dev/materione/plugin/runtime/EditorTransportService.java';
const COMMANDS = 'plugin/src/main/java/dev/materione/plugin/command/EditorCommands.java';
const ADMIN_API = 'plugin/src/main/java/dev/materione/plugin/runtime/api/AdminApiImpl.java';

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
