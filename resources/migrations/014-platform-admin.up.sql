-- ── Platform-admin role + seed user ─────────────────────────────────────────
-- Closes: any tenant's :admin (whose :all wildcard previously satisfied
-- every permission check) could list/provision other tenants via /tenants,
-- gated only on :user/manage. :platform/manage is a new permission that
-- :all deliberately does NOT satisfy (see security/rbac.clj) — only this
-- role has it. Seeded into the reserved default tenant only; NEVER add
-- "platform-admin" to db/tenants.clj's default-roles for new tenants.
INSERT INTO roles (id, tenant_id, name, permissions) VALUES
  ('00000000-0000-0000-0001-000000000009', '00000000-0000-0000-0000-000000000001', 'platform-admin',
   '["platform/manage"]')
ON CONFLICT (tenant_id, name) DO NOTHING
-- ;;
-- ── Seed: default platform-admin user (password: PlatformAdmin1234!) ─────────
-- Rotate this password immediately in any real deployment — same caveat as
-- the default admin user seeded in 007-schema-additions-seed.
INSERT INTO users (id, tenant_id, email, password_hash, full_name, role_id, active)
VALUES (
  '00000000-0000-0000-0002-000000000002',
  '00000000-0000-0000-0000-000000000001',
  'platform-admin@loanmanager.local',
  'bcrypt+sha512$13453300e89dfe19cf21a4972a79a227$12$b8c26b7a08e6113191d95fee8cc35a7df71dd0b3f294607c',
  'Platform Administrator',
  '00000000-0000-0000-0001-000000000009',
  TRUE
)
ON CONFLICT (tenant_id, email) DO NOTHING
