(ns loanmanager.api.routes.loans
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.db.loans :as loans-db]
            [loanmanager.db.customers :as customers-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.db.connection :as db]
            [loanmanager.domain.finance :as finance]
            [loanmanager.domain.credit-score :as credit-score]
            [loanmanager.domain.fraud :as fraud]
            [loanmanager.workflow.engine :as workflow]
            [loanmanager.domain.accounting :as accounting]
            [loanmanager.db.ledger :as ledger]
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
                           :body   (loans-db/list-applications ds tenant-id
                                     (assoc query-params :branch-id (rbac/scope-branch-id identity)))})}

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
                                history      (loans-db/payment-history-stats ds (parse-uuid customer-id))
                                rate         (/ (:loan-products/interest-rate-min product) 100)
                                new-payment  (finance/reducing-balance-payment
                                               requested-amount rate requested-duration :monthly)
                                score-result (credit-score/score
                                               {:monthly-income           (or (:customers/monthly-income customer) 0)
                                                :employment-years         (or (:customers/employment-years customer) 0)
                                                :employment-type          (or (:customers/employment-type customer) "permanent")
                                                :late-payments-12m        (:late-payments-12m history)
                                                :late-payments-24m        (:late-payments-24m history)
                                                :defaults                 (:defaults history)
                                                :write-offs               (:write-offs history)
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
                                               {:phone-customer-count  (customers-db/phone-customer-count
                                                                         ds tenant-id (:customers/phone customer))
                                                :id-doc-duplicate?     (customers-db/id-doc-duplicate?
                                                                         ds (parse-uuid customer-id))
                                                :applications-last-24h (loans-db/applications-last-24h
                                                                         ds (parse-uuid customer-id))
                                                :address-match-count         0
                                                :bank-account-borrower-count 1})
                                wf           (workflow/determine-workflow
                                               requested-amount
                                               (:loan-products/approval-rules product))
                                application  (loans-db/create-application!
                                               ds
                                               {:tenant-id          tenant-id
                                                :application-no     (app-no)
                                                :branch-id          (:branch-id identity)
                                                :customer-id        (parse-uuid customer-id)
                                                :product-id         (parse-uuid product-id)
                                                :requested-amount   requested-amount
                                                :requested-duration requested-duration
                                                :purpose            purpose
                                                :credit-score       (:total-score score-result)
                                                :risk-category      (name (:category score-result))
                                                :score-breakdown    [:lift (:factors score-result)]
                                                :fraud-score        (:fraud-score fraud-result)
                                                :fraud-flags        [:lift (:flags fraud-result)]
                                                :status             (if (= :auto-approve (:type wf))
                                                                      "approved" "submitted")
                                                :current-step       (-> wf :steps first :step)
                                                :workflow-state     [:lift {:steps (:steps wf) :completed-steps []}]
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
    {:post {:summary    "Approve, reject, or return an application at its current
                         maker-checker step. Multi-step workflows (large/very-large
                         loans) require every step in sequence — the right role for
                         each step, one at a time — before the application is
                         actually approved."
            :tags       ["Applications" "Workflow"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/ApprovalAction}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :application/approve)
                          (let [id  (parse-uuid (:id path-params))
                                app (loans-db/find-application ds tenant-id id)]
                            (if-not app
                              {:status 404 :body {:error "Application not found"}}
                              (let [{:keys [action comments approved-amount approved-rate approved-duration]} body-params
                                    wf-state  (or (:loan-applications/workflow-state app)
                                                  {:steps [] :completed-steps []})
                                    steps     (:steps wf-state)
                                    current   (workflow/next-step steps (:completed-steps wf-state))
                                    step-name (or (:step current)
                                                  (:loan-applications/current-step app)
                                                  "review")]
                                (when (and current
                                           (not (workflow/authorized-for-step? step-name (:role identity))))
                                  (throw (ex-info (str "This step requires " (name step-name) " approval")
                                                  {:type :forbidden :step step-name :role (:role identity)})))
                                (loans-db/add-approval-step! ds
                                  {:application-id id
                                   :step-name      step-name
                                   :step-order     (inc (count (:completed-steps wf-state)))
                                   :assigned-to    (:user-id identity)
                                   :action         action
                                   :comments       comments
                                   :acted-at       [:now]})
                                (case action
                                  "rejected"
                                  (let [updated (loans-db/update-application! ds tenant-id id
                                                  {:status "rejected" :decided-at [:now]})]
                                    (events/publish! bus {:event-type     events/LOAN-REJECTED
                                                          :tenant-id      tenant-id
                                                          :application-id id})
                                    {:status 200 :body updated})

                                  "returned"
                                  {:status 200
                                   :body (loans-db/update-application! ds tenant-id id
                                           {:status "submitted"})}

                                  "approved"
                                  (let [new-wf-state (workflow/advance! wf-state step-name :approved
                                                                        (:user-id identity) comments)
                                        done?        (workflow/complete? steps new-wf-state)
                                        next-pending (when-not done?
                                                       (workflow/next-step steps (:completed-steps new-wf-state)))
                                        updated (loans-db/update-application! ds tenant-id id
                                                  (cond-> {:workflow-state [:lift new-wf-state]}
                                                    done?     (assoc :status "approved" :decided-at [:now]
                                                                      :current-step nil)
                                                    (and done? approved-amount)   (assoc :approved-amount approved-amount)
                                                    (and done? approved-rate)     (assoc :approved-rate approved-rate)
                                                    (and done? approved-duration) (assoc :approved-duration approved-duration)
                                                    (not done?) (assoc :status       "pending_approval"
                                                                        :current-step (:step next-pending))))]
                                    (when done?
                                      (events/publish! bus {:event-type     events/LOAN-APPROVED
                                                            :tenant-id      tenant-id
                                                            :application-id id}))
                                    {:status 200 :body updated}))))))}}]

   ["/loan-applications/:id/disburse"
    {:post {:summary    "Disburse an approved loan"
            :tags       ["Loans" "Disbursement"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params]}]
                          (rbac/require-permission identity :loan/disburse)
                          (let [app-id (parse-uuid (:id path-params))
                                app    (loans-db/find-application ds tenant-id app-id)]
                          (cond
                            (not app)
                            {:status 404 :body {:error "Application not found"}}

                            (not= "approved" (:loan-applications/status app))
                            {:status 422 :body {:error (str "Application must be fully approved before disbursement "
                                                            "(current status: " (:loan-applications/status app) ")")}}

                            :else
                          (let [product  (get-product ds tenant-id (:loan-applications/product-id app))
                                amount   (or (:loan-applications/approved-amount app)
                                             (:loan-applications/requested-amount app))
                                rate     (/ (or (:loan-applications/approved-rate app)
                                                (:loan-products/interest-rate-min product))
                                            100)
                                months   (or (:loan-applications/approved-duration app)
                                             (:loan-applications/requested-duration app))
                                freq     (keyword (or (:loan-products/repayment-frequency product) "monthly"))
                                schedule (finance/build-schedule
                                           {:method          (:loan-products/interest-method product)
                                            :principal       amount
                                            :annual-rate     rate
                                            :duration-months months
                                            :freq            freq
                                            :currency        (:loan-products/currency product)})
                                currency (:loan-products/currency product)
                                loan     (jdbc/with-transaction [tx ds]
                                           ;; with-transaction's tx does NOT inherit ds's
                                           ;; with-kebab-keys wrapping — re-wrap or every
                                           ;; hyphenated key access below silently returns nil.
                                           (let [tx (db/with-kebab-keys tx)
                                                 loan (loans-db/create-loan! tx
                                                        {:tenant-id             tenant-id
                                                         :loan-no               (loan-no)
                                                         :application-id        app-id
                                                         :branch-id             (:loan-applications/branch-id app)
                                                         :customer-id           (:loan-applications/customer-id app)
                                                         :product-id            (:loan-applications/product-id app)
                                                         :principal             amount
                                                         :interest-rate         (* rate 100)
                                                         :interest-method       (name (:loan-products/interest-method product))
                                                         :duration-months       months
                                                         :repayment-freq        (name freq)
                                                         :currency              currency
                                                         :outstanding-principal amount
                                                         :disbursed-at          [:now]
                                                         :disbursed-by          (:user-id identity)
                                                         :status                "active"})
                                                 ledger-entry (accounting/disbursement-entry
                                                                {:loan-id      (:loans/loan-no loan)
                                                                 :reference-id (:loans/id loan)
                                                                 :amount       (double amount)
                                                                 :currency     currency})]
                                             (accounting/validate-entry ledger-entry)
                                             (loans-db/insert-schedule! tx (:loans/id loan) freq schedule)
                                             (loans-db/update-application! tx tenant-id app-id {:status "disbursed"})
                                             (ledger/post-entry! tx
                                               {:tenant-id tenant-id :posted-by (:user-id identity)}
                                               ledger-entry)
                                             loan))]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "loan.disbursed"
                                            :entity-type "loan"
                                            :entity-id   (:loans/id loan)})
                            (events/publish! bus {:event-type events/LOAN-DISBURSED
                                                  :tenant-id  tenant-id
                                                  :loan-id    (:loans/id loan)
                                                  :amount     amount})
                            {:status 201 :body loan}))))}}]

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
                          :body   (loans-db/list-loans ds tenant-id
                                    (assoc query-params :branch-id (rbac/scope-branch-id identity)))})}}]

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
                          (let [loan-id (parse-uuid (:id path-params))
                                loan    (loans-db/find-loan ds tenant-id loan-id)]
                          (if-not loan
                            {:status 404 :body {:error "Loan not found"}}
                          (let [{:keys [amount payment-method reference]} body-params
                                rate              (/ (:loans/interest-rate loan) 100)
                                interest-due      (* (:loans/outstanding-principal loan) (/ rate 12))
                                interest-portion  (min amount interest-due)
                                principal-portion (- amount interest-portion)
                                currency          (:loans/currency loan)
                                payment           (jdbc/with-transaction [tx ds]
                                                    (let [tx (db/with-kebab-keys tx)
                                                          payment (loans-db/record-payment! tx
                                                                    {:tenant-id         tenant-id
                                                                     :loan-id           loan-id
                                                                     :payment-no        (pay-no)
                                                                     :amount            amount
                                                                     :principal-portion principal-portion
                                                                     :interest-portion  interest-portion
                                                                     :payment-method    payment-method
                                                                     :reference         reference
                                                                     :recorded-by       (:user-id identity)})
                                                          ledger-entry (accounting/payment-entry
                                                                         {:payment-id        (:payments/payment-no payment)
                                                                          :reference-id      (:payments/id payment)
                                                                          :principal-portion (double principal-portion)
                                                                          :interest-portion  (double interest-portion)
                                                                          :currency          currency})]
                                                      (accounting/validate-entry ledger-entry)
                                                      (loans-db/update-loan! tx tenant-id loan-id
                                                        {:outstanding-principal (- (:loans/outstanding-principal loan) principal-portion)
                                                         :total-paid-principal  (+ (or (:loans/total-paid-principal loan) 0M) principal-portion)
                                                         :total-paid-interest   (+ (or (:loans/total-paid-interest loan) 0M) interest-portion)
                                                         :last-payment-date     [:now]})
                                                      (ledger/post-entry! tx
                                                        {:tenant-id tenant-id :posted-by (:user-id identity)}
                                                        ledger-entry)
                                                      payment))]
                            (events/publish! bus {:event-type events/PAYMENT-RECEIVED
                                                  :tenant-id  tenant-id
                                                  :loan-id    loan-id
                                                  :amount     amount
                                                  :payment-id (:payments/id payment)})
                            {:status 201 :body payment}))))}}]

   ["/loans/:id/restructure"
    {:post {:summary    "Restructure a loan (rate/term change). Snapshots the
                         prior terms to history and regenerates the remaining
                         schedule for the new terms — paid/partial
                         installments are left untouched as real history."
            :tags       ["Loans"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/RestructureRequest}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :loan/restructure)
                          (let [loan-id (parse-uuid (:id path-params))
                                loan    (loans-db/find-loan ds tenant-id loan-id)]
                            (if-not loan
                              {:status 404 :body {:error "Loan not found"}}
                              (let [{:keys [new-interest-rate new-duration-months
                                            new-repayment-freq reason]} body-params
                                    rate    (/ (or new-interest-rate (:loans/interest-rate loan)) 100)
                                    months  (or new-duration-months (:loans/duration-months loan))
                                    freq    (keyword (or new-repayment-freq (:loans/repayment-freq loan)))
                                    changes (cond-> {}
                                              new-interest-rate   (assoc :interest-rate new-interest-rate)
                                              new-duration-months (assoc :duration-months new-duration-months)
                                              new-repayment-freq  (assoc :repayment-freq new-repayment-freq))
                                    new-schedule (finance/build-schedule
                                                   {:method          (keyword (:loans/interest-method loan))
                                                    :principal       (:loans/outstanding-principal loan)
                                                    :annual-rate     rate
                                                    :duration-months months
                                                    :freq            freq
                                                    :currency        (:loans/currency loan)})
                                    updated (jdbc/with-transaction [tx ds]
                                              (let [tx (db/with-kebab-keys tx)]
                                                (loans-db/log-restructuring! tx
                                                  {:tenant-id                       tenant-id
                                                   :loan-id                         loan-id
                                                   :sequence-no                     (inc (or (:loans/restructure-count loan) 0))
                                                   :reason                          reason
                                                   :previous-interest-rate          (:loans/interest-rate loan)
                                                   :previous-duration-months        (:loans/duration-months loan)
                                                   :previous-repayment-freq         (:loans/repayment-freq loan)
                                                   :previous-outstanding-principal  (:loans/outstanding-principal loan)
                                                   :new-interest-rate               (* rate 100)
                                                   :new-duration-months             months
                                                   :new-repayment-freq              (name freq)
                                                   :restructured-by                 (:user-id identity)})
                                                (loans-db/delete-pending-schedule! tx loan-id)
                                                (let [offset (loans-db/max-installment-no tx loan-id)]
                                                  (loans-db/insert-schedule! tx loan-id freq new-schedule offset))
                                                (loans-db/restructure-loan! tx tenant-id loan-id
                                                  changes (:user-id identity))))]
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
                                {:status 200 :body updated}))))}}]

   ["/loans/:id/restructurings"
    {:get {:summary    "Restructuring history for a loan"
           :tags       ["Loans"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity path-params]}]
                         (rbac/require-permission identity :loan/read)
                         {:status 200
                          :body   (loans-db/restructuring-history ds (parse-uuid (:id path-params)))})}}]

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

   ["/customers/:id/loans"
    {:get {:summary    "Get all loans for a customer"
           :tags       ["Customers" "Loans"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :loan/read)
                         {:status 200
                          :body   (loans-db/customer-loans ds tenant-id
                                                           (parse-uuid (:id path-params)))})}}]])
