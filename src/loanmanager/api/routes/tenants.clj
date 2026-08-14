(ns loanmanager.api.routes.tenants
  "Tenant provisioning. Note: gated on :user/manage (which only :admin
   holds) for now, same as the rest of user administration — there's no
   separate platform-super-admin tier in this RBAC model yet, so any
   tenant's admin can currently provision a sibling tenant. Worth splitting
   out before this is used for real multi-institution hosting."
  (:require [loanmanager.db.tenants :as tenants-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.security.rbac :as rbac]))

(def TenantCreate
  [:map
   [:code            [:string {:min 2 :max 50}]]
   [:name            [:string {:min 2 :max 200}]]
   [:admin-email     [:string {:min 3 :max 255}]]
   [:admin-password  [:string {:min 8 :max 100}]]
   [:admin-full-name [:string {:min 2 :max 200}]]])

(defn routes [ds]
  [["/tenants"
    {:get  {:summary    "List tenants on this platform"
            :tags       ["Tenants"]
            :handler    (fn [{:keys [identity]}]
                          (rbac/require-permission identity :user/manage)
                          {:status 200 :body (tenants-db/list-tenants ds)})}

     :post {:summary    "Provision a new tenant (institution) — creates its
                         default roles, chart of accounts, and initial
                         admin user in one step."
            :tags       ["Tenants"]
            :parameters {:body TenantCreate}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :user/manage)
                          (let [result (tenants-db/provision! ds body-params)]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "tenant.provisioned"
                                            :entity-type "tenant"
                                            :entity-id   (:tenants/id (:tenant result))
                                            :after-state {:code (:code body-params)
                                                          :name (:name body-params)}})
                            {:status 201 :body result}))}}]

   ["/tenants/:id"
    {:get {:summary    "Get tenant by ID"
           :tags       ["Tenants"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity path-params]}]
                         (rbac/require-permission identity :user/manage)
                         (if-let [t (tenants-db/find-tenant ds (parse-uuid (:id path-params)))]
                           {:status 200 :body t}
                           {:status 404 :body {:error "Tenant not found"}}))}}]])
