(ns loanmanager.api.routes.notifications
  (:require [loanmanager.db.notifications :as notif-db]
            [loanmanager.security.rbac :as rbac]))

(defn routes [ds]
  [["/notifications"
    {:get {:summary    "List notifications for the authenticated user"
           :tags       ["Notifications"]
           :parameters {:query [:map
                                [:unread-only {:optional true} :boolean]
                                [:limit       {:optional true} :int]
                                [:offset      {:optional true} :int]]}
           :handler    (fn [{:keys [identity query-params]}]
                         (rbac/require-permission identity :customer/read)
                         {:status 200
                          :body   (notif-db/list-for-user ds (:user-id identity) query-params)})}}]

   ["/notifications/unread-count"
    {:get {:summary "Get unread notification count for the authenticated user"
           :tags    ["Notifications"]
           :handler (fn [{:keys [identity]}]
                      {:status 200
                       :body   {:count (notif-db/unread-count ds (:user-id identity))}})}}]

   ["/notifications/:id/read"
    {:post {:summary    "Mark a notification as read"
            :tags       ["Notifications"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity path-params]}]
                          (let [id      (parse-uuid (:id path-params))
                                updated (notif-db/mark-read! ds (:user-id identity) id)]
                            (if updated
                              {:status 200 :body updated}
                              {:status 404 :body {:error "Notification not found"}})))}}]])
