-- ── Self-service password reset ─────────────────────────────────────────────
-- Only the SHA-256 hash of the reset token is ever persisted, mirroring why
-- passwords aren't stored in plaintext — unlike passwords these don't need
-- slow/salted hashing since they're already 256 bits of randomness, which is
-- infeasible to brute-force regardless of hash speed.
CREATE TABLE password_reset_tokens (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  tenant_id  UUID NOT NULL REFERENCES tenants(id),
  token_hash TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
CREATE INDEX idx_password_reset_tokens_hash ON password_reset_tokens(token_hash)
