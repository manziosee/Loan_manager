(ns loanmanager.api.routes.customers
  (:require [ring.middleware.multipart-params :as multipart]
            [ring.util.response :as response]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.db.customers :as customers-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.security.rbac :as rbac]
            [loanmanager.storage.local :as storage]))

(defn- next-customer-no []
  (str "CUS-" (System/currentTimeMillis)))

(defn routes [ds upload-dir]
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
                           :body   (customers-db/search ds tenant-id
                                     (assoc query-params :branch-id (rbac/scope-branch-id identity)))})}

     :post {:summary    "Create a new customer"
            :tags       ["Customers"]
            :parameters {:body schemas/CustomerCreate}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :customer/create)
                          (let [customer (customers-db/create!
                                          ds
                                          (assoc body-params
                                                 :tenant-id   tenant-id
                                                 :branch-id   (:branch-id identity)
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
                           {:status 200 :body upd}))}}]

   ["/customers/:id/documents"
    {:get  {:summary    "List KYC documents on file for a customer"
            :tags       ["Customers"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params]}]
                          (rbac/require-permission identity :customer/read)
                          (let [id (parse-uuid (:id path-params))]
                            (if-not (customers-db/find-by-id ds tenant-id id)
                              {:status 404 :body {:error "Customer not found"}}
                              {:status 200 :body (customers-db/list-documents ds id)})))}

     :post {:summary    "Upload a KYC document. multipart/form-data fields:
                         file (required), doc-type, doc-number, issued-by,
                         issued-date (YYYY-MM-DD), expiry-date (YYYY-MM-DD)."
            :tags       ["Customers"]
            :parameters {:path [:map [:id :string]]}
            :middleware [multipart/wrap-multipart-params]
            :handler    (fn [{:keys [identity tenant-id path-params multipart-params]}]
                          (rbac/require-permission identity :customer/update)
                          (let [customer-id (parse-uuid (:id path-params))
                                customer    (customers-db/find-by-id ds tenant-id customer-id)
                                file-part   (get multipart-params "file")]
                            (cond
                              (not customer)
                              {:status 404 :body {:error "Customer not found"}}

                              (not (:tempfile file-part))
                              {:status 422 :body {:error "file is required"}}

                              :else
                              (let [storage-key (storage/store! upload-dir
                                                   {:tenant-id   tenant-id
                                                    :customer-id customer-id
                                                    :tempfile    (:tempfile file-part)
                                                    :filename    (:filename file-part)
                                                    :size        (:size file-part)})
                                    doc-type    (get multipart-params "doc-type")
                                    doc         (customers-db/add-document! ds
                                                  {:customer-id customer-id
                                                   :doc-type    doc-type
                                                   :doc-number  (get multipart-params "doc-number")
                                                   :issued-by   (get multipart-params "issued-by")
                                                   :issued-date (some-> (get multipart-params "issued-date")
                                                                        java.time.LocalDate/parse)
                                                   :expiry-date (some-> (get multipart-params "expiry-date")
                                                                        java.time.LocalDate/parse)
                                                   :file-url    storage-key})]
                                (audit/log! ds {:tenant-id   tenant-id
                                                :user-id     (:user-id identity)
                                                :action      "customer.document-uploaded"
                                                :entity-type "customer"
                                                :entity-id   customer-id
                                                :after-state {:doc-type doc-type}})
                                {:status 201 :body doc}))))}}]

   ["/customers/:id/documents/:doc-id/download"
    {:get {:summary    "Download a KYC document's stored file"
           :tags       ["Customers"]
           :parameters {:path [:map [:id :string] [:doc-id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :customer/read)
                         (let [customer-id (parse-uuid (:id path-params))
                               doc-id      (parse-uuid (:doc-id path-params))]
                           (if-not (customers-db/find-by-id ds tenant-id customer-id)
                             {:status 404 :body {:error "Customer not found"}}
                             (if-let [doc (customers-db/find-document ds customer-id doc-id)]
                               (response/file-response
                                 (str (storage/resolve-path upload-dir (:customer-documents/file-url doc))))
                               {:status 404 :body {:error "Document not found"}}))))}}]])
