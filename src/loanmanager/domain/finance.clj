(ns loanmanager.domain.finance
  "Pure financial calculation functions — no side effects, fully testable.
   Supports: reducing-balance, flat, compound, balloon, interest-only,
             principal-only, grace periods, all repayment frequencies."
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal MathContext RoundingMode]))

(def ^:private decimal-context (MathContext. 34 RoundingMode/HALF_EVEN))

(defn- decimal [value]
  (cond
    (instance? BigDecimal value) value
    (nil? value)                BigDecimal/ZERO
    :else                       (bigdec (str value))))

;; ── Frequency helpers ─────────────────────────────────────────────────────────

(def ^:private periods-per-year
  {:daily     365
   :weekly    52
   :biweekly  26
   :monthly   12
   :quarterly 4
   :bullet    1})

(defn periods-in-loan
  "Total number of payment periods for a loan given duration in months."
  [duration-months freq]
  (case freq
    :daily     (* duration-months 30)
    :weekly    (* duration-months 4)
    :biweekly  (* duration-months 2)
    :monthly   duration-months
    :quarterly (max 1 (quot duration-months 3))
    :bullet    1))

(defn periodic-rate
  "Interest rate per payment period from annual rate."
  [annual-rate freq]
  (.divide (decimal annual-rate)
           (decimal (get periods-per-year freq 12))
           decimal-context))

;; ── Payment formulas ──────────────────────────────────────────────────────────

(defn reducing-balance-payment
  "Standard annuity — equal total payment, reducing principal.
   Works for any frequency."
  [principal annual-rate n-periods freq]
  (let [principal (decimal principal)
        r         (periodic-rate annual-rate freq)
        growth    (.pow (.add BigDecimal/ONE r) n-periods decimal-context)]
    (if (zero? r)
      (.divide principal (decimal n-periods) decimal-context)
      (.multiply principal
                 (.divide (.multiply r growth)
                          (.subtract growth BigDecimal/ONE)
                          decimal-context)
                 decimal-context))))

(defn flat-periodic-payment
  "Equal principal + flat interest on original principal each period."
  [principal annual-rate n-periods freq]
  (let [principal (decimal principal)
        r         (periodic-rate annual-rate freq)]
    (+ (.divide principal (decimal n-periods) decimal-context)
       (.multiply principal r decimal-context))))

;; ── Schedule builders ─────────────────────────────────────────────────────────

(defn- round2 [n]
  (.setScale (decimal n) 2 RoundingMode/HALF_EVEN))

(defn- base-installment [n principal-due interest-due currency]
  {:installment-no n
   :principal-due  (round2 principal-due)
   :interest-due   (round2 interest-due)
   :total-due      (round2 (+ principal-due interest-due))
   :currency       currency
   :status         :pending})

(defn build-reducing-balance-schedule
  [{:keys [principal annual-rate n-periods freq currency grace-period-periods]
    :or   {currency "USD" grace-period-periods 0 freq :monthly}}]
  (let [r            (periodic-rate annual-rate freq)
        total-periods (+ n-periods grace-period-periods)
        payment      (reducing-balance-payment principal annual-rate n-periods freq)]
    (loop [remaining principal
           n         1
           schedule  []]
      (if (> n total-periods)
        schedule
        (let [grace?        (< n (inc grace-period-periods))
              interest-due  (* remaining r)
              principal-due (if grace? 0 (- payment interest-due))
              principal-due (if (= n total-periods)
                              remaining
                              principal-due)]
          (recur (- remaining principal-due)
                 (inc n)
                 (conj schedule
                       (assoc (base-installment n principal-due interest-due currency)
                              :grace-period? grace?))))))))
(defn build-flat-schedule
  [{:keys [principal annual-rate n-periods freq currency grace-period-periods]
    :or   {currency "USD" grace-period-periods 0 freq :monthly}}]
  (let [r              (periodic-rate annual-rate freq)
        principal-each (/ principal n-periods)
        interest-each  (* principal r)
        total-periods  (+ n-periods grace-period-periods)]
    (loop [n        1
           schedule []]
      (if (> n total-periods)
        schedule
        (let [grace? (< n (inc grace-period-periods))]
          (recur (inc n)
                 (conj schedule
                       (assoc (base-installment n
                                                (if grace? 0 principal-each)
                                                interest-each
                                                currency)
                              :grace-period? grace?))))))))

(defn build-interest-only-schedule
  "Interest-only payments for all periods, full principal due at end (balloon)."
  [{:keys [principal annual-rate n-periods freq currency]
    :or   {currency "USD" freq :monthly}}]
  (let [r            (periodic-rate annual-rate freq)
        interest-each (* principal r)]
    (mapv (fn [n]
            (base-installment n
                              (if (= n n-periods) principal 0)
                              interest-each
                              currency))
          (range 1 (inc n-periods)))))

(defn build-balloon-schedule
  "Reduced payments for most periods, large balloon payment at end.
   balloon-pct: fraction of principal paid at end (e.g. 0.5 = 50%)."
  [{:keys [principal annual-rate n-periods freq currency balloon-pct]
    :or   {currency "USD" freq :monthly balloon-pct 0.3}}]
  (let [balloon-amount  (* principal balloon-pct)
        amort-principal (- principal balloon-amount)
        r               (periodic-rate annual-rate freq)
        payment         (reducing-balance-payment amort-principal annual-rate n-periods freq)]
    (loop [remaining amort-principal
           n         1
           schedule  []]
      (if (> n n-periods)
        schedule
        (let [interest-due  (* remaining r)
              principal-due (if (= n n-periods)
                              (+ remaining balloon-amount)
                              (- payment interest-due))]
          (recur (- remaining (if (= n n-periods) remaining (- payment interest-due)))
                 (inc n)
                 (conj schedule
                       (assoc (base-installment n principal-due interest-due currency)
                              :balloon? (= n n-periods)))))))))

(defn build-principal-only-schedule
  "Equal principal payments, no interest (e.g. internal staff loans)."
  [{:keys [principal n-periods currency]
    :or   {currency "USD"}}]
  (let [principal-each (/ principal n-periods)]
    (mapv (fn [n]
            (base-installment n principal-each 0 currency))
          (range 1 (inc n-periods)))))

(defn build-schedule
  "Unified schedule builder. Dispatches on :method keyword.
   Methods: :reducing-balance (default), :flat, :interest-only,
            :balloon, :principal-only"
  [{:keys [method principal annual-rate duration-months freq
           currency grace-period-months balloon-pct]
    :or   {method               :reducing-balance
           freq                 :monthly
           currency             "USD"
           grace-period-months  0
           annual-rate          0}}]
  ;; interest_method is stored as an underscored string ("reducing_balance",
  ;; "interest_only", ...) but dispatch below uses hyphenated keywords —
  ;; normalize here so every caller (string or keyword, either separator)
  ;; reaches the right branch instead of silently falling back to default.
  (let [method              (-> method name (str/replace "_" "-") keyword)
        ;; principal/annual-rate may arrive as BigDecimal from NUMERIC DB
        ;; columns; keep them decimal so financial calculations remain exact.
        principal           (decimal principal)
        annual-rate         (decimal annual-rate)
        n-periods           (periods-in-loan duration-months freq)
        grace-periods       (if (zero? grace-period-months) 0
                               (periods-in-loan grace-period-months freq))
        base                {:principal            principal
                             :annual-rate          annual-rate
                             :n-periods            n-periods
                             :freq                 freq
                             :currency             currency
                             :grace-period-periods grace-periods}]
    (case method
      :reducing-balance (build-reducing-balance-schedule base)
      :flat             (build-flat-schedule base)
      :interest-only    (build-interest-only-schedule base)
      :balloon          (build-balloon-schedule (assoc base :balloon-pct (or balloon-pct 0.3)))
      :principal-only   (build-principal-only-schedule base)
      (build-reducing-balance-schedule base))))

;; ── Early repayment ───────────────────────────────────────────────────────────

(defn early-repayment-settlement
  "Calculate full settlement amount on a given date.
   Returns: {:settlement-amount :principal-outstanding :accrued-interest :savings}"
  [{:keys [outstanding-principal annual-rate days-since-last-payment
           original-total-interest total-interest-paid]}]
    (let [daily-rate      (.divide (decimal annual-rate) (decimal 365) decimal-context)
      accrued-interest (.multiply (decimal outstanding-principal)
              (.multiply daily-rate (decimal days-since-last-payment)
                    decimal-context)
              decimal-context)
        settlement       (+ outstanding-principal accrued-interest)
        interest-saved   (- original-total-interest total-interest-paid accrued-interest)]
    {:settlement-amount    (round2 settlement)
     :principal-outstanding (round2 outstanding-principal)
     :accrued-interest      (round2 accrued-interest)
     :interest-saved        (round2 (max 0 interest-saved))}))

(defn recalculate-after-prepayment
  "After a partial principal prepayment, rebuild the remaining schedule.
   Returns new schedule + summary of changes."
  [{:keys [outstanding-principal annual-rate remaining-periods freq
           currency method]
    :or   {method :reducing-balance freq :monthly currency "USD"}}]
  (let [new-schedule (build-schedule
                       {:method           method
                        :principal        outstanding-principal
                        :annual-rate      annual-rate
                        :duration-months  remaining-periods
                        :freq             freq
                        :currency         currency})
        new-payment  (-> new-schedule first :total-due)]
    {:new-schedule        new-schedule
     :new-monthly-payment new-payment
     :remaining-periods   remaining-periods
     :total-remaining     (reduce + (map :total-due new-schedule))}))

;; ── DTI analysis ─────────────────────────────────────────────────────────────

(defn debt-to-income
  "Returns DTI ratio as a decimal (e.g. 0.35 = 35%)."
  [monthly-income existing-obligations new-payment]
  (if (pos? monthly-income)
    (.divide (+ (decimal existing-obligations) (decimal new-payment))
             (decimal monthly-income)
             decimal-context)
    BigDecimal/ONE))

(defn dti-analysis
  "Full DTI breakdown with policy assessment.
   threshold: bank's max DTI policy (default 0.45)"
  [{:keys [monthly-income existing-obligations new-payment
           threshold]
    :or   {threshold 0.45}}]
  (let [total-obligations (+ existing-obligations new-payment)
        dti               (debt-to-income monthly-income existing-obligations new-payment)
        eligible?         (<= dti threshold)
        headroom          (- (* monthly-income threshold) total-obligations)]
    {:monthly-income       (round2 monthly-income)
     :existing-obligations (round2 existing-obligations)
     :new-payment          (round2 new-payment)
     :total-obligations    (round2 total-obligations)
     :dti-ratio            (round2 dti)
     :dti-pct              (str (format "%.1f" (* dti 100)) "%")
     :threshold-pct        (str (format "%.0f" (* threshold 100)) "%")
     :eligible?            eligible?
     :headroom             (round2 headroom)
     :verdict              (if eligible?
                             "PASS — within policy threshold"
                             (str "FAIL — exceeds " (format "%.0f" (* threshold 100)) "% threshold by "
                                  (format "%.1f" (* (- dti threshold) 100)) "%"))}))

(defn dti-eligible? [dti threshold] (<= dti threshold))

;; ── LTV ──────────────────────────────────────────────────────────────────────

(defn loan-to-value [loan-amount collateral-value]
  (if (pos? collateral-value)
    (.divide (decimal loan-amount) (decimal collateral-value) decimal-context)
    BigDecimal/ONE))

(defn ltv-analysis
  "Full LTV breakdown with policy assessment.
   threshold: bank's max acceptable LTV policy (default 0.80 — 80%)."
  [{:keys [loan-amount collateral-value threshold]
    :or   {threshold 0.80}}]
  (let [ltv        (loan-to-value loan-amount collateral-value)
        eligible?  (<= ltv threshold)]
    {:loan-amount       (round2 loan-amount)
     :collateral-value  (round2 collateral-value)
     :ltv-ratio         (round2 ltv)
     :ltv-pct           (str (format "%.1f" (* ltv 100)) "%")
     :threshold-pct     (str (format "%.0f" (* threshold 100)) "%")
     :eligible?         eligible?
     :verdict           (if eligible?
                          "PASS — within policy threshold"
                          (str "FAIL — exceeds " (format "%.0f" (* threshold 100))
                               "% LTV threshold by "
                               (format "%.1f" (* (- ltv threshold) 100)) "%"))}))

;; ── Guarantor capacity ────────────────────────────────────────────────────────

(defn guarantor-capacity
  "Assesses whether a guarantor has sufficient financial capacity to back a
   given guarantee amount. Treats the guarantee as an implicit obligation of
   guarantee-amount/12 (a one-year exposure proxy) and runs it through the
   same DTI-headroom check used for loan applicants — a guarantor who is
   already stretched thin shouldn't be accepted just because they signed."
  [{:keys [monthly-income existing-obligations guarantee-amount threshold]
    :or   {threshold 0.45}}]
  (let [implied-monthly (.divide (decimal guarantee-amount) (decimal 12) decimal-context)
        dti-result      (dti-analysis {:monthly-income       monthly-income
                                        :existing-obligations existing-obligations
                                        :new-payment          implied-monthly
                                        :threshold            threshold})]
    (assoc dti-result
           :guarantee-amount         (round2 guarantee-amount)
           :implied-monthly-exposure (round2 implied-monthly)
           :capacity-verdict         (if (:eligible? dti-result) "SUFFICIENT" "INSUFFICIENT"))))

;; ── Fees ─────────────────────────────────────────────────────────────────────

(defn processing-fee [principal fee-pct] (* principal fee-pct))

;; ── Totals ───────────────────────────────────────────────────────────────────

(defn total-interest [schedule]
  (reduce + (map :interest-due schedule)))

(defn total-cost [schedule]
  (reduce + (map :total-due schedule)))

(defn schedule-summary
  "Returns high-level summary of a generated schedule."
  [schedule principal]
  (let [t-interest (total-interest schedule)
        t-cost     (total-cost schedule)]
    {:installments        (count schedule)
     :monthly-payment     (-> schedule first :total-due)
     :total-principal     (round2 principal)
     :total-interest      (round2 t-interest)
     :total-cost          (round2 t-cost)
     :effective-rate-pct  (round2 (* 100 (/ t-interest principal)))}))
