-- Materione editor relay — initial schema (web.md §2/§3).
-- Times are unix seconds. Tokens are stored as sha256 hex, never plaintext.

CREATE TABLE IF NOT EXISTS channels (
  id TEXT PRIMARY KEY,
  plugin_token_hash TEXT NOT NULL,
  editor_token_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_plugin_seen INTEGER NOT NULL,
  last_editor_seen INTEGER NOT NULL,
  closed INTEGER NOT NULL DEFAULT 0,
  frames_bytes INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_channels_expiry ON channels(expires_at);
CREATE INDEX IF NOT EXISTS idx_channels_seen ON channels(last_plugin_seen, last_editor_seen);

CREATE TABLE IF NOT EXISTS frames (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  direction TEXT NOT NULL CHECK (direction IN ('p2e', 'e2p')),
  body TEXT NOT NULL,
  size INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_frames_chan_dir_id ON frames(channel_id, direction, id);
CREATE INDEX IF NOT EXISTS idx_frames_created ON frames(created_at);

CREATE TABLE IF NOT EXISTS blobs (
  code TEXT PRIMARY KEY,
  body TEXT NOT NULL,
  size INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  delete_token_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_blobs_expiry ON blobs(expires_at);

CREATE TABLE IF NOT EXISTS usage (
  day TEXT PRIMARY KEY,
  new_row_writes INTEGER NOT NULL DEFAULT 0,
  store_posts INTEGER NOT NULL DEFAULT 0,
  store_bytes INTEGER NOT NULL DEFAULT 0
);
