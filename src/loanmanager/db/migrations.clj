(ns loanmanager.db.migrations
  (:require [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.repl :as ragtime-repl]
            [clojure.tools.logging :as log]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [loanmanager.db.connection :as db])
  (:gen-class))

(defn migration-config [ds]
  {:datastore  (ragtime-jdbc/sql-database ds)
   :migrations (ragtime-jdbc/load-resources "migrations")})

(defn migrate! [ds]
  (log/info "Running DB migrations...")
  (ragtime-repl/migrate (migration-config ds))
  (log/info "Migrations complete."))

(defn rollback! [ds]
  (ragtime-repl/rollback (migration-config ds)))

(defn -main
  "Standalone migration runner for `clojure -M:migrate` (and `make migrate`).
   Pass \"rollback\" as the first arg to roll back the last migration instead."
  [& args]
  (let [config (aero/read-config (io/resource "config.edn"))
        ds     (db/init-pool! (:database config))]
    (try
      (if (= "rollback" (first args))
        (rollback! ds)
        (migrate! ds))
      (finally
        (db/close-pool! ds)))))
