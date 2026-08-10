(ns loanmanager.db.collateral
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn list-for-loan [ds loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:collateral]
                 :where    [:= :loan-id loan-id]
                 :order-by [[:created-at :asc]]})))

(defn create! [ds item]
  (db/execute-one! ds
    (sql/format {:insert-into :collateral
                 :values      [item]
                 :returning   [:*]})))

(defn update! [ds tenant-id id changes]
  (db/execute-one! ds
    (sql/format {:update    :collateral
                 :set       changes
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:*]})))

(defn list-guarantors [ds loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:g.* [:c.first-name :customer-first-name]
                             [:c.last-name :customer-last-name]
                             [:c.phone :customer-phone]]
                 :from     [[:guarantors :g]]
                 :join     [[:customers :c] [:= :g.customer-id :c.id]]
                 :where    [:= :g.loan-id loan-id]})))

(defn add-guarantor! [ds guarantor]
  (db/execute-one! ds
    (sql/format {:insert-into :guarantors
                 :values      [guarantor]
                 :returning   [:*]})))
