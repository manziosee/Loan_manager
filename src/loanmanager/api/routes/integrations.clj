(ns loanmanager.api.routes.integrations
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.db.integrations :as integrations-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.security.rbac :as rbac]))

(defn- signature [secret body]
  (let [mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec.
                 (.getBytes secret "UTF-8") "HmacSHA256"))
    (format "%064x" (java.math.BigInteger. 1 (.doFinal mac (.getBytes body "UTF-8"))))))

(defn- valid-signature? [secret provided body]
  (and (not (str/blank? secret))
       (not (str/blank? provided))
       (java.security.MessageDigest/isEqual
        (.getBytes (str/lower-case provided) "UTF-8")
        (.getBytes (signature secret body) "UTF-8"))))

(defn public-routes [config ds]
  [["/integrations/:provider/webhook"
    {:post {:summary    "Receive a signed payment-provider webhook"
            :tags       ["Integrations"]
            :parameters {:path    [:map [:provider :string]]
                         :headers [:map [:x-webhook-signature :string]]
                         :body    schemas/ProviderWebhook}
            :responses  {202 {:body schemas/WebhookAccepted}
                         401 {:body schemas/ErrorResponse}}
            :handler    (fn [{:keys [path-params headers body-params]}]
                          (let [provider (str/lower-case (:provider path-params))
                                body-str (json/write-str body-params)
                                secret   (get-in config [:integrations (keyword provider) :webhook-secret])
                                valid?   (valid-signature? secret
                                                            (get headers "x-webhook-signature")
                                                            body-str)]
                            (if-not valid?
                              {:status 401 :body {:error "Invalid webhook signature"}}
                              (if-let [event (integrations-db/receive-webhook!
                                               ds {:provider          provider
                                                    :external-event-id (:event-id body-params)
                                                    :signature-valid    true
                                                    :payload            body-params})]
                                {:status 202
                                 :body   {:accepted true
                                          :webhook-id (:payment-webhook-inbox/id event)}}
                                {:status 202 :body {:accepted true :duplicate true}}))))}}]])

(defn protected-routes [ds]
  [["/loans/:id/provider-transactions"
    {:get {:summary    "List external payment-provider transactions for a loan"
           :tags       ["Integrations"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :payment/read)
                         {:status 200
                          :body   (integrations-db/provider-transactions
                                    ds tenant-id (parse-uuid (:id path-params)))})}}]

   ["/integrations/reconciliation-exceptions"
    {:get {:summary    "List open or resolved provider reconciliation exceptions"
           :tags       ["Integrations"]
           :parameters {:query schemas/IntegrationListQuery}
           :handler    (fn [{:keys [identity tenant-id query-params]}]
                         (rbac/require-permission identity :ledger/read)
                         {:status 200
                          :body   (integrations-db/reconciliation-exceptions
                                    ds tenant-id query-params)})}}]

   ["/integrations/reconciliation-exceptions/:id/resolve"
    {:post {:summary    "Resolve a provider reconciliation exception"
            :tags       ["Integrations"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity tenant-id path-params]}]
                          (rbac/require-permission identity :ledger/read)
                          (if-let [exception (integrations-db/resolve-reconciliation-exception!
                                               ds tenant-id (parse-uuid (:id path-params))
                                               (:user-id identity))]
                            (do
                              (audit/log! ds {:tenant-id tenant-id
                                              :user-id (:user-id identity)
                                              :action "reconciliation.exception.resolved"
                                              :entity-type "reconciliation-exception"
                                              :entity-id (parse-uuid (:id path-params))})
                              {:status 200 :body exception})
                            {:status 404 :body {:error "Open reconciliation exception not found"}}))}}]])