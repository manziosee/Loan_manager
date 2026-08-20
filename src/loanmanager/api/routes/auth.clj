(ns loanmanager.api.routes.auth
  (:require [buddy.hashers :as hashers]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.security.jwt :as jwt]
            [loanmanager.security.mfa :as mfa]
            [loanmanager.security.middleware :as sec]
            [loanmanager.security.token-store :as token-store]
            [loanmanager.security.reset-token :as reset-token]
            [loanmanager.db.audit :as audit]
            [loanmanager.db.users :as users-db]
            [loanmanager.db.security :as security-db]
            [loanmanager.db.tokens :as tokens-db]
            [loanmanager.db.password-reset :as password-reset-db]
            [loanmanager.notifications.email :as email]))

(defn- user-by-email [ds email]
  (jdbc/execute-one! ds
    (sql/format {:select [:u.* [:r.name :role-name] [:r.permissions :permissions]]
                 :from   [[:users :u]]
                 :join   [[:roles :r] [:= :u.role-id :r.id]]
                 :where  [:and [:= :u.email email] [:= :u.active true]]})))

(defn- issue-tokens [ds user sec-config expiry-hours]
  (let [claims-user  {:id        (:users/id user)
                       :tenant-id (:users/tenant-id user)
                       :role-name (:roles/role-name user)
                       :branch-id (:users/branch-id user)
                       :email     (:users/email user)}
        access-token (jwt/generate-token claims-user sec-config)
        refresh      (jwt/generate-refresh-token claims-user sec-config)]
    (tokens-db/issue-refresh! ds
      {:user-id    (:id claims-user)
       :token-hash (reset-token/hash-token (:token refresh))
       :expires-at (java.sql.Timestamp. (.getTime ^java.util.Date (:expires-at refresh)))})
    {:access-token  access-token
     :refresh-token (:token refresh)
     :token-type    "Bearer"
     :expires-in    (* expiry-hours 3600)}))

(defn routes [ds config]
  (let [sec-config (:security config)]
    [["/auth"
      ["/login"
       {:post {:summary    "Authenticate and receive JWT. If MFA is enabled on
                            the account, mfa-code is required in the same
                            request (no separate MFA step/session)."
               :tags       ["Authentication"]
               :parameters {:body schemas/LoginRequest}
               :responses  {200 {:body schemas/TokenResponse}
                            401 {:body [:map [:error :string]
                                        [:mfa-required {:optional true} :boolean]]}}
               :handler    (fn [{{:keys [email password mfa-code]} :body-params :as request}]
                             (if (security-db/rate-limited? ds email)
                               {:status 429
                                :body   {:error "Too many failed login attempts — try again in a few minutes"}}
                               (let [user (user-by-email ds email)
                                     ok?  (boolean (and user (hashers/check password (:users/password-hash user))))
                                     mfa-secret  (:users/mfa-secret user)
                                     mfa-ok?     (or (nil? mfa-secret) (mfa/valid-code? mfa-secret mfa-code))]
                                 (security-db/record-login-attempt! ds
                                   {:tenant-id  (:users/tenant-id user)
                                    :email      email
                                    :ip-address (sec/client-ip request)
                                    :success    (and ok? mfa-ok?)})
                                 (cond
                                   (not ok?)
                                   {:status 401 :body {:error "Invalid credentials"}}

                                   (not mfa-ok?)
                                   {:status 401 :body {:error "Invalid or missing MFA code" :mfa-required true}}

                                   :else
                                   (do
                                     (audit/log! ds {:tenant-id   (:users/tenant-id user)
                                                     :user-id     (:users/id user)
                                                     :action      "user.login"
                                                     :entity-type "user"
                                                     :entity-id   (:users/id user)})
                                     {:status 200
                                      :body   (issue-tokens ds user sec-config
                                                (get-in config [:security :jwt-expiry-hours]))})))))}}]

      ["/mfa/enroll"
       {:post {:summary    "Start MFA enrollment — returns a new secret + QR
                            enrollment URI. Not yet active: call /mfa/confirm
                            with a code generated from it to turn MFA on."
               :tags       ["Authentication"]
               :middleware [[sec/wrap-authentication ds sec-config]
                            sec/wrap-require-auth]
               :handler    (fn [{:keys [identity]}]
                             (let [secret (mfa/generate-secret)]
                               {:status 200
                                :body   {:secret      secret
                                         :otpauth-uri (mfa/otpauth-uri secret (:email identity) "LoanOS")}}))}}]

      ["/mfa/confirm"
       {:post {:summary    "Confirm MFA enrollment by proving you can generate
                            a valid code from the secret returned by /mfa/enroll."
               :tags       ["Authentication"]
               :middleware [[sec/wrap-authentication ds sec-config]
                            sec/wrap-require-auth]
               :parameters {:body [:map [:secret :string] [:code [:string {:min 6 :max 6}]]]}
               :handler    (fn [{:keys [identity body-params]}]
                             (if (mfa/valid-code? (:secret body-params) (:code body-params))
                               (do
                                 (users-db/update! ds (:tenant-id identity) (:user-id identity)
                                   {:mfa-secret (:secret body-params)})
                                 {:status 200 :body {:message "MFA enabled."}})
                               {:status 422 :body {:error "Invalid code — check your authenticator app and try again."}}))}}]

      ["/mfa/disable"
       {:post {:summary    "Disable MFA (requires current password)"
               :tags       ["Authentication"]
               :middleware [[sec/wrap-authentication ds sec-config]
                            sec/wrap-require-auth]
               :parameters {:body [:map [:password :string]]}
               :handler    (fn [{:keys [identity body-params]}]
                             (let [user (user-by-email ds (:email identity))]
                               (if (hashers/check (:password body-params) (:users/password-hash user))
                                 (do
                                   (users-db/update! ds (:tenant-id identity) (:user-id identity)
                                     {:mfa-secret nil})
                                   {:status 200 :body {:message "MFA disabled."}})
                                 {:status 422 :body {:error "Incorrect password"}})))}}]

      ["/logout"
       {:post {:summary    "Invalidate the current access token. If the paired
                            refresh token is included in the request body, it
                            is revoked too — otherwise it stays valid until it
                            naturally expires (30 days)."
               :tags       ["Authentication"]
               :middleware [[sec/wrap-authentication ds sec-config]
                            sec/wrap-require-auth]
               :parameters {:body [:maybe [:map [:refresh-token {:optional true} :string]]]}
               :handler    (fn [{:keys [identity body-params]}]
                             (token-store/blacklist-token! ds (:claims identity))
                             (when-let [rt (:refresh-token body-params)]
                               (let [{:keys [ok claims]} (jwt/verify-token rt sec-config)]
                                 (when (and ok (= "refresh" (:type claims)))
                                   (tokens-db/revoke-refresh! ds (reset-token/hash-token rt)))))
                             {:status 200 :body {:message "Logged out"}})}}]

      ["/refresh"
       {:post {:summary    "Exchange a refresh token for a new access token"
               :tags       ["Authentication"]
               :parameters {:body schemas/RefreshRequest}
               :handler    (fn [{{:keys [refresh-token]} :body-params}]
                             (let [{:keys [ok claims]} (jwt/verify-token refresh-token sec-config)
                                   valid? (and ok (= "refresh" (:type claims))
                                               (tokens-db/refresh-valid? ds
                                                 (reset-token/hash-token refresh-token)))]
                               (if valid?
                                 (let [user (users-db/find-by-id ds
                                              (java.util.UUID/fromString (:tenant-id claims))
                                              (java.util.UUID/fromString (:sub claims)))]
                                   (if user
                                     {:status 200
                                      :body   {:access-token (jwt/generate-token
                                                               {:id        (:users/id user)
                                                                :tenant-id (:users/tenant-id user)
                                                                :role-name (:roles/role-name user)
                                                                :branch-id (:users/branch-id user)
                                                                :email     (:users/email user)}
                                                               sec-config)
                                               :token-type   "Bearer"
                                               :expires-in   (* (get-in config [:security :jwt-expiry-hours]) 3600)}}
                                     {:status 401 :body {:error "User not found"}}))
                                 {:status 401 :body {:error "Invalid or expired refresh token"}})))}}]

      ["/change-password"
       {:post {:summary    "Change your own password. Revokes every refresh
                            token issued to this account — standard practice
                            so a password change ends every other session,
                            not just the current one."
               :tags       ["Authentication"]
               :middleware [[sec/wrap-authentication ds sec-config]
                            sec/wrap-require-auth]
               :parameters {:body schemas/ChangePasswordRequest}
               :handler    (fn [{:keys [identity body-params]}]
                             (let [user (user-by-email ds (:email identity))]
                               (if (hashers/check (:current-password body-params)
                                                  (:users/password-hash user))
                                 (do
                                   (users-db/update! ds (:tenant-id identity) (:user-id identity)
                                     {:password-hash (hashers/derive (:new-password body-params))})
                                   (token-store/blacklist-token! ds (:claims identity))
                                   (tokens-db/revoke-all-refresh-for-user! ds (:user-id identity))
                                   {:status 200 :body {:message "Password changed. Please log in again."}})
                                 {:status 422 :body {:error "Current password is incorrect"}})))}}]

      ["/forgot-password"
       {:post {:summary    "Request a password reset email. Always returns 200
                            regardless of whether the email is registered, to
                            avoid leaking which addresses have accounts."
               :tags       ["Authentication"]
               :parameters {:body schemas/ForgotPasswordRequest}
               :handler    (fn [{{:keys [email]} :body-params}]
                             (when-let [user (user-by-email ds email)]
                               (let [token (reset-token/generate)
                                     hash  (reset-token/hash-token token)]
                                 (password-reset-db/create! ds
                                   {:user-id    (:users/id user)
                                    :tenant-id  (:users/tenant-id user)
                                    :token-hash hash
                                    :expires-at (java.sql.Timestamp/from
                                                  (.plusSeconds (java.time.Instant/now) 1800))})
                                 (email/send! (:email config)
                                   {:to      (:users/email user)
                                    :subject "LoanOS password reset"
                                    :body    (str "Use this code to reset your password "
                                                  "(expires in 30 minutes):\n\n" token)})))
                             {:status 200
                              :body   {:message "If that email is registered, a reset code has been sent."}})}}]

      ["/reset-password"
       {:post {:summary    "Complete a password reset using the code from
                            /auth/forgot-password. Revokes every refresh
                            token for the account, same as change-password."
               :tags       ["Authentication"]
               :parameters {:body schemas/ResetPasswordConfirm}
               :handler    (fn [{{:keys [token new-password]} :body-params}]
                             (let [hash   (reset-token/hash-token token)
                                   record (password-reset-db/find-valid ds hash)]
                               (if-not record
                                 {:status 422 :body {:error "Invalid or expired reset code"}}
                                 (let [user-id   (:password-reset-tokens/user-id record)
                                       tenant-id (:password-reset-tokens/tenant-id record)]
                                   (users-db/update! ds tenant-id user-id
                                     {:password-hash (hashers/derive new-password)})
                                   (password-reset-db/mark-used! ds (:password-reset-tokens/id record))
                                   (tokens-db/revoke-all-refresh-for-user! ds user-id)
                                   {:status 200 :body {:message "Password reset. Please log in."}}))))}}]]]))
