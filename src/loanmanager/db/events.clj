(ns loanmanager.db.events
  "Persists domain events to the domain_events table — event sourcing for
   the loan/payment lifecycle. Previously this table was pure schema with
   nothing ever writing to it; the event bus was pub/sub only, so there
   was no durable record of what happened, only whatever handlers reacted
   in the moment."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn- stringify-non-json
  "clojure.data.json doesn't know how to write java.util.UUID or
   java.time.Instant — both show up routinely in event payloads
   (:tenant-id, :loan-id, :occurred-at, ...) — so convert them to strings
   before the payload map is handed to the jsonb writer."
  [m]
  (into {}
        (map (fn [[k v]]
               [k (cond
                    (instance? java.util.UUID v)     (str v)
                    (instance? java.time.Instant v)  (str v)
                    :else                             v)]))
        m))

(defn log! [ds {:keys [event-type aggregate-type aggregate-id tenant-id] :as event}]
  (when (and aggregate-type aggregate-id)
    (db/execute-one! ds
      (sql/format
        {:insert-into :domain-events
         :values      [{:tenant-id      tenant-id
                         :event-type     (name event-type)
                         :aggregate-type (name aggregate-type)
                         :aggregate-id   aggregate-id
                         :payload        [:lift (stringify-non-json (dissoc event :event-type))]}]
         :returning   [:id]}))))

(defn for-aggregate
  "Full event history for one aggregate (e.g. all events for a given loan) —
   this is the actual point of event sourcing: reconstructing what happened
   to something over time, not just its current row."
  [ds tenant-id aggregate-type aggregate-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:domain-events]
                 :where    [:and [:= :tenant-id tenant-id]
                                 [:= :aggregate-type (name aggregate-type)]
                                 [:= :aggregate-id aggregate-id]]
                 :order-by [[:occurred-at :asc]]})))

(defn recent [ds tenant-id {:keys [limit offset] :or {limit 50 offset 0}}]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:domain-events]
                 :where    [:= :tenant-id tenant-id]
                 :order-by [[:occurred-at :desc]]
                 :limit    limit
                 :offset   offset})))
