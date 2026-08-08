(ns loanmanager.db.connection
  (:require [hikari-cp.core :as hikari]
            [next.jdbc :as jdbc]))

(defn init-pool! [{:keys [jdbc-url username password pool-size]}]
  (hikari/make-datasource
   {:jdbc-url        jdbc-url
    :username        username
    :password        password
    :maximum-pool-size pool-size
    :minimum-idle    2
    :connection-timeout 30000}))

(defn close-pool! [ds]
  (hikari/close-datasource ds))

(defn execute! [ds sql-params]
  (jdbc/execute! ds sql-params {:return-keys true}))

(defn execute-one! [ds sql-params]
  (jdbc/execute-one! ds sql-params {:return-keys true}))

(defn query [ds sql-params]
  (jdbc/execute! ds sql-params))
