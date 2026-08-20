(ns loanmanager.notifications.email
  "Real email delivery via SMTP — previously delinquency's triggered-actions
   (:send-email-reminder, :send-sms-reminder, ...) were only ever computed
   and logged to delinquency_log; nothing dispatched them anywhere. This is
   the first real channel: plain SMTP, configured via resources/config.edn's
   :email section (SMTP_HOST/PORT/USER/PASS/FROM env vars).

   SMS/push are NOT implemented here — they need a paid third-party account
   (Twilio, FCM, ...) this project has no credentials for. Faking a 'sent'
   response for those would be actively misleading, so :send-sms-reminder
   and :call-customer stay logged-only until a real provider is wired in."
  (:require [postal.core :as postal]
            [clojure.tools.logging :as log]))

(defn configured? [{:keys [smtp-host]}]
  (some? smtp-host))

(defn send!
  "Sends an email if SMTP is configured and a recipient address is given;
   otherwise logs what *would* have been sent and returns without error —
   a missing mail server should never take down the request that triggered
   the notification."
  [email-config {:keys [to subject body]}]
  (cond
    (not (configured? email-config))
    (log/info "SMTP not configured — skipping email:" subject "to" to "\n" body)

    (nil? to)
    (log/info "No recipient email — skipping:" subject)

    :else
    (try
      (postal/send-message
        {:host (:smtp-host email-config)
         :port (:smtp-port email-config)
         :user (:smtp-user email-config)
         :pass (:smtp-pass email-config)}
        {:from    (:from email-config)
         :to      to
         :subject subject
         :body    body})
      (log/info "Email sent:" subject "to" to)
      (catch Exception e
        (log/warn e "Email send failed (non-fatal):" subject "to" to)))))
