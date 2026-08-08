(ns loanmanager.api.routes.repayment
  (:require [loanmanager.api.schemas :as schemas]
            [loanmanager.db.loans :as loans-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.domain.finance :as finance]
            [loanmanager.events.bus :as events]
            [loanmanager.security.rbac :as rbac]))

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
                               loan    (loans-db/find-loan ds tenant-id loan-id)
                               schedule (loans-db/get-schedule ds loan-id)
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
                                           :currency (:loans/currency loan))}))}}]

   ;; ── Partial prepayment ────────────────────────────────────────────────────
   ["/loans/:id/prepayment"
    {:post {:summary    "Make a partial principal prepayment and recalculate schedule"
            :tags       ["Repayment Engine" "Payments"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/PrepaymentRequest}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :payment/create)
                          (let [loan-id           (parse-uuid (:id path-params))
                                loan              (loans-db/find-loan ds tenant-id loan-id)
                                {:keys [prepayment-amount payment-method reference]} body-params
                                new-outstanding   (- (:loans/outstanding-principal loan)
                                                     prepayment-amount)
                                _                 (when (neg? new-outstanding)
                                                    (throw (ex-info "Prepayment exceeds outstanding balance"
                                                                    {:type       :validation
                                                                     :outstanding (:loans/outstanding-principal loan)
                                                                     :prepayment  prepayment-amount})))
                                ;; Remaining periods from schedule
                                remaining-schedule (loans-db/get-schedule ds loan-id)
                                pending-count      (count (filter #(= "pending" (:repayment-schedules/status %))
                                                                  remaining-schedule))
                                ;; Record as payment (principal only)
                                pay-no            (str "PRE-" (System/currentTimeMillis))
                                payment           (loans-db/record-payment! ds
                                                    {:tenant-id         tenant-id
                                                     :loan-id           loan-id
                                                     :payment-no        pay-no
                                                     :amount            prepayment-amount
                                                     :principal-portion prepayment-amount
                                                     :interest-portion  0
                                                     :payment-method    payment-method
                                                     :reference         reference
                                                     :recorded-by       (:user-id identity)})
                                ;; Recalculate remaining schedule
                                new-schedule      (finance/recalculate-after-prepayment
                                                    {:outstanding-principal new-outstanding
                                                     :annual-rate           (/ (:loans/interest-rate loan) 100)
                                                     :remaining-periods     pending-count
                                                     :freq                  (keyword (:loans/repayment-freq loan))
                                                     :method                (keyword (:loans/interest-method loan))
                                                     :currency              (:loans/currency loan)})]
                            ;; Update loan balance
                            (loans-db/update-loan! ds tenant-id loan-id
                              {:outstanding-principal new-outstanding
                               :total-paid-principal  (+ (:loans/total-paid-principal loan)
                                                         prepayment-amount)
                               :last-payment-date     [:now]})
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "loan.prepayment"
                                            :entity-type "loan"
                                            :entity-id   loan-id
                                            :before-state {:outstanding (:loans/outstanding-principal loan)}
                                            :after-state  {:outstanding new-outstanding
                                                           :prepayment  prepayment-amount}})
                            (events/publish! bus {:event-type events/PAYMENT-RECEIVED
                                                  :tenant-id  tenant-id
                                                  :loan-id    loan-id
                                                  :amount     prepayment-amount
                                                  :type       :prepayment})
                            {:status 200
                             :body   {:payment          payment
                                      :new-outstanding  new-outstanding
                                      :new-schedule     (:new-schedule new-schedule)
                                      :new-monthly-payment (:new-monthly-payment new-schedule)
                                      :remaining-periods   (:remaining-periods new-schedule)}}))}}]

   ;; ── Full early settlement ─────────────────────────────────────────────────
   ["/loans/:id/settle"
    {:post {:summary    "Fully settle a loan (pay all outstanding balance)"
            :tags       ["Repayment Engine" "Loans"]
            :parameters {:path [:map [:id :string]]
                         :body schemas/PaymentCreate}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :payment/create)
                          (let [loan-id  (parse-uuid (:id path-params))
                                loan     (loans-db/find-loan ds tenant-id loan-id)
                                schedule (loans-db/get-schedule ds loan-id)
                                total-interest-original (reduce + (map :repayment-schedules/interest-due schedule))
                                quote    (finance/early-repayment-settlement
                                           {:outstanding-principal   (:loans/outstanding-principal loan)
                                            :annual-rate             (/ (:loans/interest-rate loan) 100)
                                            :days-since-last-payment 0
                                            :original-total-interest total-interest-original
                                            :total-interest-paid     (:loans/total-paid-interest loan)})
                                settlement-amount (:settlement-amount quote)
                                payment  (loans-db/record-payment! ds
                                           {:tenant-id         tenant-id
                                            :loan-id           loan-id
                                            :payment-no        (str "SET-" (System/currentTimeMillis))
                                            :amount            settlement-amount
                                            :principal-portion (:principal-outstanding quote)
                                            :interest-portion  (:accrued-interest quote)
                                            :payment-method    (:payment-method body-params)
                                            :reference         (:reference body-params)
                                            :recorded-by       (:user-id identity)})]
                            (loans-db/update-loan! ds tenant-id loan-id
                              {:outstanding-principal 0
                               :status                "closed"
                               :last-payment-date     [:now]})
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "loan.settled"
                                            :entity-type "loan"
                                            :entity-id   loan-id
                                            :after-state {:settlement-amount settlement-amount}})
                            (events/publish! bus {:event-type events/LOAN-CLOSED
                                                  :tenant-id  tenant-id
                                                  :loan-id    loan-id})
                            {:status 200
                             :body   {:payment          payment
                                      :settlement-quote quote
                                      :loan-status      "closed"}}))}}]])
