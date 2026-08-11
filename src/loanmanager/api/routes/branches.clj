(ns loanmanager.api.routes.branches
  (:require [loanmanager.db.branches :as branches-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.security.rbac :as rbac]))

(def BranchCreate
  [:map
   [:code   [:string {:min 2 :max 50}]]
   [:name   [:string {:min 2 :max 200}]]
   [:region {:optional true} :string]
   [:parent-id {:optional true} :string]])

(def BranchUpdate
  [:map
   [:name      {:optional true} [:string {:min 2 :max 200}]]
   [:region    {:optional true} :string]
   [:active    {:optional true} :boolean]])

(defn routes [ds]
  [["/branches"
    {:get  {:summary    "List all branches for the tenant"
            :tags       ["Branches"]
            :handler    (fn [{:keys [identity tenant-id]}]
                          (rbac/require-permission identity :customer/read)
                          {:status 200
                           :body   (branches-db/list-branches ds tenant-id)})}

     :post {:summary    "Create a new branch"
            :tags       ["Branches"]
            :parameters {:body BranchCreate}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :user/manage)
                          (let [branch (branches-db/create! ds
                                         (cond-> (assoc body-params :tenant-id tenant-id)
                                           (:parent-id body-params)
                                           (update :parent-id parse-uuid)))]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "branch.created"
                                            :entity-type "branch"
                                            :entity-id   (:branches/id branch)
                                            :after-state body-params})
                            {:status 201 :body branch}))}}]

   ["/branches/:id"
    {:get {:summary    "Get branch by ID"
           :tags       ["Branches"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :customer/read)
                         (if-let [b (branches-db/find-by-id ds tenant-id (parse-uuid (:id path-params)))]
                           {:status 200 :body b}
                           {:status 404 :body {:error "Branch not found"}}))}

     :put {:summary    "Update a branch"
           :tags       ["Branches"]
           :parameters {:path [:map [:id :string]]
                        :body BranchUpdate}
           :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                         (rbac/require-permission identity :user/manage)
                         (let [id      (parse-uuid (:id path-params))
                               updated (branches-db/update! ds tenant-id id body-params)]
                           (audit/log! ds {:tenant-id   tenant-id
                                           :user-id     (:user-id identity)
                                           :action      "branch.updated"
                                           :entity-type "branch"
                                           :entity-id   id
                                           :after-state body-params})
                           {:status 200 :body updated}))}}]])
