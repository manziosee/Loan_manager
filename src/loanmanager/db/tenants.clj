(ns loanmanager.db.tenants
  "Tenant provisioning. Previously the platform had exactly one tenant,
   seeded once by a migration — there was no way to onboard a second bank/
   MFI/SACCO without hand-writing SQL. This mirrors that seed migration's
   shape (same role set, same chart of accounts) so a freshly provisioned
   tenant behaves identically to the original one."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [loanmanager.db.connection :as db]))

;; loanmanager.security.rbac/permissions is the actual source of truth for
;; what each role can do (role name -> permission set, checked in-process).
;; These DB rows exist so users.role_id has something to reference and the
;; UI has a permissions list to display — the names must match rbac.clj.
(def ^:private default-roles
  [{:name "admin"           :permissions ["all" "user/read" "user/manage"]}
   {:name "loan-officer"    :permissions ["customer/read" "customer/create" "customer/update"
                                          "application/read" "application/create"
                                          "loan/read" "schedule/read"]}
   {:name "branch-manager"  :permissions ["customer/read" "customer/create" "customer/update"
                                          "application/read" "application/create" "application/approve"
                                          "loan/read" "loan/approve-small" "schedule/read" "payment/read"]}
   {:name "credit-officer"  :permissions ["customer/read" "customer/create" "customer/update"
                                          "application/read" "application/create" "application/approve"
                                          "credit-score/read" "credit-score/override"
                                          "loan/read" "loan/approve" "loan/restructure" "schedule/read" "payment/read"]}
   {:name "finance"         :permissions ["loan/read" "loan/disburse" "payment/read" "payment/create"
                                          "payment/reverse" "loan/restructure" "loan/write-off"
                                          "ledger/read" "schedule/read"]}
   {:name "collections"     :permissions ["loan/read" "payment/read" "payment/create"
                                          "collection/read" "collection/create" "collection/update" "customer/read"]}
   {:name "risk-officer"    :permissions ["customer/read" "application/read" "application/approve"
                                          "loan/read" "loan/approve" "loan/restructure" "loan/write-off"
                                          "credit-score/read" "credit-score/override"
                                          "fraud/read" "fraud/override" "portfolio/read"]}
   {:name "auditor"         :permissions ["customer/read" "application/read" "loan/read" "payment/read"
                                          "ledger/read" "audit/read" "schedule/read" "collection/read"
                                          "portfolio/read" "user/read"]}])

(def ^:private default-chart-of-accounts
  [{:code "1010" :name "Cash at Bank"     :account-type "asset"}
   {:code "1100" :name "Loans Receivable" :account-type "asset"}
   {:code "4100" :name "Interest Income"  :account-type "income"}
   {:code "4200" :name "Processing Fees"  :account-type "income"}])

(defn list-tenants [ds]
  (jdbc/execute! ds (sql/format {:select [:*] :from [:tenants] :order-by [[:created-at :asc]]})))

(defn find-tenant [ds id]
  (jdbc/execute-one! ds (sql/format {:select [:*] :from [:tenants] :where [:= :id id]})))

(defn provision!
  "Creates a new tenant with its default roles, chart of accounts, and an
   initial admin user, all in one transaction — either the whole tenant
   comes up ready to use, or none of it does."
  [ds {:keys [code name admin-email admin-password admin-full-name]}]
  (jdbc/with-transaction [tx ds]
    (let [tx      (db/with-kebab-keys tx)
          tenant  (db/execute-one! tx
                    (sql/format {:insert-into :tenants
                                 :values      [{:code code :name name :active true}]
                                 :returning   [:*]}))
          tenant-id (:tenants/id tenant)
          roles   (mapv (fn [{:keys [name permissions]}]
                          (db/execute-one! tx
                            (sql/format {:insert-into :roles
                                         :values      [{:tenant-id   tenant-id
                                                        :name        name
                                                        :permissions [:lift permissions]}]
                                         :returning   [:*]})))
                        default-roles)
          admin-role-id (:roles/id (first (filter #(= "admin" (:roles/name %)) roles)))]
      (doseq [{:keys [code name account-type]} default-chart-of-accounts]
        (db/execute-one! tx
          (sql/format {:insert-into :chart-of-accounts
                       :values      [{:tenant-id tenant-id :code code :name name :account-type account-type}]})))
      (let [admin-user (db/execute-one! tx
                          (sql/format {:insert-into :users
                                       :values      [{:tenant-id     tenant-id
                                                      :email         admin-email
                                                      :password-hash (hashers/derive admin-password)
                                                      :full-name     admin-full-name
                                                      :role-id       admin-role-id
                                                      :active        true}]
                                       :returning   [:id :email :full-name]}))]
        {:tenant tenant :admin-user admin-user}))))
