/** Makes `Cloudflare.Env` (used by `cloudflare:test`'s `env`) match the worker's Env. */
declare namespace Cloudflare {
  interface Env {
    DB: D1Database;
    RATE_LIMITER?: RateLimit;
    CF_ACCOUNT_ID?: string;
    CF_AIG_GATEWAY_ID?: string;
    CF_AIG_TOKEN?: string;
    IP_HASH_SALT?: string;
    AIG_BASE_URL?: string;
    CALLER_DAILY_CAP_PICOS?: string;
    GLOBAL_DAILY_POOL_PICOS?: string;
    TEST_MIGRATIONS: { name: string; queries: string[] }[];
  }
}
