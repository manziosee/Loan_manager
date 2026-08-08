(ns loanmanager.api.schemas
  (:require [malli.core :as m]))

;; ── Primitives ────────────────────────────────────────────────────────────────
(def UUID-str [:re #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])
(def Money    [:and :double [:> 0]])
(def Rate     [:and :double [:>= 0] [:<= 1]])

;; ── Auth ──────────────────────────────────────────────────────────────────────
(def LoginRequest
  [:map
   [:email    [:string {:min 3 :max 255}]]
   [:password [:string {:min 6 :max 100}]]])

(def TokenResponse
  [:map
   [:access-token  :string]
   [:token-type    :string]
   [:expires-in    :int]])

;; ── Customer ──────────────────────────────────────────────────────────────────
(def CustomerCreate
  [:map
   [:type          [:enum "individual" "business" "joint"]]
   [:first-name    {:optional true} [:string {:min 1 :max 100}]]
   [:last-name     {:optional true} [:string {:min 1 :max 100}]]
   [:company-name  {:optional true} [:string {:min 1 :max 200}]]
   [:email         {:optional true} [:string {:min 3 :max 255}]]
   [:phone         {:optional true} [:string {:min 5 :max 50}]]
   [:monthly-income {:optional true} [:double {:min 0}]]
   [:employment-type {:optional true} :string]
   [:employment-years {:optional true} :int]])

(def CustomerResponse
  [:map
   [:id            :string]
   [:customer-no   :string]
   [:type          :string]
   [:kyc-status    :string]
   [:risk-score    {:optional true} :int]])

;; ── Loan product ──────────────────────────────────────────────────────────────
(def LoanProductCreate
  [:map
   [:code                 [:string {:min 2 :max 50}]]
   [:name                 [:string {:min 2 :max 200}]]
   [:interest-rate-min    [:double {:min 0}]]
   [:interest-rate-max    [:double {:min 0}]]
   [:interest-method      [:enum "reducing_balance" "flat" "compound"]]
   [:duration-min         [:int {:min 1}]]
   [:duration-max         [:int {:min 1}]]
   [:amount-min           [:double {:min 0}]]
   [:amount-max           [:double {:min 0}]]
   [:repayment-frequency  [:enum "daily" "weekly" "biweekly" "monthly" "quarterly" "bullet"]]
   [:processing-fee-pct   {:optional true} [:double {:min 0}]]
   [:late-fee-pct         {:optional true} [:double {:min 0}]]
   [:grace-period-days    {:optional true} :int]
   [:collateral-required  {:optional true} :boolean]])

;; ── Loan application ──────────────────────────────────────────────────────────
(def ApplicationCreate
  [:map
   [:customer-id       UUID-str]
   [:product-id        UUID-str]
   [:requested-amount  [:double {:min 1}]]
   [:requested-duration [:int {:min 1}]]
   [:purpose           {:optional true} :string]])

(def ApprovalAction
  [:map
   [:action   [:enum "approved" "rejected" "returned"]]
   [:comments {:optional true} :string]])

;; ── Payment ───────────────────────────────────────────────────────────────────
(def PaymentCreate
  [:map
   [:amount         [:double {:min 0.01}]]
   [:payment-method {:optional true} :string]
   [:reference      {:optional true} :string]])

;; ── Loan simulator ───────────────────────────────────────────────────────────
(def SimulateRequest
  [:map
   [:principal    [:double {:min 1}]]
   [:annual-rate  [:double {:min 0}]]
   [:months       [:int {:min 1}]]])
