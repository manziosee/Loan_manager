(ns loanmanager.domain.delinquency-test
  (:require [clojure.test :refer [deftest is testing are]]
            [loanmanager.domain.delinquency :as d]
            [loanmanager.domain.collections :as c]))

;; ── Bucket classification ─────────────────────────────────────────────────────

(deftest bucket-classification
  (are [days expected]
       (= expected (d/classify-bucket days))
    0    :current
    -5   :current
    1    :1-30
    30   :1-30
    31   :31-60
    60   :31-60
    61   :61-90
    90   :61-90
    91   :90-plus
    180  :90-plus
    181  :npl
    365  :npl))

(deftest bucket-labels
  (testing "All buckets have labels"
    (doseq [bucket [:current :1-30 :31-60 :61-90 :90-plus :npl]]
      (is (string? (d/bucket->label bucket))))))

(deftest bucket-loan-status
  (testing "Current maps to active"
    (is (= "active" (d/bucket->loan-status :current))))
  (testing "NPL maps to npl"
    (is (= "npl" (d/bucket->loan-status :npl))))
  (testing "Overdue buckets map to overdue"
    (doseq [b [:1-30 :31-60 :61-90 :90-plus]]
      (is (= "overdue" (d/bucket->loan-status b))))))

;; ── Provisioning ─────────────────────────────────────────────────────────────

(deftest provision-amounts
  (testing "Current loans provisioned at 1%"
    (is (= 100.0 (double (d/provision-amount 10000 :current)))))

  (testing "NPL loans provisioned at 100%"
    (is (= 10000.0 (double (d/provision-amount 10000 :npl)))))

  (testing "Provision increases with bucket severity"
    (let [amounts (map #(d/provision-amount 10000 %) [:current :1-30 :31-60 :61-90 :90-plus :npl])]
      (is (apply < amounts)))))

;; ── Action triggers ───────────────────────────────────────────────────────────

(deftest action-triggers
  (testing "No actions for current loan"
    (is (nil? (d/triggered-actions 0))))

  (testing "SMS reminder triggered at 1 day"
    (is (some #{:send-sms-reminder} (d/triggered-actions 1))))

  (testing "Collection officer assigned at 15 days"
    (is (some #{:assign-collection-officer} (d/triggered-actions 15))))

  (testing "NPL classification triggered at 91 days"
    (is (some #{:classify-npl} (d/triggered-actions 91)))))

;; ── Full assessment ───────────────────────────────────────────────────────────

(deftest full-assessment
  (testing "Assessment returns all required keys"
    (let [result (d/assess {:loan-id               #uuid "00000000-0000-0000-0000-000000000001"
                             :outstanding-principal 10000
                             :days-overdue          45})]
      (is (= :31-60 (:bucket result)))
      (is (= "31\u201360 Days Overdue" (:bucket-label result)))
      (is (pos? (:provision-amount result)))
      (is (seq (:triggered-actions result)))
      (is (:requires-collection result))
      (is (not (:is-npl result)))))

  (testing "NPL assessment"
    (let [result (d/assess {:loan-id               #uuid "00000000-0000-0000-0000-000000000002"
                             :outstanding-principal 5000
                             :days-overdue          200})]
      (is (:is-npl result))
      (is (= "npl" (:loan-status result))))))

;; ── Portfolio summary ─────────────────────────────────────────────────────────

(deftest portfolio-summary
  (testing "NPL ratio calculated correctly"
    (let [assessments [{:bucket :current  :outstanding-principal 80000}
                       {:bucket :1-30     :outstanding-principal 10000}
                       {:bucket :npl      :outstanding-principal 10000}]
          summary     (d/portfolio-summary assessments)]
      (is (= 3 (:total-loans summary)))
      (is (= 100000.0 (double (:total-outstanding summary))))
      (is (= 0.1 (double (:npl-ratio summary)))))))

;; ── Collections domain ────────────────────────────────────────────────────────

(deftest case-priority
  (testing "High balance + high overdue = critical"
    (is (= :critical (c/case-priority 60000 95))))

  (testing "Low balance + low overdue = low"
    (is (= :low (c/case-priority 5000 10)))))

(deftest promise-status
  (testing "No promise date returns :no-promise"
    (is (= :no-promise (c/promise-status {}))))

  (testing "Kept promise"
    (let [yesterday (str (.minusDays (java.time.LocalDate/now) 1))]
      (is (= :kept (c/promise-status {:promise-date          yesterday
                                       :promise-amount        500
                                       :actual-payment-date   yesterday
                                       :actual-payment-amount 500})))))

  (testing "Broken promise — past due with no payment"
    (let [yesterday (str (.minusDays (java.time.LocalDate/now) 1))]
      (is (= :broken (c/promise-status {:promise-date  yesterday
                                         :promise-amount 500}))))))

(deftest next-action
  (testing "Broken promise produces high urgency follow-up"
    (let [action (c/next-action {:days-overdue 10 :promise-status :broken})]
      (is (= :follow-up-broken-promise (:action action)))
      (is (= :high (:urgency action)))))

  (testing "90+ days with no contact produces critical legal notice"
    (let [action (c/next-action {:days-overdue     95
                                  :contact-attempts 0
                                  :promise-status   nil})]
      (is (= :legal-notice (:action action)))
      (is (= :critical (:urgency action))))))
