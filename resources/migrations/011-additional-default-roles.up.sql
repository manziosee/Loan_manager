-- ── Seed: roles missing from the original 007 seed ────────────────────────────
-- branch-manager, risk-officer, and collections exist in the RBAC permission
-- map and in the new-tenant provisioning defaults (loanmanager.db.tenants),
-- but were never added to the legacy single-tenant migration seed — without
-- them the maker-checker workflow's branch_manager/risk_officer steps can
-- never be assigned to a real user on the default tenant.
INSERT INTO roles (id, tenant_id, name, permissions) VALUES
  ('00000000-0000-0000-0001-000000000006', '00000000-0000-0000-0000-000000000001', 'branch-manager',
   '["customer/read","customer/create","customer/update","application/read","application/create","application/approve","loan/read","loan/approve-small","schedule/read","payment/read"]'),
  ('00000000-0000-0000-0001-000000000007', '00000000-0000-0000-0000-000000000001', 'risk-officer',
   '["customer/read","application/read","application/approve","loan/read","loan/approve","loan/restructure","loan/write-off","credit-score/read","credit-score/override","fraud/read","fraud/override","portfolio/read"]'),
  ('00000000-0000-0000-0001-000000000008', '00000000-0000-0000-0000-000000000001', 'collections',
   '["loan/read","payment/read","payment/create","collection/read","collection/create","collection/update","customer/read"]')
ON CONFLICT (tenant_id, name) DO NOTHING
