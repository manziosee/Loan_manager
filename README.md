# LoanOS — Bank-grade Loan Management Platform

A configurable, event-driven lending platform built in Clojure.

## Architecture

```
Web/Mobile UI
      │
   REST API
      │
 Ring + Reitit
      │
 ┌────┴────────────────────┐
 │                         │
Loan Engine           Risk Engine
 │  Finance.clj        CreditScore.clj
 │  Workflow.clj        Fraud.clj
 │  Accounting.clj
 │
 └──── PostgreSQL (next.jdbc + HoneySQL)
       Event Bus (core.async)
       Audit Log (immutable)
       Double-entry Ledger
```

## Stack

| Layer       | Technology                        |
|-------------|-----------------------------------|
| Language    | Clojure 1.11                      |
| HTTP        | Ring + Reitit 0.7                 |
| API Docs    | reitit-swagger + Swagger UI       |
| Validation  | Malli                             |
| Database    | PostgreSQL 16                     |
| SQL         | next.jdbc + HoneySQL              |
| Pool        | HikariCP                          |
| Migrations  | Ragtime                           |
| Auth        | buddy-sign (JWT HS256)            |
| Passwords   | buddy-hashers (bcrypt)            |
| Events      | core.async                        |
| Config      | Aero                              |
| Container   | Docker + Docker Compose           |

## Quick Start

### Prerequisites
- Docker + Docker Compose
- OR: JDK 21 + Clojure CLI + PostgreSQL

### With Docker

```bash
docker-compose up
```

API: http://localhost:8080/api/v1  
Swagger UI: http://localhost:8080/swagger-ui

### Local Development

```bash
# Start PostgreSQL
docker-compose up db -d

# Run migrations + start REPL
clojure -M:dev

# In REPL:
(require '[loanmanager.core :as core])
(core/start!)
```

## API Endpoints

### Authentication
```
POST /api/v1/auth/login          Login → JWT token
```

### Customers
```
GET  /api/v1/customers           Search customers
POST /api/v1/customers           Create customer
GET  /api/v1/customers/:id       Get customer
PUT  /api/v1/customers/:id       Update customer
GET  /api/v1/customers/:id/credit-score   Run credit assessment
GET  /api/v1/customers/:id/loans          Customer's loans
```

### Loan Products
```
GET  /api/v1/loan-products       List products
POST /api/v1/loan-products       Create product (admin)
GET  /api/v1/loan-products/:id   Get product
```

### Applications & Loans
```
GET  /api/v1/loan-applications           List applications
POST /api/v1/loan-applications           Submit application
GET  /api/v1/loan-applications/:id       Get application
POST /api/v1/loan-applications/:id/approve   Approve/reject
POST /api/v1/loan-applications/:id/disburse  Disburse loan

GET  /api/v1/loans/:id           Get loan
GET  /api/v1/loans/:id/schedule  Repayment schedule
GET  /api/v1/loans/:id/payments  Payment history
POST /api/v1/loans/:id/payments  Record payment

POST /api/v1/loans/simulate      Simulate schedule (public)
```

### Audit
```
GET /api/v1/audit/entities/:type/:id     Entity history
GET /api/v1/audit/users/:id/activity     User activity
```

## Security

All protected endpoints require:
```
Authorization: Bearer <jwt-token>
```

### RBAC Roles

| Role            | Key Permissions                              |
|-----------------|----------------------------------------------|
| loan-officer    | Create customers, submit applications        |
| branch-manager  | Approve loans up to threshold                |
| credit-officer  | Full credit assessment + approval            |
| finance         | Disburse loans, record payments              |
| collections     | Manage collection cases                      |
| risk-officer    | Risk override, fraud review                  |
| auditor         | Read-only access to everything               |
| admin           | All permissions                              |

## Domain Events

The event bus publishes:
- `:loan/application-submitted`
- `:loan/approved`
- `:loan/rejected`
- `:loan/disbursed`
- `:loan/payment-received`
- `:loan/overdue`
- `:loan/restructured`
- `:loan/closed`

Subscribe in any namespace:
```clojure
(events/subscribe! :loan/payment-received
  (fn [event]
    ;; update ledger, send receipt, update credit score...
    ))
```

## Credit Scoring

Rules-based scoring with full explainability:

```
Score: 0–100
  0–30   High Risk
 31–50   Medium Risk
 51–70   Low Risk
 71–100  Very Low Risk

Factors: employment stability, repayment history,
         DTI ratio, existing debt, account activity,
         income level
```

## Double-Entry Accounting

Every financial event produces immutable journal entries:

```
Disbursement:
  DR Loans Receivable    $10,000
  CR Cash at Bank        $10,000

Payment:
  DR Cash at Bank           $900
  CR Loans Receivable       $800
  CR Interest Income        $100
```

## Project Structure

```
src/loanmanager/
├── core.clj                  Entry point
├── domain/
│   ├── finance.clj           Pure financial calculations
│   ├── credit_score.clj      Credit scoring engine
│   ├── fraud.clj             Fraud detection
│   ├── accounting.clj        Double-entry journal builders
│   └── workflow.clj          Approval workflow engine (not yet extracted)
├── workflow/
│   └── engine.clj            Configurable approval steps
├── db/
│   ├── connection.clj        HikariCP pool
│   ├── migrations.clj        Ragtime runner
│   ├── customers.clj         Customer queries
│   ├── loans.clj             Loan/application/payment queries
│   └── audit.clj             Audit log (append-only)
├── api/
│   ├── server.clj            Reitit router + Swagger
│   ├── schemas.clj           Malli validation schemas
│   └── routes/
│       ├── auth.clj
│       ├── customers.clj
│       ├── products.clj
│       ├── loans.clj
│       └── audit.clj
├── security/
│   ├── jwt.clj               Token generation/verification
│   ├── rbac.clj              Role permissions
│   └── middleware.clj        Auth/tenant/error middleware
└── events/
    └── bus.clj               core.async event bus
```
