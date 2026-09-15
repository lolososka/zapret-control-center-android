CREATE TABLE sessions (
  id TEXT PRIMARY KEY NOT NULL,
  token_hash TEXT NOT NULL,
  version TEXT NOT NULL,
  nonce TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  telegram_user_id TEXT,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'not_member', 'verified')),
  verified_until INTEGER,
  checking_until INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX sessions_expiry ON sessions(expires_at);

-- No raw addresses are stored: keys are HMACs scoped to a short time bucket.
CREATE TABLE rate_limits (
  bucket_key TEXT PRIMARY KEY NOT NULL,
  count INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX rate_limits_expiry ON rate_limits(expires_at);
