(ns loanmanager.db.security
  "DB-backed login rate limiting. Replaces the old in-memory atom, which
   reset on every restart and couldn't work across more than one app
   instance — and which, in the previous version of this code, was never
   actually called from the login handler at all."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(def ^:private max-attempts 10)
(def ^:private window-minutes 15)

(defn record-login-attempt! [ds {:keys [tenant-id email ip-address success]}]
  (db/execute-one! ds
    (sql/format {:insert-into :login-attempts
                 :values      [{:tenant-id  tenant-id
                                 :email      email
                                 :ip-address ip-address
                                 :success    success}]
                 :returning   [:id]})))

(defn- recent-failed-count [ds email]
  (-> (jdbc/execute-one! ds
        (sql/format {:select [[[:count :id] :cnt]]
                     :from   [:login-attempts]
                     :where  [:and [:= :email email]
                                   [:= :success false]
                                   [:>= :attempted-at [:- [:now] [:raw (str "INTERVAL '" window-minutes " minutes'")]]]]}))
      vals first (or 0)))

(defn rate-limited? [ds email]
  (>= (recent-failed-count ds email) max-attempts))
