(ns loanmanager.security.token-store
  "JWT blacklist backed by in-memory atom (dev) or DB (prod).
   Uses :jti claim for per-token revocation — not :sub.")

(defonce ^:private blacklist (atom {}))

(defn blacklist! [jti expires-at-ms]
  (swap! blacklist assoc jti expires-at-ms))

(defn blacklisted? [jti]
  (when-let [exp (get @blacklist jti)]
    (< (System/currentTimeMillis) exp)))

(defn- prune! []
  (let [now (System/currentTimeMillis)]
    (swap! blacklist (fn [m] (into {} (filter #(> (val %) now) m))))))

(defn blacklist-token!
  "Blacklist a decoded claims map by its :jti until :exp."
  [{:keys [jti exp]}]
  (when jti
    (prune!)
    (blacklist! jti (long (* exp 1000)))))
