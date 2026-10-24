/** Makes `Cloudflare.Env` (used by `cloudflare:test`'s `env`) match the worker's Env. */
declare namespace Cloudflare {
  interface Env {
    DB: D1Database;
    RATE_LIMITER?: RateLimit;
    TEST_MIGRATIONS: { name: string; queries: string[] }[];
    /** Test-only: the shared golden fixture JSON, injected from Node by vitest.config.ts. */
    MAILBOX_FIXTURE_JSON: string;
  }
}
