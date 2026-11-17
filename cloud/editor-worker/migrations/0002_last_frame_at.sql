-- Adaptive long-poll (web.md §3.2): when a frame was last posted on the
-- channel, in unix seconds. Set by the frame post's existing channel-row
-- UPDATE (no extra write); poll ticks read it with the row they already load.
ALTER TABLE channels ADD COLUMN last_frame_at INTEGER NOT NULL DEFAULT 0;
