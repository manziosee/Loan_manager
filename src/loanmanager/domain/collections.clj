(ns loanmanager.domain.collections
  "Collections domain logic — pure functions for case management,
   promise-to-pay tracking, and contact history.")

;; ── Case priority ─────────────────────────────────────────────────────────────

(defn case-priority
  "Determine collection case priority based on outstanding balance and days overdue."
  [outstanding-principal days-overdue]
  (let [exposure-score (cond
                         (>= outstanding-principal 50000) 3
                         (>= outstanding-principal 10000) 2
                         :else                            1)
        overdue-score  (cond
                         (>= days-overdue 90) 3
                         (>= days-overdue 30) 2
                         :else                1)
        total          (+ exposure-score overdue-score)]
    (cond
      (>= total 5) :critical
      (>= total 4) :high
      (>= total 3) :medium
      :else        :low)))

;; ── Promise-to-pay ────────────────────────────────────────────────────────────

(defn promise-status
  "Evaluate the status of a promise-to-pay.
   Returns :kept :broken :pending :expired"
  [{:keys [promise-date promise-amount actual-payment-date actual-payment-amount]}]
  (let [today (java.time.LocalDate/now)
        pdate (when promise-date
                (java.time.LocalDate/parse (str promise-date)))]
    (cond
      (nil? pdate)                                    :no-promise
      (and actual-payment-date
           (>= actual-payment-amount (* promise-amount 0.95))) :kept
      (and actual-payment-date
           (< actual-payment-amount (* promise-amount 0.95)))  :partial
      (.isAfter today pdate)                          :broken
      :else                                           :pending)))

(defn next-action
  "Suggest next collection action based on case state."
  [{:keys [days-overdue contact-attempts last-contact-days-ago promise-status]}]
  (cond
    (= promise-status :broken)
    {:action      :follow-up-broken-promise
     :description "Customer broke payment promise — escalate immediately"
     :urgency     :high}

    (= promise-status :pending)
    {:action      :confirm-payment
     :description "Payment promise pending — confirm receipt on due date"
     :urgency     :medium}

    (and (>= days-overdue 90) (< (or contact-attempts 0) 3))
    {:action      :legal-notice
     :description "90+ days overdue — issue formal legal notice"
     :urgency     :critical}

    (>= days-overdue 30)
    {:action      :field-visit
     :description "30+ days overdue — schedule field visit"
     :urgency     :high}

    (or (nil? last-contact-days-ago) (>= last-contact-days-ago 7))
    {:action      :phone-call
     :description "No contact in 7+ days — call customer"
     :urgency     :medium}

    :else
    {:action      :monitor
     :description "Continue monitoring — next contact in 3 days"
     :urgency     :low}))

;; ── Activity recording ────────────────────────────────────────────────────────

(def valid-activity-types
  #{:call :sms :email :field-visit :promise-to-pay :payment-received
    :legal-notice :escalation :case-note :restructure-offer})

(defn validate-activity [activity]
  (when-not (contains? valid-activity-types (keyword (:activity-type activity)))
    (throw (ex-info "Invalid activity type"
                    {:type          :validation
                     :activity-type (:activity-type activity)
                     :valid-types   valid-activity-types})))
  activity)

;; ── Case summary view ─────────────────────────────────────────────────────────

(defn case-summary
  "Build the collection officer's view of a case."
  [{:keys [customer loan activities promises]}]
  (let [last-activity  (first (sort-by :recorded-at #(compare %2 %1) activities))
        open-promise   (first (filter #(= :pending (promise-status %)) promises))
        broken-promise (first (filter #(= :broken  (promise-status %)) promises))]
    {:customer-name     (str (:first-name customer) " " (:last-name customer))
     :customer-phone    (:phone customer)
     :loan-no           (:loan-no loan)
     :outstanding       (:outstanding-principal loan)
     :days-overdue      (:days-overdue loan)
     :last-payment-date (:last-payment-date loan)
     :risk-score        (:risk-score customer)
     :contact-attempts  (count (filter #(= :call (keyword (:activity-type %))) activities))
     :last-contact      (:recorded-at last-activity)
     :open-promise      open-promise
     :broken-promise    broken-promise
     :next-action       (next-action
                          {:days-overdue      (:days-overdue loan)
                           :contact-attempts  (count activities)
                           :promise-status    (when open-promise (promise-status open-promise))})}))
