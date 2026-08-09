(ns loanmanager.db.loans
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

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
    (sql/format {:select   [:*]
                 :from     [:approval-steps]
                 :where    [:= :application-id application-id]
                 :order-by [[:step-order :asc]]})))

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
