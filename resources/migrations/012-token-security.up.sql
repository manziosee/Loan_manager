-- ── Refresh token revocation ────────────────────────────────────────────────
-- refresh_tokens (id, user_id, token_hash, expires_at, created_at) already
-- existed from 001-auth but was never actually written to or queried —
-- refresh tokens were pure stateless JWTs with no server-side record at all,
-- so a stolen one stayed usable for its full 30-day life regardless of
-- logout or password change. Wiring it up (loanmanager.db.tokens) needs one
-- more column: a way to mark a token revoked before its natural expiry.
ALTER TABLE refresh_tokens ADD COLUMN revoked_at TIMESTAMPTZ
-- ;;
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id) WHERE revoked_at IS NULL
