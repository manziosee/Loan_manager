(ns loanmanager.core
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [loanmanager.db.connection :as db]
            [loanmanager.db.migrations :as migrations]
            [loanmanager.events.bus :as events]
            [loanmanager.events.handlers :as event-handlers]
            [loanmanager.api.server :as server])
  (:gen-class))

(defonce state (atom {}))

(defn load-config []
  (aero/read-config (io/resource "config.edn")))

(defn start! []
  (let [config (load-config)]
    (log/info "Starting LoanOS...")
    (let [raw-ds  (db/init-pool! (:database config))
          _       (migrations/migrate! raw-ds)
          ds      (db/with-kebab-keys raw-ds)
          bus     (events/start! (:events config))
          _       (event-handlers/register! ds bus)
          handler (server/create-handler config ds bus)
          srv     (server/start-server! handler (get-in config [:server :port]))]
      (reset! state {:config config :raw-ds raw-ds :ds ds :bus bus :server srv})
      (log/info "LoanOS started on port" (get-in config [:server :port])))))

(defn stop! []
  (when-let [srv (:server @state)]
    (server/stop-server! srv))
  (when-let [raw-ds (:raw-ds @state)]
    (db/close-pool! raw-ds))
  (when-let [bus (:bus @state)]
    (events/stop! bus))
  (reset! state {})
  (log/info "LoanOS stopped."))

(defn -main [& _]
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop!))
  (start!))
