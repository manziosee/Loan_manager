(ns loanmanager.api.routes.audit
  (:require [loanmanager.db.audit :as audit-db]
            [loanmanager.db.events :as events-db]
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
                                     query-params)})}}]

    ["/events"
     {:get {:summary    "Recent domain events (event-sourced feed) across the tenant"
            :tags       ["Audit"]
            :parameters {:query [:map
                                 [:limit  {:optional true} :int]
                                 [:offset {:optional true} :int]]}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :audit/read)
                          {:status 200
                           :body   (events-db/recent ds tenant-id query-params)})}}]

    ["/events/:type/:id"
     {:get {:summary    "Full event history for one aggregate (e.g. a loan) —
                         reconstructs what happened over time, not just its
                         current row."
            :tags       ["Audit"]
            :parameters {:path [:map [:type :string] [:id :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params]}]
                          (rbac/require-permission identity :audit/read)
                          {:status 200
                           :body   (events-db/for-aggregate
                                     ds tenant-id
                                     (:type path-params)
                                     (parse-uuid (:id path-params)))})}}]]])
