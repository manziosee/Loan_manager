(ns loanmanager.api.routes.products
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.security.rbac :as rbac]))

(defn routes [ds]
  [["/loan-products"
    {:get  {:summary "List all active loan products"
            :tags    ["Loan Products"]
            :handler (fn [{:keys [identity tenant-id]}]
                       (rbac/require-permission identity :loan/read)
                       {:status 200
                        :body   (jdbc/execute! ds
                                  (sql/format {:select [:*]
                                               :from   [:loan-products]
                                               :where  [:and
                                                        [:= :tenant-id tenant-id]
                                                        [:= :active true]]
                                               :order-by [[:name :asc]]}))})}

     :post {:summary    "Create a new loan product"
            :tags       ["Loan Products"]
            :parameters {:body schemas/LoanProductCreate}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :admin)
                          (let [product (jdbc/execute-one! ds
                                          (sql/format {:insert-into :loan-products
                                                       :values      [(assoc body-params
                                                                            :tenant-id  tenant-id
                                                                            :created-by (:user-id identity))]
                                                       :returning   [:*]}))]
                            {:status 201 :body product}))}}]

   ["/loan-products/:id"
    {:get    {:summary    "Get loan product by ID"
              :tags       ["Loan Products"]
              :parameters {:path [:map [:id :string]]}
              :handler    (fn [{:keys [identity tenant-id path-params]}]
                            (rbac/require-permission identity :loan/read)
                            (if-let [p (jdbc/execute-one! ds
                                         (sql/format {:select [:*]
                                                      :from   [:loan-products]
                                                      :where  [:and
                                                               [:= :tenant-id tenant-id]
                                                               [:= :id (parse-uuid (:id path-params))]]}))]
                              {:status 200 :body p}
                              {:status 404 :body {:error "Product not found"}}))}

     :put    {:summary    "Update a loan product"
              :tags       ["Loan Products"]
              :parameters {:path [:map [:id :string]]
                           :body schemas/LoanProductUpdate}
              :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                            (rbac/require-permission identity :admin)
                            (if-let [updated (jdbc/execute-one! ds
                                               (sql/format {:update    :loan-products
                                                            :set       (assoc body-params :updated-at [:now])
                                                            :where     [:and
                                                                        [:= :tenant-id tenant-id]
                                                                        [:= :id (parse-uuid (:id path-params))]]
                                                            :returning [:*]}))]
                              {:status 200 :body updated}
                              {:status 404 :body {:error "Product not found"}}))}

     :delete {:summary    "Deactivate a loan product (soft delete)"
              :tags       ["Loan Products"]
              :parameters {:path [:map [:id :string]]}
              :handler    (fn [{:keys [identity tenant-id path-params]}]
                            (rbac/require-permission identity :admin)
                            (jdbc/execute-one! ds
                              (sql/format {:update :loan-products
                                           :set    {:active false :updated-at [:now]}
                                           :where  [:and
                                                    [:= :tenant-id tenant-id]
                                                    [:= :id (parse-uuid (:id path-params))]]}))
                            {:status 204})}}]])
