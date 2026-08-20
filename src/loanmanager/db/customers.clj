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

(defn search [ds tenant-id {:keys [q limit offset branch-id] :or {limit 20 offset 0}}]
  (jdbc/execute! ds
    (sql/format {:select   [:id :customer-no :first-name :last-name
                             :company-name :email :phone :type :kyc-status]
                 :from     [:customers]
                 :where    (cond-> [:and [:= :tenant-id tenant-id]]
                             q         (conj [:or
                                               [:ilike :first-name  (str "%" q "%")]
                                               [:ilike :last-name   (str "%" q "%")]
                                               [:ilike :email       (str "%" q "%")]
                                               [:ilike :customer-no (str "%" q "%")]])
                             branch-id (conj [:= :branch-id branch-id]))
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

(defn find-document [ds customer-id doc-id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:customer-documents]
                 :where  [:and [:= :customer-id customer-id] [:= :id doc-id]]})))

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

;; ── Fraud signals ─────────────────────────────────────────────────────────────

(defn phone-customer-count
  "How many customers (within the tenant) share this phone number.
   1 = unique to this customer, >1 = shared/suspicious."
  [ds tenant-id phone]
  (if (nil? phone)
    1
    (-> (jdbc/execute-one! ds
          (sql/format {:select [[[:count :id] :cnt]]
                       :from   [:customers]
                       :where  [:and [:= :tenant-id tenant-id] [:= :phone phone]]}))
        :cnt
        (or 1))))

(defn id-doc-duplicate?
  "True if any of this customer's ID document numbers are also on file for a
   different customer — a strong fraud signal."
  [ds customer-id]
  (-> (jdbc/execute-one! ds
        (sql/format {:select [[[:count [:distinct :cd2.customer-id]] :cnt]]
                     :from   [[:customer-documents :cd1]]
                     :join   [[:customer-documents :cd2] [:= :cd1.doc-number :cd2.doc-number]]
                     :where  [:and [:= :cd1.customer-id customer-id]
                                   [:not= :cd2.customer-id customer-id]]}))
      :cnt
      (or 0)
      pos?))
