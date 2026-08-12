-- ── Seed: default chart of accounts ─────────────────────────────────────────
-- Codes must match loanmanager.domain.accounting (LOANS-RECEIVABLE, CASH-AT-BANK,
-- INTEREST-INCOME, PROCESSING-FEES) so journal entries can resolve account_id.
INSERT INTO chart_of_accounts (tenant_id, code, name, account_type) VALUES
  ('00000000-0000-0000-0000-000000000001', '1010', 'Cash at Bank',       'asset'),
  ('00000000-0000-0000-0000-000000000001', '1100', 'Loans Receivable',   'asset'),
  ('00000000-0000-0000-0000-000000000001', '4100', 'Interest Income',    'income'),
  ('00000000-0000-0000-0000-000000000001', '4200', 'Processing Fees',    'income')
ON CONFLICT (tenant_id, code) DO NOTHING
