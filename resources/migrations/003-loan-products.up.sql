-- :up
CREATE TYPE interest_method AS ENUM ('reducing_balance','flat','compound');
CREATE TYPE repayment_freq  AS ENUM ('daily','weekly','biweekly','monthly','quarterly','bullet');

CREATE TABLE loan_products (
  id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id             UUID NOT NULL REFERENCES tenants(id),
  code                  VARCHAR(50) NOT NULL,
  name                  VARCHAR(200) NOT NULL,
  description           TEXT,
  -- Interest
  interest_rate_min     NUMERIC(8,4) NOT NULL,
  interest_rate_max     NUMERIC(8,4) NOT NULL,
  interest_method       interest_method NOT NULL DEFAULT 'reducing_balance',
  -- Duration (months)
  duration_min          INTEGER NOT NULL,
  duration_max          INTEGER NOT NULL,
  -- Amount
  amount_min            NUMERIC(18,2) NOT NULL,
  amount_max            NUMERIC(18,2) NOT NULL,
  currency              VARCHAR(10) NOT NULL DEFAULT 'USD',
  -- Fees
  processing_fee_pct    NUMERIC(8,4) NOT NULL DEFAULT 0,
  late_fee_pct          NUMERIC(8,4) NOT NULL DEFAULT 0,
  early_repayment_fee_pct NUMERIC(8,4) NOT NULL DEFAULT 0,
  -- Grace period (days)
  grace_period_days     INTEGER NOT NULL DEFAULT 0,
  -- Repayment
  repayment_frequency   repayment_freq NOT NULL DEFAULT 'monthly',
  -- Approval rules (JSON rules engine config)
  approval_rules        JSONB NOT NULL DEFAULT '{}',
  -- Collateral required?
  collateral_required   BOOLEAN NOT NULL DEFAULT FALSE,
  -- Active
  active                BOOLEAN NOT NULL DEFAULT TRUE,
  created_by            UUID REFERENCES users(id),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(tenant_id, code)
);

-- :down
DROP TABLE IF EXISTS loan_products;
DROP TYPE  IF EXISTS repayment_freq;
DROP TYPE  IF EXISTS interest_method;
