/** Makes `Cloudflare.Env` (used by `cloudflare:test`'s `env`) match the worker's Env. */
declare namespace Cloudflare {
  interface Env {
    DB: D1Database;
    RATE_LIMITER: RateLimit;
    CF_ACCOUNT_ID?: string;
    CF_AIG_GATEWAY_ID?: string;
    CF_AIG_TOKEN?: string;
    IP_HASH_SALT?: string;
    AIG_BASE_URL?: string;
    ALLOW_TEST_UPSTREAM_OVERRIDE?: string;
    CALLER_DAILY_CAP_PICOS?: string;
    GLOBAL_DAILY_POOL_PICOS?: string;
    SESSION_HMAC_SECRET?: string;
    PUBLIC_BASE_URL?: string;
    GOOGLE_CLIENT_ID?: string;
    GOOGLE_CLIENT_SECRET?: string;
    DISCORD_CLIENT_ID?: string;
    DISCORD_CLIENT_SECRET?: string;
    POLAR_ACCESS_TOKEN?: string;
    POLAR_WEBHOOK_SECRET?: string;
    POLAR_CREDITS_PRODUCT_ID?: string;
    POLAR_API_BASE?: string;
    ALERT_WEBHOOK_URL?: string;
    LOADED_GATEWAY_CREDITS_USD?: string;
    TEST_MIGRATIONS: { name: string; queries: string[] }[];
  }
}
