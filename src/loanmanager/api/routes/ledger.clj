(ns loanmanager.api.routes.ledger
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.domain.accounting :as accounting]
            [loanmanager.security.rbac :as rbac]))

;; ── DB helpers (ledger queries inline — no separate db ns needed) ─────────────

(defn- journal-entries [ds tenant-id {:keys [reference-type reference-id limit offset]
                                       :or   {limit 50 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:je.* [[:count :jl.id] :line-count]
                                    [[:coalesce [:sum :jl.debit]  0] :total-debit]
                                    [[:coalesce [:sum :jl.credit] 0] :total-credit]]
                          :from     [[:journal-entries :je]]
                          :left-join [[:journal-lines :jl] [:= :jl.journal-entry-id :je.id]]
                          :where    [:= :je.tenant-id tenant-id]
                          :group-by [:je.id]
                          :order-by [[:je.posted-at :desc]]
                          :limit    limit
                          :offset   offset}
                  reference-type (update :where conj [:= :je.reference-type reference-type])
                  reference-id   (update :where conj [:= :je.reference-id (parse-uuid reference-id)])))))

(defn- journal-entry-with-lines [ds tenant-id id]
  (let [entry (jdbc/execute-one! ds
                (sql/format {:select [:*]
                             :from   [:journal-entries]
                             :where  [:and [:= :tenant-id tenant-id] [:= :id id]]}))
        lines (jdbc/execute! ds
                (sql/format {:select    [:jl.* [:coa.code :account-code] [:coa.name :account-name]]
                             :from      [[:journal-lines :jl]]
                             :join      [[:chart-of-accounts :coa] [:= :jl.account-id :coa.id]]
                             :where     [:= :jl.journal-entry-id id]
                             :order-by  [[:jl.debit :desc]]}))]
    (when entry (assoc entry :lines lines))))

(defn- chart-of-accounts [ds tenant-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:chart-of-accounts]
                 :where    [:= :tenant-id tenant-id]
                 :order-by [[:code :asc]]})))

(defn- account-balance [ds tenant-id account-code]
  (jdbc/execute-one! ds
    (sql/format {:select   [[:coa.code :code] [:coa.name :name] [:coa.account-type :type]
                             [[:coalesce [:sum :jl.debit]  0] :total-debit]
                             [[:coalesce [:sum :jl.credit] 0] :total-credit]]
                 :from     [[:journal-lines :jl]]
                 :join     [[:chart-of-accounts :coa] [:= :jl.account-id :coa.id]
                             [:journal-entries  :je]  [:= :jl.journal-entry-id :je.id]]
                 :where    [:and [:= :je.tenant-id tenant-id]
                                 [:= :coa.code account-code]
                                 [:= :je.reversed false]]
                 :group-by [:coa.code :coa.name :coa.account-type]})))

(defn routes [ds]
  [["/ledger/journal"
    {:get {:summary    "List journal entries with totals"
           :tags       ["Ledger"]
           :parameters {:query [:map
                                [:reference-type {:optional true} :string]
                                [:reference-id   {:optional true} :string]
                                [:limit          {:optional true} :int]
                                [:offset         {:optional true} :int]]}
           :handler    (fn [{:keys [identity tenant-id query-params]}]
                         (rbac/require-permission identity :ledger/read)
                         {:status 200
                          :body   (journal-entries ds tenant-id query-params)})}}]

   ["/ledger/journal/:id"
    {:get {:summary    "Get a journal entry with all lines"
           :tags       ["Ledger"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :ledger/read)
                         (if-let [entry (journal-entry-with-lines ds tenant-id
                                                                   (parse-uuid (:id path-params)))]
                           {:status 200 :body entry}
                           {:status 404 :body {:error "Journal entry not found"}}))}}]

   ["/ledger/accounts"
    {:get {:summary "List chart of accounts"
           :tags    ["Ledger"]
           :handler (fn [{:keys [identity tenant-id]}]
                      (rbac/require-permission identity :ledger/read)
                      {:status 200 :body (chart-of-accounts ds tenant-id)})}}]

   ["/ledger/accounts/:code/balance"
    {:get {:summary    "Get debit/credit balance for an account code"
           :tags       ["Ledger"]
           :parameters {:path [:map [:code :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :ledger/read)
                         (if-let [bal (account-balance ds tenant-id (:code path-params))]
                           {:status 200 :body bal}
                           {:status 404 :body {:error "Account not found"}}))}}]

   ;; ── Accounting entry preview (no DB write) ────────────────────────────────
   ["/ledger/preview/disbursement"
    {:post {:summary    "Preview journal entry for a loan disbursement"
            :tags       ["Ledger"]
            :parameters {:body [:map
                                [:loan-id  :string]
                                [:amount   [:double {:min 0}]]
                                [:currency {:optional true} :string]]}
            :handler    (fn [{:keys [identity body-params]}]
                          (rbac/require-permission identity :ledger/read)
                          (let [entry (accounting/disbursement-entry
                                        {:loan-id  (:loan-id body-params)
                                         :amount   (:amount body-params)
                                         :currency (or (:currency body-params) "USD")})]
                            (accounting/validate-entry entry)
                            {:status 200 :body entry}))}}]

   ["/ledger/preview/payment"
    {:post {:summary    "Preview journal entry for a loan payment"
            :tags       ["Ledger"]
            :parameters {:body [:map
                                [:payment-id        :string]
                                [:principal-portion [:double {:min 0}]]
                                [:interest-portion  [:double {:min 0}]]
                                [:currency          {:optional true} :string]]}
            :handler    (fn [{:keys [identity body-params]}]
                          (rbac/require-permission identity :ledger/read)
                          (let [entry (accounting/payment-entry
                                        {:payment-id        (:payment-id body-params)
                                         :principal-portion (:principal-portion body-params)
                                         :interest-portion  (:interest-portion body-params)
                                         :currency          (or (:currency body-params) "USD")})]
                            (accounting/validate-entry entry)
                            {:status 200 :body entry}))}}]])
