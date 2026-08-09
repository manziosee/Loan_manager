(ns loanmanager.security.jwt
  (:require [buddy.sign.jwt :as jwt]
            [clojure.tools.logging :as log]
            [tick.core :as t]))

(defn- claims [user expiry-hours]
  {:sub       (str (:id user))
   :tenant-id (str (:tenant-id user))
   :role      (str (:role-name user))
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

(defn token->identity [token config]
  (let [{:keys [ok claims]} (verify-token token config)]
    (when ok
      {:user-id   (java.util.UUID/fromString (:sub claims))
       :tenant-id (java.util.UUID/fromString (:tenant-id claims))
       :role      (keyword (:role claims))
       :email     (:email claims)})))
