(ns loanmanager.db.loans
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]
            [loanmanager.domain.finance :as finance]
            [loanmanager.domain.accounting :as accounting]
            [loanmanager.db.ledger :as ledger]
            [tick.core :as t]
            [clojure.string :as str]))

(defn- freq->days [freq]
  (case freq
    :daily     1
    :weekly    7
    :biweekly  14
    :monthly   30
    :quarterly 90
    :bullet    30
    30))

(defn list-loans [ds tenant-id {:keys [status customer-id limit offset branch-id]
                                 :or   {limit 20 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:l.* [:c.first-name :customer-first-name]
                                    [:c.last-name :customer-last-name]
                                    [:p.name :product-name]]
                          :from     [[:loans :l]]
                          :join     [[:customers :c]     [:= :l.customer-id :c.id]
                                     [:loan-products :p] [:= :l.product-id :p.id]]
                          :where    [:and [:= :l.tenant-id tenant-id]]
                          :order-by [[:l.created-at :desc]]
                          :limit    limit
                          :offset   offset}
                  status      (update :where conj [:= :l.status status])
                  customer-id (update :where conj [:= :l.customer-id customer-id])
                  branch-id   (update :where conj [:= :l.branch-id branch-id])))))

(defn create-application! [ds application]
  (db/execute-one! ds
    (sql/format {:insert-into :loan-applications
                 :values      [application]
                 :returning   [:*]})))

(defn find-application [ds tenant-id id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:loan-applications]
                 :where  [:and [:= :tenant-id tenant-id] [:= :id id]]})))

(defn update-application! [ds tenant-id id changes]
  (db/execute-one! ds
    (sql/format {:update    :loan-applications
                 :set       (assoc changes :updated-at [:now])
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:*]})))

(defn list-applications [ds tenant-id {:keys [status limit offset branch-id]
                                        :or   {limit 20 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:la.* [:c.first-name :customer-first-name]
                                    [:c.last-name :customer-last-name]
                                    [:p.name :product-name]]
                          :from     [[:loan-applications :la]]
                          :join     [[:customers :c]     [:= :la.customer-id :c.id]
                                     [:loan-products :p] [:= :la.product-id :p.id]]
                          :where    [:and [:= :la.tenant-id tenant-id]]
                          :limit    limit
                          :offset   offset}
                  status    (update :where conj [:= :la.status (name status)])
                  branch-id (update :where conj [:= :la.branch-id branch-id])))))

(defn applications-last-24h
  "Count of applications this customer has submitted in the last 24 hours —
   used as a fraud signal (rapid/duplicate applications)."
  [ds customer-id]
  (-> (jdbc/execute-one! ds
        (sql/format {:select [[[:count :id] :cnt]]
                     :from   [:loan-applications]
                     :where  [:and [:= :customer-id customer-id]
                                   [:>= :submitted-at [:- [:now] [:raw "INTERVAL '24 hours'"]]]]}))
      :cnt
      (or 0)))

(defn add-approval-step! [ds step]
  (db/execute-one! ds
    (sql/format {:insert-into :approval-steps
                 :values      [step]
                 :returning   [:*]})))

(defn get-approval-steps [ds tenant-id application-id]
  (jdbc/execute! ds
    (sql/format {:select   [:as.* [:u.full-name :actor-name] [:u.email :actor-email]]
                 :from     [[:approval-steps :as]]
                 :left-join [[:users :u] [:= :as.assigned-to :u.id]]
                 :join     [[:loan-applications :la] [:= :as.application-id :la.id]]
                 :where    [:and [:= :as.application-id application-id]
                                  [:= :la.tenant-id tenant-id]]
                 :order-by [[:as.step-order :asc]]})))

(defn payment-history-stats
  "Returns late payment counts and defaults for credit scoring."
  [ds customer-id]
  (let [stats (jdbc/execute-one! ds
                [(str "SELECT"
                      "  COUNT(*) FILTER (WHERE p.payment_date > rs.due_date"
                      "    AND p.payment_date >= NOW() - INTERVAL '12 months') AS late_12m,"
                      "  COUNT(*) FILTER (WHERE p.payment_date > rs.due_date"
                      "    AND p.payment_date >= NOW() - INTERVAL '24 months') AS late_24m,"
                      "  COUNT(*) FILTER (WHERE l.status = 'written_off') AS write_offs,"
                      "  COUNT(*) FILTER (WHERE l.status IN ('npl','written_off')) AS defaults"
                      " FROM loans l"
                      " LEFT JOIN payments p ON p.loan_id = l.id AND p.reversed = false"
                      " LEFT JOIN repayment_schedules rs ON rs.loan_id = l.id"
                      " WHERE l.customer_id = ?")
                 customer-id])]
    {:late-payments-12m (or (:late_12m stats) 0)
     :late-payments-24m (or (:late_24m stats) 0)
     :write-offs        (or (:write_offs stats) 0)
     :defaults          (or (:defaults stats) 0)}))

(defn create-loan! [ds loan]
  (db/execute-one! ds
    (sql/format {:insert-into :loans
                 :values      [loan]
                 :returning   [:*]})))

(defn find-loan [ds tenant-id id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:loans]
                 :where  [:and [:= :tenant-id tenant-id] [:= :id id]]})))

(defn update-loan! [ds tenant-id id changes]
  (db/execute-one! ds
    (sql/format {:update    :loans
                 :set       (assoc changes :updated-at [:now])
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:*]})))

(defn customer-loans [ds tenant-id customer-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:loans]
                 :where    [:and [:= :tenant-id tenant-id] [:= :customer-id customer-id]]
                 :order-by [[:created-at :desc]]})))

(defn insert-schedule!
  "Persists a generated schedule (loanmanager.domain.finance/build-schedule).
   Its installment maps carry :currency (not a column — currency lives on the
   loan) and no :due-date (required, no schema default), so both need
   resolving here: due-date = today + (installment-no * period length).
   start-offset shifts installment numbers past any already-paid ones (used
   when restructuring lays a fresh schedule on top of partial payment
   history) so it doesn't collide with the UNIQUE(loan_id, installment_no)
   constraint; ordinary disbursement passes 0."
  ([ds loan-id freq installments] (insert-schedule! ds loan-id freq installments 0))
  ([ds loan-id freq installments start-offset]
   (let [start (t/date)
         days  (freq->days freq)
         rows  (mapv (fn [{:keys [installment-no status] :as inst}]
                       (let [no (+ installment-no start-offset)]
                         (-> inst
                             (dissoc :currency :grace-period? :balloon?)
                             (assoc :loan-id       loan-id
                                    :installment-no no
                                    :status        (name status)
                                    :due-date      (t/>> start (t/new-period (* no days) :days))))))
                     installments)]
     (db/execute! ds
       (sql/format {:insert-into :repayment-schedules
                    :values      rows})))))

(defn max-installment-no [ds loan-id]
  (-> (jdbc/execute-one! ds
        (sql/format {:select [[[:coalesce [:max :installment-no] 0] :max-no]]
                     :from   [:repayment-schedules]
                     :where  [:= :loan-id loan-id]}))
      vals first))

(defn max-paid-installment-no [ds loan-id]
  (-> (jdbc/execute-one! ds
        (sql/format {:select [[[:coalesce [:max :installment-no] 0] :max-no]]
                     :from   [:repayment-schedules]
                     :where  [:and [:= :loan-id loan-id]
                                    [:in :status ["partial" "paid" "overdue"]]]}))
      vals first))

(defn get-schedule [ds tenant-id loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:rs.*]
                 :from     [[:repayment-schedules :rs]]
                 :join     [[:loans :l] [:= :rs.loan-id :l.id]]
                 :where    [:and [:= :rs.loan-id loan-id]
                                  [:= :l.tenant-id tenant-id]]
                 :order-by [[:installment-no :asc]]})))

(defn delete-pending-schedule!
  "Removes not-yet-due installments so a restructure can lay down a fresh
   schedule for the remaining term. Paid/partial installments are left
   alone — they're real payment history, not a plan to be replaced."
  [ds loan-id]
  (jdbc/execute! ds
    (sql/format {:delete-from :repayment-schedules
                 :where       [:and [:= :loan-id loan-id] [:= :status "pending"]]})))

(defn mark-schedule-settled! [tx loan-id]
  (db/execute! tx
    (sql/format {:update :repayment-schedules
                 :set    {:principal-paid :principal-due
                          :interest-paid  :interest-due
                          :status         "paid"
                          :paid-at        [:now]}
                 :where  [:and [:= :loan-id loan-id]
                                [:not= :status "paid"]]})))

(defn next-due-installment [ds loan-id]
  (jdbc/execute-one! ds
    (sql/format {:select   [:*]
                 :from     [:repayment-schedules]
                 :where    [:and [:= :loan-id loan-id]
                                 [:in :status ["pending" "partial" "overdue"]]]
                 :order-by [[:due-date :asc]]
                 :limit    1})))

(defn apply-payment-allocation!
  "Allocates a payment against a tenant-owned loan schedule and persists the
   installment balances. The caller must already be inside the payment
  transaction so the schedule and payment cannot diverge."
  [tx tenant-id loan-id amount]
  (let [rows     (get-schedule tx tenant-id loan-id)
        schedule (mapv (fn [row]
                         {:id              (:repayment-schedules/id row)
                          :installment-no  (:repayment-schedules/installment-no row)
                          :interest-due    (:repayment-schedules/interest-due row)
                          :principal-due   (:repayment-schedules/principal-due row)
                          :interest-paid   (:repayment-schedules/interest-paid row)
                          :principal-paid  (:repayment-schedules/principal-paid row)
                          :status          (keyword (:repayment-schedules/status row))})
                       rows)
        allocation (finance/allocate-payment schedule amount)]
    (doseq [{:keys [id interest-paid principal-paid status paid-at]} (:schedule allocation)]
      (db/execute-one! tx
        (sql/format {:update :repayment-schedules
                     :set    (cond-> {:interest-paid  interest-paid
                                      :principal-paid principal-paid
                                      :status         (name status)}
                               paid-at (assoc :paid-at paid-at))
                     :where  [:= :id id]})))
    allocation))

(defn record-payment-allocations! [tx tenant-id payment-id allocation]
  (doseq [{:keys [schedule-id principal-applied interest-applied]} (:allocated allocation)]
    (db/execute-one! tx
      (sql/format {:insert-into :payment-allocations
                   :values      [{:tenant-id         tenant-id
                                  :payment-id         payment-id
                                  :schedule-id        schedule-id
                                  :principal-applied principal-applied
                                  :interest-applied  interest-applied}]}))))

(defn reverse-payment-schedule! [tx tenant-id payment-id]
  (let [allocations (jdbc/execute! tx
                      (sql/format {:select [:pa.*]
                                   :from   [[:payment-allocations :pa]]
                                   :join   [[:payments :p] [:= :pa.payment-id :p.id]]
                                   :where  [:and [:= :pa.payment-id payment-id]
                                                  [:= :pa.tenant-id tenant-id]
                                                  [:= :p.tenant-id tenant-id]]}))]
    (doseq [{:keys [payment-allocations/schedule-id
                    payment-allocations/principal-applied
                    payment-allocations/interest-applied]} allocations]
      (db/execute-one! tx
        (sql/format {:update :repayment-schedules
                     :set    {:principal-paid [:- :principal-paid principal-applied]
                              :interest-paid  [:- :interest-paid interest-applied]
                              :status         "pending"
                              :paid-at        nil}
                     :where  [:= :id schedule-id]})))))

(defn record-payment! [ds payment]
  (db/execute-one! ds
    (sql/format {:insert-into :payments
                 :values      [payment]
                 :returning   [:*]})))

(defn loan-payments [ds tenant-id loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:p.*]
                 :from     [[:payments :p]]
                 :join     [[:loans :l] [:= :p.loan-id :l.id]]
                 :where    [:and [:= :p.loan-id loan-id]
                                  [:= :l.tenant-id tenant-id]
                                  [:= :p.reversed false]]
                 :order-by [[:p.payment-date :desc]]})))

(defn overdue-loans [ds tenant-id]
  (jdbc/execute! ds
    (sql/format {:select [:l.* [:c.email :customer-email] [:c.phone :customer-phone]
                          [:c.first-name :customer-first-name] [:c.last-name :customer-last-name]]
                 :from   [[:loans :l]]
                 :join   [[:customers :c] [:= :l.customer-id :c.id]]
                 :where  [:and
                          [:= :l.tenant-id tenant-id]
                          [:in :l.status ["active" "overdue" "npl"]]]})))

(defn all-active-loans [ds tenant-id]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from   [:loans]
                 :where  [:and
                          [:= :tenant-id tenant-id]
                          [:in :status ["active" "overdue"]]]})))

(defn update-delinquency! [ds tenant-id loan-id days-overdue bucket]
  (db/execute-one! ds
    (sql/format {:update    :loans
                 :set       {:days-overdue       days-overdue
                             ;; delinquency_bucket enum uses underscores (1_30, 90_plus);
                             ;; domain/delinquency.clj's bucket keywords use hyphens (:1-30, :90-plus)
                             :delinquency-bucket (str/replace (name bucket) "-" "_")
                             :status             (case bucket
                                                   :npl     "npl"
                                                   :current "active"
                                                   "overdue")
                             :updated-at         [:now]}
                 :where     [:and [:= :tenant-id tenant-id] [:= :id loan-id]]
                 :returning [:id :days-overdue :delinquency-bucket :status]})))

(defn prepayment-settlement
  "Fetch all data needed for early repayment calculation."
  [ds tenant-id loan-id]
  (jdbc/execute-one! ds
    (sql/format {:select    [:l.* [:p.name :product-name]
                             [[:coalesce [:sum :py.amount] 0] :total-paid]]
                 :from      [[:loans :l]]
                 :join      [[:loan-products :p] [:= :l.product-id :p.id]]
                 :left-join [[:payments :py] [:and [:= :py.loan-id :l.id]
                                                   [:= :py.reversed false]]]
                 :where     [:and [:= :l.tenant-id tenant-id] [:= :l.id loan-id]]
                 :group-by  [:l.id :p.name]})))

(defn find-payment [ds tenant-id id]
  (jdbc/execute-one! ds
    (sql/format {:select [:p.* [:l.tenant-id :tenant-id] [:l.currency :loan-currency]]
                 :from   [[:payments :p]]
                 :join   [[:loans :l] [:= :p.loan-id :l.id]]
                 :where  [:and [:= :l.tenant-id tenant-id] [:= :p.id id]]})))

(defn reverse-payment! [ds tenant-id payment-id reason reversed-by]
  (jdbc/with-transaction [tx ds]
    (let [tx      (db/with-kebab-keys tx)
          payment (find-payment tx tenant-id payment-id)
          _       (when (or (nil? payment) (:payments/reversed payment))
                    (throw (ex-info "Payment not found or already reversed"
                                   {:type :validation})))
          loan-id (:payments/loan-id payment)]
      (db/execute-one! tx
        (sql/format {:update    :payments
                     :set       {:reversed true :reversed-at [:now]
                                 :reversal-reason reason :reversed-by reversed-by}
                     :where     [:= :id payment-id]
                     :returning [:*]}))
                (reverse-payment-schedule! tx tenant-id payment-id)
                (let [reversal (accounting/payment-reversal-entry
                         {:payment-id        (:payments/payment-no payment)
                      :reference-id      (:payments/id payment)
                      :principal-portion (:payments/principal-portion payment)
                      :interest-portion  (:payments/interest-portion payment)
                        :currency           (:loan-currency payment)})]
                  (accounting/validate-entry reversal)
                  (ledger/post-entry! tx {:tenant-id tenant-id :posted-by reversed-by} reversal)
                  (ledger/mark-reference-reversed! tx tenant-id :payment (:payments/id payment)))
      ;; restore outstanding principal
      (db/execute-one! tx
        (sql/format {:update :loans
                     :set    {:outstanding-principal [:+ :outstanding-principal
                                                      (:payments/principal-portion payment)]
                              :total-paid-principal  [:- :total-paid-principal
                                                      (:payments/principal-portion payment)]
                              :total-paid-interest   [:- :total-paid-interest
                                                      (:payments/interest-portion payment)]
                              :updated-at            [:now]}
                     :where  [:and [:= :tenant-id tenant-id] [:= :id loan-id]]}))
      payment)))

(defn write-off-loan! [ds tenant-id loan-id written-off-by reason]
  (db/execute-one! ds
    (sql/format {:update    :loans
                 :set       {:status          "written_off"
                             :written-off-at  [:now]
                             :written-off-by  written-off-by
                             :write-off-reason reason
                             :updated-at      [:now]}
                 :where     [:and [:= :tenant-id tenant-id] [:= :id loan-id]]
                 :returning [:*]})))

(defn restructure-loan! [ds tenant-id loan-id changes restructured-by]
  (db/execute-one! ds
    (sql/format {:update    :loans
                 :set       (assoc changes
                                   :restructured-at   [:now]
                                   :restructured-by   restructured-by
                                   :restructure-count [:+ :restructure-count 1]
                                   :status            "active"
                                   :updated-at        [:now])
                 :where     [:and [:= :tenant-id tenant-id] [:= :id loan-id]]
                 :returning [:*]})))

(defn log-restructuring!
  "Snapshots the loan's terms BEFORE a restructure is applied — this is what
   makes 'Restructuring #1 -> #2' a real, queryable history instead of an
   in-place overwrite that destroys the original terms."
  [ds entry]
  (db/execute-one! ds
    (sql/format {:insert-into :loan-restructurings
                 :values      [entry]
                 :returning   [:*]})))

(defn restructuring-history [ds tenant-id loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:r.*]
                 :from     [[:loan-restructurings :r]]
                 :join     [[:loans :l] [:= :r.loan-id :l.id]]
                 :where    [:and [:= :r.loan-id loan-id]
                                  [:= :l.tenant-id tenant-id]]
                 :order-by [[:sequence-no :asc]]})))

;; ── Report queries ────────────────────────────────────────────────────────────

(defn portfolio-summary [ds tenant-id]
  (jdbc/execute! ds
    (sql/format {:select   [:status
                            [[:count :id] :count]
                            [[:coalesce [:sum :outstanding-principal] 0] :outstanding]
                            [[:coalesce [:sum :principal] 0] :disbursed]]
                 :from     [:loans]
                 :where    [:= :tenant-id tenant-id]
                 :group-by [:status]})))

(defn par-buckets [ds tenant-id]
  (jdbc/execute! ds
    (sql/format {:select   [:delinquency-bucket
                            [[:count :id] :count]
                            [[:coalesce [:sum :outstanding-principal] 0] :outstanding]]
                 :from     [:loans]
                 :where    [:and [:= :tenant-id tenant-id]
                                 [:in :status ["active" "overdue" "npl"]]]
                 :group-by [:delinquency-bucket]})))

(defn disbursements-by-period [ds tenant-id from-date to-date]
  (jdbc/execute! ds
    (sql/format {:select   [[[:date_trunc [:inline "month"] :disbursed-at] :month]
                            [[:count :id] :count]
                            [[:sum :principal] :total-disbursed]]
                 :from     [:loans]
                 :where    [:and [:= :tenant-id tenant-id]
                                 [:>= :disbursed-at from-date]
                                 [:<= :disbursed-at to-date]]
                 :group-by [[:date_trunc [:inline "month"] :disbursed-at]]
                 :order-by [[[:date_trunc [:inline "month"] :disbursed-at] :asc]]})))

(defn income-by-period [ds tenant-id from-date to-date]
  (jdbc/execute! ds
    (sql/format {:select   [[[:date_trunc [:inline "month"] :payment-date] :month]
                            [[:sum :interest-portion] :interest-income]
                            [[:sum :principal-portion] :principal-collected]
                            [[:count :id] :payment-count]]
                 :from     [:payments]
                 :where    [:and
                            [:exists {:select [:id] :from [:loans]
                                      :where  [:and [:= :loans.id :payments.loan-id]
                                                    [:= :loans.tenant-id tenant-id]]}]
                            [:= :reversed false]
                            [:>= :payment-date from-date]
                            [:<= :payment-date to-date]]
                 :group-by [[:date_trunc [:inline "month"] :payment-date]]
                 :order-by [[[:date_trunc [:inline "month"] :payment-date] :asc]]})))

(defn collections-performance [ds tenant-id from-date to-date]
  (jdbc/execute! ds
    (sql/format {:select   [[[:date_trunc [:inline "month"] :ca.recorded-at] :month]
                            [:ca.activity-type]
                            [[:count :ca.id] :count]]
                 :from     [[:collection-activities :ca]]
                 :join     [[:collection-cases :cc] [:= :ca.case-id :cc.id]]
                 :where    [:and [:= :cc.tenant-id tenant-id]
                                 [:>= :ca.recorded-at from-date]
                                 [:<= :ca.recorded-at to-date]]
                 :group-by [[:date_trunc [:inline "month"] :ca.recorded-at] :ca.activity-type]
                 :order-by [[[:date_trunc [:inline "month"] :ca.recorded-at] :asc]]})))
