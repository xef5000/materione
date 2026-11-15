-- Model proxy daily allowance & atomic reserve/settle ledger (codemode.md §12.1, #402).
-- Monetary amounts are exact integer picodollars (1 USD = 1_000_000_000_000 picos).
-- Only daily-salted SHA-256 IP hashes and integer counters are stored; never raw IPs or prompts.

CREATE TABLE IF NOT EXISTS daily_caller_usage (
  day TEXT NOT NULL,
  caller_hash TEXT NOT NULL,
  used_picos INTEGER NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (day, caller_hash)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS daily_pool_usage (
  day TEXT PRIMARY KEY,
  used_picos INTEGER NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS reservations (
  id TEXT PRIMARY KEY,
  day TEXT NOT NULL,
  caller_hash TEXT NOT NULL,
  model TEXT NOT NULL,
  reserved_picos INTEGER NOT NULL,
  caller_cap_picos INTEGER NOT NULL,
  pool_cap_picos INTEGER NOT NULL,
  settled_picos INTEGER,
  status TEXT NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved', 'settled', 'kept')),
  input_tokens INTEGER,
  output_tokens INTEGER,
  cache_read_tokens INTEGER,
  cache_write_tokens INTEGER,
  created_at INTEGER NOT NULL,
  settled_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_reservations_day ON reservations(day);

-- Single-statement atomic reserve: ensures caller & pool rows exist, enforces both caps,
-- and debits worst-case reserved_picos before any upstream call is made.
CREATE TRIGGER IF NOT EXISTS trg_reservations_before_insert
BEFORE INSERT ON reservations
BEGIN
  INSERT INTO daily_caller_usage (day, caller_hash, used_picos, request_count, updated_at)
  VALUES (NEW.day, NEW.caller_hash, 0, 0, NEW.created_at)
  ON CONFLICT(day, caller_hash) DO NOTHING;

  INSERT INTO daily_pool_usage (day, used_picos, request_count, updated_at)
  VALUES (NEW.day, 0, 0, NEW.created_at)
  ON CONFLICT(day) DO NOTHING;

  SELECT RAISE(ABORT, 'ALLOWANCE_EXHAUSTED')
  FROM daily_caller_usage
  WHERE day = NEW.day
    AND caller_hash = NEW.caller_hash
    AND used_picos + NEW.reserved_picos > NEW.caller_cap_picos;

  SELECT RAISE(ABORT, 'POOL_EXHAUSTED')
  FROM daily_pool_usage
  WHERE day = NEW.day
    AND used_picos + NEW.reserved_picos > NEW.pool_cap_picos;

  UPDATE daily_caller_usage
  SET used_picos = used_picos + NEW.reserved_picos,
      request_count = request_count + 1,
      updated_at = NEW.created_at
  WHERE day = NEW.day AND caller_hash = NEW.caller_hash;

  UPDATE daily_pool_usage
  SET used_picos = used_picos + NEW.reserved_picos,
      request_count = request_count + 1,
      updated_at = NEW.created_at
  WHERE day = NEW.day;
END;

-- Single-statement atomic settle/keep: reconciles the difference between reserved_picos
-- and settled_picos exactly once when transitioning from 'reserved' to 'settled' or 'kept'.
CREATE TRIGGER IF NOT EXISTS trg_reservations_after_settle
AFTER UPDATE OF status ON reservations
WHEN OLD.status = 'reserved' AND NEW.status IN ('settled', 'kept')
BEGIN
  UPDATE daily_caller_usage
  SET used_picos = MAX(0, used_picos + (COALESCE(NEW.settled_picos, OLD.reserved_picos) - OLD.reserved_picos)),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE day = OLD.day AND caller_hash = OLD.caller_hash;

  UPDATE daily_pool_usage
  SET used_picos = MAX(0, used_picos + (COALESCE(NEW.settled_picos, OLD.reserved_picos) - OLD.reserved_picos)),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE day = OLD.day;
END;
