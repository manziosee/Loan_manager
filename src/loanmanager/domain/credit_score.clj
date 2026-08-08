(ns loanmanager.domain.credit-score
  "Rules-based credit scoring with full explainability.
   Each rule returns {:score int :factor keyword :description string :positive? bool}")

(def ^:private rules
  [{:factor       :income-stability
    :description  "Stable employment history"
    :positive?    true
    :score-fn     (fn [{:keys [employment-years]}]
                    (cond
                      (>= employment-years 5) 20
                      (>= employment-years 2) 12
                      (>= employment-years 1) 6
                      :else                   0))}

   {:factor       :repayment-history
    :description  "Previous loan repayment record"
    :positive?    true
    :score-fn     (fn [{:keys [late-payments-12m defaults]}]
                    (cond
                      (pos? defaults)          0
                      (>= late-payments-12m 3) 5
                      (>= late-payments-12m 1) 12
                      :else                    25))}

   {:factor       :dti-ratio
    :description  "Debt-to-income ratio"
    :positive?    false
    :score-fn     (fn [{:keys [dti]}]
                    (cond
                      (> dti 0.60) -25
                      (> dti 0.45) -15
                      (> dti 0.35) -8
                      :else         0))}

   {:factor       :existing-debt
    :description  "Number of active credit facilities"
    :positive?    false
    :score-fn     (fn [{:keys [active-facilities]}]
                    (cond
                      (>= active-facilities 5) -20
                      (>= active-facilities 3) -10
                      :else                     0))}

   {:factor       :account-activity
    :description  "Bank account transaction activity"
    :positive?    true
    :score-fn     (fn [{:keys [avg-monthly-transactions]}]
                    (cond
                      (>= avg-monthly-transactions 20) 10
                      (>= avg-monthly-transactions 10) 6
                      :else                             2))}

   {:factor       :income-level
    :description  "Monthly income relative to loan amount"
    :positive?    true
    :score-fn     (fn [{:keys [monthly-income requested-amount]}]
                    (let [ratio (if (pos? requested-amount)
                                  (/ monthly-income requested-amount)
                                  0)]
                      (cond
                        (> ratio 0.5) 15
                        (> ratio 0.3) 8
                        :else         0)))}])

(defn score
  "Returns {:total-score int :category keyword :factors [...]}
   Input map keys: employment-years, late-payments-12m, defaults,
                   dti, active-facilities, avg-monthly-transactions,
                   monthly-income, requested-amount"
  [profile]
  (let [factors (mapv (fn [{:keys [factor description positive? score-fn]}]
                        (let [s (score-fn profile)]
                          {:factor      factor
                           :description description
                           :positive?   positive?
                           :score       s}))
                      rules)
        total   (max 0 (min 100 (+ 50 (reduce + (map :score factors)))))
        category (cond
                   (<= total 30) :high-risk
                   (<= total 50) :medium-risk
                   (<= total 70) :low-risk
                   :else         :very-low-risk)]
    {:total-score total
     :category    category
     :factors     factors}))

(defn explain
  "Returns human-readable risk explanation from a score result."
  [{:keys [total-score category factors]}]
  {:summary   (str "Credit Score: " total-score "/100 — " (name category))
   :warnings  (->> factors (filter #(neg? (:score %))) (map :description))
   :strengths (->> factors (filter #(pos? (:score %))) (map :description))})
