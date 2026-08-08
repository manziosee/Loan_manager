(ns loanmanager.api.routes.audit
  (:require [loanmanager.db.audit :as audit-db]
            [loanmanager.security.rbac :as rbac]))

(defn routes [ds]
  [["/audit"
    ["/entities/:type/:id"
     {:get {:summary    "Get audit history for an entity"
            :tags       ["Audit"]
            :parameters {:path [:map [:type :string] [:id :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params]}]
                          (rbac/require-permission identity :audit/read)
                          {:status 200
                           :body   (audit-db/entity-history
                                     ds tenant-id
                                     (:type path-params)
                                     (parse-uuid (:id path-params)))})}}]

    ["/users/:id/activity"
     {:get {:summary    "Get activity log for a user"
            :tags       ["Audit"]
            :parameters {:path  [:map [:id :string]]
                         :query [:map [:limit {:optional true} :int]]}
            :handler    (fn [{:keys [identity tenant-id path-params query-params]}]
                          (rbac/require-permission identity :audit/read)
                          {:status 200
                           :body   (audit-db/user-activity
                                     ds tenant-id
                                     (parse-uuid (:id path-params))
                                     query-params)})}}]]])
