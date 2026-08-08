(ns loanmanager.api.routes.collections
  (:require [loanmanager.api.schemas :as schemas]
            [loanmanager.db.loans :as loans-db]
            [loanmanager.db.customers :as customers-db]
            [loanmanager.db.collections :as coll-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.domain.collections :as collections]
            [loanmanager.domain.delinquency :as delinquency]
            [loanmanager.security.rbac :as rbac]))

(defn routes [ds]
  [;; ── List collection cases ─────────────────────────────────────────────────
   ["/collections"
    {:get {:summary    "List collection cases with loan and customer details"
           :tags       ["Collections"]
           :parameters {:query [:map
                                [:status      {:optional true} :string]
                                [:priority    {:optional true} :string]
                                [:assigned-to {:optional true} :string]
                                [:limit       {:optional true} :int]
                                [:offset      {:optional true} :int]]}
           :handler    (fn [{:keys [identity tenant-id query-params]}]
                         (rbac/require-permission identity :collection/read)
                         {:status 200
                          :body   (coll-db/list-cases ds tenant-id query-params)})}}]

   ;; ── Get case detail (officer view) ───────────────────────────────────────
   ["/collections/:id"
    {:get {:summary    "Get full collection case — officer view with next action"
           :tags       ["Collections"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :collection/read)
                         (let [case-id    (parse-uuid (:id path-params))
                               case-data  (coll-db/find-case ds tenant-id case-id)
                               activities (coll-db/case-activities ds case-id)
                               promises   (coll-db/case-promises ds case-id)
                               loan       (loans-db/find-loan ds tenant-id
                                                              (:collection-cases/loan-id case-data))
                               customer   (customers-db/find-by-id ds tenant-id
                                                                    (:loans/customer-id loan))
                               summary    (collections/case-summary
                                            {:customer  customer
                                             :loan      loan
                                             :activities activities
                                             :promises   promises})]
                           {:status 200
                            :body   {:case       case-data
                                     :activities activities
                                     :promises   promises
                                     :summary    summary}}))}}]

   ;; ── Record a collection activity ─────────────────────────────────────────
   ["/collections/:id/activities"
    {:post {:summary    "Record a collection activity (call, SMS, visit, etc.)"
            :tags       ["Collections"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/ActivityCreate}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :collection/create)
                          (let [case-id  (parse-uuid (:id path-params))
                                activity (coll-db/add-activity! ds
                                           {:case-id       case-id
                                            :activity-type (:activity-type body-params)
                                            :outcome       (:outcome body-params)
                                            :notes         (:notes body-params)
                                            :recorded-by   (:user-id identity)})]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "collection.activity-recorded"
                                            :entity-type "collection-case"
                                            :entity-id   case-id
                                            :after-state body-params})
                            {:status 201 :body activity}))}}]

   ;; ── Create promise-to-pay ─────────────────────────────────────────────────
   ["/collections/:id/promises"
    {:get  {:summary    "List all promises-to-pay for a case"
            :tags       ["Collections"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity path-params]}]
                          (rbac/require-permission identity :collection/read)
                          {:status 200
                           :body   (coll-db/case-promises ds (parse-uuid (:id path-params)))})}

     :post {:summary    "Record a promise-to-pay from the customer"
            :tags       ["Collections"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/PromiseCreate}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :collection/create)
                          (let [case-id  (parse-uuid (:id path-params))
                                case-row (coll-db/find-case ds tenant-id case-id)
                                promise  (coll-db/create-promise! ds
                                           {:case-id        case-id
                                            :loan-id        (:collection-cases/loan-id case-row)
                                            :promise-date   (:promise-date body-params)
                                            :promise-amount (:promise-amount body-params)
                                            :status         "pending"
                                            :recorded-by    (:user-id identity)})]
                            ;; Also log as activity
                            (coll-db/add-activity! ds
                              {:case-id       case-id
                               :activity-type "promise_to_pay"
                               :notes         (str "Promise: $" (:promise-amount body-params)
                                                   " by " (:promise-date body-params))
                               :recorded-by   (:user-id identity)})
                            {:status 201 :body promise}))}}]

   ;; ── Update promise status ─────────────────────────────────────────────────
   ["/collections/promises/:id"
    {:put {:summary    "Update promise-to-pay status (kept/broken/partial)"
           :tags       ["Collections"]
           :parameters {:path [:map [:id :string]]
                        :body schemas/PromiseUpdate}
           :handler    (fn [{:keys [identity path-params body-params]}]
                         (rbac/require-permission identity :collection/update)
                         (let [updated (coll-db/update-promise! ds
                                         (parse-uuid (:id path-params))
                                         body-params)]
                           {:status 200 :body updated}))}}]

   ;; ── Broken promises dashboard ─────────────────────────────────────────────
   ["/collections/broken-promises"
    {:get {:summary    "List all broken promises-to-pay requiring follow-up"
           :tags       ["Collections"]
           :handler    (fn [{:keys [identity tenant-id]}]
                         (rbac/require-permission identity :collection/read)
                         {:status 200
                          :body   (coll-db/broken-promises ds tenant-id)})}}]

   ;; ── Escalate a case ───────────────────────────────────────────────────────
   ["/collections/:id/escalate"
    {:post {:summary    "Escalate a collection case to a supervisor"
            :tags       ["Collections"]
            :parameters {:path [:map [:id :string]]
                         :body [:map
                                [:escalate-to UUID-str]
                                [:reason      :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :collection/update)
                          (let [case-id (parse-uuid (:id path-params))
                                updated (coll-db/update-case! ds tenant-id case-id
                                          {:escalated-to (parse-uuid (:escalate-to body-params))
                                           :escalated-at [:now]
                                           :priority     "high"})]
                            (coll-db/add-activity! ds
                              {:case-id       case-id
                               :activity-type "escalation"
                               :notes         (:reason body-params)
                               :recorded-by   (:user-id identity)})
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "collection.escalated"
                                            :entity-type "collection-case"
                                            :entity-id   case-id
                                            :after-state body-params})
                            {:status 200 :body updated}))}}]

   ;; ── Close a case ─────────────────────────────────────────────────────────
   ["/collections/:id/close"
    {:post {:summary    "Close a resolved collection case"
            :tags       ["Collections"]
            :parameters {:path [:map [:id :string]]
                         :body [:map [:resolution :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :collection/update)
                          (let [case-id (parse-uuid (:id path-params))
                                updated (coll-db/update-case! ds tenant-id case-id
                                          {:status      "closed"
                                           :resolved-at [:now]})]
                            (coll-db/add-activity! ds
                              {:case-id       case-id
                               :activity-type "case_note"
                               :notes         (str "Case closed: " (:resolution body-params))
                               :recorded-by   (:user-id identity)})
                            {:status 200 :body updated}))}}]])
