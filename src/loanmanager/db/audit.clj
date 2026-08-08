(ns loanmanager.db.audit
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn log! [ds entry]
  (db/execute-one! ds
    (sql/format {:insert-into :audit-log
                 :values      [(assoc entry :occurred-at [:now])]
                 :returning   [:id]})))

(defn entity-history [ds tenant-id entity-type entity-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:audit-log]
                 :where    [:and
                            [:= :tenant-id tenant-id]
                            [:= :entity-type (name entity-type)]
                            [:= :entity-id entity-id]]
                 :order-by [[:occurred-at :desc]]})))

(defn user-activity [ds tenant-id user-id {:keys [limit] :or {limit 50}}]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:audit-log]
                 :where    [:and [:= :tenant-id tenant-id] [:= :user-id user-id]]
                 :order-by [[:occurred-at :desc]]
                 :limit    limit})))
