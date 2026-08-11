(ns loanmanager.db.loans
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn list-loans [ds tenant-id {:keys [status customer-id limit offset]
                                 :or   {limit 20 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:l.* [:c.first-name :customer-first-name]
                                    [:c.last-name :customer-last-name]
                                    [:p.name :product-name]]
                          :from     [[:loans :l]]
                          :join     [[:customers :c]     [:= :l.customer-id :c.id]
                                     [:loan-products :p] [:= :l.product-id :p.id]]
                          :where    [:= :l.tenant-id tenant-id]
                          :order-by [[:l.created-at :desc]]
                          :limit    limit
                          :offset   offset}
                  status      (update :where conj [:= :l.status status])
                  customer-id (update :where conj [:= :l.customer-id customer-id])))))

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

(defn list-applications [ds tenant-id {:keys [status limit offset]
                                        :or   {limit 20 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:la.* [:c.first-name :customer-first-name]
                                    [:c.last-name :customer-last-name]
                                    [:p.name :product-name]]
                          :from     [[:loan-applications :la]]
                          :join     [[:customers :c]     [:= :la.customer-id :c.id]
                                     [:loan-products :p] [:= :la.product-id :p.id]]
                          :where    [:= :la.tenant-id tenant-id]
                          :limit    limit
                          :offset   offset}
                  status (update :where conj [:= :la.status (name status)])))))

(defn add-approval-step! [ds step]
  (db/execute-one! ds
    (sql/format {:insert-into :approval-steps
                 :values      [step]
                 :returning   [:*]})))

(defn get-approval-steps [ds application-id]
  (jdbc/execute! ds
    (sql/format {:select   [:as.* [:u.full-name :actor-name] [:u.email :actor-email]]
                 :from     [[:approval-steps :as]]
                 :left-join [[:users :u] [:= :as.assigned-to :u.id]]
                 :where    [:= :as.application-id application-id]
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

(defn insert-schedule! [ds loan-id installments]
  (db/execute! ds
    (sql/format {:insert-into :repayment-schedules
                 :values      (mapv #(assoc % :loan-id loan-id) installments)})))

(defn get-schedule [ds loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:repayment-schedules]
                 :where    [:= :loan-id loan-id]
                 :order-by [[:installment-no :asc]]})))

(defn next-due-installment [ds loan-id]
  (jdbc/execute-one! ds
    (sql/format {:select   [:*]
                 :from     [:repayment-schedules]
                 :where    [:and [:= :loan-id loan-id]
                                 [:in :status ["pending" "partial" "overdue"]]]
                 :order-by [[:due-date :asc]]
                 :limit    1})))

(defn record-payment! [ds payment]
  (db/execute-one! ds
    (sql/format {:insert-into :payments
                 :values      [payment]
                 :returning   [:*]})))

(defn loan-payments [ds loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:payments]
                 :where    [:and [:= :loan-id loan-id] [:= :reversed false]]
                 :order-by [[:payment-date :desc]]})))

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
                             :delinquency-bucket (name bucket)
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
    (sql/format {:select [:p.* [:l.tenant-id :tenant-id]]
                 :from   [[:payments :p]]
                 :join   [[:loans :l] [:= :p.loan-id :l.id]]
                 :where  [:and [:= :l.tenant-id tenant-id] [:= :p.id id]]})))

(defn reverse-payment! [ds tenant-id payment-id reason reversed-by]
  (jdbc/with-transaction [tx ds]
    (let [payment (find-payment tx tenant-id payment-id)
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
                 :set       {:status          "written-off"
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
                                   :restructured-at [:now]
                                   :restructured-by restructured-by
                                   :status          "active"
                                   :updated-at      [:now])
                 :where     [:and [:= :tenant-id tenant-id] [:= :id loan-id]]
                 :returning [:*]})))

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
    (sql/format {:select   [[[:date-trunc "month" :disbursed-at] :month]
                            [[:count :id] :count]
                            [[:sum :principal] :total-disbursed]]
                 :from     [:loans]
                 :where    [:and [:= :tenant-id tenant-id]
                                 [:>= :disbursed-at from-date]
                                 [:<= :disbursed-at to-date]]
                 :group-by [[:date-trunc "month" :disbursed-at]]
                 :order-by [[[:date-trunc "month" :disbursed-at] :asc]]})))

(defn income-by-period [ds tenant-id from-date to-date]
  (jdbc/execute! ds
    (sql/format {:select   [[[:date-trunc "month" :payment-date] :month]
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
                 :group-by [[:date-trunc "month" :payment-date]]
                 :order-by [[[:date-trunc "month" :payment-date] :asc]]})))

(defn collections-performance [ds tenant-id from-date to-date]
  (jdbc/execute! ds
    (sql/format {:select   [[[:date-trunc "month" :ca.recorded-at] :month]
                            [:ca.activity-type]
                            [[:count :ca.id] :count]]
                 :from     [[:collection-activities :ca]]
                 :join     [[:collection-cases :cc] [:= :ca.case-id :cc.id]]
                 :where    [:and [:= :cc.tenant-id tenant-id]
                                 [:>= :ca.recorded-at from-date]
                                 [:<= :ca.recorded-at to-date]]
                 :group-by [[:date-trunc "month" :ca.recorded-at] :ca.activity-type]
                 :order-by [[[:date-trunc "month" :ca.recorded-at] :asc]]})))
