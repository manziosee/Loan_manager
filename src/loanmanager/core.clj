(ns loanmanager.core
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

;; The defaults baked into resources/config.edn and docker-compose.yml exist
;; so local dev works with zero setup — but nothing previously stopped those
;; same defaults from silently reaching a real deployment. This is the one
;; place that distinction matters: refuse to boot rather than run production
;; traffic on a JWT secret or DB password anyone can read in this repo.
(def ^:private known-dev-defaults
  {[:security :jwt-secret]  "change-me-in-production-min-32-chars!!"
   [:database :password]    "postgres"})

(defn- assert-safe-config! [config]
  (when (= "production" (:env config))
    (doseq [[path default] known-dev-defaults]
      (when (= default (get-in config path))
        (throw (ex-info (str "Refusing to start with ENV=production while " path
                             " is still set to its development default. Set a real value.")
                        {:config-path path}))))
    (when (str/blank? (get-in config [:security :encryption-key]))
      (throw (ex-info "Refusing to start with ENV=production without :security :encryption-key (ENCRYPTION_KEY) set."
                      {:config-path [:security :encryption-key]})))))

(defn start! []
  (let [config (load-config)]
    (assert-safe-config! config)
    (log/info "Starting LoanOS...")
    (let [raw-ds  (db/init-pool! (:database config))
          _       (migrations/migrate! raw-ds)
          ds      (db/with-kebab-keys raw-ds)
          bus     (events/start! (:events config))
          _       (event-handlers/register! ds (:email config) bus)
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
