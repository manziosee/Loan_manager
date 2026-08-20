(ns loanmanager.db.password-reset
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn create! [ds {:keys [user-id tenant-id token-hash expires-at]}]
  (db/execute-one! ds
    (sql/format {:insert-into :password-reset-tokens
                 :values      [{:user-id user-id :tenant-id tenant-id
                                 :token-hash token-hash :expires-at expires-at}]
                 :returning   [:id]})))

(defn find-valid [ds token-hash]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:password-reset-tokens]
                 :where  [:and [:= :token-hash token-hash]
                               [:is :used-at nil]
                               [:> :expires-at [:now]]]})))

(defn mark-used! [ds id]
  (db/execute-one! ds
    (sql/format {:update :password-reset-tokens
                 :set    {:used-at [:now]}
                 :where  [:= :id id]})))
