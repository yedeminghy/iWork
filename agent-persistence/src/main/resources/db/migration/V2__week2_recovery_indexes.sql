CREATE INDEX IF NOT EXISTS idx_thread_status_updated
  ON thread(status, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_turn_status
  ON turn(status, started_at, id);

CREATE INDEX IF NOT EXISTS idx_item_thread_turn_sequence
  ON item(thread_id, turn_id, sequence, id);
