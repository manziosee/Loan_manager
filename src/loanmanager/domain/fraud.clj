(ns loanmanager.domain.fraud
  "Rule-based fraud detection engine with explainable flags.")

(def ^:private fraud-rules
  [{:flag        :shared-phone
    :description "Phone number used by multiple customers"
    :weight      25
    :check-fn    (fn [{:keys [phone-customer-count]}]
                   (> phone-customer-count 1))}

   {:flag        :shared-id-document
    :description "ID document number linked to another customer"
    :weight      35
    :check-fn    (fn [{:keys [id-doc-duplicate?]}]
                   id-doc-duplicate?)}

   {:flag        :rapid-applications
    :description "Multiple loan applications within 24 hours"
    :weight      20
    :check-fn    (fn [{:keys [applications-last-24h]}]
                   (> applications-last-24h 1))}

   {:flag        :address-match
    :description "Address matches another unrelated borrower"
    :weight      15
    :check-fn    (fn [{:keys [address-match-count]}]
                   (> address-match-count 0))}

   {:flag        :sudden-info-change
    :description "Customer information changed within 7 days of application"
    :weight      20
    :check-fn    (fn [{:keys [days-since-last-update]}]
                   (and days-since-last-update
                        (< days-since-last-update 7)))}

   {:flag        :shared-bank-account
    :description "Bank account linked to multiple borrowers"
    :weight      30
    :check-fn    (fn [{:keys [bank-account-borrower-count]}]
                   (> bank-account-borrower-count 1))}])

(defn evaluate
  "Returns {:fraud-score int :flags [...] :requires-review? bool}
   Input map should contain keys checked by each rule's :check-fn."
  [signals]
  (let [triggered (->> fraud-rules
                       (filter #((:check-fn %) signals))
                       (mapv #(select-keys % [:flag :description :weight])))
        score     (min 100 (reduce + 0 (map :weight triggered)))]
    {:fraud-score      score
     :flags            triggered
     :requires-review? (>= score 40)}))
