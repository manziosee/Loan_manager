# LoanOS — Bank-grade Loan Management Platform

[![CI/CD](https://github.com/loanmanager/loanmanager/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/loanmanager/loanmanager/actions/workflows/ci-cd.yml)
[![License: Proprietary](https://img.shields.io/badge/license-Proprietary-red.svg)]()

A configurable, event-driven lending platform built in Clojure. Designed for banks, microfinance institutions, SACCOs, and digital lenders.

---

## Architecture

```
                    ┌─────────────────────┐
                    │   Web / Mobile UI   │
                    └──────────┬──────────┘
                               │  REST API
                    ┌──────────▼──────────┐
                    │   Ring + Reitit     │
                    │   Swagger UI        │
                    └──────────┬──────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
 ┌──────▼──────┐       ┌───────▼───────┐     ┌───────▼──────┐
 │ Loan Engine │       │  Risk Engine  │     │  Workflow    │
 │ finance.clj │       │ credit_score  │     │  engine.clj  │
 │ accounting  │       │ fraud.clj     │     │              │
 └──────┬──────┘       └───────┬───────┘     └───────┬──────┘
        │                      │                      │
        └──────────────────────┼──────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │    PostgreSQL 16     │
                    │  Event Bus (async)  │
                    │  Audit Log (append) │
                    │  Double-entry Ledger│
                    └─────────────────────┘
```

## Stack

| Layer        | Technology                          |
|--------------|-------------------------------------|
| Language     | Clojure 1.11                        |
| HTTP         | Ring + Reitit 0.7                   |
| API Docs     | reitit-swagger + Swagger UI         |
| Validation   | Malli                               |
| Database     | PostgreSQL 16                       |
| SQL          | next.jdbc + HoneySQL                |
| Pool         | HikariCP                            |
| Migrations   | Ragtime 0.9                         |
| Auth         | buddy-sign (JWT HS256)              |
| Passwords    | buddy-hashers (bcrypt cost 12)      |
| Events       | core.async                          |
| Config       | Aero                                |
| Container    | Docker + Docker Compose             |
| CI/CD        | GitHub Actions                      |
| Tests        | clojure.test + Kaocha               |

---

## Quick Start

### Prerequisites

- Docker + Docker Compose, **or**
- JDK 21 + Clojure CLI + PostgreSQL 16

### With Docker (recommended)

```bash
# Production stack (app + db)
docker-compose up -d

# Development stack (app + db + pgAdmin)
docker-compose --profile dev up -d
```

| Service   | URL                                    |
|-----------|----------------------------------------|
| API       | http://localhost:8080/api/v1           |
| Swagger   | http://localhost:8080/swagger-ui       |
| pgAdmin   | http://localhost:5050 (dev profile)    |

### Local Development

```bash
# Start only the database
make db-up

# Start REPL (auto-connects to DB)
make dev

# In REPL:
(start!)          ; start the server
(restart!)        ; hot-reload
(migrate!)        ; run pending migrations
```

### Default credentials

| Field    | Value                        |
|----------|------------------------------|
| Email    | `admin@loanmanager.local`    |
| Password | `Admin1234!`                 |

> **Change the default password immediately in any non-local environment.**

---

## Developer Commands

```bash
make help          # list all commands
make test          # run unit tests
make lint          # clj-kondo lint
make check         # lint + test
make build         # build uberjar → target/loanmanager.jar
make docker-build  # build Docker image
make migrate       # run pending DB migrations
make psql          # open psql shell
make outdated      # check for outdated deps
```

---

## API Reference

All protected endpoints require:
```
Authorization: Bearer <jwt-token>
```

Obtain a token via `POST /api/v1/auth/login`.

### System
```
GET  /api/v1/health          Liveness + DB check
GET  /api/v1/health/live     Liveness probe (no DB)
GET  /api/v1/health/ready    Readiness probe
```

### Authentication
```
POST /api/v1/auth/login              Login → JWT + refresh token
POST /api/v1/auth/logout             Invalidate token (blacklist JTI)
POST /api/v1/auth/refresh            Exchange refresh token
POST /api/v1/auth/change-password    Change own password
```

### Users
```
GET  /api/v1/users                   List users
POST /api/v1/users                   Create user (admin)
GET  /api/v1/users/:id               Get user
PUT  /api/v1/users/:id               Update role / active status
POST /api/v1/users/:id/reset-password  Admin password reset
```

### Branches
```
GET  /api/v1/branches                List branches
POST /api/v1/branches                Create branch
GET  /api/v1/branches/:id            Get branch
PUT  /api/v1/branches/:id            Update branch
```

### Customers
```
GET  /api/v1/customers               Search customers
POST /api/v1/customers               Create customer
GET  /api/v1/customers/:id           Get customer
PUT  /api/v1/customers/:id           Update customer
GET  /api/v1/customers/:id/loans     Customer's loans
GET  /api/v1/customers/:id/credit-score   Credit assessment
GET  /api/v1/customers/:id/dti            DTI for proposed payment
GET  /api/v1/customers/:id/fraud-check    Automated fraud check
```

### Loan Products
```
GET    /api/v1/loan-products         List active products
POST   /api/v1/loan-products         Create product (admin)
GET    /api/v1/loan-products/:id     Get product
PUT    /api/v1/loan-products/:id     Update product (admin)
DELETE /api/v1/loan-products/:id     Deactivate product (admin)
```

### Applications & Workflow
```
GET  /api/v1/loan-applications               List applications
POST /api/v1/loan-applications               Submit application
GET  /api/v1/loan-applications/:id           Get application
PUT  /api/v1/loan-applications/:id           Update draft
POST /api/v1/loan-applications/:id/withdraw  Withdraw application
POST /api/v1/loan-applications/:id/approve   Approve / reject / return
POST /api/v1/loan-applications/:id/disburse  Disburse approved loan
```

### Loans
```
GET  /api/v1/loans                   List loans
GET  /api/v1/loans/:id               Get loan
GET  /api/v1/loans/:id/schedule      Repayment schedule
GET  /api/v1/loans/:id/payments      Payment history
POST /api/v1/loans/:id/payments      Record payment
POST /api/v1/loans/:id/restructure   Restructure loan
POST /api/v1/loans/:id/write-off     Write off NPL loan
GET  /api/v1/loans/:id/collateral    List collateral
POST /api/v1/loans/:id/collateral    Attach collateral
GET  /api/v1/loans/:id/guarantors    List guarantors
POST /api/v1/loans/:id/guarantors    Add guarantor
GET  /api/v1/loans/:id/delinquency   Delinquency assessment
GET  /api/v1/loans/:id/delinquency/history  Delinquency history
```

### Repayment Engine
```
POST /api/v1/loans/simulate              Simulate schedule (public, no auth)
GET  /api/v1/loans/:id/settlement-quote  Full early settlement quote
POST /api/v1/loans/:id/prepayment        Partial principal prepayment
POST /api/v1/loans/:id/settle            Full early settlement
POST /api/v1/payments/:id/reverse        Reverse a payment
```

### Credit Scoring
```
POST /api/v1/credit/score            Score with full explainability
POST /api/v1/credit/dti              DTI analysis with policy verdict
GET  /api/v1/customers/:id/credit-score  Score existing customer
GET  /api/v1/customers/:id/dti           DTI for existing customer
```

### Fraud Detection
```
POST /api/v1/fraud/check                 Manual fraud signal check
GET  /api/v1/customers/:id/fraud-check   Automated fraud check
```

### Delinquency & Collections
```
POST /api/v1/delinquency/run             Classify all active loans
GET  /api/v1/delinquency/portfolio       Portfolio NPL summary

GET  /api/v1/collections                 List collection cases
GET  /api/v1/collections/:id             Case detail + next action
POST /api/v1/collections/:id/activities  Record activity (call/SMS/visit)
GET  /api/v1/collections/:id/promises    List promises-to-pay
POST /api/v1/collections/:id/promises    Record promise-to-pay
PUT  /api/v1/collections/promises/:id    Update promise status
GET  /api/v1/collections/broken-promises Broken promises needing follow-up
POST /api/v1/collections/:id/escalate    Escalate case
POST /api/v1/collections/:id/close       Close resolved case
```

### Ledger (Double-Entry Accounting)
```
GET  /api/v1/ledger/journal              List journal entries
GET  /api/v1/ledger/journal/:id          Entry with all lines
GET  /api/v1/ledger/accounts             Chart of accounts
GET  /api/v1/ledger/accounts/:code/balance  Account balance
POST /api/v1/ledger/preview/disbursement Preview disbursement entry
POST /api/v1/ledger/preview/payment      Preview payment entry
```

### Reports
```
GET /api/v1/reports/portfolio     PAR buckets, NPL ratio, totals
GET /api/v1/reports/disbursements Disbursements by month
GET /api/v1/reports/income        Interest income by month
GET /api/v1/reports/collections   Collections performance by month
```

### Notifications
```
GET  /api/v1/notifications              List notifications
GET  /api/v1/notifications/unread-count Unread count
POST /api/v1/notifications/:id/read     Mark as read
```

### Audit
```
GET /api/v1/audit/entities/:type/:id   Entity change history
GET /api/v1/audit/users/:id/activity   User activity log
```

---

## Security

### RBAC Roles

| Role            | Key Permissions                                          |
|-----------------|----------------------------------------------------------|
| `loan-officer`  | Create customers, submit applications, read loans        |
| `branch-manager`| Approve loans up to threshold                            |
| `credit-officer`| Full credit assessment, approve loans, restructure       |
| `finance`       | Disburse loans, record/reverse payments, write-offs      |
| `collections`   | Manage collection cases, record activities               |
| `risk-officer`  | Risk override, fraud review, portfolio read              |
| `auditor`       | Read-only access to everything                           |
| `admin`         | All permissions                                          |

### JWT Flow

```
POST /auth/login  →  { access_token, refresh_token }
                          │
                    Bearer <access_token>  (24h)
                          │
POST /auth/refresh  →  new access_token   (using refresh_token, 30d)
POST /auth/logout   →  JTI blacklisted
```

---

## Domain Events

The event bus publishes:

| Event                          | Triggered by              |
|--------------------------------|---------------------------|
| `:loan/application-submitted`  | Application creation      |
| `:loan/approved`               | Approval step completed   |
| `:loan/rejected`               | Application rejected      |
| `:loan/disbursed`              | Loan disbursed            |
| `:loan/payment-received`       | Payment recorded          |
| `:loan/overdue`                | Delinquency job (NPL)     |
| `:loan/restructured`           | Loan restructured         |
| `:loan/closed`                 | Loan settled / written off|

Subscribe in any namespace:

```clojure
(events/subscribe! :loan/payment-received
  (fn [{:keys [loan-id amount tenant-id]}]
    ;; update ledger, send receipt, update credit score...
    ))
```

---

## Credit Scoring

Rules-based scoring with full explainability. Score: **0–100**.

| Range   | Category       | Recommendation                              |
|---------|----------------|---------------------------------------------|
| 0–30    | High Risk      | Decline or require significant collateral   |
| 31–50   | Medium Risk    | Proceed with caution, extra documentation   |
| 51–70   | Low Risk       | Approve with standard conditions            |
| 71–100  | Very Low Risk  | Approve — preferred customer                |

**Scoring factors:**

| Factor                  | Max Impact |
|-------------------------|-----------|
| Repayment history       | ±25       |
| Employment stability    | +23       |
| Income level            | +15       |
| DTI ratio               | ±25       |
| Existing debt           | −15       |
| Account activity        | +12       |
| Income-to-loan ratio    | +10       |

Every score includes a full explanation:

```json
{
  "score": 72,
  "category": "very-low-risk",
  "warnings": ["DTI ratio 38.0% exceeds recommended threshold"],
  "strengths": ["Clean repayment history", "4 years stable employment"],
  "recommendation": "Approve — preferred customer"
}
```

---

## Double-Entry Accounting

Every financial event produces immutable journal entries:

```
Disbursement:
  DR  1100 Loans Receivable    $10,000
  CR  1010 Cash at Bank        $10,000

Payment ($900 = $800 principal + $100 interest):
  DR  1010 Cash at Bank           $900
  CR  1100 Loans Receivable       $800
  CR  4100 Interest Income        $100

Processing Fee:
  DR  1010 Cash at Bank           $200
  CR  4200 Processing Fees        $200
```

---

## Delinquency Classification

| Bucket      | Days Overdue | Provision Rate | Auto Actions                        |
|-------------|-------------|----------------|-------------------------------------|
| Current     | 0           | 1%             | —                                   |
| 1–30        | 1–30        | 5%             | SMS + email reminder                |
| 31–60       | 31–60       | 25%            | Assign collection officer           |
| 61–90       | 61–90       | 50%            | Escalate to supervisor              |
| 90+         | 91–180      | 75%            | Legal notice warning                |
| NPL         | 181+        | 100%           | Classify NPL, assign legal team     |

---

## Project Structure

```
src/loanmanager/
├── core.clj                    Entry point + lifecycle
├── domain/
│   ├── finance.clj             Pure financial calculations (schedules, DTI, LTV)
│   ├── credit_score.clj        Rules-based credit scoring engine
│   ├── fraud.clj               Fraud detection engine
│   ├── accounting.clj          Double-entry journal builders
│   ├── delinquency.clj         Delinquency classification + provisioning
│   └── collections.clj         Collections case logic + promise-to-pay
├── workflow/
│   └── engine.clj              Configurable maker-checker approval steps
├── db/
│   ├── connection.clj          HikariCP pool
│   ├── migrations.clj          Ragtime runner
│   ├── customers.clj           Customer + document + liability queries
│   ├── loans.clj               Loan / application / payment / schedule queries
│   ├── collections.clj         Collection case + activity + promise queries
│   ├── collateral.clj          Collateral + guarantor queries
│   ├── branches.clj            Branch hierarchy queries
│   ├── users.clj               User + role queries
│   ├── notifications.clj       Notification queries
│   └── audit.clj               Append-only audit log
├── api/
│   ├── server.clj              Reitit router + Swagger
│   ├── schemas.clj             Malli validation schemas
│   └── routes/
│       ├── health.clj          Health probes
│       ├── auth.clj            Login / logout / refresh / password
│       ├── users.clj           User management
│       ├── branches.clj        Branch management
│       ├── customers.clj       Customer CRUD + credit score
│       ├── products.clj        Loan product catalogue
│       ├── loans.clj           Applications + loans + payments
│       ├── repayment.clj       Simulate / prepay / settle
│       ├── credit.clj          Credit scoring + DTI endpoints
│       ├── fraud.clj           Fraud detection endpoints
│       ├── delinquency.clj     Delinquency classification + portfolio
│       ├── collections.clj     Collections case management
│       ├── collateral.clj      Collateral + guarantors
│       ├── ledger.clj          Journal entries + chart of accounts
│       ├── notifications.clj   In-app notifications
│       ├── reports.clj         Portfolio + income + disbursement reports
│       └── audit.clj           Audit trail
├── security/
│   ├── jwt.clj                 Token generation / verification
│   ├── rbac.clj                Role-based access control
│   ├── middleware.clj          Auth / tenant / error / rate-limit middleware
│   └── token_store.clj         JWT blacklist (in-memory)
└── events/
    └── bus.clj                 core.async event bus

resources/
├── config.edn                  Aero configuration
├── logback.xml                 Structured JSON logging
└── migrations/
    ├── 001-auth.up.sql         Tenants, roles, users
    ├── 002-customers.up.sql    Customers, documents, liabilities, branches
    ├── 003-loan-products.up.sql Loan product catalogue
    ├── 004-loans.up.sql        Applications, loans, schedules, payments
    ├── 005-ledger-audit-events.up.sql  Ledger, collateral, guarantors, audit, events
    ├── 006-delinquency-collections.up.sql  Delinquency log, collection cases, promises
    ├── 007-schema-additions-seed.up.sql    Columns, seed data, default admin
    └── 008-gaps.up.sql         Indexes, notifications, token blacklist

test/loanmanager/
├── domain/
│   ├── finance_test.clj        Schedule, DTI, LTV, early repayment tests
│   ├── credit_score_test.clj   Scoring, explainability, delta tests
│   ├── fraud_test.clj          Fraud flag and score tests
│   ├── delinquency_test.clj    Bucket, provision, action, portfolio tests
│   └── accounting_test.clj     Journal entry balance tests
├── workflow/
│   └── engine_test.clj         Workflow step and completion tests
└── security/
    └── rbac_test.clj           Permission and role tests
```

---

## Configuration

All configuration is in `resources/config.edn` and overridable via environment variables:

| Env Var        | Default                                    | Description              |
|----------------|--------------------------------------------|--------------------------|
| `PORT`         | `8080`                                     | HTTP port                |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/loanmanager` | JDBC URL             |
| `DB_USER`      | `postgres`                                 | DB username              |
| `DB_PASS`      | `postgres`                                 | DB password              |
| `JWT_SECRET`   | *(must set in prod)*                       | HS256 signing secret     |
| `LOG_FORMAT`   | `json`                                     | `json` or plain text     |

> **Production**: `JWT_SECRET` must be at least 32 characters and kept in a secrets manager.

---

## Running Tests

```bash
# All tests
make test

# Single namespace
clojure -M:test --focus loanmanager.domain.finance-test

# Watch mode
make test-watch
```

---

## CI/CD Pipeline

```
push/PR → lint → test → build uberjar
                              │
                         (main/develop only)
                              │
                         docker build + push → GHCR
                              │
                    ┌─────────┴──────────┐
               develop                 main
                  │                     │
           deploy staging         deploy production
                  │                     │
           smoke test             smoke test
```

Security scanning: Trivy (CRITICAL/HIGH CVEs) → GitHub Security tab via SARIF upload.
