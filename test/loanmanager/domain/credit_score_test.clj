(ns loanmanager.domain.credit-score-test
  (:require [clojure.test :refer [deftest is testing]]
            [loanmanager.domain.credit-score :as cs]))

(def ^:private strong-profile
  {:monthly-income           5000
   :employment-years         6
   :employment-type          "permanent"
   :late-payments-12m        0
   :late-payments-24m        0
   :defaults                 0
   :write-offs               0
   :dti                      0.20
   :active-facilities        1
   :total-outstanding-debt   2000
   :avg-monthly-transactions 20
   :avg-monthly-savings      300
   :months-banking           36
   :requested-amount         10000})

(def ^:private weak-profile
  {:monthly-income           500
   :employment-years         0
   :employment-type          "contract"
   :late-payments-12m        5
   :late-payments-24m        8
   :defaults                 1
   :write-offs               0
   :dti                      0.75
   :active-facilities        6
   :total-outstanding-debt   20000
   :avg-monthly-transactions 2
   :avg-monthly-savings      0
   :months-banking           3
   :requested-amount         50000})

;; ── Score range ───────────────────────────────────────────────────────────────

(deftest score-range
  (testing "Score is always between 0 and 100"
    (doseq [profile [strong-profile weak-profile
                     (assoc strong-profile :write-offs 3)
                     (assoc weak-profile   :monthly-income 0)]]
      (let [{:keys [total-score]} (cs/score profile)]
        (is (<= 0 total-score 100)
            (str "Score out of range: " total-score))))))

;; ── Category assignment ───────────────────────────────────────────────────────

(deftest category-assignment
  (testing "Strong profile → very-low-risk or low-risk"
    (let [{:keys [category]} (cs/score strong-profile)]
      (is (#{:very-low-risk :low-risk} category))))

  (testing "Weak profile → high-risk or medium-risk"
    (let [{:keys [category]} (cs/score weak-profile)]
      (is (#{:high-risk :medium-risk} category))))

  (testing "Write-off drives score down significantly"
    (let [with-wo    (cs/score (assoc strong-profile :write-offs 2))
          without-wo (cs/score strong-profile)]
      (is (< (:total-score with-wo) (:total-score without-wo))))))

;; ── Explainability ────────────────────────────────────────────────────────────

(deftest explanation-structure
  (testing "Explanation has required keys"
    (let [{:keys [explanation]} (cs/score-with-explanation strong-profile)]
      (is (contains? explanation :summary))
      (is (contains? explanation :warnings))
      (is (contains? explanation :strengths))
      (is (contains? explanation :recommendation))
      (is (string? (:summary explanation)))))

  (testing "Strong profile has more strengths than warnings"
    (let [{:keys [explanation]} (cs/score-with-explanation strong-profile)]
      (is (> (:strength-count explanation) (:warning-count explanation)))))

  (testing "Weak profile has more warnings than strengths"
    (let [{:keys [explanation]} (cs/score-with-explanation weak-profile)]
      (is (> (:warning-count explanation) (:strength-count explanation))))))

;; ── DTI finding ───────────────────────────────────────────────────────────────

(deftest dti-finding
  (testing "High DTI produces a warning finding"
    (let [result (cs/score (assoc strong-profile :dti 0.72))
          dti-factor (first (filter #(= :dti-ratio (:factor %)) (:factors result)))]
      (is (= :warning (:type (:finding dti-factor))))))

  (testing "Low DTI produces a strength finding"
    (let [result (cs/score (assoc strong-profile :dti 0.15))
          dti-factor (first (filter #(= :dti-ratio (:factor %)) (:factors result)))]
      (is (= :strength (:type (:finding dti-factor)))))))

;; ── Score delta ───────────────────────────────────────────────────────────────

(deftest score-delta
  (testing "Improvement detected correctly"
    (let [delta (cs/score-delta 45 60)]
      (is (:improved? delta))
      (is (= 15 (:delta delta)))
      (is (= "improved" (:direction delta)))))

  (testing "Deterioration detected correctly"
    (let [delta (cs/score-delta 70 55)]
      (is (not (:improved? delta)))
      (is (= -15 (:delta delta)))
      (is (= "deteriorated" (:direction delta)))))

  (testing "Unchanged detected correctly"
    (let [delta (cs/score-delta 50 50)]
      (is (= "unchanged" (:direction delta))))))

;; ── Factors completeness ─────────────────────────────────────────────────────

(deftest factors-completeness
  (testing "All 7 factors are evaluated"
    (let [{:keys [factors]} (cs/score strong-profile)]
      (is (= 7 (count factors)))
      (is (every? #(contains? % :factor) factors))
      (is (every? #(contains? % :finding) factors)))))
