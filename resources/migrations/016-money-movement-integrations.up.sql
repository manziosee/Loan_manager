-- Durable contracts for real-money integrations. These records are tenant
-- scoped and deliberately separate provider state from the authoritative loan
-- and double-entry ledger state.

CREATE TABLE idempotency_keys (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  idempotency_key VARCHAR(200) NOT NULL,
  request_hash    VARCHAR(128) NOT NULL,
  response_status INTEGER,
  response_body   JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at    TIMESTAMPTZ,
  UNIQUE(tenant_id, idempotency_key)
)
-- ;;

CREATE TABLE integration_outbox (
  id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id      UUID NOT NULL REFERENCES tenants(id),
  event_type     VARCHAR(200) NOT NULL,
  aggregate_type VARCHAR(100) NOT NULL,
  aggregate_id   UUID NOT NULL,
  payload        JSONB NOT NULL,
  status         VARCHAR(20) NOT NULL DEFAULT 'pending',
  attempts       INTEGER NOT NULL DEFAULT 0,
  available_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  locked_at      TIMESTAMPTZ,
  delivered_at   TIMESTAMPTZ,
  last_error     TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT integration_outbox_status CHECK (status IN ('pending', 'processing', 'delivered', 'failed'))
)
-- ;;

CREATE INDEX idx_integration_outbox_ready
  ON integration_outbox(status, available_at)
-- ;;

CREATE TABLE payment_provider_transactions (
  id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id          UUID NOT NULL REFERENCES tenants(id),
  provider           VARCHAR(50) NOT NULL,
  direction          VARCHAR(20) NOT NULL,
  loan_id            UUID REFERENCES loans(id),
  payment_id         UUID REFERENCES payments(id),
  external_id        VARCHAR(255),
  idempotency_key    VARCHAR(200) NOT NULL,
  amount             NUMERIC(18,2) NOT NULL,
  currency           VARCHAR(10) NOT NULL,
  status             VARCHAR(30) NOT NULL DEFAULT 'pending',
  provider_payload   JSONB NOT NULL DEFAULT '{}',
  last_error         TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(tenant_id, provider, idempotency_key),
  UNIQUE(tenant_id, provider, external_id)
)
-- ;;

CREATE TABLE payment_webhook_inbox (
  id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id        UUID REFERENCES tenants(id),
  provider         VARCHAR(50) NOT NULL,
  external_event_id VARCHAR(255) NOT NULL,
  signature_valid  BOOLEAN NOT NULL DEFAULT FALSE,
  payload          JSONB NOT NULL,
  status           VARCHAR(20) NOT NULL DEFAULT 'received',
  processed_at     TIMESTAMPTZ,
  last_error       TEXT,
  received_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(provider, external_event_id)
)
-- ;;

CREATE TABLE reconciliation_exceptions (
  id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id          UUID NOT NULL REFERENCES tenants(id),
  provider           VARCHAR(50) NOT NULL,
  provider_reference VARCHAR(255),
  internal_reference UUID,
  exception_type     VARCHAR(100) NOT NULL,
  status             VARCHAR(20) NOT NULL DEFAULT 'open',
  expected_amount    NUMERIC(18,2),
  actual_amount      NUMERIC(18,2),
  details            JSONB NOT NULL DEFAULT '{}',
  resolved_by        UUID REFERENCES users(id),
  resolved_at        TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;

CREATE INDEX idx_reconciliation_exceptions_open
  ON reconciliation_exceptions(tenant_id, status)