 (ns loanmanager.api.routes.tenants
  "Tenant provisioning. Gated on :platform/manage — a platform-level
   permission held only by the :platform-admin role, deliberately never
   granted to a regular tenant's :admin (whose :all wildcard does not
   satisfy platform permissions; see security/rbac.clj). Previously gated
   on :user/manage, which any tenant's admin already had, letting any
   tenant list or provision sibling tenants."
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
                          (rbac/require-permission identity :platform/manage)
                          {:status 200 :body (tenants-db/list-tenants ds)})}

     :post {:summary    "Provision a new tenant (institution) — creates its
                         default roles, chart of accounts, and initial
                         admin user in one step."
            :tags       ["Tenants"]
            :parameters {:body TenantCreate}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :platform/manage)
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
                         (rbac/require-permission identity :platform/manage)
                         (if-let [t (tenants-db/find-tenant ds (parse-uuid (:id path-params)))]
                           {:status 200 :body t}
                           {:status 404 :body {:error "Tenant not found"}}))}}]])
