(ns loanmanager.db.migrations
  (:require [ragtime.jdbc :as ragtime-jdbc]
            [ragtime.repl :as ragtime]
            [clojure.tools.logging :as log]))

(defn migration-config [ds]
  {:datastore  (ragtime-jdbc/sql-database ds)
   :migrations (ragtime-jdbc/load-resources "migrations")})

(defn run! [ds]
  (log/info "Running DB migrations...")
  (ragtime/migrate (migration-config ds))
  (log/info "Migrations complete."))

(defn rollback! [ds]
  (ragtime/rollback (migration-config ds)))
