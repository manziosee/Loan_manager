-- ── Delinquency history log ───────────────────────────────────────────────────
CREATE TABLE delinquency_log (
  id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  loan_id              UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
  tenant_id            UUID NOT NULL REFERENCES tenants(id),
  days_overdue         INTEGER NOT NULL,
  bucket               VARCHAR(20) NOT NULL,
  outstanding_principal NUMERIC(18,2) NOT NULL,
  provision_rate       NUMERIC(8,4) NOT NULL,
  provision_amount     NUMERIC(18,2) NOT NULL,
  triggered_actions    JSONB NOT NULL DEFAULT '[]',
  assessed_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
CREATE INDEX idx_delinquency_loan ON delinquency_log(loan_id, assessed_at DESC)
-- ;;
CREATE INDEX idx_delinquency_bucket ON delinquency_log(tenant_id, bucket)
-- ;;
-- ── Enhanced collection cases ─────────────────────────────────────────────────
-- Drop and recreate with full schema (was minimal in migration 005)
DROP TABLE IF EXISTS collection_activities
-- ;;
DROP TABLE IF EXISTS collection_cases
-- ;;
CREATE TABLE collection_cases (
  id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id            UUID NOT NULL REFERENCES tenants(id),
  case_no              VARCHAR(50) NOT NULL,
  loan_id              UUID NOT NULL REFERENCES loans(id),
  customer_id          UUID NOT NULL REFERENCES customers(id),
  assigned_to          UUID REFERENCES users(id),
  status               VARCHAR(50) NOT NULL DEFAULT 'open',
  priority             VARCHAR(20) NOT NULL DEFAULT 'medium',
  days_overdue_at_open INTEGER NOT NULL DEFAULT 0,
  outstanding_at_open  NUMERIC(18,2) NOT NULL DEFAULT 0,
  total_collected      NUMERIC(18,2) NOT NULL DEFAULT 0,
  notes                TEXT,
  escalated_to         UUID REFERENCES users(id),
  escalated_at         TIMESTAMPTZ,
  resolved_at          TIMESTAMPTZ,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(tenant_id, case_no)
)
-- ;;
CREATE TABLE collection_activities (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  case_id         UUID NOT NULL REFERENCES collection_cases(id) ON DELETE CASCADE,
  activity_type   VARCHAR(100) NOT NULL,
  outcome         VARCHAR(100),
  notes           TEXT,
  recorded_by     UUID REFERENCES users(id),
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
-- ── Promise-to-pay ────────────────────────────────────────────────────────────
CREATE TABLE promises_to_pay (
  id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  case_id               UUID NOT NULL REFERENCES collection_cases(id) ON DELETE CASCADE,
  loan_id               UUID NOT NULL REFERENCES loans(id),
  promise_date          DATE NOT NULL,
  promise_amount        NUMERIC(18,2) NOT NULL,
  actual_payment_date   DATE,
  actual_payment_amount NUMERIC(18,2),
  status                VARCHAR(20) NOT NULL DEFAULT 'pending',
  broken_reason         TEXT,
  recorded_by           UUID REFERENCES users(id),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
CREATE INDEX idx_promises_case   ON promises_to_pay(case_id)
-- ;;
CREATE INDEX idx_promises_status ON promises_to_pay(status, promise_date)
-- ;;
CREATE INDEX idx_collection_cases_loan   ON collection_cases(loan_id)
-- ;;
CREATE INDEX idx_collection_cases_status ON collection_cases(tenant_id, status, priority)
