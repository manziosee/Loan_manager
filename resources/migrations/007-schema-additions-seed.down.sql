DELETE FROM users  WHERE id = '00000000-0000-0000-0002-000000000001'
-- ;;
DELETE FROM roles  WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
-- ;;
DELETE FROM tenants WHERE id = '00000000-0000-0000-0000-000000000001'
-- ;;
ALTER TABLE payments DROP COLUMN IF EXISTS reversal_reason
-- ;;
ALTER TABLE payments DROP COLUMN IF EXISTS reversed_by
-- ;;
ALTER TABLE users    DROP COLUMN IF EXISTS updated_at
-- ;;
ALTER TABLE loans    DROP COLUMN IF EXISTS restructured_by
-- ;;
ALTER TABLE loans    DROP COLUMN IF EXISTS restructured_at
-- ;;
ALTER TABLE loans    DROP COLUMN IF EXISTS written_off_by
-- ;;
ALTER TABLE loans    DROP COLUMN IF EXISTS written_off_at
-- ;;
ALTER TABLE loans    DROP COLUMN IF EXISTS write_off_reason
