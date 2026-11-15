-- Accounts, OAuth identities, sessions, Polar orders, and two-bucket atomic reserve/settle (#386, #409).
-- Monetary amounts are exact integer picodollars (1 USD = 1_000_000_000_000 picos) of wholesale AI budget.
-- Never stores raw IPs, OAuth tokens, Polar secrets, or prompt/SSE content.

DROP TRIGGER IF EXISTS trg_reservations_before_insert;
DROP TRIGGER IF EXISTS trg_reservations_after_settle;
DROP TABLE IF EXISTS reservations;
DROP TABLE IF EXISTS daily_caller_usage;
DROP TABLE IF EXISTS daily_pool_usage;

CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  email_verified INTEGER NOT NULL DEFAULT 1,
  display_name TEXT,
  credit_balance_picos INTEGER NOT NULL DEFAULT 0,
  free_eligible INTEGER NOT NULL DEFAULT 1,
  suspended INTEGER NOT NULL DEFAULT 0,
  suspend_reason TEXT,
  polar_customer_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_email ON accounts(email);

CREATE TABLE IF NOT EXISTS account_identities (
  provider TEXT NOT NULL CHECK (provider IN ('google', 'discord')),
  provider_subject TEXT NOT NULL,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  provider_email TEXT,
  provider_created_at INTEGER,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (provider, provider_subject)
);

CREATE INDEX IF NOT EXISTS idx_identities_account ON account_identities(account_id);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_sessions_account ON sessions(account_id);

CREATE TABLE IF NOT EXISTS credit_ledger_entries (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  kind TEXT NOT NULL CHECK (kind IN ('purchase', 'refund', 'dispute_hold', 'dispute_release', 'manual_adjustment')),
  delta_picos INTEGER NOT NULL,
  balance_after_picos INTEGER NOT NULL,
  polar_order_id TEXT,
  webhook_id TEXT UNIQUE,
  note TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_account ON credit_ledger_entries(account_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ledger_unique_purchase
  ON credit_ledger_entries(polar_order_id)
  WHERE kind = 'purchase';

CREATE TABLE IF NOT EXISTS polar_orders (
  order_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  net_amount_cents INTEGER NOT NULL,
  granted_picos INTEGER NOT NULL,
  refunded_amount_cents INTEGER NOT NULL DEFAULT 0,
  refunded_picos INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_polar_orders_account ON polar_orders(account_id);

CREATE TABLE IF NOT EXISTS daily_account_free_usage (
  day TEXT NOT NULL,
  account_id TEXT NOT NULL,
  used_picos INTEGER NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (day, account_id)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS daily_subnet_free_usage (
  day TEXT NOT NULL,
  subnet_hash TEXT NOT NULL,
  used_picos INTEGER NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (day, subnet_hash)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS daily_pool_free_usage (
  day TEXT PRIMARY KEY,
  used_picos INTEGER NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS reservations (
  id TEXT PRIMARY KEY,
  day TEXT NOT NULL,
  account_id TEXT NOT NULL,
  subnet_hash TEXT NOT NULL,
  model TEXT NOT NULL,
  reserved_picos INTEGER NOT NULL,
  free_reserved_picos INTEGER NOT NULL,
  paid_reserved_picos INTEGER NOT NULL,
  account_cap_picos INTEGER NOT NULL,
  subnet_cap_picos INTEGER NOT NULL,
  pool_cap_picos INTEGER NOT NULL,
  settled_picos INTEGER,
  free_settled_picos INTEGER,
  paid_settled_picos INTEGER,
  status TEXT NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved', 'settled', 'kept')),
  input_tokens INTEGER,
  output_tokens INTEGER,
  cache_read_tokens INTEGER,
  cache_write_tokens INTEGER,
  created_at INTEGER NOT NULL,
  settled_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_reservations_day ON reservations(day);
CREATE INDEX IF NOT EXISTS idx_reservations_account ON reservations(account_id, created_at);

CREATE TABLE IF NOT EXISTS gateway_spend_totals (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  settled_picos INTEGER NOT NULL DEFAULT 0,
  last_alerted_threshold_picos INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
);

-- Atomic ledger balance & status update upon ledger entry insertion.
CREATE TRIGGER IF NOT EXISTS trg_credit_ledger_after_insert
AFTER INSERT ON credit_ledger_entries
BEGIN
  UPDATE accounts
  SET credit_balance_picos = credit_balance_picos + NEW.delta_picos,
      free_eligible = CASE WHEN credit_balance_picos + NEW.delta_picos < 0 OR NEW.kind = 'dispute_hold' THEN 0 ELSE free_eligible END,
      suspended = CASE
        WHEN NEW.kind = 'dispute_hold' THEN 1
        WHEN NEW.kind = 'dispute_release'
          AND suspend_reason = 'chargeback_dispute'
          AND NOT EXISTS (
            SELECT 1 FROM credit_ledger_entries h
            WHERE h.account_id = NEW.account_id
              AND h.kind = 'dispute_hold'
              AND NOT EXISTS (
                SELECT 1 FROM credit_ledger_entries r
                WHERE r.account_id = NEW.account_id
                  AND r.kind = 'dispute_release'
                  AND COALESCE(r.polar_order_id, '') = COALESCE(h.polar_order_id, '')
                  AND r.rowid > h.rowid
              )
          ) THEN 0
        ELSE suspended
      END,
      suspend_reason = CASE
        WHEN NEW.kind = 'dispute_hold' THEN 'chargeback_dispute'
        WHEN NEW.kind = 'dispute_release'
          AND suspend_reason = 'chargeback_dispute'
          AND NOT EXISTS (
            SELECT 1 FROM credit_ledger_entries h
            WHERE h.account_id = NEW.account_id
              AND h.kind = 'dispute_hold'
              AND NOT EXISTS (
                SELECT 1 FROM credit_ledger_entries r
                WHERE r.account_id = NEW.account_id
                  AND r.kind = 'dispute_release'
                  AND COALESCE(r.polar_order_id, '') = COALESCE(h.polar_order_id, '')
                  AND r.rowid > h.rowid
              )
          ) THEN NULL
        ELSE suspend_reason
      END,
      updated_at = NEW.created_at
  WHERE id = NEW.account_id;
END;

-- Single-statement two-bucket atomic reserve: enforces suspension/negative-balance,
-- dual-gated daily free caps, and prepaid credit balance before any upstream call.
CREATE TRIGGER IF NOT EXISTS trg_reservations_before_insert
BEFORE INSERT ON reservations
BEGIN
  SELECT RAISE(ABORT, 'ACCOUNT_SUSPENDED')
  FROM accounts
  WHERE id = NEW.account_id
    AND (suspended != 0 OR credit_balance_picos < 0);

  INSERT INTO daily_account_free_usage (day, account_id, used_picos, request_count, updated_at)
  VALUES (NEW.day, NEW.account_id, 0, 0, NEW.created_at)
  ON CONFLICT(day, account_id) DO NOTHING;

  INSERT INTO daily_subnet_free_usage (day, subnet_hash, used_picos, request_count, updated_at)
  VALUES (NEW.day, NEW.subnet_hash, 0, 0, NEW.created_at)
  ON CONFLICT(day, subnet_hash) DO NOTHING;

  INSERT INTO daily_pool_free_usage (day, used_picos, request_count, updated_at)
  VALUES (NEW.day, 0, 0, NEW.created_at)
  ON CONFLICT(day) DO NOTHING;

  SELECT RAISE(ABORT, 'INVALID_RESERVATION_SPLIT')
  WHERE NEW.free_reserved_picos < 0
     OR NEW.paid_reserved_picos < 0
     OR NEW.free_reserved_picos + NEW.paid_reserved_picos != NEW.reserved_picos;

  SELECT RAISE(ABORT, 'FREE_NOT_ELIGIBLE')
  FROM accounts
  WHERE id = NEW.account_id
    AND NEW.free_reserved_picos > 0
    AND free_eligible != 1;

  SELECT RAISE(ABORT, 'ALLOWANCE_EXHAUSTED')
  FROM daily_account_free_usage a, daily_subnet_free_usage s
  WHERE a.day = NEW.day AND a.account_id = NEW.account_id
    AND s.day = NEW.day AND s.subnet_hash = NEW.subnet_hash
    AND NEW.free_reserved_picos > 0
    AND (a.used_picos + NEW.free_reserved_picos > NEW.account_cap_picos
      OR s.used_picos + NEW.free_reserved_picos > NEW.subnet_cap_picos);

  SELECT RAISE(ABORT, 'POOL_EXHAUSTED')
  FROM daily_pool_free_usage
  WHERE day = NEW.day
    AND NEW.free_reserved_picos > 0
    AND used_picos + NEW.free_reserved_picos > NEW.pool_cap_picos;

  SELECT RAISE(ABORT, 'INSUFFICIENT_CREDITS')
  FROM accounts
  WHERE id = NEW.account_id
    AND credit_balance_picos < NEW.paid_reserved_picos;

  UPDATE daily_account_free_usage
  SET used_picos = used_picos + NEW.free_reserved_picos,
      request_count = request_count + (CASE WHEN NEW.free_reserved_picos > 0 THEN 1 ELSE 0 END),
      updated_at = NEW.created_at
  WHERE day = NEW.day AND account_id = NEW.account_id AND NEW.free_reserved_picos > 0;

  UPDATE daily_subnet_free_usage
  SET used_picos = used_picos + NEW.free_reserved_picos,
      request_count = request_count + (CASE WHEN NEW.free_reserved_picos > 0 THEN 1 ELSE 0 END),
      updated_at = NEW.created_at
  WHERE day = NEW.day AND subnet_hash = NEW.subnet_hash AND NEW.free_reserved_picos > 0;

  UPDATE daily_pool_free_usage
  SET used_picos = used_picos + NEW.free_reserved_picos,
      request_count = request_count + (CASE WHEN NEW.free_reserved_picos > 0 THEN 1 ELSE 0 END),
      updated_at = NEW.created_at
  WHERE day = NEW.day AND NEW.free_reserved_picos > 0;

  UPDATE accounts
  SET credit_balance_picos = credit_balance_picos - NEW.paid_reserved_picos,
      updated_at = NEW.created_at
  WHERE id = NEW.account_id AND NEW.paid_reserved_picos > 0;
END;

-- Single-statement atomic settle/keep: consumes free reservation first, paid reservation second,
-- and refunds unused portions to their respective buckets exactly once.
CREATE TRIGGER IF NOT EXISTS trg_reservations_after_settle
AFTER UPDATE OF status ON reservations
WHEN OLD.status = 'reserved' AND NEW.status IN ('settled', 'kept')
BEGIN
  UPDATE daily_account_free_usage
  SET used_picos = MAX(0, used_picos - (OLD.free_reserved_picos - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos))),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE day = OLD.day
    AND account_id = OLD.account_id
    AND OLD.free_reserved_picos > MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos);

  UPDATE daily_subnet_free_usage
  SET used_picos = MAX(0, used_picos - (OLD.free_reserved_picos - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos))),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE day = OLD.day
    AND subnet_hash = OLD.subnet_hash
    AND OLD.free_reserved_picos > MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos);

  UPDATE daily_pool_free_usage
  SET used_picos = MAX(0, used_picos - (OLD.free_reserved_picos - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos))),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE day = OLD.day
    AND OLD.free_reserved_picos > MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos);

  UPDATE accounts
  SET credit_balance_picos = credit_balance_picos + (
        OLD.paid_reserved_picos - MAX(0, COALESCE(NEW.settled_picos, OLD.reserved_picos) - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos))
      ),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE id = OLD.account_id
    AND OLD.paid_reserved_picos != MAX(0, COALESCE(NEW.settled_picos, OLD.reserved_picos) - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos));

  INSERT INTO gateway_spend_totals (id, settled_picos, last_alerted_threshold_picos, updated_at)
  VALUES (1, COALESCE(NEW.settled_picos, OLD.reserved_picos), 0, COALESCE(NEW.settled_at, OLD.created_at))
  ON CONFLICT(id) DO UPDATE SET
    settled_picos = settled_picos + excluded.settled_picos,
    updated_at = excluded.updated_at;
END;
