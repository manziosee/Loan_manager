(ns loanmanager.api.routes.health
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn routes [ds]
  [["/health"
    {:get {:summary  "Health check — liveness + DB connectivity"
           :tags     ["System"]
           :no-doc   false
           :handler  (fn [_]
                       (try
                         (jdbc/execute-one! ds (sql/format {:select [1]}))
                         {:status 200
                          :body   {:status   "ok"
                                   :db       "ok"
                                   :version  "1.0.0"
                                   :ts       (str (java.time.Instant/now))}}
                         (catch Exception _
                           {:status 503
                            :body   {:status "degraded"
                                     :db     "unreachable"}})))}}]

   ["/health/live"
    {:get {:summary "Liveness probe (no DB check)"
           :tags    ["System"]
           :handler (fn [_] {:status 200 :body {:status "ok"}})}}]

   ["/health/ready"
    {:get {:summary "Readiness probe — checks DB"
           :tags    ["System"]
           :handler (fn [_]
                      (try
                        (jdbc/execute-one! ds (sql/format {:select [1]}))
                        {:status 200 :body {:status "ready"}}
                        (catch Exception _
                          {:status 503 :body {:status "not-ready"}})))}}]])
