(ns loanmanager.events.handlers
  "Bridges domain events published on the bus to concrete side effects.
   Without this, events/publish! fires into the void — nothing ever
   reacted to a loan being approved, disbursed, or paid. Register once
   at startup with the shared datasource and event bus."
  (:require [clojure.tools.logging :as log]
            [loanmanager.events.bus :as events]
            [loanmanager.db.notifications :as notifications-db]
            [loanmanager.db.loans :as loans-db]
            [loanmanager.db.users :as users-db]
            [loanmanager.db.events :as events-db]
            [loanmanager.notifications.email :as email]))

(defn- aggregate-ref
  "Derives (aggregate-type, aggregate-id) from whichever id the event
   actually carries — events don't currently tag this explicitly."
  [event]
  (cond
    (:loan-id event)        [:loan (:loan-id event)]
    (:application-id event) [:loan-application (:application-id event)]
    :else                   [nil nil]))

(defn- persist-event! [ds event]
  (let [[agg-type agg-id] (aggregate-ref event)]
    (events-db/log! ds (assoc event :aggregate-type agg-type :aggregate-id agg-id))))

(defn- notify! [ds email-config tenant-id user-id type title body ref-type ref-id]
  (when user-id
    (notifications-db/create! ds
      {:tenant-id      tenant-id
       :user-id        user-id
       :type           type
       :title          title
       :body           body
       :reference-type (name ref-type)
       :reference-id   ref-id})
    (when-let [user (users-db/find-by-id ds tenant-id user-id)]
      (email/send! email-config
        {:to (:users/email user) :subject title :body body}))))

(defn- notify-application-owner! [ds email-config tenant-id application-id type title body]
  (when-let [app (loans-db/find-application ds tenant-id application-id)]
    (notify! ds email-config tenant-id (:loan-applications/submitted-by app) type title body
             :loan-application application-id)))

(defn- notify-loan-owner! [ds email-config tenant-id loan-id type title body]
  (when-let [loan (loans-db/find-loan ds tenant-id loan-id)]
    (when-let [app (loans-db/find-application ds tenant-id (:loans/application-id loan))]
      (notify! ds email-config tenant-id (:loan-applications/submitted-by app) type title body
               :loan loan-id))))

(def ^:private all-event-types
  [events/LOAN-APPLICATION-SUBMITTED events/LOAN-APPROVED events/LOAN-REJECTED
   events/LOAN-DISBURSED events/PAYMENT-RECEIVED events/LOAN-OVERDUE
   events/LOAN-RESTRUCTURED events/LOAN-CLOSED])

(defn register!
  [ds email-config _bus]
  ;; Event sourcing: every domain event lands in domain_events, regardless
  ;; of whether anything else below reacts to it.
  (doseq [event-type all-event-types]
    (events/subscribe! event-type (fn [event] (persist-event! ds event))))

  (events/subscribe! events/LOAN-APPROVED
    (fn [{:keys [tenant-id application-id]}]
      (notify-application-owner! ds email-config tenant-id application-id
        "loan.approved" "Application approved"
        "Your loan application has been approved.")))

  (events/subscribe! events/LOAN-REJECTED
    (fn [{:keys [tenant-id application-id]}]
      (notify-application-owner! ds email-config tenant-id application-id
        "loan.rejected" "Application rejected"
        "Your loan application has been rejected.")))

  (events/subscribe! events/LOAN-DISBURSED
    (fn [{:keys [tenant-id loan-id amount]}]
      (notify-loan-owner! ds email-config tenant-id loan-id
        "loan.disbursed" "Loan disbursed"
        (str "Loan disbursed: " amount "."))))

  (events/subscribe! events/PAYMENT-RECEIVED
    (fn [{:keys [tenant-id loan-id amount]}]
      (notify-loan-owner! ds email-config tenant-id loan-id
        "payment.received" "Payment received"
        (str "Payment received: " amount "."))))

  (events/subscribe! events/LOAN-RESTRUCTURED
    (fn [{:keys [tenant-id loan-id reason]}]
      (notify-loan-owner! ds email-config tenant-id loan-id
        "loan.restructured" "Loan restructured"
        (str "Loan restructured." (when reason (str " Reason: " reason))))))

  (log/info "Event handlers registered: domain-event persistence for all event types, "
            "notifications (in-app + email when configured) for approve/reject/disburse/payment/restructure"))
