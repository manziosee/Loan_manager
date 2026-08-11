(ns loanmanager.domain.accounting-test
  (:require [clojure.test :refer [deftest is testing]]
            [loanmanager.domain.accounting :as a]))

(deftest disbursement-entry
  (testing "Debits equal credits"
    (let [entry (a/disbursement-entry {:loan-id "LN-001" :amount 10000 :currency "USD"})]
      (is (nil? (a/validate-entry entry)))))

  (testing "Correct accounts used"
    (let [{:keys [lines]} (a/disbursement-entry {:loan-id "LN-001" :amount 10000 :currency "USD"})]
      (is (some #(and (= a/LOANS-RECEIVABLE (:account-code %)) (= 10000 (:debit %))) lines))
      (is (some #(and (= a/CASH-AT-BANK (:account-code %)) (= 10000 (:credit %))) lines))))

  (testing "Entry has description and reference"
    (let [entry (a/disbursement-entry {:loan-id "LN-001" :amount 5000 :currency "USD"})]
      (is (string? (:description entry)))
      (is (= :disbursement (:reference-type entry))))))

(deftest payment-entry
  (testing "Debits equal credits for full payment"
    (let [entry (a/payment-entry {:payment-id        "PAY-001"
                                   :principal-portion 800
                                   :interest-portion  100
                                   :currency          "USD"})]
      (is (nil? (a/validate-entry entry)))))

  (testing "Cash debit equals total payment"
    (let [{:keys [lines]} (a/payment-entry {:payment-id        "PAY-001"
                                             :principal-portion 800
                                             :interest-portion  100
                                             :currency          "USD"})
          cash-line (first (filter #(= a/CASH-AT-BANK (:account-code %)) lines))]
      (is (= 900 (:debit cash-line)))))

  (testing "Interest-only payment has no loans-receivable credit"
    (let [{:keys [lines]} (a/payment-entry {:payment-id        "PAY-002"
                                             :principal-portion 0
                                             :interest-portion  100
                                             :currency          "USD"})]
      (is (not (some #(= a/LOANS-RECEIVABLE (:account-code %)) lines))))))

(deftest processing-fee-entry
  (testing "Fee entry balances"
    (let [entry (a/processing-fee-entry {:loan-id "LN-001" :amount 200 :currency "USD"})]
      (is (nil? (a/validate-entry entry)))))

  (testing "Fee credited to processing fees account"
    (let [{:keys [lines]} (a/processing-fee-entry {:loan-id "LN-001" :amount 200 :currency "USD"})]
      (is (some #(= a/PROCESSING-FEES (:account-code %)) lines)))))

(deftest validate-entry-throws-on-imbalance
  (testing "Imbalanced entry throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (a/validate-entry {:lines [{:account-code "1100" :debit 1000 :credit 0 :currency "USD"}
                                            {:account-code "1010" :debit 0 :credit 500 :currency "USD"}]})))))
