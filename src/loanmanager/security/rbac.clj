(ns loanmanager.security.rbac
  "Role-Based Access Control.
   Permissions are hierarchical: role → set of allowed actions.")

;; ── Permission registry ───────────────────────────────────────────────────────
(def permissions
  {:loan-officer    #{:customer/read    :customer/create   :customer/update
                      :application/read :application/create
                      :loan/read        :schedule/read}

   :branch-manager  #{:customer/read    :customer/create   :customer/update
                      :application/read :application/create :application/approve
                      :loan/read        :loan/approve-small
                      :schedule/read    :payment/read}

   :credit-officer  #{:customer/read    :customer/create   :customer/update
                      :application/read :application/create :application/approve
                      :credit-score/read :credit-score/override
                      :loan/read        :loan/approve      :loan/restructure
                      :schedule/read    :payment/read}

   :finance         #{:loan/read        :loan/disburse
                      :payment/read     :payment/create    :payment/reverse
                      :loan/restructure :loan/write-off
                      :ledger/read      :schedule/read}

   :collections     #{:loan/read        :payment/read      :payment/create
                      :collection/read  :collection/create :collection/update
                      :customer/read}

   :risk-officer    #{:customer/read    :application/read  :application/approve
                      :loan/read        :loan/approve      :loan/restructure :loan/write-off
                      :credit-score/read :credit-score/override
                      :fraud/read       :fraud/override
                      :portfolio/read}

   :auditor         #{:customer/read    :application/read  :loan/read
                      :payment/read     :ledger/read       :audit/read
                      :schedule/read    :collection/read   :portfolio/read
                      :user/read}

   :admin           #{:all :user/read :user/manage}})

(defn has-permission? [role permission]
  (let [perms (get permissions role #{})]
    (or (contains? perms :all)
        (contains? perms permission))))

(defn require-permission
  "Throws ex-info if identity lacks the required permission."
  [identity permission]
  (when-not (has-permission? (:role identity) permission)
    (throw (ex-info "Forbidden"
                    {:type       :forbidden
                     :permission permission
                     :role       (:role identity)}))))
