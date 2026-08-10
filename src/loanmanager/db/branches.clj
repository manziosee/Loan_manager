(ns loanmanager.db.branches
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn list-branches [ds tenant-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:branches]
                 :where    [:= :tenant-id tenant-id]
                 :order-by [[:name :asc]]})))

(defn find-by-id [ds tenant-id id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:branches]
                 :where  [:and [:= :tenant-id tenant-id] [:= :id id]]})))

(defn create! [ds branch]
  (db/execute-one! ds
    (sql/format {:insert-into :branches
                 :values      [branch]
                 :returning   [:*]})))

(defn update! [ds tenant-id id changes]
  (db/execute-one! ds
    (sql/format {:update    :branches
                 :set       changes
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:*]})))
