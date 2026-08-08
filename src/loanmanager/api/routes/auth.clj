(ns loanmanager.api.routes.auth
  (:require [buddy.hashers :as hashers]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.security.jwt :as jwt]
            [loanmanager.db.audit :as audit]))

(defn login-handler [ds config]
  (fn [{{:keys [email password]} :body-params}]
    (let [user (jdbc/execute-one! ds
                 (sql/format {:select [:u.* [:r.name :role-name] [:r.permissions :permissions]]
                               :from   [[:users :u]]
                               :join   [[:roles :r] [:= :u.role-id :r.id]]
                               :where  [:and [:= :u.email email] [:= :u.active true]]}))]
      (if (and user (hashers/check password (:users/password-hash user)))
        (let [token (jwt/generate-token user (:security config))]
          (audit/log! ds {:tenant-id  (:users/tenant-id user)
                          :user-id    (:users/id user)
                          :action     "user.login"
                          :entity-type "user"
                          :entity-id  (:users/id user)})
          {:status 200
           :body   {:access-token token
                    :token-type   "Bearer"
                    :expires-in   (* (get-in config [:security :jwt-expiry-hours]) 3600)}})
        {:status 401
         :body   {:error "Invalid credentials"}}))))

(defn routes [ds config]
  [["/auth"
    ["/login"
     {:post {:summary    "Authenticate and receive JWT"
             :tags       ["Authentication"]
             :parameters {:body schemas/LoginRequest}
             :responses  {200 {:body schemas/TokenResponse}
                          401 {:body [:map [:error :string]]}}
             :handler    (login-handler ds config)}}]]])
