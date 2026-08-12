(ns loanmanager.domain.finance-test
  (:require [clojure.test :refer [deftest is testing are]]
            [loanmanager.domain.finance :as f]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- total-principal [schedule]
  (reduce + (map :principal-due schedule)))

;; ── Reducing balance schedule ─────────────────────────────────────────────────

(deftest reducing-balance-basic
  (testing "12-month reducing balance schedule sums to principal"
    (let [schedule (f/build-schedule {:method          :reducing-balance
                                      :principal       10000
                                      :annual-rate     0.12
                                      :duration-months 12
                                      :freq            :monthly})]
      (is (= 12 (count schedule)))
      (is (< (Math/abs (- 10000 (double (total-principal schedule)))) 0.02)
          "Total principal should equal loan amount within rounding")))

  (testing "All installments have positive principal and interest"
    (let [schedule (f/build-schedule {:method          :reducing-balance
                                      :principal       5000
                                      :annual-rate     0.18
                                      :duration-months 6
                                      :freq            :monthly})]
      (is (every? #(pos? (:principal-due %)) schedule))
      (is (every? #(pos? (:interest-due %)) schedule))))

  (testing "Zero interest rate — equal principal payments"
    (let [schedule (f/build-schedule {:method          :reducing-balance
                                      :principal       1200
                                      :annual-rate     0.0
                                      :duration-months 12
                                      :freq            :monthly})]
      (is (= 12 (count schedule)))
      (is (every? #(= 0.0 (double (:interest-due %))) schedule)))))

(deftest reducing-balance-grace-period
  (testing "Grace period installments have zero principal"
    (let [schedule (f/build-schedule {:method               :reducing-balance
                                      :principal            10000
                                      :annual-rate          0.12
                                      :duration-months      12
                                      :grace-period-months  2
                                      :freq                 :monthly})]
      (is (= 14 (count schedule)) "12 repayment + 2 grace = 14 total")
      (is (every? #(zero? (double (:principal-due %)))
                  (take 2 schedule))
          "First 2 periods are grace — no principal")
      (is (every? #(pos? (:interest-due %))
                  (take 2 schedule))
          "Interest still accrues during grace"))))

;; ── Flat schedule ─────────────────────────────────────────────────────────────

(deftest flat-schedule-basic
  (testing "Flat schedule has equal principal each period"
    (let [schedule (f/build-schedule {:method          :flat
                                      :principal       12000
                                      :annual-rate     0.15
                                      :duration-months 12
                                      :freq            :monthly})]
      (is (= 12 (count schedule)))
      (let [principals (map :principal-due schedule)]
        (is (apply = principals) "All principal payments should be equal"))))

  (testing "Flat schedule total principal equals loan amount"
    (let [schedule (f/build-schedule {:method          :flat
                                      :principal       6000
                                      :annual-rate     0.10
                                      :duration-months 6
                                      :freq            :monthly})]
      (is (< (Math/abs (- 6000 (double (total-principal schedule)))) 0.01)))))

;; ── Interest-only schedule ────────────────────────────────────────────────────

(deftest interest-only-schedule
  (testing "All periods except last have zero principal"
    (let [schedule (f/build-schedule {:method          :interest-only
                                      :principal       20000
                                      :annual-rate     0.10
                                      :duration-months 6
                                      :freq            :monthly})]
      (is (= 6 (count schedule)))
      (is (every? #(zero? (double (:principal-due %))) (butlast schedule)))
      (is (= 20000.0 (double (:principal-due (last schedule))))
          "Full principal due at last period")))

  (testing "Interest is constant each period"
    (let [schedule (f/build-schedule {:method          :interest-only
                                      :principal       10000
                                      :annual-rate     0.12
                                      :duration-months 3
                                      :freq            :monthly})
          interests (map :interest-due schedule)]
      (is (apply = interests) "Interest should be equal every period"))))

;; ── Balloon schedule ──────────────────────────────────────────────────────────

(deftest balloon-schedule
  (testing "Last installment is marked as balloon"
    (let [schedule (f/build-schedule {:method          :balloon
                                      :principal       10000
                                      :annual-rate     0.12
                                      :duration-months 12
                                      :freq            :monthly
                                      :balloon-pct     0.4})]
      (is (:balloon? (last schedule)) "Last installment should be balloon")))

  (testing "Total principal equals loan amount"
    (let [schedule (f/build-schedule {:method          :balloon
                                      :principal       10000
                                      :annual-rate     0.12
                                      :duration-months 12
                                      :freq            :monthly
                                      :balloon-pct     0.3})]
      (is (< (Math/abs (- 10000 (double (total-principal schedule)))) 0.05)))))

;; ── Principal-only schedule ───────────────────────────────────────────────────

(deftest principal-only-schedule
  (testing "No interest charged"
    (let [schedule (f/build-schedule {:method          :principal-only
                                      :principal       6000
                                      :annual-rate     0.0
                                      :duration-months 6
                                      :freq            :monthly})]
      (is (every? #(zero? (double (:interest-due %))) schedule))))

  (testing "Equal principal payments"
    (let [schedule (f/build-schedule {:method          :principal-only
                                      :principal       1200
                                      :annual-rate     0.0
                                      :duration-months 12
                                      :freq            :monthly})]
      (is (apply = (map :principal-due schedule))))))

;; ── Frequency variants ────────────────────────────────────────────────────────

(deftest frequency-variants
  (are [freq expected-count]
       (= expected-count
          (count (f/build-schedule {:method          :reducing-balance
                                    :principal       10000
                                    :annual-rate     0.12
                                    :duration-months 12
                                    :freq            freq})))
    :monthly   12
    :weekly    48
    :biweekly  24
    :quarterly  4))

;; ── DTI analysis ─────────────────────────────────────────────────────────────

(deftest dti-analysis
  (testing "DTI within threshold passes"
    (let [result (f/dti-analysis {:monthly-income       2000
                                   :existing-obligations 400
                                   :new-payment          300
                                   :threshold            0.45})]
      (is (:eligible? result))
      (is (= "PASS — within policy threshold" (:verdict result)))))

  (testing "DTI exceeding threshold fails"
    (let [result (f/dti-analysis {:monthly-income       1000
                                   :existing-obligations 400
                                   :new-payment          300
                                   :threshold            0.45})]
      (is (not (:eligible? result)))
      (is (.startsWith (:verdict result) "FAIL"))))

  (testing "Zero income returns DTI of 1.0"
    (is (= 1.0 (f/debt-to-income 0 500 200)))))

;; ── LTV ──────────────────────────────────────────────────────────────────────

(deftest loan-to-value
  (testing "Standard LTV calculation"
    (is (= 0.7 (f/loan-to-value 70000 100000))))

  (testing "Zero collateral returns 1.0"
    (is (= 1.0 (f/loan-to-value 50000 0)))))

;; ── Early repayment ───────────────────────────────────────────────────────────

(deftest early-repayment-settlement
  (testing "Settlement amount = principal + accrued interest"
    (let [result (f/early-repayment-settlement
                   {:outstanding-principal    10000
                    :annual-rate              0.12
                    :days-since-last-payment  30
                    :original-total-interest  1200
                    :total-interest-paid      600})]
      (is (pos? (:settlement-amount result)))
      (is (pos? (:accrued-interest result)))
      (is (= 10000.0 (double (:principal-outstanding result))))))

  (testing "Interest saved is non-negative"
    (let [result (f/early-repayment-settlement
                   {:outstanding-principal    5000
                    :annual-rate              0.15
                    :days-since-last-payment  15
                    :original-total-interest  800
                    :total-interest-paid      400})]
      (is (>= (double (:interest-saved result)) 0)))))

;; ── Schedule summary ─────────────────────────────────────────────────────────

(deftest schedule-summary
  (testing "Summary fields are present and positive"
    (let [schedule (f/build-schedule {:method          :reducing-balance
                                      :principal       10000
                                      :annual-rate     0.12
                                      :duration-months 12
                                      :freq            :monthly})
          summary  (f/schedule-summary schedule 10000)]
      (is (= 12 (:installments summary)))
      (is (pos? (double (:total-interest summary))))
      (is (= 10000.0 (double (:total-principal summary)))))))
