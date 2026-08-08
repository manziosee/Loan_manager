(ns loanmanager.domain.accounting
  "Double-entry journal entry builders for loan lifecycle events.
   Each function returns an immutable journal entry map — no DB side effects.")

(defn- entry [description ref-type ref-id lines]
  {:description    description
   :reference-type ref-type
   :reference-id   ref-id
   :lines          lines})

(defn- debit  [account-code amount currency] {:account-code account-code :debit amount  :credit 0      :currency currency})
(defn- credit [account-code amount currency] {:account-code account-code :debit 0       :credit amount :currency currency})

;; Account codes (configured per tenant in chart_of_accounts)
(def LOANS-RECEIVABLE  "1100")
(def CASH-AT-BANK      "1010")
(def INTEREST-INCOME   "4100")
(def PROCESSING-FEES   "4200")
(def INTEREST-RECEIVABLE "1200")

(defn disbursement-entry
  "Debit: Loans Receivable | Credit: Cash"
  [{:keys [loan-id amount currency]}]
  (entry (str "Loan disbursement " loan-id)
         :disbursement loan-id
         [(debit  LOANS-RECEIVABLE amount currency)
          (credit CASH-AT-BANK     amount currency)]))

(defn payment-entry
  "Debit: Cash | Credit: Loans Receivable (principal) + Interest Income"
  [{:keys [loan-id payment-id principal-portion interest-portion currency]}]
  (entry (str "Loan payment " payment-id)
         :payment payment-id
         (cond-> [(debit CASH-AT-BANK (+ principal-portion interest-portion) currency)]
           (pos? principal-portion)
           (conj (credit LOANS-RECEIVABLE  principal-portion currency))
           (pos? interest-portion)
           (conj (credit INTEREST-INCOME   interest-portion  currency)))))

(defn processing-fee-entry
  [{:keys [loan-id amount currency]}]
  (entry (str "Processing fee " loan-id)
         :fee loan-id
         [(debit  CASH-AT-BANK    amount currency)
          (credit PROCESSING-FEES amount currency)]))

(defn validate-entry
  "Ensures debits == credits (fundamental accounting identity)."
  [{:keys [lines]}]
  (let [total-debit  (reduce + (map :debit  lines))
        total-credit (reduce + (map :credit lines))]
    (when-not (== total-debit total-credit)
      (throw (ex-info "Journal entry imbalanced"
                      {:debit total-debit :credit total-credit})))))
