(ns loanmanager.api.routes.credit
  (:require [loanmanager.api.schemas :as schemas]
            [loanmanager.db.customers :as customers-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.domain.credit-score :as credit-score]
            [loanmanager.domain.finance :as finance]
            [loanmanager.security.rbac :as rbac]))

(defn routes [ds]
  [["/credit/score"
    {:post {:summary    "Run a credit score assessment with full explainability"
            :tags       ["Credit Scoring"]
            :parameters {:body schemas/CreditScoreRequest}
            :handler    (fn [{:keys [identity body-params]}]
                          (rbac/require-permission identity :credit-score/read)
                          (let [profile (merge {:late-payments-12m        0
                                                :late-payments-24m        0
                                                :defaults                 0
                                                :write-offs               0
                                                :dti                      0.0
                                                :active-facilities        0
                                                :total-outstanding-debt   0
                                                :avg-monthly-transactions 0
                                                :avg-monthly-savings      0
                                                :months-banking           0
                                                :requested-amount         0}
                                               body-params)]
                            {:status 200 :body (credit-score/score-with-explanation profile)}))}}]

   ["/customers/:id/credit-score"
    {:get {:summary    "Run credit score assessment for an existing customer"
           :tags       ["Credit Scoring" "Customers"]
           :parameters {:path  [:map [:id :string]]
                        :query [:map [:requested-amount {:optional true} :double]]}
           :handler    (fn [{:keys [identity tenant-id path-params query-params]}]
                         (rbac/require-permission identity :credit-score/read)
                         (let [id       (parse-uuid (:id path-params))
                               customer (customers-db/find-by-id ds tenant-id id)
                               obligs   (customers-db/total-monthly-obligations ds id)
                               req-amt  (or (:requested-amount query-params) 0)
                               new-pmt  (if (pos? req-amt)
                                          (finance/reducing-balance-payment req-amt 0.15 12 :monthly)
                                          0)
                               dti      (finance/debt-to-income
                                          (or (:customers/monthly-income customer) 0)
                                          obligs new-pmt)
                               profile  {:monthly-income           (or (:customers/monthly-income customer) 0)
                                         :employment-years         (or (:customers/employment-years customer) 0)
                                         :employment-type          (or (:customers/employment-type customer) "permanent")
                                         :late-payments-12m        0
                                         :late-payments-24m        0
                                         :defaults                 0
                                         :write-offs               0
                                         :dti                      dti
                                         :active-facilities        1
                                         :total-outstanding-debt   (double obligs)
                                         :avg-monthly-transactions 10
                                         :avg-monthly-savings      0
                                         :months-banking           12
                                         :requested-amount         req-amt}
                               result   (credit-score/score-with-explanation profile)]
                           (audit/log! ds {:tenant-id   tenant-id
                                           :user-id     (:user-id identity)
                                           :action      "credit-score.assessed"
                                           :entity-type "customer"
                                           :entity-id   id
                                           :after-state {:score    (:total-score result)
                                                         :category (name (:category result))}})
                           {:status 200 :body result}))}}]

   ["/credit/dti"
    {:post {:summary    "Calculate debt-to-income ratio with policy assessment"
            :tags       ["Credit Scoring"]
            :parameters {:body schemas/DTIRequest}
            :handler    (fn [{:keys [identity body-params]}]
                          (rbac/require-permission identity :credit-score/read)
                          (let [{:keys [monthly-income existing-obligations new-payment threshold]} body-params]
                            {:status 200
                             :body   (finance/dti-analysis
                                       {:monthly-income       monthly-income
                                        :existing-obligations existing-obligations
                                        :new-payment          new-payment
                                        :threshold            (or threshold 0.45)})}))}}]

   ["/customers/:id/dti"
    {:get {:summary    "Calculate DTI for a customer given a proposed new payment"
           :tags       ["Credit Scoring" "Customers"]
           :parameters {:path  [:map [:id :string]]
                        :query [:map
                                [:new-payment [:double {:min 0}]]
                                [:threshold   {:optional true} :double]]}
           :handler    (fn [{:keys [identity tenant-id path-params query-params]}]
                         (rbac/require-permission identity :credit-score/read)
                         (let [id       (parse-uuid (:id path-params))
                               customer (customers-db/find-by-id ds tenant-id id)
                               obligs   (customers-db/total-monthly-obligations ds id)]
                           {:status 200
                            :body   (finance/dti-analysis
                                      {:monthly-income       (or (:customers/monthly-income customer) 0)
                                       :existing-obligations (double obligs)
                                       :new-payment          (or (:new-payment query-params) 0)
                                       :threshold            (or (:threshold query-params) 0.45)})}))}}]])
