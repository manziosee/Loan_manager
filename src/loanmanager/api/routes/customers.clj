(ns loanmanager.api.routes.customers
  (:require [loanmanager.api.schemas :as schemas]
            [loanmanager.db.customers :as customers-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.security.rbac :as rbac]))

(defn- next-customer-no []
  (str "CUS-" (System/currentTimeMillis)))

(defn routes [ds]
  [["/customers"
    {:get  {:summary    "Search customers"
            :tags       ["Customers"]
            :parameters {:query [:map
                                 [:q      {:optional true} :string]
                                 [:limit  {:optional true} :int]
                                 [:offset {:optional true} :int]]}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :customer/read)
                          {:status 200
                           :body   (customers-db/search ds tenant-id query-params)})}

     :post {:summary    "Create a new customer"
            :tags       ["Customers"]
            :parameters {:body schemas/CustomerCreate}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :customer/create)
                          (let [customer (customers-db/create!
                                          ds
                                          (assoc body-params
                                                 :tenant-id   tenant-id
                                                 :customer-no (next-customer-no)
                                                 :created-by  (:user-id identity)))]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "customer.created"
                                            :entity-type "customer"
                                            :entity-id   (:customers/id customer)
                                            :after-state customer})
                            {:status 201 :body customer}))}}]

   ["/customers/:id"
    {:get {:summary    "Get customer by ID"
           :tags       ["Customers"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :customer/read)
                         (if-let [c (customers-db/find-by-id ds tenant-id
                                                              (parse-uuid (:id path-params)))]
                           {:status 200 :body c}
                           {:status 404 :body {:error "Customer not found"}}))}

     :put {:summary    "Update customer"
           :tags       ["Customers"]
           :parameters {:path [:map [:id :string]]
                        :body schemas/CustomerCreate}
           :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                         (rbac/require-permission identity :customer/update)
                         (let [id  (parse-uuid (:id path-params))
                               old (customers-db/find-by-id ds tenant-id id)
                               upd (customers-db/update! ds tenant-id id body-params)]
                           (audit/log! ds {:tenant-id    tenant-id
                                           :user-id      (:user-id identity)
                                           :action       "customer.updated"
                                           :entity-type  "customer"
                                           :entity-id    id
                                           :before-state old
                                           :after-state  upd})
                           {:status 200 :body upd}))}}]])
