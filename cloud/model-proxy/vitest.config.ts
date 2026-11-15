import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest(async () => {
      const migrations = await readD1Migrations(path.join(import.meta.dirname, "migrations"));
      return {
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            CF_ACCOUNT_ID: "test-account-id",
            CF_AIG_GATEWAY_ID: "test-gateway-id",
            CF_AIG_TOKEN: "test-aig-token-secret",
            IP_HASH_SALT: "test-ip-hash-salt-0123456789abcdef",
            SESSION_HMAC_SECRET: "test-session-hmac-secret-0123456789abcdef",
            GOOGLE_CLIENT_ID: "test-google-client-id",
            GOOGLE_CLIENT_SECRET: "test-google-client-secret",
            DISCORD_CLIENT_ID: "test-discord-client-id",
            DISCORD_CLIENT_SECRET: "test-discord-client-secret",
            POLAR_ACCESS_TOKEN: "test-polar-access-token",
            POLAR_WEBHOOK_SECRET: "whsec_dGVzdC1wb2xhci13ZWJob29rLXNlY3JldC0wMTIzNDU=",
            POLAR_CREDITS_PRODUCT_ID: "06c6939d-72a3-4bef-8ded-5c6543811a28",
          },
        },
      };
    }),
  ],
  test: {
    include: ["test/**/*.test.ts"],
    setupFiles: ["./test/setup.ts"],
    testTimeout: 30_000,
  },
});
