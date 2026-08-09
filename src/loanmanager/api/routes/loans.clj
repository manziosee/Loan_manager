(ns loanmanager.api.routes.loans
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.db.loans :as loans-db]
            [loanmanager.db.customers :as customers-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.domain.finance :as finance]
            [loanmanager.domain.credit-score :as credit-score]
            [loanmanager.domain.fraud :as fraud]
            [loanmanager.workflow.engine :as workflow]
            [loanmanager.events.bus :as events]
            [loanmanager.security.rbac :as rbac]))

(defn- app-no [] (str "APP-" (System/currentTimeMillis)))
(defn- loan-no [] (str "LN-"  (System/currentTimeMillis)))
(defn- pay-no  [] (str "PAY-" (System/currentTimeMillis)))

(defn- get-product [ds tenant-id product-id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*] :from [:loan-products]
                 :where  [:and [:= :tenant-id tenant-id] [:= :id product-id]]})))

(defn routes [ds bus]
  [["/loan-applications"
    {:get  {:summary    "List loan applications"
            :tags       ["Applications"]
            :parameters {:query [:map
                                 [:status {:optional true} :string]
                                 [:limit  {:optional true} :int]
                                 [:offset {:optional true} :int]]}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :application/read)
                          {:status 200
                           :body   (loans-db/list-applications ds tenant-id query-params)})}

     :post {:summary    "Submit a loan application"
            :tags       ["Applications"]
            :parameters {:body schemas/ApplicationCreate}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :application/create)
                          (let [{:keys [customer-id product-id requested-amount
                                        requested-duration purpose]} body-params
                                customer     (customers-db/find-by-id ds tenant-id (parse-uuid customer-id))
                                product      (get-product ds tenant-id (parse-uuid product-id))
                                obligs       (customers-db/total-monthly-obligations ds (parse-uuid customer-id))
                                rate         (/ (:loan-products/interest-rate-min product) 100)
                                new-payment  (finance/reducing-balance-payment
                                               requested-amount rate requested-duration :monthly)
                                score-result (credit-score/score
                                               {:monthly-income           (or (:customers/monthly-income customer) 0)
                                                :employment-years         (or (:customers/employment-years customer) 0)
                                                :employment-type          (or (:customers/employment-type customer) "permanent")
                                                :late-payments-12m        0
                                                :late-payments-24m        0
                                                :defaults                 0
                                                :write-offs               0
                                                :dti                      (finance/debt-to-income
                                                                            (or (:customers/monthly-income customer) 0)
                                                                            obligs new-payment)
                                                :active-facilities        1
                                                :total-outstanding-debt   (double obligs)
                                                :avg-monthly-transactions 10
                                                :avg-monthly-savings      0
                                                :months-banking           12
                                                :requested-amount         requested-amount})
                                fraud-result (fraud/evaluate
                                               {:phone-customer-count        1
                                                :id-doc-duplicate?           false
                                                :applications-last-24h       1
                                                :address-match-count         0
                                                :bank-account-borrower-count 1})
                                wf           (workflow/determine-workflow
                                               requested-amount
                                               (:loan-products/approval-rules product))
                                application  (loans-db/create-application!
                                               ds
                                               {:tenant-id          tenant-id
                                                :application-no     (app-no)
                                                :customer-id        (parse-uuid customer-id)
                                                :product-id         (parse-uuid product-id)
                                                :requested-amount   requested-amount
                                                :requested-duration requested-duration
                                                :purpose            purpose
                                                :credit-score       (:total-score score-result)
                                                :risk-category      (name (:category score-result))
                                                :score-breakdown    (:factors score-result)
                                                :fraud-score        (:fraud-score fraud-result)
                                                :fraud-flags        (:flags fraud-result)
                                                :status             (if (= :auto-approve (:type wf))
                                                                      "approved" "submitted")
                                                :current-step       (-> wf :steps first :step)
                                                :workflow-state     {:steps (:steps wf) :completed []}
                                                :submitted-by       (:user-id identity)
                                                :submitted-at       [:now]})]
                            (events/publish! bus {:event-type     events/LOAN-APPLICATION-SUBMITTED
                                                  :tenant-id      tenant-id
                                                  :application-id (:loan-applications/id application)})
                            {:status 201 :body application}))}}]

   ["/loan-applications/:id"
    {:get {:summary    "Get application details"
           :tags       ["Applications"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :application/read)
                         (if-let [app (loans-db/find-application ds tenant-id (parse-uuid (:id path-params)))]
                           {:status 200 :body app}
                           {:status 404 :body {:error "Application not found"}}))}

     :put {:summary    "Update a draft application before submission"
           :tags       ["Applications"]
           :parameters {:path [:map [:id :string]]
                        :body schemas/ApplicationUpdate}
           :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                         (rbac/require-permission identity :application/create)
                         (let [id  (parse-uuid (:id path-params))
                               app (loans-db/find-application ds tenant-id id)]
                           (when-not (= "draft" (:loan-applications/status app))
                             (throw (ex-info "Only draft applications can be updated"
                                            {:type :validation})))
                           {:status 200
                            :body   (loans-db/update-application! ds tenant-id id
                                      (select-keys body-params [:requested-amount
                                                                 :requested-duration
                                                                 :purpose]))}))}}]

   ["/loan-applications/:id/withdraw"
    {:post {:summary    "Withdraw a pending application"
            :tags       ["Applications"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params]}]
                          (rbac/require-permission identity :application/create)
                          (let [id  (parse-uuid (:id path-params))
                                app (loans-db/find-application ds tenant-id id)]
                            (when-not (#{ "draft" "submitted"} (:loan-applications/status app))
                              (throw (ex-info "Application cannot be withdrawn in its current state"
                                             {:type :validation})))
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "application.withdrawn"
                                            :entity-type "loan-application"
                                            :entity-id   id})
                            {:status 200
                             :body   (loans-db/update-application! ds tenant-id id
                                       {:status "withdrawn" :decided-at [:now]})}))}}]

   ["/loan-applications/:id/approve"
    {:post {:summary    "Approve or reject an application (maker-checker)"
            :tags       ["Applications" "Workflow"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/ApprovalAction}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :application/approve)
                          (let [id         (parse-uuid (:id path-params))
                                app        (loans-db/find-application ds tenant-id id)
                                {:keys [action comments]} body-params
                                new-status (case action
                                             "approved" "approved"
                                             "rejected" "rejected"
                                             "returned" "submitted")]
                            (loans-db/add-approval-step! ds
                              {:application-id id
                               :step-name      (or (:loan-applications/current-step app) "review")
                               :step-order     1
                               :assigned-to    (:user-id identity)
                               :action         action
                               :comments       comments
                               :acted-at       [:now]})
                            (let [updated (loans-db/update-application! ds tenant-id id
                                            {:status new-status :decided-at [:now]})]
                              (when (= action "approved")
                                (events/publish! bus {:event-type     events/LOAN-APPROVED
                                                      :tenant-id      tenant-id
                                                      :application-id id}))
                              {:status 200 :body updated})))}}]

   ["/loan-applications/:id/disburse"
    {:post {:summary    "Disburse an approved loan"
            :tags       ["Loans" "Disbursement"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params]}]
                          (rbac/require-permission identity :loan/disburse)
                          (let [app-id   (parse-uuid (:id path-params))
                                app      (loans-db/find-application ds tenant-id app-id)
                                product  (get-product ds tenant-id (:loan-applications/product-id app))
                                amount   (:loan-applications/approved-amount app
                                           (:loan-applications/requested-amount app))
                                rate     (/ (or (:loan-applications/approved-rate app)
                                                (:loan-products/interest-rate-min product))
                                            100)
                                months   (or (:loan-applications/approved-duration app)
                                             (:loan-applications/requested-duration app))
                                freq     (keyword (or (:loan-products/repayment-frequency product) "monthly"))
                                schedule (finance/build-schedule
                                           {:method          :reducing-balance
                                            :principal       amount
                                            :annual-rate     rate
                                            :duration-months months
                                            :freq            freq
                                            :currency        (:loan-products/currency product)})
                                loan     (loans-db/create-loan! ds
                                           {:tenant-id             tenant-id
                                            :loan-no               (loan-no)
                                            :application-id        app-id
                                            :customer-id           (:loan-applications/customer-id app)
                                            :product-id            (:loan-applications/product-id app)
                                            :principal             amount
                                            :interest-rate         (* rate 100)
                                            :interest-method       (name (:loan-products/interest-method product))
                                            :duration-months       months
                                            :repayment-freq        (name freq)
                                            :currency              (:loan-products/currency product)
                                            :outstanding-principal amount
                                            :disbursed-at          [:now]
                                            :disbursed-by          (:user-id identity)
                                            :status                "active"})]
                            (loans-db/insert-schedule! ds (:loans/id loan) schedule)
                            (loans-db/update-application! ds tenant-id app-id {:status "approved"})
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "loan.disbursed"
                                            :entity-type "loan"
                                            :entity-id   (:loans/id loan)})
                            (events/publish! bus {:event-type events/LOAN-DISBURSED
                                                  :tenant-id  tenant-id
                                                  :loan-id    (:loans/id loan)
                                                  :amount     amount})
                            {:status 201 :body loan}))}}]

   ["/loans"
    {:get {:summary    "List all loans"
           :tags       ["Loans"]
           :parameters {:query [:map
                                [:status      {:optional true} :string]
                                [:customer-id {:optional true} :string]
                                [:limit       {:optional true} :int]
                                [:offset      {:optional true} :int]]}
           :handler    (fn [{:keys [identity tenant-id query-params]}]
                         (rbac/require-permission identity :loan/read)
                         {:status 200
                          :body   (loans-db/list-loans ds tenant-id query-params)})}}]

   ["/loans/:id"
    {:get {:summary    "Get loan details"
           :tags       ["Loans"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :loan/read)
                         (if-let [loan (loans-db/find-loan ds tenant-id (parse-uuid (:id path-params)))]
                           {:status 200 :body loan}
                           {:status 404 :body {:error "Loan not found"}}))}}]

   ["/loans/:id/schedule"
    {:get {:summary    "Get repayment schedule"
           :tags       ["Loans"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity path-params]}]
                         (rbac/require-permission identity :schedule/read)
                         {:status 200
                          :body   (loans-db/get-schedule ds (parse-uuid (:id path-params)))})}}]

   ["/loans/:id/payments"
    {:get  {:summary    "List payments for a loan"
            :tags       ["Payments"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity path-params]}]
                          (rbac/require-permission identity :payment/read)
                          {:status 200
                           :body   (loans-db/loan-payments ds (parse-uuid (:id path-params)))})}

     :post {:summary    "Record a payment"
            :tags       ["Payments"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/PaymentCreate}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :payment/create)
                          (let [loan-id           (parse-uuid (:id path-params))
                                loan              (loans-db/find-loan ds tenant-id loan-id)
                                {:keys [amount payment-method reference]} body-params
                                rate              (/ (:loans/interest-rate loan) 100)
                                interest-due      (* (:loans/outstanding-principal loan) (/ rate 12))
                                interest-portion  (min amount interest-due)
                                principal-portion (- amount interest-portion)
                                payment           (loans-db/record-payment! ds
                                                    {:tenant-id         tenant-id
                                                     :loan-id           loan-id
                                                     :payment-no        (pay-no)
                                                     :amount            amount
                                                     :principal-portion principal-portion
                                                     :interest-portion  interest-portion
                                                     :payment-method    payment-method
                                                     :reference         reference
                                                     :recorded-by       (:user-id identity)})]
                            (loans-db/update-loan! ds tenant-id loan-id
                              {:outstanding-principal (- (:loans/outstanding-principal loan) principal-portion)
                               :total-paid-principal  (+ (:loans/total-paid-principal loan) principal-portion)
                               :total-paid-interest   (+ (:loans/total-paid-interest loan) interest-portion)
                               :last-payment-date     [:now]})
                            (events/publish! bus {:event-type events/PAYMENT-RECEIVED
                                                  :tenant-id  tenant-id
                                                  :loan-id    loan-id
                                                  :amount     amount
                                                  :payment-id (:payments/id payment)})
                            {:status 201 :body payment}))}}]

   ["/loans/:id/restructure"
    {:post {:summary    "Restructure a loan (rate/term change)"
            :tags       ["Loans"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/RestructureRequest}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :loan/restructure)
                          (let [loan-id  (parse-uuid (:id path-params))
                                loan     (loans-db/find-loan ds tenant-id loan-id)
                                {:keys [new-interest-rate new-duration-months
                                        new-repayment-freq reason]} body-params
                                changes  (cond-> {}
                                           new-interest-rate   (assoc :interest-rate new-interest-rate)
                                           new-duration-months (assoc :duration-months new-duration-months)
                                           new-repayment-freq  (assoc :repayment-freq new-repayment-freq))
                                updated  (loans-db/restructure-loan! ds tenant-id loan-id
                                           changes (:user-id identity))]
                            (audit/log! ds {:tenant-id    tenant-id
                                            :user-id      (:user-id identity)
                                            :action       "loan.restructured"
                                            :entity-type  "loan"
                                            :entity-id    loan-id
                                            :before-state {:interest-rate   (:loans/interest-rate loan)
                                                           :duration-months (:loans/duration-months loan)}
                                            :after-state  changes})
                            (events/publish! bus {:event-type events/LOAN-RESTRUCTURED
                                                  :tenant-id  tenant-id
                                                  :loan-id    loan-id
                                                  :reason     reason})
                            {:status 200 :body updated}))}}]

   ["/loans/:id/write-off"
    {:post {:summary    "Write off an NPL loan"
            :tags       ["Loans"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/WriteOffRequest}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :loan/write-off)
                          (let [loan-id (parse-uuid (:id path-params))
                                loan    (loans-db/find-loan ds tenant-id loan-id)]
                            (when-not (#{ "npl" "overdue"} (:loans/status loan))
                              (throw (ex-info "Only NPL or overdue loans can be written off"
                                             {:type :validation})))
                            (let [updated (loans-db/write-off-loan! ds tenant-id loan-id
                                            (:user-id identity) (:reason body-params))]
                              (audit/log! ds {:tenant-id   tenant-id
                                              :user-id     (:user-id identity)
                                              :action      "loan.written-off"
                                              :entity-type "loan"
                                              :entity-id   loan-id
                                              :after-state {:reason           (:reason body-params)
                                                            :outstanding-at-wo (:loans/outstanding-principal loan)}})
                              (events/publish! bus {:event-type events/LOAN-CLOSED
                                                    :tenant-id  tenant-id
                                                    :loan-id    loan-id})
                              {:status 200 :body updated})))}}]

   ["/payments/:id/reverse"
    {:post {:summary    "Reverse a payment"
            :tags       ["Payments"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/PaymentReverseRequest}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :payment/reverse)
                          (let [payment-id (parse-uuid (:id path-params))
                                payment    (loans-db/reverse-payment! ds tenant-id payment-id
                                             (:reason body-params) (:user-id identity))]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "payment.reversed"
                                            :entity-type "payment"
                                            :entity-id   payment-id
                                            :after-state {:reason (:reason body-params)}})
                            {:status 200 :body payment}))}}]

   ["/loans/simulate"
    {:post {:summary    "Simulate loan repayment schedule (no auth required)"
            :tags       ["Loans" "Tools"]
            :parameters {:body schemas/SimulateRequest}
            :handler    (fn [{:keys [body-params]}]
                          (let [{:keys [principal annual-rate months]} body-params
                                schedule (finance/build-schedule
                                           {:method          :reducing-balance
                                            :principal       principal
                                            :annual-rate     (/ annual-rate 100)
                                            :duration-months months})
                                summary  (finance/schedule-summary schedule principal)]
                            {:status 200
                             :body   {:summary  summary
                                      :schedule schedule}}))}}]

   ["/customers/:id/loans"
    {:get {:summary    "Get all loans for a customer"
           :tags       ["Customers" "Loans"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :loan/read)
                         {:status 200
                          :body   (loans-db/customer-loans ds tenant-id
                                                           (parse-uuid (:id path-params)))})}}]])
