(ns loanmanager.db.migrations
  (:require [ragtime.jdbc :as ragtime-jdbc]
            [ragtime.next-jdbc :as ragtime-next]
            [ragtime.repl :as ragtime-repl]
            [clojure.tools.logging :as log]))

(defn migration-config [ds]
  {:datastore  (ragtime-next/sql-database ds)
   :migrations (ragtime-jdbc/load-resources "migrations")})

(defn migrate! [ds]
  (log/info "Running DB migrations...")
  (ragtime-repl/migrate (migration-config ds))
  (log/info "Migrations complete."))

(defn rollback! [ds]
  (ragtime-repl/rollback (migration-config ds)))
