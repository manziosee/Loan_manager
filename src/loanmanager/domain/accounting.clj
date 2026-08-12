(ns loanmanager.domain.accounting
  "Double-entry journal entry builders for loan lifecycle events.
   Each function returns an immutable journal entry map — no DB side effects.")

(defn- entry [description ref-type ref-id lines]
  {:description    description
   :reference-type ref-type
   :reference-id   ref-id
   :lines          lines})

(defn- debit  [account-code amount currency] {:account-code account-code :debit amount :credit 0      :currency currency})
(defn- credit [account-code amount currency] {:account-code account-code :debit 0      :credit amount :currency currency})

(def LOANS-RECEIVABLE "1100")
(def CASH-AT-BANK     "1010")
(def INTEREST-INCOME  "4100")
(def PROCESSING-FEES  "4200")

(defn disbursement-entry
  "Debit: Loans Receivable | Credit: Cash.
   loan-id is used for the human-readable description; reference-id (the
   loan's actual UUID — journal_entries.reference_id is a uuid column) is
   what gets stored for lookups, and falls back to loan-id when omitted so
   pure/test callers that only care about the entry shape still work."
  [{:keys [loan-id amount currency reference-id]}]
  (entry (str "Loan disbursement " loan-id)
         :disbursement (or reference-id loan-id)
         [(debit  LOANS-RECEIVABLE amount currency)
          (credit CASH-AT-BANK     amount currency)]))

(defn payment-entry
  "Debit: Cash | Credit: Loans Receivable (principal) + Interest Income.
   See disbursement-entry re: payment-id (display) vs reference-id (uuid)."
  [{:keys [payment-id principal-portion interest-portion currency reference-id]}]
  (entry (str "Loan payment " payment-id)
         :payment (or reference-id payment-id)
         (cond-> [(debit CASH-AT-BANK (+ principal-portion interest-portion) currency)]
           (pos? principal-portion) (conj (credit LOANS-RECEIVABLE principal-portion currency))
           (pos? interest-portion)  (conj (credit INTEREST-INCOME  interest-portion  currency)))))

(defn processing-fee-entry
  [{:keys [loan-id amount currency reference-id]}]
  (entry (str "Processing fee " loan-id)
         :fee (or reference-id loan-id)
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
