(ns loanmanager.db.integrations
  "Persistence primitives for idempotent requests and durable integration work.
   Provider adapters should use these functions rather than writing directly to
   the loan or ledger tables."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn begin-idempotency! [ds tenant-id idempotency-key request-hash]
  (db/execute-one! ds
    (sql/format {:insert-into :idempotency-keys
                 :values      [{:tenant-id       tenant-id
                                :idempotency-key idempotency-key
                                :request-hash    request-hash}]
                 :on-conflict [:tenant-id :idempotency-key]
                 :do-nothing  true
                 :returning   [:*]})))

(defn find-idempotency [ds tenant-id idempotency-key]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from   [:idempotency-keys]
                 :where  [:and [:= :tenant-id tenant-id]
                               [:= :idempotency-key idempotency-key]]})))

(defn complete-idempotency! [ds tenant-id idempotency-key request-hash status body]
  (db/execute-one! ds
    (sql/format {:update :idempotency-keys
                 :set    {:request-hash    request-hash
                          :response-status status
                          :response-body   [:lift body]
                          :completed-at    [:now]}
                 :where  [:and [:= :tenant-id tenant-id]
                               [:= :idempotency-key idempotency-key]]
                 :returning [:*]})))

(defn enqueue! [ds event]
  (db/execute-one! ds
    (sql/format {:insert-into :integration-outbox
                 :values      [{:tenant-id      (:tenant-id event)
                                :event-type     (name (:event-type event))
                                :aggregate-type (name (:aggregate-type event))
                                :aggregate-id   (:aggregate-id event)
                                :payload        [:lift event]}]
                 :returning [:*]})))

(defn claim-next! [ds]
  (jdbc/execute-one! ds
    ["WITH next_job AS (\n       SELECT id FROM integration_outbox\n       WHERE status = 'pending' AND available_at <= NOW()\n       ORDER BY created_at\n       FOR UPDATE SKIP LOCKED\n       LIMIT 1\n     )\n     UPDATE integration_outbox o\n     SET status = 'processing', locked_at = NOW(), attempts = attempts + 1\n     FROM next_job\n     WHERE o.id = next_job.id\n     RETURNING o.*"]))

(defn mark-delivered! [ds id]
  (db/execute-one! ds
    (sql/format {:update :integration-outbox
                 :set    {:status       "delivered"
                          :delivered-at [:now]}
                 :where  [:= :id id]
                 :returning [:*]})))

(defn mark-failed! [ds id error retry-at]
  (db/execute-one! ds
    (sql/format {:update :integration-outbox
                 :set    {:status      "failed"
                          :last-error  error
                          :available-at retry-at}
                 :where  [:= :id id]
                 :returning [:*]})))

(defn receive-webhook! [ds {:keys [provider external-event-id tenant-id
                                   signature-valid payload]}]
  (db/execute-one! ds
    (sql/format {:insert-into :payment-webhook-inbox
                 :values      [{:provider          provider
                                :external-event-id external-event-id
                                :tenant-id          tenant-id
                                :signature-valid    signature-valid
                                :payload            [:lift payload]}]
                 :on-conflict [:provider :external-event-id]
                 :do-nothing  true
                 :returning   [:*]})))

(defn provider-transactions [ds tenant-id loan-id]
  (jdbc/execute! ds
    (sql/format {:select   [:*]
                 :from     [:payment-provider-transactions]
                 :where    [:and [:= :tenant-id tenant-id]
                                  [:= :loan-id loan-id]]
                 :order-by [[:created-at :desc]]})))

(defn reconciliation-exceptions [ds tenant-id {:keys [status limit offset]
                                               :or   {limit 50 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:*]
                         :from     [:reconciliation-exceptions]
                         :where    [:= :tenant-id tenant-id]
                         :order-by [[:created-at :desc]]
                         :limit    limit
                         :offset   offset}
                  status (update :where conj [:= :status status])))))

(defn resolve-reconciliation-exception! [ds tenant-id id user-id]
  (db/execute-one! ds
    (sql/format {:update :reconciliation-exceptions
                 :set    {:status      "resolved"
                          :resolved-by  user-id
                          :resolved-at  [:now]}
                 :where  [:and [:= :tenant-id tenant-id]
                               [:= :id id]
                               [:= :status "open"]]
                 :returning [:*]})))