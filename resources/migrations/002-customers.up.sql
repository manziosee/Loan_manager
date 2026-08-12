CREATE TABLE branches (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id   UUID NOT NULL REFERENCES tenants(id),
  parent_id   UUID REFERENCES branches(id),
  code        VARCHAR(50) NOT NULL,
  name        VARCHAR(200) NOT NULL,
  region      VARCHAR(100),
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(tenant_id, code)
)
-- ;;
ALTER TABLE users ADD CONSTRAINT fk_user_branch
  FOREIGN KEY (branch_id) REFERENCES branches(id)
-- ;;
CREATE TYPE customer_type AS ENUM ('individual','business','joint')
-- ;;
CREATE TYPE id_doc_type   AS ENUM ('national_id','passport','driving_license','company_reg')
-- ;;
CREATE TYPE kyc_status    AS ENUM ('pending','verified','rejected','expired')
-- ;;
CREATE TABLE customers (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  branch_id       UUID REFERENCES branches(id),
  customer_no     VARCHAR(50) NOT NULL,
  type            customer_type NOT NULL DEFAULT 'individual',
  -- Individual fields
  first_name      VARCHAR(100),
  last_name       VARCHAR(100),
  date_of_birth   DATE,
  gender          VARCHAR(20),
  nationality     VARCHAR(100),
  -- Business fields
  company_name    VARCHAR(200),
  registration_no VARCHAR(100),
  tax_id          VARCHAR(100),
  -- Contact
  email           VARCHAR(255),
  phone           VARCHAR(50),
  address         JSONB,
  -- Financial profile
  monthly_income  NUMERIC(18,2),
  employment_type VARCHAR(50),
  employer_name   VARCHAR(200),
  employment_years INTEGER,
  -- Risk
  risk_score      INTEGER,
  risk_category   VARCHAR(20),
  kyc_status      kyc_status NOT NULL DEFAULT 'pending',
  -- Meta
  metadata        JSONB NOT NULL DEFAULT '{}',
  created_by      UUID REFERENCES users(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(tenant_id, customer_no)
)
-- ;;
CREATE TABLE customer_documents (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  customer_id   UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  doc_type      id_doc_type NOT NULL,
  doc_number    VARCHAR(100) NOT NULL,
  issued_by     VARCHAR(100),
  issued_date   DATE,
  expiry_date   DATE,
  file_url      VARCHAR(500),
  verified      BOOLEAN NOT NULL DEFAULT FALSE,
  verified_by   UUID REFERENCES users(id),
  verified_at   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- ;;
CREATE TABLE customer_liabilities (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  customer_id     UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  institution     VARCHAR(200) NOT NULL,
  liability_type  VARCHAR(100) NOT NULL,
  outstanding     NUMERIC(18,2) NOT NULL,
  monthly_payment NUMERIC(18,2) NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
