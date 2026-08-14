(ns loanmanager.domain.delinquency
  "Delinquency classification and automated action trigger engine.
   Pure functions — no DB or side effects."
  (:require [clojure.string :as str]))

;; ── Bucket classification ─────────────────────────────────────────────────────

(defn classify-bucket
  "Returns delinquency bucket keyword based on days overdue."
  [days-overdue]
  (cond
    (<= days-overdue 0)   :current
    (<= days-overdue 30)  :1-30
    (<= days-overdue 60)  :31-60
    (<= days-overdue 90)  :61-90
    (<= days-overdue 180) :90-plus
    :else                 :npl))

(defn bucket->label [bucket]
  (case bucket
    :current  "Current"
    :1-30     "1–30 Days Overdue"
    :31-60    "31–60 Days Overdue"
    :61-90    "61–90 Days Overdue"
    :90-plus  "90+ Days Overdue"
    :npl      "Non-Performing Loan"
    "Unknown"))

(defn bucket->loan-status [bucket]
  (case bucket
    :current  "active"
    :1-30     "overdue"
    :31-60    "overdue"
    :61-90    "overdue"
    :90-plus  "overdue"
    :npl      "npl"))

;; ── Provisioning rates (standard banking) ────────────────────────────────────

(def ^:private provision-rates
  {:current  0.01    ; 1%
   :1-30     0.05    ; 5%
   :31-60    0.25    ; 25%
   :61-90    0.50    ; 50%
   :90-plus  0.75    ; 75%
   :npl      1.00})  ; 100%

(defn provision-amount
  "Calculate required loan loss provision for a given outstanding balance."
  [outstanding-principal bucket]
  (let [rate (get provision-rates bucket 1.0)]
    (* outstanding-principal rate)))

;; ── Automated action triggers ─────────────────────────────────────────────────

(def ^:private action-triggers
  [{:min-days 1   :max-days 7   :actions [:send-sms-reminder :send-email-reminder]}
   {:min-days 8   :max-days 14  :actions [:send-sms-reminder :send-email-reminder :call-customer]}
   {:min-days 15  :max-days 30  :actions [:send-sms-reminder :assign-collection-officer]}
   {:min-days 31  :max-days 60  :actions [:escalate-to-supervisor :send-formal-notice]}
   {:min-days 61  :max-days 90  :actions [:escalate-to-manager :legal-notice-warning]}
   {:min-days 91  :max-days 180 :actions [:classify-npl :assign-legal-team :freeze-account]}
   {:min-days 181 :max-days ##Inf :actions [:write-off-consideration :legal-proceedings]}])

(defn triggered-actions
  "Returns list of action keywords that should be triggered for given days overdue."
  [days-overdue]
  (when (pos? days-overdue)
    (->> action-triggers
         (filter #(<= (:min-days %) days-overdue (:max-days %)))
         (mapcat :actions)
         vec)))

;; ── Full delinquency assessment ───────────────────────────────────────────────

(defn assess
  "Returns full delinquency assessment for a loan.
   Input: {:loan-id :outstanding-principal :days-overdue :last-payment-date}"
  [{:keys [loan-id outstanding-principal days-overdue]}]
  (let [bucket   (classify-bucket days-overdue)
        actions  (triggered-actions days-overdue)
        provision (provision-amount outstanding-principal bucket)]
    {:loan-id             loan-id
     :days-overdue        days-overdue
     :bucket              bucket
     :bucket-label        (bucket->label bucket)
     :loan-status         (bucket->loan-status bucket)
     :provision-rate      (get provision-rates bucket)
     :provision-amount    provision
     :triggered-actions   actions
     :requires-collection (>= days-overdue 15)
     :is-npl              (= bucket :npl)}))

;; ── Portfolio delinquency summary ────────────────────────────────────────────

(defn portfolio-summary
  "Aggregate delinquency stats across a collection of loan assessments."
  [assessments]
  (let [by-bucket (group-by :bucket assessments)
        total-outstanding (reduce + (map :outstanding-principal assessments))]
    {:total-loans       (count assessments)
     :total-outstanding total-outstanding
     :by-bucket         (into {}
                              (map (fn [[bucket loans]]
                                     [bucket {:count       (count loans)
                                              :outstanding (reduce + (map :outstanding-principal loans))
                                              :pct         (if (pos? total-outstanding)
                                                             (/ (double (reduce + (map :outstanding-principal loans)))
                                                                (double total-outstanding))
                                                             0)}])
                                   by-bucket))
     :npl-ratio         (if (pos? total-outstanding)
                          (/ (double (reduce + (map :outstanding-principal
                                                    (filter #(= :npl (:bucket %)) assessments))))
                             (double total-outstanding))
                          0)}))

;; ── Portfolio stress testing ──────────────────────────────────────────────────
;; "What happens to our portfolio if unemployment rises 10%?" — modeled as a
;; fraction of each bucket's outstanding balance migrating N buckets worse
;; (borrowers who were current or lightly delinquent tipping over), then
;; re-provisioning the shifted portfolio at the same standard rates used
;; everywhere else in this file.

(def ^:private bucket-order [:current :1-30 :31-60 :61-90 :90-plus :npl])

(defn- normalize-bucket
  "loans.delinquency_bucket stores underscored strings (1_30, 90_plus);
   this file's keywords use hyphens (:1-30, :90-plus) — accept either."
  [b]
  (-> b name (str/replace "_" "-") keyword))

(defn- migrate-bucket [bucket steps]
  (let [idx (.indexOf ^java.util.List bucket-order bucket)]
    (if (neg? idx)
      bucket
      (nth bucket-order (min (dec (count bucket-order)) (+ idx steps))))))

(defn stress-test
  "Projects portfolio loss under a stress scenario.
   portfolio-by-bucket: [{:bucket ... :outstanding ...} ...] — e.g. from
   loanmanager.db.loans/par-buckets.
   scenario: {:label \"Unemployment +10%\"
              :migration-steps 1     ; how many buckets worse
              :migration-rate  0.15} ; fraction of each bucket's balance that migrates"
  [portfolio-by-bucket {:keys [label migration-steps migration-rate]
                         :or   {migration-steps 1 migration-rate 0.15}}]
  (let [rows          (map #(update % :bucket normalize-bucket) portfolio-by-bucket)
        baseline-loss (reduce + 0 (map #(provision-amount (:outstanding %) (:bucket %)) rows))
        stressed-by-bucket
        (reduce (fn [acc {:keys [bucket outstanding]}]
                  (let [moved      (* outstanding migration-rate)
                        stayed     (- outstanding moved)
                        new-bucket (migrate-bucket bucket migration-steps)]
                    (-> acc
                        (update bucket     (fnil + 0) stayed)
                        (update new-bucket (fnil + 0) moved))))
                {} rows)
        stressed-loss (reduce-kv (fn [sum bucket amt] (+ sum (provision-amount amt bucket)))
                                  0 stressed-by-bucket)
        total-outstanding (reduce + 0 (map :outstanding rows))
        loss-delta        (- stressed-loss baseline-loss)]
    {:scenario                (or label "Custom stress scenario")
     :migration-rate          migration-rate
     :migration-steps         migration-steps
     :total-outstanding       total-outstanding
     :baseline-expected-loss  baseline-loss
     :stressed-expected-loss  stressed-loss
     :additional-loss         loss-delta
     :loss-increase-pct       (if (pos? baseline-loss)
                                 (* 100 (/ (double loss-delta) (double baseline-loss)))
                                 0.0)
     :stressed-by-bucket      stressed-by-bucket}))
