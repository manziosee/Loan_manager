-- ── Loans: write-off and restructure tracking columns ────────────────────────
ALTER TABLE loans
  ADD COLUMN IF NOT EXISTS write_off_reason   TEXT,
  ADD COLUMN IF NOT EXISTS written_off_at     TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS written_off_by     UUID REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS restructured_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS restructured_by    UUID REFERENCES users(id)
-- ;;
-- ── Payments: reversal tracking columns ──────────────────────────────────────
ALTER TABLE payments
  ADD COLUMN IF NOT EXISTS reversal_reason    TEXT,
  ADD COLUMN IF NOT EXISTS reversed_by        UUID REFERENCES users(id)
-- ;;
-- ── Users: updated_at column ─────────────────────────────────────────────────
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
-- ;;
-- ── Seed: default tenant ──────────────────────────────────────────────────────
INSERT INTO tenants (id, code, name, active)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEFAULT', 'Default Tenant', TRUE)
ON CONFLICT (code) DO NOTHING
-- ;;
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
ON CONFLICT (tenant_id, name) DO NOTHING
-- ;;
-- ── Seed: default admin user (password: Admin1234!) ───────────────────────────
-- Hash produced by buddy.hashers/derive (bcrypt+sha512, its own encoding —
-- NOT a raw bcrypt hash, which buddy.hashers/check will always reject).
INSERT INTO users (id, tenant_id, email, password_hash, full_name, role_id, active)
VALUES (
  '00000000-0000-0000-0002-000000000001',
  '00000000-0000-0000-0000-000000000001',
  'admin@loanmanager.local',
  'bcrypt+sha512$1c2a0dfd2b3a890d296f4501d339b3f3$12$0365794abdacbf160099bd9c981e15f718079cd4d942d0bd',
  'System Administrator',
  '00000000-0000-0000-0001-000000000001',
  TRUE
)
ON CONFLICT (tenant_id, email) DO NOTHING
