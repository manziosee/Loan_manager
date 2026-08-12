(ns loanmanager.db.audit
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn- lift-maps
  "HoneySQL tries to interpret a raw Clojure map/vector value as a nested
   SQL clause (subquery, etc.) rather than an opaque parameter, which
   crashes format for jsonb columns like before_state/after_state. [:lift v]
   is HoneySQL's escape hatch telling it to pass v through untouched, so it
   reaches next.jdbc for JSONB conversion instead."
  [entry]
  (cond-> entry
    (:before-state entry) (update :before-state #(vector :lift %))
    (:after-state  entry) (update :after-state  #(vector :lift %))))

(defn log! [ds entry]
  (db/execute-one! ds
    (sql/format {:insert-into :audit-log
                 :values      [(-> entry lift-maps (assoc :occurred-at [:now]))]
                 :returning   [:id]})))

(defn entity-history [ds tenant-id entity-type entity-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:audit-log]
                 :where    [:and
                            [:= :tenant-id tenant-id]
                            [:= :entity-type (name entity-type)]
                            [:= :entity-id entity-id]]
                 :order-by [[:occurred-at :desc]]})))

(defn user-activity [ds tenant-id user-id {:keys [limit] :or {limit 50}}]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:audit-log]
                 :where    [:and [:= :tenant-id tenant-id] [:= :user-id user-id]]
                 :order-by [[:occurred-at :desc]]
                 :limit    limit})))
