-- ── Fix application_status enum — add missing values ───────────────────────
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'withdrawn'
-- ;;
ALTER TYPE application_status ADD VALUE IF NOT EXISTS 'disbursed'
-- ;;
-- ── Performance indexes ───────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_loans_updated_at          ON loans(tenant_id, updated_at DESC)
-- ;;
CREATE INDEX IF NOT EXISTS idx_customers_updated_at      ON customers(tenant_id, updated_at DESC)
-- ;;
CREATE INDEX IF NOT EXISTS idx_applications_updated_at   ON loan_applications(tenant_id, updated_at DESC)
-- ;;
CREATE INDEX IF NOT EXISTS idx_payments_loan_date        ON payments(loan_id, payment_date DESC)
-- ;;
CREATE INDEX IF NOT EXISTS idx_users_tenant_email        ON users(tenant_id, email)
-- ;;
CREATE INDEX IF NOT EXISTS idx_collateral_loan           ON collateral(loan_id)
-- ;;
CREATE INDEX IF NOT EXISTS idx_guarantors_loan           ON guarantors(loan_id)
-- ;;
-- ── Collateral: add updated_at and make customer_id nullable ────────────────
ALTER TABLE collateral
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ALTER COLUMN customer_id DROP NOT NULL
-- ;;
-- ── Soft-delete on customers ──────────────────────────────────────────────────
ALTER TABLE customers
  ADD COLUMN IF NOT EXISTS active          BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS deactivated_at  TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deactivated_by  UUID REFERENCES users(id)
-- ;;
CREATE INDEX IF NOT EXISTS idx_customers_active          ON customers(tenant_id, kyc_status) WHERE active = TRUE
-- ;;
-- ── Notifications ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notifications (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id     UUID NOT NULL REFERENCES tenants(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  type          VARCHAR(100) NOT NULL,
  title         VARCHAR(300) NOT NULL,
  body          TEXT,
  reference_type VARCHAR(100),
  reference_id  UUID,
  read          BOOLEAN NOT NULL DEFAULT FALSE,
  read_at       TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, read, created_at DESC)
-- ;;
-- ── Rate-limit tracking (login brute-force) ───────────────────────────────────
CREATE TABLE IF NOT EXISTS login_attempts (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id   UUID REFERENCES tenants(id),
  email       VARCHAR(255) NOT NULL,
  ip_address  INET,
  success     BOOLEAN NOT NULL DEFAULT FALSE,
  attempted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
CREATE INDEX IF NOT EXISTS idx_login_attempts_email ON login_attempts(email, attempted_at DESC)
-- ;;
-- ── Token blacklist (DB-backed for persistence across restarts) ───────────────
CREATE TABLE IF NOT EXISTS token_blacklist (
  jti         VARCHAR(100) PRIMARY KEY,
  user_id     UUID REFERENCES users(id),
  expires_at  TIMESTAMPTZ NOT NULL,
  blacklisted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires ON token_blacklist(expires_at)
