DROP TABLE IF EXISTS token_blacklist
-- ;;
DROP TABLE IF EXISTS login_attempts
-- ;;
DROP TABLE IF EXISTS notifications
-- ;;
ALTER TABLE customers
  DROP COLUMN IF EXISTS deactivated_by,
  DROP COLUMN IF EXISTS deactivated_at,
  DROP COLUMN IF EXISTS active
-- ;;
DROP INDEX IF EXISTS idx_customers_active
-- ;;
DROP INDEX IF EXISTS idx_guarantors_loan
-- ;;
DROP INDEX IF EXISTS idx_collateral_loan
-- ;;
DROP INDEX IF EXISTS idx_users_tenant_email
-- ;;
DROP INDEX IF EXISTS idx_payments_loan_date
-- ;;
DROP INDEX IF EXISTS idx_applications_updated_at
-- ;;
DROP INDEX IF EXISTS idx_customers_updated_at
-- ;;
ALTER TABLE collateral DROP COLUMN IF EXISTS updated_at
-- ;;
DROP INDEX IF EXISTS idx_loans_updated_at
