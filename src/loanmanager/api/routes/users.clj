(ns loanmanager.api.routes.users
  (:require [buddy.hashers :as hashers]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.db.users :as users-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.security.rbac :as rbac]))

(defn routes [ds]
  [["/users"
    {:get  {:summary    "List users in the tenant"
            :tags       ["Users"]
            :parameters {:query [:map
                                 [:role   {:optional true} :string]
                                 [:active {:optional true} :boolean]
                                 [:limit  {:optional true} :int]
                                 [:offset {:optional true} :int]]}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :user/read)
                          {:status 200
                           :body   (users-db/list-users ds tenant-id query-params)})}

     :post {:summary    "Create a new user"
            :tags       ["Users"]
            :parameters {:body schemas/UserCreate}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :user/manage)
                          (let [{:keys [email full-name password role-id branch-id]} body-params
                                user (users-db/create! ds
                                       {:tenant-id     tenant-id
                                        :email         email
                                        :full-name     full-name
                                        :password-hash (hashers/derive password)
                                        :role-id       (parse-uuid role-id)
                                        :branch-id     (some-> branch-id parse-uuid)
                                        :active        true})]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "user.created"
                                            :entity-type "user"
                                            :entity-id   (:users/id user)
                                            :after-state {:email email :role-id role-id}})
                            {:status 201 :body user}))}}]

   ["/users/:id"
    {:get {:summary    "Get user by ID"
           :tags       ["Users"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :user/read)
                         (if-let [user (users-db/find-by-id ds tenant-id
                                                             (parse-uuid (:id path-params)))]
                           {:status 200 :body user}
                           {:status 404 :body {:error "User not found"}}))}

     :put {:summary    "Update user role or active status"
           :tags       ["Users"]
           :parameters {:path [:map [:id :string]]
                        :body schemas/UserUpdate}
           :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                         (rbac/require-permission identity :user/manage)
                         (let [id  (parse-uuid (:id path-params))
                               old (users-db/find-by-id ds tenant-id id)
                               upd (users-db/update! ds tenant-id id
                                     (cond-> {}
                                       (:full-name body-params) (assoc :full-name (:full-name body-params))
                                       (:role-id   body-params) (assoc :role-id (parse-uuid (:role-id body-params)))
                                       (some? (:active body-params)) (assoc :active (:active body-params))))]
                           (audit/log! ds {:tenant-id    tenant-id
                                           :user-id      (:user-id identity)
                                           :action       "user.updated"
                                           :entity-type  "user"
                                           :entity-id    id
                                           :before-state {:role-id (:users/role-id old) :active (:users/active old)}
                                           :after-state  body-params})
                           {:status 200 :body upd}))}}]

   ["/users/:id/reset-password"
    {:post {:summary    "Admin: reset a user's password"
            :tags       ["Users"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/ResetPasswordRequest}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :user/manage)
                          (let [id (parse-uuid (:id path-params))]
                            (users-db/update! ds tenant-id id
                              {:password-hash (hashers/derive (:new-password body-params))})
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "user.password-reset"
                                            :entity-type "user"
                                            :entity-id   id})
                            {:status 200 :body {:message "Password reset successfully"}}))}}]])
