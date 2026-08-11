(ns user
  "REPL development utilities.
   Start with: clojure -M:dev  then  (start!)"
  (:require [loanmanager.core :as core]
            [loanmanager.db.connection :as db]
            [loanmanager.db.migrations :as migrations]
            [loanmanager.events.bus :as events]
            [clojure.tools.logging :as log]))

;; ── Lifecycle ─────────────────────────────────────────────────────────────────

(defn start! []
  (core/start!)
  (println "LoanOS started. Swagger UI → http://localhost:8080/swagger-ui"))

(defn stop! []
  (core/stop!)
  (println "LoanOS stopped."))

(defn restart! []
  (stop!)
  (start!))

;; ── DB helpers ────────────────────────────────────────────────────────────────

(defn migrate! []
  (let [ds (get-in @core/state [:ds])]
    (migrations/migrate! ds)))

(defn rollback! []
  (let [ds (get-in @core/state [:ds])]
    (migrations/rollback! ds)))

;; ── Event helpers ─────────────────────────────────────────────────────────────

(defn publish-test-event! [event-type payload]
  (when-let [bus (get-in @core/state [:bus])]
    (events/publish! bus (assoc payload :event-type event-type))))

;; ── System state ──────────────────────────────────────────────────────────────

(defn system [] @core/state)

(defn ds [] (:ds @core/state))
