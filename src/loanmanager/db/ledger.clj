(ns loanmanager.db.ledger
  "Persists double-entry journal entries built by loanmanager.domain.accounting.
   Callers must run loanmanager.domain.accounting/validate-entry on the entry
   before posting, and must pass a connection already inside a transaction
   shared with the triggering loan/payment write so the ledger never drifts
   from the loan state it describes."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn- entry-no [] (str "JE-" (System/currentTimeMillis)))

(defn- account-id
  "Table-qualified keys aren't reliably kebab-cased on every query shape, so
   grab the single selected column by value rather than guessing the key."
  [tx tenant-id account-code]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:id]
                     :from   [:chart-of-accounts]
                     :where  [:and [:= :tenant-id tenant-id] [:= :code account-code]]}))
      vals first))

(defn post-entry!
  "Persist a validated journal entry (as built by loanmanager.domain.accounting)
   as one journal_entries row plus one journal_lines row per line.
   tx must be a connection already participating in the caller's transaction."
  [tx {:keys [tenant-id posted-by]} {:keys [description reference-type reference-id lines]}]
  (let [je-id (-> (db/execute-one! tx
                    (sql/format {:insert-into :journal-entries
                                 :values      [{:tenant-id      tenant-id
                                                :entry-no       (entry-no)
                                                :description    description
                                                :reference-type (name reference-type)
                                                :reference-id   reference-id
                                                :posted-by      posted-by}]
                                 :returning   [:id]}))
                  vals first)]
    (doseq [{:keys [account-code debit credit currency]} lines]
      (let [acc-id (account-id tx tenant-id account-code)]
        (when (nil? acc-id)
          (throw (ex-info (str "Unknown ledger account code: " account-code)
                          {:type :server-error :account-code account-code})))
        (db/execute-one! tx
          (sql/format {:insert-into :journal-lines
                       :values      [{:journal-entry-id je-id
                                      :account-id        acc-id
                                      :debit             debit
                                      :credit            credit
                                      :currency          currency}]}))))
    je-id))
