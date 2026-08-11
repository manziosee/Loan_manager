(ns loanmanager.domain.credit-score
  "Rules + statistical credit scoring engine with full explainability.
   Base score: 50. Final score clamped to [0, 100].")

(def ^:private rules
  [{:factor      :income-level
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
    :finding-fn  (fn [{:keys [monthly-income]} score-delta]
                   (if (pos? score-delta)
                     {:type :strength :text (str "Monthly income $" monthly-income " supports repayment capacity")}
                     {:type :warning  :text "Low monthly income relative to loan request"}))}

   {:factor      :employment-stability
    :description "Employment history and stability"
    :weight      20
    :positive?   true
    :score-fn    (fn [{:keys [employment-years employment-type]}]
                   (let [base  (cond
                                 (>= employment-years 5) 20
                                 (>= employment-years 3) 15
                                 (>= employment-years 2) 10
                                 (>= employment-years 1) 5
                                 :else                   0)
                         bonus (case (keyword (or employment-type ""))
                                 :permanent     3
                                 :government    5
                                 :contract     -2
                                 :self-employed -3
                                 0)]
                     (+ base bonus)))
    :finding-fn  (fn [{:keys [employment-years employment-type]} score-delta]
                   (if (>= score-delta 10)
                     {:type :strength :text (str employment-years " years of stable " (or employment-type "employment"))}
                     {:type :warning  :text (str "Limited employment history (" employment-years " years)"
                                                 (when (#{:contract :self-employed} (keyword (or employment-type "")))
                                                   " — variable income type"))}))}

   {:factor      :repayment-history
    :description "Previous loan repayment record"
    :weight      25
    :positive?   true
    :score-fn    (fn [{:keys [late-payments-12m late-payments-24m defaults write-offs]}]
                   (cond
                     (pos? write-offs)                              -25
                     (pos? defaults)                                -20
                     (>= late-payments-12m 5)                      -10
                     (>= late-payments-12m 3)                       -5
                     (>= late-payments-12m 1)                        0
                     (and (zero? late-payments-12m)
                          (zero? late-payments-24m))                 25
                     :else                                           15))
    :finding-fn  (fn [{:keys [late-payments-12m defaults write-offs]} _]
                   (cond
                     (pos? write-offs)        {:type :warning  :text "Previous loan write-off on record"}
                     (pos? defaults)          {:type :warning  :text "Active or recent loan default"}
                     (>= late-payments-12m 3) {:type :warning  :text (str late-payments-12m " late payments in last 12 months")}
                     (pos? late-payments-12m) {:type :warning  :text (str late-payments-12m " late payment(s) in last 12 months")}
                     :else                    {:type :strength :text "Clean repayment history — no late payments"}))}

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
    :finding-fn  (fn [{:keys [dti]} _]
                   (let [pct (format "%.1f%%" (* dti 100))]
                     (if (> dti 0.35)
                       {:type :warning  :text (str "DTI ratio " pct " exceeds recommended threshold")}
                       {:type :strength :text (str "Healthy DTI ratio of " pct)})))}

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
    :finding-fn  (fn [{:keys [active-facilities]} score-delta]
                   (if (neg? score-delta)
                     {:type :warning  :text (str active-facilities " active credit facilities — high debt burden")}
                     {:type :strength :text "Manageable number of existing credit facilities"}))}

   {:factor      :account-activity
    :description "Bank account activity and savings behaviour"
    :weight      10
    :positive?   true
    :score-fn    (fn [{:keys [avg-monthly-transactions avg-monthly-savings months-banking]}]
                   (+ (cond
                        (>= avg-monthly-transactions 30) 5
                        (>= avg-monthly-transactions 15) 3
                        (>= avg-monthly-transactions 5)  1
                        :else                             0)
                      (cond
                        (>= (or avg-monthly-savings 0) 500) 5
                        (>= (or avg-monthly-savings 0) 100) 3
                        :else                               0)
                      (cond
                        (>= (or months-banking 0) 24) 2
                        (>= (or months-banking 0) 12) 1
                        :else                          0)))
    :finding-fn  (fn [{:keys [avg-monthly-savings]} score-delta]
                   (if (>= score-delta 5)
                     {:type :strength :text (str "Active banking history"
                                                  (when (pos? (or avg-monthly-savings 0))
                                                    (str " with $" avg-monthly-savings "/mo savings")))}
                     {:type :warning  :text "Limited banking activity or savings history"}))}

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
    :finding-fn  (fn [{:keys [monthly-income requested-amount]} score-delta]
                   (let [ratio (if (pos? (or requested-amount 1))
                                 (* 100 (/ (or monthly-income 0) requested-amount))
                                 0)]
                     (if (>= score-delta 5)
                       {:type :strength :text (str "Income-to-loan ratio of " (format "%.1f%%" ratio) " is adequate")}
                       {:type :warning  :text "Loan amount is high relative to monthly income"})))}])

(def ^:private base-score 50)

(defn score [profile]
  (let [evaluated (mapv (fn [{:keys [factor description weight positive? score-fn finding-fn]}]
                          (let [d (score-fn profile)]
                            {:factor      factor
                             :description description
                             :weight      weight
                             :positive?   positive?
                             :score-delta d
                             :finding     (finding-fn profile d)}))
                        rules)
        total     (max 0 (min 100 (+ base-score (reduce + (map :score-delta evaluated)))))
        category  (cond
                    (<= total 30) :high-risk
                    (<= total 50) :medium-risk
                    (<= total 70) :low-risk
                    :else         :very-low-risk)]
    {:total-score total :category category :base-score base-score :factors evaluated}))

(defn explain [{:keys [total-score category factors]}]
  (let [findings   (map :finding factors)
        warnings   (filterv #(= :warning  (:type %)) findings)
        strengths  (filterv #(= :strength (:type %)) findings)
        risk-label (case category
                     :high-risk     "HIGH RISK"
                     :medium-risk   "MEDIUM RISK"
                     :low-risk      "LOW RISK"
                     :very-low-risk "VERY LOW RISK")]
    {:summary        (str "Credit Score: " total-score "/100 — " risk-label)
     :score          total-score
     :category       (name category)
     :risk-label     risk-label
     :warnings       (mapv :text warnings)
     :strengths      (mapv :text strengths)
     :warning-count  (count warnings)
     :strength-count (count strengths)
     :recommendation (case category
                       :high-risk     "Decline or require significant collateral and guarantors"
                       :medium-risk   "Proceed with caution — require additional documentation"
                       :low-risk      "Approve with standard conditions"
                       :very-low-risk "Approve — preferred customer")}))

(defn score-with-explanation [profile]
  (let [result (score profile)]
    (assoc result :explanation (explain result))))

(defn score-delta [old-score new-score]
  {:previous  old-score
   :current   new-score
   :delta     (- new-score old-score)
   :improved? (> new-score old-score)
   :direction (cond
                (> new-score old-score) "improved"
                (< new-score old-score) "deteriorated"
                :else                   "unchanged")})
