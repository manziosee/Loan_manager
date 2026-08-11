(ns loanmanager.domain.fraud-test
  (:require [clojure.test :refer [deftest is testing]]
            [loanmanager.domain.fraud :as fraud]))

(def ^:private clean-signals
  {:phone-customer-count        1
   :id-doc-duplicate?           false
   :applications-last-24h       1
   :address-match-count         0
   :days-since-last-update      30
   :bank-account-borrower-count 1})

(def ^:private suspicious-signals
  {:phone-customer-count        4
   :id-doc-duplicate?           true
   :applications-last-24h       3
   :address-match-count         2
   :days-since-last-update      3
   :bank-account-borrower-count 3})

(deftest clean-customer
  (testing "Clean signals produce zero fraud score"
    (let [{:keys [fraud-score flags requires-review?]} (fraud/evaluate clean-signals)]
      (is (= 0 fraud-score))
      (is (empty? flags))
      (is (not requires-review?)))))

(deftest suspicious-customer
  (testing "All flags triggered produce high score"
    (let [{:keys [fraud-score flags requires-review?]} (fraud/evaluate suspicious-signals)]
      (is (>= fraud-score 40))
      (is (seq flags))
      (is requires-review?)))

  (testing "Score is capped at 100"
    (let [{:keys [fraud-score]} (fraud/evaluate suspicious-signals)]
      (is (<= fraud-score 100)))))

(deftest individual-flags
  (testing "Shared phone triggers flag"
    (let [{:keys [flags]} (fraud/evaluate (assoc clean-signals :phone-customer-count 3))]
      (is (some #(= :shared-phone (:flag %)) flags))))

  (testing "Duplicate ID triggers flag"
    (let [{:keys [flags]} (fraud/evaluate (assoc clean-signals :id-doc-duplicate? true))]
      (is (some #(= :shared-id-document (:flag %)) flags))))

  (testing "Rapid applications triggers flag"
    (let [{:keys [flags]} (fraud/evaluate (assoc clean-signals :applications-last-24h 2))]
      (is (some #(= :rapid-applications (:flag %)) flags))))

  (testing "Recent info change triggers flag"
    (let [{:keys [flags]} (fraud/evaluate (assoc clean-signals :days-since-last-update 5))]
      (is (some #(= :sudden-info-change (:flag %)) flags))))

  (testing "Shared bank account triggers flag"
    (let [{:keys [flags]} (fraud/evaluate (assoc clean-signals :bank-account-borrower-count 2))]
      (is (some #(= :shared-bank-account (:flag %)) flags)))))

(deftest review-threshold
  (testing "Score below 40 does not require review"
    (let [{:keys [requires-review?]} (fraud/evaluate
                                       (assoc clean-signals :address-match-count 1))]
      (is (not requires-review?))))

  (testing "Score at or above 40 requires review"
    (let [{:keys [requires-review?]} (fraud/evaluate
                                       (assoc clean-signals
                                              :phone-customer-count 3
                                              :id-doc-duplicate? true))]
      (is requires-review?))))
