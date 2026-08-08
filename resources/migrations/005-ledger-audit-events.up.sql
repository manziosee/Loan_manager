-- :up

-- ── Double-entry accounting ledger ──────────────────────────────────────────
CREATE TABLE chart_of_accounts (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id   UUID NOT NULL REFERENCES tenants(id),
  code        VARCHAR(50) NOT NULL,
  name        VARCHAR(200) NOT NULL,
  account_type VARCHAR(50) NOT NULL,  -- asset|liability|income|expense|equity
  parent_id   UUID REFERENCES chart_of_accounts(id),
  UNIQUE(tenant_id, code)
);

CREATE TABLE journal_entries (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  entry_no        VARCHAR(50) NOT NULL,
  description     TEXT NOT NULL,
  reference_type  VARCHAR(100),   -- loan|payment|disbursement|fee
  reference_id    UUID,
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  posted_by       UUID REFERENCES users(id),
  reversed        BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE(tenant_id, entry_no)
);

CREATE TABLE journal_lines (
  id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  journal_entry_id UUID NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
  account_id       UUID NOT NULL REFERENCES chart_of_accounts(id),
  debit            NUMERIC(18,2) NOT NULL DEFAULT 0,
  credit           NUMERIC(18,2) NOT NULL DEFAULT 0,
  currency         VARCHAR(10) NOT NULL DEFAULT 'USD',
  CONSTRAINT debit_or_credit CHECK (
    (debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)
  )
);

-- ── Collateral ───────────────────────────────────────────────────────────────
CREATE TABLE collateral (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  loan_id         UUID REFERENCES loans(id),
  customer_id     UUID NOT NULL REFERENCES customers(id),
  asset_type      VARCHAR(100) NOT NULL,  -- property|vehicle|equipment|cash
  description     TEXT,
  valuation       NUMERIC(18,2) NOT NULL,
  valuation_date  DATE NOT NULL,
  valuator        VARCHAR(200),
  location        TEXT,
  insurance_policy VARCHAR(200),
  insurance_expiry DATE,
  lien_registered BOOLEAN NOT NULL DEFAULT FALSE,
  documents       JSONB NOT NULL DEFAULT '[]',
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Guarantors ───────────────────────────────────────────────────────────────
CREATE TABLE guarantors (
  id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  loan_id           UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
  customer_id       UUID NOT NULL REFERENCES customers(id),
  guarantee_amount  NUMERIC(18,2) NOT NULL,
  guarantee_pct     NUMERIC(8,4),
  expiry_date       DATE,
  status            VARCHAR(50) NOT NULL DEFAULT 'active',
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Immutable audit trail ────────────────────────────────────────────────────
CREATE TABLE audit_log (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id     UUID NOT NULL REFERENCES tenants(id),
  user_id       UUID REFERENCES users(id),
  action        VARCHAR(200) NOT NULL,
  entity_type   VARCHAR(100) NOT NULL,
  entity_id     UUID,
  before_state  JSONB,
  after_state   JSONB,
  reason        TEXT,
  ip_address    INET,
  user_agent    TEXT,
  occurred_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Audit log is append-only — revoke DELETE/UPDATE from app role
-- REVOKE UPDATE, DELETE ON audit_log FROM loanmanager_app;

-- ── Domain event store ───────────────────────────────────────────────────────
CREATE TABLE domain_events (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id     UUID NOT NULL REFERENCES tenants(id),
  event_type    VARCHAR(200) NOT NULL,
  aggregate_type VARCHAR(100) NOT NULL,
  aggregate_id  UUID NOT NULL,
  payload       JSONB NOT NULL,
  metadata      JSONB NOT NULL DEFAULT '{}',
  occurred_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_domain_events_aggregate ON domain_events(aggregate_type, aggregate_id);
CREATE INDEX idx_domain_events_type      ON domain_events(event_type);
CREATE INDEX idx_audit_log_entity        ON audit_log(entity_type, entity_id);
CREATE INDEX idx_loans_customer          ON loans(customer_id);
CREATE INDEX idx_loans_status            ON loans(status);
CREATE INDEX idx_payments_loan           ON payments(loan_id);
CREATE INDEX idx_schedule_loan           ON repayment_schedules(loan_id, due_date);

-- ── Collections ──────────────────────────────────────────────────────────────
CREATE TABLE collection_cases (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  loan_id         UUID NOT NULL REFERENCES loans(id),
  assigned_to     UUID REFERENCES users(id),
  status          VARCHAR(50) NOT NULL DEFAULT 'open',
  priority        VARCHAR(20) NOT NULL DEFAULT 'medium',
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  closed_at       TIMESTAMPTZ
);

CREATE TABLE collection_activities (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  case_id         UUID NOT NULL REFERENCES collection_cases(id) ON DELETE CASCADE,
  activity_type   VARCHAR(100) NOT NULL,  -- call|sms|visit|promise_to_pay
  notes           TEXT,
  promise_date    DATE,
  promise_amount  NUMERIC(18,2),
  outcome         VARCHAR(100),
  recorded_by     UUID REFERENCES users(id),
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- :down
DROP TABLE IF EXISTS collection_activities;
DROP TABLE IF EXISTS collection_cases;
DROP INDEX IF EXISTS idx_schedule_loan;
DROP INDEX IF EXISTS idx_payments_loan;
DROP INDEX IF EXISTS idx_loans_status;
DROP INDEX IF EXISTS idx_loans_customer;
DROP INDEX IF EXISTS idx_audit_log_entity;
DROP INDEX IF EXISTS idx_domain_events_type;
DROP INDEX IF EXISTS idx_domain_events_aggregate;
DROP TABLE IF EXISTS domain_events;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS guarantors;
DROP TABLE IF EXISTS collateral;
DROP TABLE IF EXISTS journal_lines;
DROP TABLE IF EXISTS journal_entries;
DROP TABLE IF EXISTS chart_of_accounts;
