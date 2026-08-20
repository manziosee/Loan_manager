(ns loanmanager.security.jwt
  (:require [buddy.sign.jwt :as jwt]
            [clojure.tools.logging :as log]
            [tick.core :as t]))

(defn- new-jti [] (str (java.util.UUID/randomUUID)))

(defn- claims [user expiry-hours]
  {:sub       (str (:id user))
   :jti       (new-jti)
   :tenant-id (str (:tenant-id user))
   :role      (str (:role-name user))
   :branch-id (some-> (:branch-id user) str)
   :email     (:email user)
   :exp       (-> (t/now) (t/>> (t/new-duration expiry-hours :hours)) t/inst)
   :iat       (t/inst (t/now))})

(defn generate-token [user {:keys [jwt-secret jwt-expiry-hours]}]
  (jwt/sign (claims user jwt-expiry-hours) jwt-secret {:alg :hs256}))

(defn verify-token [token {:keys [jwt-secret]}]
  (try
    {:ok true :claims (jwt/unsign token jwt-secret {:alg :hs256})}
    (catch Exception e
      (log/debug "JWT verification failed:" (.getMessage e))
      {:ok false :error (.getMessage e)})))

(defn token->identity
  "Returns identity map + raw :claims for blacklist checking."
  [token config]
  (let [{:keys [ok claims]} (verify-token token config)]
    (when ok
      {:user-id   (java.util.UUID/fromString (:sub claims))
       :tenant-id (java.util.UUID/fromString (:tenant-id claims))
       :role      (keyword (:role claims))
       :branch-id (some-> (:branch-id claims) java.util.UUID/fromString)
       :email     (:email claims)
       :claims    claims})))

(defn generate-refresh-token
  "Returns {:token ... :jti ... :expires-at ...} rather than a bare token
   string — callers persist :jti/:expires-at (loanmanager.db.tokens) so the
   refresh token can be individually revoked on logout or bulk-revoked on
   password change, instead of remaining a stateless, unrevocable JWT for
   its full 30-day life."
  [user {:keys [jwt-secret]}]
  (let [jti        (new-jti)
        expires-at (-> (t/now) (t/>> (t/new-duration 30 :days)) t/inst)]
    {:token      (jwt/sign {:sub       (str (:id user))
                             :jti       jti
                             :tenant-id (str (:tenant-id user))
                             :type      "refresh"
                             :exp       expires-at
                             :iat       (t/inst (t/now))}
                            jwt-secret {:alg :hs256})
     :jti        jti
     :expires-at expires-at}))
