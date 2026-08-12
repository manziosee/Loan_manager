(ns loanmanager.api.routes.delinquency
  (:require [loanmanager.db.loans :as loans-db]
            [loanmanager.db.collections :as coll-db]
            [loanmanager.domain.delinquency :as delinquency]
            [loanmanager.domain.collections :as collections]
            [loanmanager.events.bus :as events]
            [loanmanager.security.rbac :as rbac]))

(defn routes [ds bus]
  [["/loans/:id/delinquency"
    {:get {:summary    "Get delinquency assessment for a loan"
           :tags       ["Delinquency"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :loan/read)
                         (let [loan-id (parse-uuid (:id path-params))
                               loan    (loans-db/find-loan ds tenant-id loan-id)]
                           (if-not loan
                             {:status 404 :body {:error "Loan not found"}}
                             {:status 200
                              :body   (delinquency/assess
                                        {:loan-id               loan-id
                                         :outstanding-principal (:loans/outstanding-principal loan)
                                         :days-overdue          (or (:loans/days-overdue loan) 0)})})))}}]

   ["/loans/:id/delinquency/history"
    {:get {:summary    "Get delinquency classification history for a loan"
           :tags       ["Delinquency"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity path-params]}]
                         (rbac/require-permission identity :loan/read)
                         {:status 200
                          :body   (coll-db/loan-delinquency-history
                                    ds (parse-uuid (:id path-params)))})}}]

   ["/delinquency/run"
    {:post {:summary    "Classify all active loans and update delinquency buckets"
            :tags       ["Delinquency"]
            :handler    (fn [{:keys [identity tenant-id]}]
                          (rbac/require-permission identity :loan/approve)
                          (let [loans    (loans-db/all-active-loans ds tenant-id)
                                assessed (mapv (fn [loan]
                                                 (let [result (delinquency/assess
                                                                {:loan-id               (:loans/id loan)
                                                                 :outstanding-principal (:loans/outstanding-principal loan)
                                                                 :days-overdue          (:loans/days-overdue loan)})]
                                                   (loans-db/update-delinquency! ds tenant-id
                                                                                  (:loans/id loan)
                                                                                  (:days-overdue result)
                                                                                  (:bucket result))
                                                   (coll-db/log-delinquency! ds
                                                     {:loan-id               (:loans/id loan)
                                                      :tenant-id             tenant-id
                                                      :days-overdue          (:days-overdue result)
                                                      :bucket                (name (:bucket result))
                                                      :outstanding-principal (:loans/outstanding-principal loan)
                                                      :provision-rate        (:provision-rate result)
                                                      :provision-amount      (:provision-amount result)
                                                      :triggered-actions     (:triggered-actions result)})
                                                   (when (and (:requires-collection result)
                                                              (nil? (coll-db/find-case-by-loan ds tenant-id (:loans/id loan))))
                                                     (coll-db/create-case! ds
                                                       {:tenant-id            tenant-id
                                                        :loan-id              (:loans/id loan)
                                                        :customer-id          (:loans/customer-id loan)
                                                        :priority             (name (collections/case-priority
                                                                                      (:loans/outstanding-principal loan)
                                                                                      (:days-overdue result)))
                                                        :days-overdue-at-open (:days-overdue result)
                                                        :outstanding-at-open  (:loans/outstanding-principal loan)}))
                                                   (when (:is-npl result)
                                                     (events/publish! bus {:event-type events/LOAN-OVERDUE
                                                                           :tenant-id  tenant-id
                                                                           :loan-id    (:loans/id loan)
                                                                           :bucket     (name (:bucket result))}))
                                                   result))
                                               loans)
                                summary  (delinquency/portfolio-summary
                                           (mapv (fn [loan]
                                                   {:bucket                (delinquency/classify-bucket (:loans/days-overdue loan))
                                                    :outstanding-principal (:loans/outstanding-principal loan)})
                                                 loans))]
                            {:status 200 :body {:processed (count assessed) :summary summary}}))}}]

   ["/delinquency/portfolio"
    {:get {:summary    "Portfolio-wide delinquency and NPL summary"
           :tags       ["Delinquency" "Portfolio"]
           :handler    (fn [{:keys [identity tenant-id]}]
                         (rbac/require-permission identity :portfolio/read)
                         (let [loans   (loans-db/all-active-loans ds tenant-id)
                               summary (delinquency/portfolio-summary
                                         (mapv (fn [l]
                                                 {:bucket                (delinquency/classify-bucket (:loans/days-overdue l))
                                                  :outstanding-principal (:loans/outstanding-principal l)})
                                               loans))]
                           {:status 200 :body summary}))}}]])
