(ns loanmanager.integrations.provider
  "Provider boundary for payment rails and accounting exports.
   Implementations must be idempotent and return normalized results; network
   details stay outside loan and ledger domain code.")

(defprotocol PaymentProvider
  (create-disbursement! [provider request])
  (lookup-transaction [provider request])
  (verify-webhook [provider request])
  (parse-webhook [provider request]))

(defprotocol AccountingExporter
  (export-journal-entry! [provider entry]))

(defrecord UnsupportedProvider [name]
  PaymentProvider
  (create-disbursement! [_ _]
    (throw (ex-info (str "Payment provider is not configured: " name)
                    {:type :provider-not-configured :provider name})))
  (lookup-transaction [_ _]
    (throw (ex-info (str "Payment provider is not configured: " name)
                    {:type :provider-not-configured :provider name})))
  (verify-webhook [_ _] false)
  (parse-webhook [_ _]
    (throw (ex-info (str "Payment provider is not configured: " name)
                    {:type :provider-not-configured :provider name})))

  AccountingExporter
  (export-journal-entry! [_ _]
    (throw (ex-info (str "Accounting exporter is not configured: " name)
                    {:type :provider-not-configured :provider name}))))