(ns loanmanager.api.routes.fraud
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.domain.fraud :as fraud]
            [loanmanager.db.audit :as audit]
            [loanmanager.security.rbac :as rbac]))

(def FraudCheckRequest
  [:map
   [:customer-id              :string]
   [:phone-customer-count     {:optional true} [:int {:min 0}]]
   [:id-doc-duplicate?        {:optional true} :boolean]
   [:applications-last-24h    {:optional true} [:int {:min 0}]]
   [:address-match-count      {:optional true} [:int {:min 0}]]
   [:days-since-last-update   {:optional true} [:int {:min 0}]]
   [:bank-account-borrower-count {:optional true} [:int {:min 0}]]])

(defn- build-signals-from-db
  "Auto-populate fraud signals from DB for a given customer."
  [ds tenant-id customer-id]
  (let [phone-count (-> (jdbc/execute-one! ds
                          (sql/format {:select [[[:count :id] :cnt]]
                                       :from   [:customers]
                                       :where  [:and
                                                [:= :tenant-id tenant-id]
                                                [:= :phone
                                                 {:select [:phone]
                                                  :from   [:customers]
                                                  :where  [:= :id customer-id]}]]}))
                        :cnt (or 1))
        apps-24h    (-> (jdbc/execute-one! ds
                          (sql/format {:select [[[:count :id] :cnt]]
                                       :from   [:loan-applications]
                                       :where  [:and
                                                [:= :customer-id customer-id]
                                                [:>= :created-at [:- [:now] [:raw "INTERVAL '24 hours'"]]]]}))
                        :cnt (or 0))]
    {:phone-customer-count        (or phone-count 1)
     :id-doc-duplicate?           false
     :applications-last-24h       (or apps-24h 0)
     :address-match-count         0
     :bank-account-borrower-count 1}))

(defn routes [ds]
  [["/fraud/check"
    {:post {:summary    "Run fraud detection on a set of signals"
            :tags       ["Fraud Detection"]
            :parameters {:body FraudCheckRequest}
            :handler    (fn [{:keys [identity tenant-id body-params]}]
                          (rbac/require-permission identity :fraud/read)
                          (let [signals (dissoc body-params :customer-id)
                                result  (fraud/evaluate signals)]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "fraud.check-run"
                                            :entity-type "customer"
                                            :entity-id   (parse-uuid (:customer-id body-params))
                                            :after-state {:fraud-score      (:fraud-score result)
                                                          :requires-review? (:requires-review? result)}})
                            {:status 200 :body result}))}}]

   ["/customers/:id/fraud-check"
    {:get {:summary    "Run automated fraud check for an existing customer"
           :tags       ["Fraud Detection" "Customers"]
           :parameters {:path [:map [:id :string]]}
           :handler    (fn [{:keys [identity tenant-id path-params]}]
                         (rbac/require-permission identity :fraud/read)
                         (let [customer-id (parse-uuid (:id path-params))
                               signals     (build-signals-from-db ds tenant-id customer-id)
                               result      (fraud/evaluate signals)]
                           (audit/log! ds {:tenant-id   tenant-id
                                           :user-id     (:user-id identity)
                                           :action      "fraud.auto-check"
                                           :entity-type "customer"
                                           :entity-id   customer-id
                                           :after-state {:fraud-score      (:fraud-score result)
                                                         :requires-review? (:requires-review? result)}})
                           {:status 200 :body (assoc result :customer-id customer-id)}))}}]])
