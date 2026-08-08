(ns loanmanager.domain.finance
  "Pure financial calculation functions — no side effects, fully testable.")

;; ── Interest ─────────────────────────────────────────────────────────────────

(defn monthly-rate [annual-rate]
  (/ annual-rate 12.0))

(defn flat-monthly-payment
  "Equal principal + flat interest each month."
  [principal annual-rate months]
  (let [monthly-interest (* principal (/ annual-rate 12.0))]
    (+ (/ principal months) monthly-interest)))

(defn reducing-balance-payment
  "Standard annuity formula — equal total payment, reducing principal."
  [principal annual-rate months]
  (if (zero? annual-rate)
    (/ principal months)
    (let [r (monthly-rate annual-rate)]
      (* principal (/ (* r (Math/pow (+ 1 r) months))
                      (- (Math/pow (+ 1 r) months) 1))))))

;; ── Amortization schedule ────────────────────────────────────────────────────

(defn- next-due-date [start-date installment-no freq]
  ;; Returns ISO date string — real impl uses tick/java.time
  (let [months (case freq
                 :monthly    installment-no
                 :quarterly  (* installment-no 3)
                 :biweekly   nil   ; handled separately
                 :weekly     nil
                 installment-no)]
    {:months-offset months}))

(defn build-schedule
  "Returns a vector of installment maps for a reducing-balance loan."
  [{:keys [principal annual-rate months currency]
    :or   {currency "USD"}}]
  (let [payment (reducing-balance-payment principal annual-rate months)
        r       (monthly-rate annual-rate)]
    (loop [remaining principal
           n         1
           schedule  []]
      (if (> n months)
        schedule
        (let [interest-due  (* remaining r)
              principal-due (- payment interest-due)
              ;; Last installment: clear rounding residual
              principal-due (if (= n months) remaining principal-due)]
          (recur (- remaining principal-due)
                 (inc n)
                 (conj schedule
                       {:installment-no n
                        :principal-due  (bigdec (format "%.2f" principal-due))
                        :interest-due   (bigdec (format "%.2f" interest-due))
                        :total-due      (bigdec (format "%.2f" (+ principal-due interest-due)))
                        :currency       currency
                        :status         :pending})))))))

;; ── Early repayment recalculation ────────────────────────────────────────────

(defn recalculate-after-prepayment
  "Given outstanding principal after a partial prepayment, recalculate
   remaining schedule."
  [{:keys [outstanding-principal annual-rate remaining-months currency]}]
  (build-schedule {:principal    outstanding-principal
                   :annual-rate  annual-rate
                   :months       remaining-months
                   :currency     currency}))

;; ── DTI ──────────────────────────────────────────────────────────────────────

(defn debt-to-income
  "Returns DTI ratio as a decimal (e.g. 0.35 = 35%)."
  [monthly-income existing-obligations new-payment]
  (if (pos? monthly-income)
    (/ (+ existing-obligations new-payment) monthly-income)
    1.0))

(defn dti-eligible?
  "Returns true if DTI is within the bank's policy threshold."
  [dti threshold]
  (<= dti threshold))

;; ── LTV ──────────────────────────────────────────────────────────────────────

(defn loan-to-value [loan-amount collateral-value]
  (if (pos? collateral-value)
    (/ loan-amount collateral-value)
    1.0))

;; ── Processing fee ───────────────────────────────────────────────────────────

(defn processing-fee [principal fee-pct]
  (* principal fee-pct))

;; ── Total cost of credit ─────────────────────────────────────────────────────

(defn total-cost
  "Sum of all interest payments across the full schedule."
  [schedule]
  (reduce + (map :interest-due schedule)))
