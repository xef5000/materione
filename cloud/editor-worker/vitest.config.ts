import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest(async () => {
      const migrations = await readD1Migrations(path.join(__dirname, "migrations"));
      // workerd's node:fs cannot read Windows drive-letter paths, so the shared
      // golden fixture flows in from Node as a plain string binding. process.cwd()
      // is this package's root under both `npx vitest` and `npm test`.
      const fixtureJson = readFileSync(
        path.resolve(process.cwd(), "../../fixtures/protocol/relay-mailbox-v1.json"),
        "utf8",
      );
      // Relay routes every released plugin calls (fixtures/protocol/released/,
      // scripts/compat/editor-compat.mjs): a redeployed Worker must keep them.
      const releasedDir = path.resolve(process.cwd(), "../../fixtures/protocol/released");
      const releasedJson = JSON.stringify(
        readdirSync(releasedDir)
          .filter((name) => name.endsWith(".json"))
          .map((name) => JSON.parse(readFileSync(path.join(releasedDir, name), "utf8"))),
      );
      return {
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            MAILBOX_FIXTURE_JSON: fixtureJson,
            RELEASED_MANIFESTS_JSON: releasedJson,
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
