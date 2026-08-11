(ns loanmanager.api.routes.collateral
  (:require [loanmanager.api.schemas :as schemas]
            [loanmanager.db.collateral :as collateral-db]
            [loanmanager.db.audit :as audit]
            [loanmanager.security.rbac :as rbac]))

;; ── Schemas ───────────────────────────────────────────────────────────────────

(def CollateralCreate
  [:map
   [:asset-type   [:enum "property" "vehicle" "equipment" "cash" "other"]]
   [:description  {:optional true} :string]
   [:valuation    [:double {:min 0}]]
   [:valuation-date :string]
   [:valuator     {:optional true} :string]
   [:location     {:optional true} :string]
   [:insurance-policy {:optional true} :string]
   [:insurance-expiry {:optional true} :string]
   [:lien-registered  {:optional true} :boolean]])

(def CollateralUpdate
  [:map
   [:valuation       {:optional true} [:double {:min 0}]]
   [:valuation-date  {:optional true} :string]
   [:valuator        {:optional true} :string]
   [:insurance-policy {:optional true} :string]
   [:insurance-expiry {:optional true} :string]
   [:lien-registered  {:optional true} :boolean]])

(def GuarantorCreate
  [:map
   [:customer-id      schemas/UUID-str]
   [:guarantee-amount [:double {:min 0}]]
   [:guarantee-pct    {:optional true} [:double {:min 0 :max 100}]]
   [:expiry-date      {:optional true} :string]])

(defn routes [ds]
  [;; ── Collateral ────────────────────────────────────────────────────────────
   ["/loans/:id/collateral"
    {:get  {:summary    "List collateral items for a loan"
            :tags       ["Loans"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity path-params]}]
                          (rbac/require-permission identity :loan/read)
                          {:status 200
                           :body   (collateral-db/list-for-loan ds (parse-uuid (:id path-params)))})}

     :post {:summary    "Attach collateral to a loan"
            :tags       ["Loans"]
            :parameters {:path [:map [:id :string]]
                         :body CollateralCreate}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :loan/approve)
                          (let [loan-id (parse-uuid (:id path-params))
                                item    (collateral-db/create! ds
                                          (assoc body-params
                                                 :loan-id     loan-id
                                                 :tenant-id   tenant-id
                                                 :customer-id nil))]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "collateral.attached"
                                            :entity-type "loan"
                                            :entity-id   loan-id
                                            :after-state body-params})
                            {:status 201 :body item}))}}]

   ["/collateral/:id"
    {:put {:summary    "Update a collateral item (revaluation)"
           :tags       ["Loans"]
           :parameters {:path [:map [:id :string]]
                        :body CollateralUpdate}
           :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                         (rbac/require-permission identity :loan/approve)
                         (let [id      (parse-uuid (:id path-params))
                               updated (collateral-db/update! ds tenant-id id body-params)]
                           (audit/log! ds {:tenant-id   tenant-id
                                           :user-id     (:user-id identity)
                                           :action      "collateral.updated"
                                           :entity-type "collateral"
                                           :entity-id   id
                                           :after-state body-params})
                           {:status 200 :body updated}))}}]

   ;; ── Guarantors ────────────────────────────────────────────────────────────
   ["/loans/:id/guarantors"
    {:get  {:summary    "List guarantors for a loan"
            :tags       ["Loans"]
            :parameters {:path [:map [:id :string]]}
            :handler    (fn [{:keys [identity path-params]}]
                          (rbac/require-permission identity :loan/read)
                          {:status 200
                           :body   (collateral-db/list-guarantors ds (parse-uuid (:id path-params)))})}

     :post {:summary    "Add a guarantor to a loan"
            :tags       ["Loans"]
            :parameters {:path [:map [:id :string]]
                         :body GuarantorCreate}
            :handler    (fn [{:keys [identity tenant-id path-params body-params]}]
                          (rbac/require-permission identity :loan/approve)
                          (let [loan-id   (parse-uuid (:id path-params))
                                guarantor (collateral-db/add-guarantor! ds
                                            (assoc body-params
                                                   :loan-id     loan-id
                                                   :customer-id (parse-uuid (:customer-id body-params))
                                                   :status      "active"))]
                            (audit/log! ds {:tenant-id   tenant-id
                                            :user-id     (:user-id identity)
                                            :action      "guarantor.added"
                                            :entity-type "loan"
                                            :entity-id   loan-id
                                            :after-state (dissoc body-params :customer-id)})
                            {:status 201 :body guarantor}))}}]])
