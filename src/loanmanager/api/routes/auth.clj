(ns loanmanager.api.routes.auth
  (:require [buddy.hashers :as hashers]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.security.jwt :as jwt]
            [loanmanager.security.middleware :as sec]
            [loanmanager.security.token-store :as token-store]
            [loanmanager.db.audit :as audit]
            [loanmanager.db.users :as users-db]))

(defn- user-by-email [ds email]
  (jdbc/execute-one! ds
    (sql/format {:select [:u.* [:r.name :role-name] [:r.permissions :permissions]]
                 :from   [[:users :u]]
                 :join   [[:roles :r] [:= :u.role-id :r.id]]
                 :where  [:and [:= :u.email email] [:= :u.active true]]})))

(defn routes [ds config]
  (let [sec-config (:security config)]
    [[\"/auth\"
      [\"/login\"
       {:post {:summary    "Authenticate and receive JWT"
               :tags       ["Authentication"]
               :parameters {:body schemas/LoginRequest}
               :responses  {200 {:body schemas/TokenResponse}
                            401 {:body [:map [:error :string]]}}
               :handler    (fn [{{:keys [email password]} :body-params}]
                             (let [user (user-by-email ds email)]
                               (if (and user (hashers/check password (:users/password-hash user)))
                                 (let [token         (jwt/generate-token user sec-config)
                                       refresh-token (jwt/generate-refresh-token user sec-config)]
                                   (audit/log! ds {:tenant-id   (:users/tenant-id user)
                                                   :user-id     (:users/id user)
                                                   :action      "user.login"
                                                   :entity-type "user"
                                                   :entity-id   (:users/id user)})
                                   {:status 200
                                    :body   {:access-token  token
                                             :refresh-token refresh-token
                                             :token-type    "Bearer"
                                             :expires-in    (* (get-in config [:security :jwt-expiry-hours]) 3600)}})
                                 {:status 401 :body {:error "Invalid credentials"}})))}}]

      [\"/logout"
       {:post {:summary    "Invalidate the current access token"
               :tags       ["Authentication"]
               :middleware [[sec/wrap-authentication sec-config]
                            sec/wrap-require-auth]
               :handler    (fn [{:keys [identity]}]
                             (token-store/blacklist-token! (:claims identity))
                             {:status 200 :body {:message "Logged out"}})}}]

      [\"/refresh"
       {:post {:summary    "Exchange a refresh token for a new access token"
               :tags       ["Authentication"]
               :parameters {:body schemas/RefreshRequest}
               :handler    (fn [{{:keys [refresh-token]} :body-params}]
                             (let [{:keys [ok claims]} (jwt/verify-token refresh-token sec-config)]
                               (if (and ok (= "refresh" (:type claims)))
                                 (let [user (users-db/find-by-id ds
                                              (java.util.UUID/fromString (:tenant-id claims))
                                              (java.util.UUID/fromString (:sub claims)))]
                                   (if user
                                     {:status 200
                                      :body   {:access-token (jwt/generate-token
                                                               {:id          (:users/id user)
                                                                :tenant-id   (:users/tenant-id user)
                                                                :role-name   (:users/role-name user)
                                                                :email       (:users/email user)}
                                                               sec-config)
                                               :token-type   "Bearer"
                                               :expires-in   (* (get-in config [:security :jwt-expiry-hours]) 3600)}}
                                     {:status 401 :body {:error "User not found"}}))
                                 {:status 401 :body {:error "Invalid or expired refresh token"}})))}}]

      [\"/change-password"
       {:post {:summary    "Change your own password"
               :tags       ["Authentication"]
               :middleware [[sec/wrap-authentication sec-config]
                            sec/wrap-require-auth]
               :parameters {:body schemas/ChangePasswordRequest}
               :handler    (fn [{:keys [identity body-params]}]
                             (let [user (user-by-email ds (:email identity))]
                               (if (hashers/check (:current-password body-params)
                                                  (:users/password-hash user))
                                 (do
                                   (users-db/update! ds (:tenant-id identity) (:user-id identity)
                                     {:password-hash (hashers/derive (:new-password body-params))})
                                   ;; invalidate current token so re-login is required
                                   (token-store/blacklist-token! (:claims identity))
                                   {:status 200 :body {:message "Password changed. Please log in again."}})
                                 {:status 422 :body {:error "Current password is incorrect"}})))}}]]]))
