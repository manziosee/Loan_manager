(ns loanmanager.events.bus
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defonce ^:private subscribers (atom {}))

(defn start! [{:keys [buffer-size] :or {buffer-size 1024}}]
  (let [ch (async/chan buffer-size)]
    (async/go-loop []
      (when-let [event (async/<! ch)]
        (let [handlers (get @subscribers (:event-type event) [])]
          (doseq [h handlers]
            (try (h event)
                 (catch Exception e
                   (log/error e "Event handler error" (:event-type event))))))
        (recur)))
    {:channel ch}))

(defn stop! [{:keys [channel]}]
  (async/close! channel))

(defn publish! [{:keys [channel]} event]
  (async/put! channel (assoc event :occurred-at (java.time.Instant/now))))

(defn subscribe! [event-type handler-fn]
  (swap! subscribers update event-type (fnil conj []) handler-fn))

;; ── Event type constants ──────────────────────────────────────────────────────
(def LOAN-APPLICATION-SUBMITTED :loan/application-submitted)
(def LOAN-APPROVED               :loan/approved)
(def LOAN-REJECTED               :loan/rejected)
(def LOAN-DISBURSED              :loan/disbursed)
(def PAYMENT-RECEIVED            :loan/payment-received)
(def LOAN-OVERDUE                :loan/overdue)
(def LOAN-RESTRUCTURED           :loan/restructured)
(def LOAN-CLOSED                 :loan/closed)
