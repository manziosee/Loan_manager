CREATE TYPE application_status AS ENUM (
  'draft','submitted','kyc_check','credit_assessment',
  'risk_scoring','pending_approval','approved','rejected','cancelled'
)
-- ;;
CREATE TYPE loan_status AS ENUM (
  'active','overdue','npl','restructured','closed','written_off'
)
-- ;;
CREATE TYPE delinquency_bucket AS ENUM (
  'current','1_30','31_60','61_90','90_plus','npl'
)
-- ;;
-- Loan applications
CREATE TABLE loan_applications (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  application_no  VARCHAR(50) NOT NULL,
  customer_id     UUID NOT NULL REFERENCES customers(id),
  product_id      UUID NOT NULL REFERENCES loan_products(id),
  branch_id       UUID REFERENCES branches(id),
  -- Requested terms
  requested_amount   NUMERIC(18,2) NOT NULL,
  requested_duration INTEGER NOT NULL,
  purpose            TEXT,
  -- Assessed
  approved_amount    NUMERIC(18,2),
  approved_rate      NUMERIC(8,4),
  approved_duration  INTEGER,
  -- Scoring
  credit_score       INTEGER,
  risk_category      VARCHAR(20),
  dti_ratio          NUMERIC(8,4),
  score_breakdown    JSONB,
  fraud_score        INTEGER,
  fraud_flags        JSONB,
  -- Status
  status             application_status NOT NULL DEFAULT 'draft',
  rejection_reason   TEXT,
  -- Workflow
  current_step       VARCHAR(100),
  workflow_state     JSONB NOT NULL DEFAULT '{}',
  -- Meta
  submitted_by       UUID REFERENCES users(id),
  submitted_at       TIMESTAMPTZ,
  decided_at         TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(tenant_id, application_no)
)
-- ;;
-- Approval workflow steps
CREATE TABLE approval_steps (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  application_id  UUID NOT NULL REFERENCES loan_applications(id) ON DELETE CASCADE,
  step_name       VARCHAR(100) NOT NULL,
  step_order      INTEGER NOT NULL,
  assigned_to     UUID REFERENCES users(id),
  action          VARCHAR(50),   -- approved | rejected | returned
  comments        TEXT,
  acted_at        TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
-- Active loans
CREATE TABLE loans (
  id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id         UUID NOT NULL REFERENCES tenants(id),
  loan_no           VARCHAR(50) NOT NULL,
  application_id    UUID REFERENCES loan_applications(id),
  customer_id       UUID NOT NULL REFERENCES customers(id),
  product_id        UUID NOT NULL REFERENCES loan_products(id),
  branch_id         UUID REFERENCES branches(id),
  -- Terms
  principal         NUMERIC(18,2) NOT NULL,
  interest_rate     NUMERIC(8,4) NOT NULL,
  interest_method   interest_method NOT NULL,
  duration_months   INTEGER NOT NULL,
  repayment_freq    repayment_freq NOT NULL,
  currency          VARCHAR(10) NOT NULL DEFAULT 'USD',
  -- Balances
  outstanding_principal NUMERIC(18,2) NOT NULL,
  accrued_interest      NUMERIC(18,2) NOT NULL DEFAULT 0,
  total_paid_principal  NUMERIC(18,2) NOT NULL DEFAULT 0,
  total_paid_interest   NUMERIC(18,2) NOT NULL DEFAULT 0,
  -- Dates
  disbursed_at      TIMESTAMPTZ,
  first_payment_date DATE,
  maturity_date     DATE,
  last_payment_date TIMESTAMPTZ,
  -- Delinquency
  days_overdue      INTEGER NOT NULL DEFAULT 0,
  delinquency_bucket delinquency_bucket NOT NULL DEFAULT 'current',
  -- Status
  status            loan_status NOT NULL DEFAULT 'active',
  -- Restructuring
  parent_loan_id    UUID REFERENCES loans(id),
  restructure_count INTEGER NOT NULL DEFAULT 0,
  -- Meta
  disbursed_by      UUID REFERENCES users(id),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(tenant_id, loan_no)
)
-- ;;
-- Amortization schedule
CREATE TABLE repayment_schedules (
  id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  loan_id           UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
  installment_no    INTEGER NOT NULL,
  due_date          DATE NOT NULL,
  principal_due     NUMERIC(18,2) NOT NULL,
  interest_due      NUMERIC(18,2) NOT NULL,
  total_due         NUMERIC(18,2) NOT NULL,
  principal_paid    NUMERIC(18,2) NOT NULL DEFAULT 0,
  interest_paid     NUMERIC(18,2) NOT NULL DEFAULT 0,
  status            VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending|partial|paid|overdue
  paid_at           TIMESTAMPTZ,
  UNIQUE(loan_id, installment_no)
)
-- ;;
-- Payments
CREATE TABLE payments (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  loan_id         UUID NOT NULL REFERENCES loans(id),
  payment_no      VARCHAR(50) NOT NULL,
  amount          NUMERIC(18,2) NOT NULL,
  principal_portion NUMERIC(18,2) NOT NULL DEFAULT 0,
  interest_portion  NUMERIC(18,2) NOT NULL DEFAULT 0,
  fee_portion       NUMERIC(18,2) NOT NULL DEFAULT 0,
  payment_method  VARCHAR(50),
  reference       VARCHAR(200),
  payment_date    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  recorded_by     UUID REFERENCES users(id),
  reversed        BOOLEAN NOT NULL DEFAULT FALSE,
  reversed_at     TIMESTAMPTZ,
  reversed_by     UUID REFERENCES users(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(tenant_id, payment_no)
)
