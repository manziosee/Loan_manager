-- :up

-- ── Loans: write-off and restructure tracking columns ────────────────────────
ALTER TABLE loans
  ADD COLUMN IF NOT EXISTS write_off_reason   TEXT,
  ADD COLUMN IF NOT EXISTS written_off_at     TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS written_off_by     UUID REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS restructured_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS restructured_by    UUID REFERENCES users(id);

-- ── Payments: reversal tracking columns ──────────────────────────────────────
ALTER TABLE payments
  ADD COLUMN IF NOT EXISTS reversal_reason    TEXT,
  ADD COLUMN IF NOT EXISTS reversed_by        UUID REFERENCES users(id);

-- ── Users: updated_at column ─────────────────────────────────────────────────
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- ── Seed: default tenant ──────────────────────────────────────────────────────
INSERT INTO tenants (id, code, name, active)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEFAULT', 'Default Tenant', TRUE)
ON CONFLICT (code) DO NOTHING;

-- ── Seed: default roles ───────────────────────────────────────────────────────
INSERT INTO roles (id, tenant_id, name, permissions) VALUES
  ('00000000-0000-0000-0001-000000000001', '00000000-0000-0000-0000-000000000001', 'admin',
   '["all","user/read","user/manage"]'),
  ('00000000-0000-0000-0001-000000000002', '00000000-0000-0000-0000-000000000001', 'loan-officer',
   '["customer/read","customer/create","customer/update","application/read","application/create","loan/read","schedule/read"]'),
  ('00000000-0000-0000-0001-000000000003', '00000000-0000-0000-0000-000000000001', 'credit-officer',
   '["customer/read","customer/create","customer/update","application/read","application/create","application/approve","credit-score/read","credit-score/override","loan/read","loan/approve","loan/restructure","schedule/read","payment/read"]'),
  ('00000000-0000-0000-0001-000000000004', '00000000-0000-0000-0000-000000000001', 'finance',
   '["loan/read","loan/disburse","payment/read","payment/create","payment/reverse","loan/restructure","loan/write-off","ledger/read","schedule/read"]'),
  ('00000000-0000-0000-0001-000000000005', '00000000-0000-0000-0000-000000000001', 'auditor',
   '["customer/read","application/read","loan/read","payment/read","ledger/read","audit/read","schedule/read","collection/read","portfolio/read","user/read"]')
ON CONFLICT (tenant_id, name) DO NOTHING;

-- ── Seed: default admin user (password: Admin1234!) ───────────────────────────
-- bcrypt hash of "Admin1234!" with cost 12
INSERT INTO users (id, tenant_id, email, password_hash, full_name, role_id, active)
VALUES (
  '00000000-0000-0000-0002-000000000001',
  '00000000-0000-0000-0000-000000000001',
  'admin@loanmanager.local',
  '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4J/HS.iK8i',
  'System Administrator',
  '00000000-0000-0000-0001-000000000001',
  TRUE
)
ON CONFLICT (tenant_id, email) DO NOTHING;

-- :down
DELETE FROM users  WHERE id = '00000000-0000-0000-0002-000000000001';
DELETE FROM roles  WHERE tenant_id = '00000000-0000-0000-0000-000000000001';
DELETE FROM tenants WHERE id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE payments DROP COLUMN IF EXISTS reversal_reason;
ALTER TABLE payments DROP COLUMN IF EXISTS reversed_by;
ALTER TABLE users    DROP COLUMN IF EXISTS updated_at;
ALTER TABLE loans    DROP COLUMN IF EXISTS restructured_by;
ALTER TABLE loans    DROP COLUMN IF EXISTS restructured_at;
ALTER TABLE loans    DROP COLUMN IF EXISTS written_off_by;
ALTER TABLE loans    DROP COLUMN IF EXISTS written_off_at;
ALTER TABLE loans    DROP COLUMN IF EXISTS write_off_reason;
