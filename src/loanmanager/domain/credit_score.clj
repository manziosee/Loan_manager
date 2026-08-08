(ns loanmanager.domain.credit-score
  "Rules + statistical credit scoring engine with full explainability.
   Each rule contributes a weighted score delta and a human-readable explanation.
   Base score: 50. Final score clamped to [0, 100].")

;; ── Scoring rules ─────────────────────────────────────────────────────────────
;; Each rule: {:factor :description :weight :positive? :score-fn :threshold-fn}
;; score-fn   → returns numeric delta (positive = good, negative = bad)
;; threshold-fn → returns human-readable finding string for the explanation

(def ^:private rules
  [;; ── Income level ──────────────────────────────────────────────────────────
   {:factor      :income-level
    :description "Monthly income level"
    :weight      15
    :positive?   true
    :score-fn    (fn [{:keys [monthly-income]}]
                   (cond
                     (>= monthly-income 5000) 15
                     (>= monthly-income 2000) 10
                     (>= monthly-income 1000) 5
                     (>= monthly-income 500)  2
                     :else                    0))
    :finding-fn  (fn [{:keys [monthly-income]} delta]
                   (if (pos? delta)
                     {:type :strength :text (str "Monthly income $" monthly-income " supports repayment capacity")}
                     {:type :warning  :text "Low monthly income relative to loan request"}))}

   ;; ── Employment stability ──────────────────────────────────────────────────
   {:factor      :employment-stability
    :description "Employment history and stability"
    :weight      20
    :positive?   true
    :score-fn    (fn [{:keys [employment-years employment-type]}]
                   (let [base (cond
                                (>= employment-years 5) 20
                                (>= employment-years 3) 15
                                (>= employment-years 2) 10
                                (>= employment-years 1) 5
                                :else                   0)
                         bonus (case (keyword (or employment-type ""))
                                 :permanent  3
                                 :government 5
                                 :contract   -2
                                 :self-employed -3
                                 0)]
                     (+ base bonus)))
    :finding-fn  (fn [{:keys [employment-years employment-type]} delta]
                   (if (>= delta 10)
                     {:type :strength :text (str employment-years " years of stable " (or employment-type "employment"))}
                     {:type :warning  :text (str "Limited employment history (" employment-years " years)"
                                                 (when (#{:contract :self-employed} (keyword (or employment-type "")))
                                                   " — variable income type"))}))}

   ;; ── Repayment history ─────────────────────────────────────────────────────
   {:factor      :repayment-history
    :description "Previous loan repayment record"
    :weight      25
    :positive?   true
    :score-fn    (fn [{:keys [late-payments-12m late-payments-24m defaults write-offs]}]
                   (cond
                     (pos? write-offs)          -25
                     (pos? defaults)            -20
                     (>= late-payments-12m 5)   -10
                     (>= late-payments-12m 3)    -5
                     (>= late-payments-12m 1)     0
                     (and (zero? late-payments-12m)
                          (zero? late-payments-24m)) 25
                     :else                        15))
    :finding-fn  (fn [{:keys [late-payments-12m defaults write-offs]} delta]
                   (cond
                     (pos? write-offs)         {:type :warning  :text "Previous loan write-off on record"}
                     (pos? defaults)           {:type :warning  :text "Active or recent loan default"}
                     (>= late-payments-12m 3)  {:type :warning  :text (str late-payments-12m " late payments in last 12 months")}
                     (pos? late-payments-12m)  {:type :warning  :text (str late-payments-12m " late payment(s) in last 12 months")}
                     :else                     {:type :strength :text "Clean repayment history — no late payments"}))}

   ;; ── Debt-to-income ratio ──────────────────────────────────────────────────
   {:factor      :dti-ratio
    :description "Debt-to-income ratio"
    :weight      20
    :positive?   false
    :score-fn    (fn [{:keys [dti]}]
                   (cond
                     (> dti 0.70) -25
                     (> dti 0.60) -20
                     (> dti 0.50) -15
                     (> dti 0.45) -10
                     (> dti 0.35)  -5
                     (> dti 0.25)   0
                     :else          5))
    :finding-fn  (fn [{:keys [dti]} delta]
                   (let [pct (format "%.1f%%" (* dti 100))]
                     (if (neg? delta)
                       {:type :warning  :text (str "DTI ratio " pct " exceeds recommended threshold")}
                       {:type :strength :text (str "Healthy DTI ratio of " pct)})))}

   ;; ── Existing debt burden ──────────────────────────────────────────────────
   {:factor      :existing-debt
    :description "Number of active credit facilities"
    :weight      10
    :positive?   false
    :score-fn    (fn [{:keys [active-facilities total-outstanding-debt monthly-income]}]
                   (let [debt-ratio (if (pos? (or monthly-income 0))
                                      (/ (or total-outstanding-debt 0) (* (or monthly-income 1) 12))
                                      1.0)]
                     (cond
                       (>= active-facilities 6) -15
                       (>= active-facilities 4) -10
                       (>= active-facilities 3)  -5
                       (> debt-ratio 3.0)        -10
                       (> debt-ratio 2.0)         -5
                       :else                       0)))
    :finding-fn  (fn [{:keys [active-facilities]} delta]
                   (if (neg? delta)
                     {:type :warning  :text (str active-facilities " active credit facilities — high debt burden")}
                     {:type :strength :text "Manageable number of existing credit facilities"}))}

   ;; ── Account activity / savings behaviour ─────────────────────────────────
   {:factor      :account-activity
    :description "Bank account activity and savings behaviour"
    :weight      10
    :positive?   true
    :score-fn    (fn [{:keys [avg-monthly-transactions avg-monthly-savings months-banking]}]
                   (let [tx-score  (cond
                                     (>= avg-monthly-transactions 30) 5
                                     (>= avg-monthly-transactions 15) 3
                                     (>= avg-monthly-transactions 5)  1
                                     :else                             0)
                         sav-score (cond
                                     (>= (or avg-monthly-savings 0) 500) 5
                                     (>= (or avg-monthly-savings 0) 100) 3
                                     :else                               0)
                         age-score (cond
                                     (>= (or months-banking 0) 24) 2
                                     (>= (or months-banking 0) 12) 1
                                     :else                          0)]
                     (+ tx-score sav-score age-score)))
    :finding-fn  (fn [{:keys [avg-monthly-savings months-banking]} delta]
                   (if (>= delta 5)
                     {:type :strength :text (str "Active banking history"
                                                  (when (pos? (or avg-monthly-savings 0))
                                                    (str " with $" avg-monthly-savings "/mo savings")
                                                  ))}
                     {:type :warning  :text "Limited banking activity or savings history"}))}

   ;; ── Income-to-loan ratio ──────────────────────────────────────────────────
   {:factor      :income-loan-ratio
    :description "Monthly income relative to requested loan amount"
    :weight      10
    :positive?   true
    :score-fn    (fn [{:keys [monthly-income requested-amount]}]
                   (let [ratio (if (pos? (or requested-amount 1))
                                 (/ (or monthly-income 0) requested-amount)
                                 0)]
                     (cond
                       (> ratio 0.5)  10
                       (> ratio 0.3)   7
                       (> ratio 0.15)  3
                       :else           0)))
    :finding-fn  (fn [{:keys [monthly-income requested-amount]} delta]
                   (let [ratio (if (pos? (or requested-amount 1))
                                 (* 100 (/ (or monthly-income 0) requested-amount))
                                 0)]
                     (if (>= delta 5)
                       {:type :strength :text (str "Income-to-loan ratio of " (format "%.1f%%" ratio) " is adequate")}
                       {:type :warning  :text (str "Loan amount is high relative to monthly income")})))}])

;; ── Scoring engine ────────────────────────────────────────────────────────────

(def ^:private base-score 50)

(defn score
  "Returns {:total-score int :category keyword :factors [...] :findings [...]}

   Required profile keys:
     :monthly-income          — gross monthly income
     :employment-years        — years at current employer
     :employment-type         — :permanent :contract :self-employed :government
     :late-payments-12m       — count of late payments in last 12 months
     :late-payments-24m       — count of late payments in last 24 months
     :defaults                — count of active/recent defaults
     :write-offs              — count of write-offs
     :dti                     — debt-to-income ratio (decimal)
     :active-facilities       — number of active credit lines
     :total-outstanding-debt  — total outstanding debt amount
     :avg-monthly-transactions — average monthly bank transactions
     :avg-monthly-savings     — average monthly savings
     :months-banking          — months of banking relationship
     :requested-amount        — loan amount requested"
  [profile]
  (let [evaluated (mapv (fn [{:keys [factor description weight positive? score-fn finding-fn]}]
                          (let [delta   (score-fn profile)
                                finding (finding-fn profile delta)]
                            {:factor      factor
                             :description description
                             :weight      weight
                             :positive?   positive?
                             :score-delta delta
                             :finding     finding}))
                        rules)
        total     (max 0 (min 100 (+ base-score (reduce + (map :score-delta evaluated)))))
        category  (cond
                    (<= total 30) :high-risk
                    (<= total 50) :medium-risk
                    (<= total 70) :low-risk
                    :else         :very-low-risk)]
    {:total-score total
     :category    category
     :base-score  base-score
     :factors     evaluated}))

;; ── Explainability ────────────────────────────────────────────────────────────

(defn explain
  "Returns structured human-readable explanation for a loan officer.
   Input: result from (score profile)"
  [{:keys [total-score category factors]}]
  (let [findings  (map :finding factors)
        warnings  (filter #(= :warning (:type %)) findings)
        strengths (filter #(= :strength (:type %)) findings)
        risk-label (case category
                     :high-risk     "⛔ HIGH RISK"
                     :medium-risk   "⚠️  MEDIUM RISK"
                     :low-risk      "✅ LOW RISK"
                     :very-low-risk "✅ VERY LOW RISK")]
    {:summary      (str "Credit Score: " total-score "/100 — " risk-label)
     :score        total-score
     :category     (name category)
     :risk-label   risk-label
     :warnings     (mapv :text warnings)
     :strengths    (mapv :text strengths)
     :warning-count  (count warnings)
     :strength-count (count strengths)
     :recommendation (case category
                       :high-risk     "Decline or require significant collateral and guarantors"
                       :medium-risk   "Proceed with caution — require additional documentation"
                       :low-risk      "Approve with standard conditions"
                       :very-low-risk "Approve — preferred customer")}))

(defn score-with-explanation
  "Convenience: run score + explain in one call."
  [profile]
  (let [result (score profile)]
    (assoc result :explanation (explain result))))

;; ── Score comparison (for restructuring / re-assessment) ─────────────────────

(defn score-delta
  "Returns the change in score between two assessments."
  [old-score new-score]
  {:previous    old-score
   :current     new-score
   :delta       (- new-score old-score)
   :improved?   (> new-score old-score)
   :direction   (cond
                  (> new-score old-score) "improved"
                  (< new-score old-score) "deteriorated"
                  :else                   "unchanged")})
