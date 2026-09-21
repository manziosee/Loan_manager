-- Immutable allocation ledger: every payment records exactly which
-- repayment-schedule rows it settled, enabling accurate reversals and audit.
CREATE TABLE payment_allocations (
  id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id           UUID NOT NULL REFERENCES tenants(id),
  payment_id          UUID NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
  schedule_id         UUID NOT NULL REFERENCES repayment_schedules(id) ON DELETE RESTRICT,
  principal_applied   NUMERIC(18,2) NOT NULL DEFAULT 0,
  interest_applied    NUMERIC(18,2) NOT NULL DEFAULT 0,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT payment_allocations_positive CHECK (principal_applied >= 0 AND interest_applied >= 0),
  UNIQUE(payment_id, schedule_id)
)
-- ;;
CREATE INDEX idx_payment_allocations_schedule ON payment_allocations(schedule_id)