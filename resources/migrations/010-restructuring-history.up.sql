-- ── Restructuring history ──────────────────────────────────────────────────
-- Preserves prior terms every time a loan is restructured, instead of
-- overwriting them in place on the loans row (loans.restructure_count keeps
-- counting up; this table is what "Restructuring #1 -> #2" actually means).
CREATE TABLE loan_restructurings (
  id                             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id                      UUID NOT NULL REFERENCES tenants(id),
  loan_id                        UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
  sequence_no                    INTEGER NOT NULL,
  reason                         TEXT,
  previous_interest_rate         NUMERIC(8,4),
  previous_duration_months       INTEGER,
  previous_repayment_freq        VARCHAR(20),
  previous_outstanding_principal NUMERIC(18,2),
  new_interest_rate              NUMERIC(8,4),
  new_duration_months            INTEGER,
  new_repayment_freq             VARCHAR(20),
  restructured_by                UUID REFERENCES users(id),
  restructured_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(loan_id, sequence_no)
)
-- ;;
CREATE INDEX idx_loan_restructurings_loan ON loan_restructurings(loan_id, sequence_no)
