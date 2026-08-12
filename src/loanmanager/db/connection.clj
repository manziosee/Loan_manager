(ns loanmanager.db.connection
  (:require [hikari-cp.core :as hikari]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.prepare :as prepare]
            [clojure.data.json :as json])
  (:import [org.postgresql.util PGobject]
           [java.sql PreparedStatement]))

;; ── JSONB <-> Clojure data ──────────────────────────────────────────────────
;; next.jdbc has no built-in JSON support: reads return a raw PGobject, and
;; writing a Clojure map/vector into a jsonb column fails outright. Every
;; jsonb column in this schema (customers.address/metadata, loan_products
;; .approval_rules, loan_applications.score_breakdown/fraud_flags
;; /workflow_state, ...) needs both directions handled transparently.

(defn- ->pgobject [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str x))))

(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [^PGobject v _label]
    (if (#{"json" "jsonb"} (.getType v))
      (some-> (.getValue v) (json/read-str :key-fn keyword))
      v))
  (read-column-by-index [^PGobject v _rsmeta _idx]
    (if (#{"json" "jsonb"} (.getType v))
      (some-> (.getValue v) (json/read-str :key-fn keyword))
      v)))

(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement ps ^long i]
    (.setObject ps i (->pgobject m)))
  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement ps ^long i]
    (.setObject ps i (->pgobject v))))

(defn init-pool! [{:keys [jdbc-url username password pool-size]}]
  (hikari/make-datasource
   {:jdbc-url        jdbc-url
    :username        username
    :password        password
    :maximum-pool-size pool-size
    :minimum-idle    2
    :connection-timeout 30000}))

(defn close-pool! [ds]
  (hikari/close-datasource ds))

(defn with-kebab-keys
  "Wraps a raw datasource so every query through it returns rows with
   kebab-case keys (:users/password-hash, not :users/password_hash) —
   next.jdbc does NOT do this by default; it preserves SQL column names
   verbatim. Every db.* namespace assumes kebab-case keys, so this must
   wrap the datasource actually handed to the app (queries, transactions),
   while db/init-pool!'s raw HikariDataSource is kept separately for
   close-pool! (which needs the concrete Hikari type)."
  [ds]
  (jdbc/with-options ds {:builder-fn rs/as-kebab-maps}))

(defn execute! [ds sql-params]
  (jdbc/execute! ds sql-params {:return-keys true}))

(defn execute-one! [ds sql-params]
  (jdbc/execute-one! ds sql-params {:return-keys true}))

(defn query [ds sql-params]
  (jdbc/execute! ds sql-params))
