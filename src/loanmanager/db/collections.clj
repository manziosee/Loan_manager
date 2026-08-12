(ns loanmanager.db.collections
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn- case-no [] (str "COL-" (System/currentTimeMillis)))

(defn create-case! [ds case-data]
  (db/execute-one! ds
    (sql/format {:insert-into :collection-cases
                 :values      [(assoc case-data :case-no (case-no))]
                 :returning   [:*]})))

(defn find-case [ds tenant-id id]
  (jdbc/execute-one! ds
    (sql/format {:select    [:cc.* [:l.loan-no :loan-no] [:l.outstanding-principal :outstanding]
                             [:l.days-overdue :days-overdue] [:l.last-payment-date :last-payment-date]
                             [:c.first-name :customer-first-name] [:c.last-name :customer-last-name]
                             [:c.phone :customer-phone] [:c.email :customer-email]
                             [:c.risk-score :customer-risk-score]]
                 :from      [[:collection-cases :cc]]
                 :join      [[:loans :l]     [:= :cc.loan-id :l.id]
                             [:customers :c] [:= :cc.customer-id :c.id]]
                 :where     [:and [:= :cc.tenant-id tenant-id] [:= :cc.id id]]})))

(defn find-case-by-loan [ds tenant-id loan-id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:collection-cases]
                 :where  [:and
                          [:= :tenant-id tenant-id]
                          [:= :loan-id loan-id]
                          [:= :status "open"]]
                 :limit  1})))

(defn list-cases [ds tenant-id {:keys [status priority assigned-to limit offset]
                                 :or   {limit 20 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:cc.* [:l.loan-no :loan-no]
                                    [:l.outstanding-principal :outstanding]
                                    [:l.days-overdue :days-overdue]
                                    [:c.first-name :customer-first-name]
                                    [:c.last-name :customer-last-name]
                                    [:c.phone :customer-phone]]
                          :from     [[:collection-cases :cc]]
                          :join     [[:loans :l]     [:= :cc.loan-id :l.id]
                                     [:customers :c] [:= :cc.customer-id :c.id]]
                          :where    [:= :cc.tenant-id tenant-id]
                          :order-by [[:cc.priority :desc] [:l.days-overdue :desc]]
                          :limit    limit
                          :offset   offset}
                  status      (update :where conj [:= :cc.status status])
                  priority    (update :where conj [:= :cc.priority priority])
                  assigned-to (update :where conj [:= :cc.assigned-to (parse-uuid assigned-to)])))))

(defn update-case! [ds tenant-id id changes]
  (db/execute-one! ds
    (sql/format {:update    :collection-cases
                 :set       (assoc changes :updated-at [:now])
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:*]})))

(defn add-activity! [ds activity]
  (db/execute-one! ds
    (sql/format {:insert-into :collection-activities
                 :values      [activity]
                 :returning   [:*]})))

(defn case-activities [ds case-id]
  (jdbc/execute! ds
    (sql/format {:select    [:ca.* [:u.full-name :officer-name]]
                 :from      [[:collection-activities :ca]]
                 :left-join [[:users :u] [:= :ca.recorded-by :u.id]]
                 :where     [:= :ca.case-id case-id]
                 :order-by  [[:ca.recorded-at :desc]]})))

(defn create-promise! [ds promise-data]
  (db/execute-one! ds
    (sql/format {:insert-into :promises-to-pay
                 :values      [promise-data]
                 :returning   [:*]})))

(defn case-promises [ds case-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:promises-to-pay]
                 :where    [:= :case-id case-id]
                 :order-by [[:created-at :desc]]})))

(defn update-promise! [ds id changes]
  (db/execute-one! ds
    (sql/format {:update    :promises-to-pay
                 :set       (assoc changes :updated-at [:now])
                 :where     [:= :id id]
                 :returning [:*]})))

(defn broken-promises
  "Find promises past their due date with no payment recorded."
  [ds tenant-id]
  (jdbc/execute! ds
    (sql/format {:select [:p.* [:cc.assigned-to :officer-id]
                          [:l.loan-no :loan-no]
                          [:c.first-name :customer-first-name]
                          [:c.last-name :customer-last-name]
                          [:c.phone :customer-phone]]
                 :from   [[:promises-to-pay :p]]
                 :join   [[:collection-cases :cc] [:= :p.case-id :cc.id]
                          [:loans :l]             [:= :p.loan-id :l.id]
                          [:customers :c]         [:= :l.customer-id :c.id]]
                 :where  [:and
                          [:= :cc.tenant-id tenant-id]
                          [:= :p.status "pending"]
                          [:< :p.promise-date [:cast [:now] :date]]]})))

(defn log-delinquency! [ds entry]
  (db/execute-one! ds
    (sql/format {:insert-into :delinquency-log
                 :values      [(cond-> entry
                                 (:triggered-actions entry)
                                 (update :triggered-actions #(vector :lift %)))]
                 :returning   [:id]})))

(defn loan-delinquency-history [ds loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:delinquency-log]
                 :where    [:= :loan-id loan-id]
                 :order-by [[:assessed-at :desc]]})))
