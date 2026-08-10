(ns loanmanager.db.customers
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn find-by-id [ds tenant-id id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:customers]
                 :where  [:and [:= :tenant-id tenant-id] [:= :id id]]})))

(defn find-by-customer-no [ds tenant-id customer-no]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:customers]
                 :where  [:and [:= :tenant-id tenant-id] [:= :customer-no customer-no]]})))

(defn search [ds tenant-id {:keys [q limit offset] :or {limit 20 offset 0}}]
  (jdbc/execute! ds
    (sql/format {:select   [:id :customer-no :first-name :last-name
                             :company-name :email :phone :type :kyc-status]
                 :from     [:customers]
                 :where    (cond-> [:= :tenant-id tenant-id]
                             q (conj [:or
                                      [:ilike :first-name  (str "%" q "%")]
                                      [:ilike :last-name   (str "%" q "%")]
                                      [:ilike :email       (str "%" q "%")]
                                      [:ilike :customer-no (str "%" q "%")]]))
                 :limit    limit
                 :offset   offset})))

(defn create! [ds customer]
  (db/execute-one! ds
    (sql/format {:insert-into :customers
                 :values      [customer]
                 :returning   [:*]})))

(defn update! [ds tenant-id id changes]
  (db/execute-one! ds
    (sql/format {:update    :customers
                 :set       (assoc changes :updated-at [:now])
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:*]})))

(defn deactivate! [ds tenant-id id deactivated-by]
  (db/execute-one! ds
    (sql/format {:update    :customers
                 :set       {:active false :deactivated-at [:now] :deactivated-by deactivated-by
                             :updated-at [:now]}
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:id :customer-no :active :deactivated-at]})))

(defn update-kyc! [ds tenant-id id kyc-status]
  (db/execute-one! ds
    (sql/format {:update    :customers
                 :set       {:kyc-status (name kyc-status) :updated-at [:now]}
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:id :customer-no :kyc-status]})))

;; ── Documents ─────────────────────────────────────────────────────────────────

(defn list-documents [ds customer-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:customer-documents]
                 :where    [:= :customer-id customer-id]
                 :order-by [[:created-at :desc]]})))

(defn add-document! [ds doc]
  (db/execute-one! ds
    (sql/format {:insert-into :customer-documents
                 :values      [doc]
                 :returning   [:*]})))

;; ── Liabilities ───────────────────────────────────────────────────────────────

(defn liabilities [ds customer-id]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from   [:customer-liabilities]
                 :where  [:= :customer-id customer-id]})))

(defn add-liability! [ds liability]
  (db/execute-one! ds
    (sql/format {:insert-into :customer-liabilities
                 :values      [liability]
                 :returning   [:*]})))

(defn remove-liability! [ds customer-id id]
  (db/execute-one! ds
    (sql/format {:delete-from :customer-liabilities
                 :where       [:and [:= :customer-id customer-id] [:= :id id]]
                 :returning   [:id]})))

(defn total-monthly-obligations [ds customer-id]
  (-> (jdbc/execute-one! ds
        (sql/format {:select [[[:sum :monthly-payment] :total]]
                     :from   [:customer-liabilities]
                     :where  [:= :customer-id customer-id]}))
      :total
      (or 0M)))
