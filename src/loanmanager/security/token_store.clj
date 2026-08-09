(ns loanmanager.security.token-store
  "In-memory JWT blacklist. Tokens are stored by their jti/sub+exp key
   and pruned lazily on each check to avoid unbounded growth.")

(defonce ^:private blacklist (atom {}))

(defn blacklist! [token-key expires-at]
  (swap! blacklist assoc token-key expires-at))

(defn blacklisted? [token-key]
  (let [now   (System/currentTimeMillis)
        entry (get @blacklist token-key)]
    (when entry
      ;; prune expired entries lazily
      (when (< now entry)
        true))))

(defn- prune! []
  (let [now (System/currentTimeMillis)]
    (swap! blacklist (fn [m] (into {} (filter #(> (val %) now) m))))))

(defn blacklist-token!
  "Blacklist a decoded claims map until its :exp timestamp."
  [{:keys [sub exp]}]
  (let [exp-ms (long (* exp 1000))]
    (prune!)
    (blacklist! sub exp-ms)))
