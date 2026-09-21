(ns loanmanager.api.routes.repayment
  (:require [next.jdbc :as jdbc]
            [clojure.string :as str]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.db.loans :as loans-db]
            [loanmanager.db.connection :as db]
            [loanmanager.db.integrations :as integrations-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.domain.finance :as finance]
            [loanmanager.domain.accounting :as accounting]
            [loanmanager.db.ledger :as ledger]
            [loanmanager.events.bus :as events]
            [loanmanager.security.rbac :as rbac]))

(defn- reserve-idempotency! [ds tenant-id headers body]
  (let [key      (get headers "idempotency-key")
        hash     (str (clojure.core/hash body))
        existing (when key (integrations-db/find-idempotency ds tenant-id key))
        created  (when (and key (nil? existing))
                   (integrations-db/begin-idempotency! ds tenant-id key hash))
        record   (or existing created)]
    (cond
      (str/blank? key)
      (throw (ex-info "Idempotency-Key header is required" {:type :validation}))
      (nil? record)
      (throw (ex-info "A request with this idempotency key is already processing"
                      {:type :idempotency-in-progress}))
      (not= hash (:idempotency-keys/request-hash record))
      (throw (ex-info "Idempotency key was reused for a different request"
                      {:type :validation}))
      :else {:key key :hash hash :record record})))

(defn- cached-response [reservation]
  (when-let [record (:record reservation)]
    (when (:idempotency-keys/completed-at record)
      {:status (:idempotency-keys/response-status record)
       :body   (:idempotency-keys/response-body record)})))

(defn routes [ds bus]
  [;; ── Full repayment simulator (public) ────────────────────────────────────
   ["/loans/simulate"
    {:post {:summary    "Simulate repayment schedule — all methods and frequencies"
            :tags       ["Repayment Engine" "Tools"]
            :parameters {:body schemas/SimulateRequest}
            :handler    (fn [{{:keys [principal annual-rate months method
                                      frequency grace-period-months balloon-pct]} :body-params}]
                          (let [freq     (keyword (or frequency "monthly"))
                                meth     (keyword (or method "reducing_balance"))
                                schedule (finance/build-schedule
                                           {:method               meth
                                            :principal            principal
                                            :annual-rate          (/ annual-rate 100)
                                            :duration-months      months
                                            :freq                 freq
                                            :grace-period-months  (or grace-period-months 0)
                                            :balloon-pct          balloon-pct})
                                summary  (finance/schedule-summary schedule principal)]
                            {:status 200
                             :body   {:method          (name meth)
                                      :frequency       (name freq)
                                      :summary         summary
                                      :schedule        schedule}}))}}]

   ;; ── Early repayment settlement quote ─────────────────────────────────────
   ["/loans/:id/settlement-quote"
    {:get {:summary    "Get full settlement amount for early repayment"
           :tags       ["Repayment Engine" "Loans"]
           :parameters {:path  [:map [:id :string]]
                        :query [:map [:days-since-last-payment {:optional true} :int]]}
           :handler    (fn [{:keys [identity tenant-id path-params query-params]}]
                         (rbac/require-permission identity :loan/read)
                         (let [loan-id (parse-uuid (:id path-params))
                               loan    (loans-db/find-loan ds tenant-id loan-id)]
                           (if-not loan
                             {:status 404 :body {:error "Loan not found"}}
                             (let [schedule (loans-db/get-schedule ds tenant-id loan-id)
                                   total-interest-original (reduce + (map :repayment-schedules/interest-due schedule))
                                   total-interest-paid     (:loans/total-paid-interest loan)
                                   days    (or (:days-since-last-payment query-params) 0)
                                   quote   (finance/early-repayment-settlement
                                             {:outstanding-principal    (:loans/outstanding-principal loan)
                                              :annual-rate              (/ (:loans/interest-rate loan) 100)
                                              :days-since-last-payment  days
                                              :original-total-interest  total-interest-original
                                              :total-interest-paid      total-interest-paid})]
                               {:status 200
                                :body   (assoc quote
                                               :loan-no  (:loans/loan-no loan)
                                               :currency (:loans/currency loan))}))))}}]

   ;; ── Partial prepayment ────────────────────────────────────────────────────
   ["/loans/:id/prepayment"
    {:post {:summary    "Make a partial principal prepayment and recalculate schedule"
            :tags       ["Repayment Engine" "Payments"]
            :parameters {:path [:map [:id :string]]
                         :headers [:map [:idempotency-key :string]]
                         :body schemas/PrepaymentRequest}
            :handler    (fn [{:keys [identity tenant-id path-params body-params headers]}]
                          (rbac/require-permission identity :payment/create)
                          (let [loan-id (parse-uuid (:id path-params))
                                loan    (loans-db/find-loan ds tenant-id loan-id)]
                            (if-not loan
                              {:status 404 :body {:error "Loan not found"}}
                              (let [reservation (reserve-idempotency! ds tenant-id headers body-params)]
                                (if-let [cached (cached-response reservation)]
                                  cached
                                  (let [{:keys [prepayment-amount payment-method reference]} body-params
                                        new-outstanding (- (:loans/outstanding-principal loan) prepayment-amount)
                                        _ (when (neg? new-outstanding)
                                            (throw (ex-info "Prepayment exceeds outstanding balance"
                                                            {:type :validation})))
                                        result (jdbc/with-transaction [tx ds]
                                                 (let [tx (db/with-kebab-keys tx)
                                                       schedule (loans-db/get-schedule tx tenant-id loan-id)
                                                       pending-count (count (filter #(= "pending" (:repayment-schedules/status %)) schedule))
                                                       recalculated (finance/recalculate-after-prepayment
                                                                      {:outstanding-principal new-outstanding
                                                                       :annual-rate (/ (:loans/interest-rate loan) 100)
                                                                       :remaining-periods pending-count
                                                                       :freq (keyword (:loans/repayment-freq loan))
                                                                       :method (keyword (:loans/interest-method loan))
                                                                       :currency (:loans/currency loan)})
                                                       payment (loans-db/record-payment! tx
                                                                 {:tenant-id tenant-id :loan-id loan-id
                                                                  :payment-no (str "PRE-" (System/currentTimeMillis))
                                                                  :amount prepayment-amount
                                                                  :principal-portion prepayment-amount
                                                                  :interest-portion 0
                                                                  :payment-method payment-method
                                                                  :reference reference
                                                                  :recorded-by (:user-id identity)})
                                                       entry (accounting/payment-entry
                                                               {:payment-id (:payments/payment-no payment)
                                                                :reference-id (:payments/id payment)
                                                                :principal-portion prepayment-amount
                                                                :interest-portion 0
                                                                :currency (:loans/currency loan)})]
                                                   (accounting/validate-entry entry)
                                                   (ledger/post-entry! tx {:tenant-id tenant-id :posted-by (:user-id identity)} entry)
                                                   (loans-db/update-loan! tx tenant-id loan-id
                                                     {:outstanding-principal new-outstanding
                                                      :total-paid-principal (+ (:loans/total-paid-principal loan) prepayment-amount)
                                                      :last-payment-date [:now]})
                                                   (loans-db/delete-pending-schedule! tx loan-id)
                                                   (loans-db/insert-schedule! tx loan-id
                                                     (keyword (:loans/repayment-freq loan))
                                                     (:new-schedule recalculated)
                                                     (loans-db/max-paid-installment-no tx loan-id))
                                                   {:payment payment
                                                    :new-outstanding new-outstanding
                                                    :new-schedule (:new-schedule recalculated)
                                                    :new-monthly-payment (:new-monthly-payment recalculated)
                                                    :remaining-periods (:remaining-periods recalculated)}))
                                        response {:status 200 :body result}]
                                    (integrations-db/complete-idempotency! ds tenant-id
                                      (:key reservation) (:hash reservation) (:status response) (:body response))
                                    (audit/log! ds {:tenant-id tenant-id :user-id (:user-id identity)
                                                    :action "loan.prepayment" :entity-type "loan" :entity-id loan-id
                                                    :after-state {:outstanding new-outstanding :prepayment prepayment-amount}})
                                    (events/publish! bus {:event-type events/PAYMENT-RECEIVED
                                                          :tenant-id tenant-id :loan-id loan-id
                                                          :amount prepayment-amount :type :prepayment})
                                    response))))))}}]

   ;; ── Full early settlement ─────────────────────────────────────────────────
   ["/loans/:id/settle"
    {:post {:summary    "Fully settle a loan (pay all outstanding balance)"
            :tags       ["Repayment Engine" "Loans"]
            :parameters {:path [:map [:id :string]]
                         :headers [:map [:idempotency-key :string]]
                         :body schemas/PaymentCreate}
            :handler    (fn [{:keys [identity tenant-id path-params body-params headers]}]
                          (rbac/require-permission identity :payment/create)
                          (let [loan-id (parse-uuid (:id path-params))
                                loan    (loans-db/find-loan ds tenant-id loan-id)]
                            (if-not loan
                              {:status 404 :body {:error "Loan not found"}}
                              (let [reservation (reserve-idempotency! ds tenant-id headers body-params)]
                                (if-let [cached (cached-response reservation)]
                                  cached
                                  (let [result (jdbc/with-transaction [tx ds]
                                                 (let [tx (db/with-kebab-keys tx)
                                                       schedule (loans-db/get-schedule tx tenant-id loan-id)
                                                       total-interest-original (reduce + (map :repayment-schedules/interest-due schedule))
                                                       quote (finance/early-repayment-settlement
                                                               {:outstanding-principal (:loans/outstanding-principal loan)
                                                                :annual-rate (/ (:loans/interest-rate loan) 100)
                                                                :days-since-last-payment 0
                                                                :original-total-interest total-interest-original
                                                                :total-interest-paid (:loans/total-paid-interest loan)})
                                                       payment (loans-db/record-payment! tx
                                                                 {:tenant-id tenant-id :loan-id loan-id
                                                                  :payment-no (str "SET-" (System/currentTimeMillis))
                                                                  :amount (:settlement-amount quote)
                                                                  :principal-portion (:principal-outstanding quote)
                                                                  :interest-portion (:accrued-interest quote)
                                                                  :payment-method (:payment-method body-params)
                                                                  :reference (:reference body-params)
                                                                  :recorded-by (:user-id identity)})
                                                       entry (accounting/payment-entry
                                                               {:payment-id (:payments/payment-no payment)
                                                                :reference-id (:payments/id payment)
                                                                :principal-portion (:principal-outstanding quote)
                                                                :interest-portion (:accrued-interest quote)
                                                                :currency (:loans/currency loan)})]
                                                   (accounting/validate-entry entry)
                                                   (ledger/post-entry! tx {:tenant-id tenant-id :posted-by (:user-id identity)} entry)
                                                   (loans-db/mark-schedule-settled! tx loan-id)
                                                   (loans-db/update-loan! tx tenant-id loan-id
                                                     {:outstanding-principal 0 :status "closed" :last-payment-date [:now]})
                                                   {:payment payment :settlement-quote quote :loan-status "closed"}))
                                        response {:status 200 :body result}]
                                    (integrations-db/complete-idempotency! ds tenant-id
                                      (:key reservation) (:hash reservation) (:status response) (:body response))
                                    (audit/log! ds {:tenant-id tenant-id :user-id (:user-id identity)
                                                    :action "loan.settled" :entity-type "loan" :entity-id loan-id
                                                    :after-state {:settlement-amount (get-in result [:settlement-quote :settlement-amount])}})
                                    (events/publish! bus {:event-type events/LOAN-CLOSED
                                                          :tenant-id tenant-id :loan-id loan-id})
                                    response))))))}}]])
