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

   :admin           #{:all :user/read :user/manage}

   ;; Platform-level: manages tenants themselves, not a tenant's business
   ;; data. Deliberately NOT included in db/tenants.clj's default-roles —
   ;; new tenants must never get this role. See :platform/manage below.
   :platform-admin  #{:platform/manage}})

;; Permissions in this set are platform-level (they act across tenants, not
;; within one) and are NEVER satisfied by a regular :admin's :all wildcard —
;; :all previously bypassed every permission check with no carve-out, which
;; let any tenant's admin list/create other tenants via :user/manage's
;; sibling check. Only a role that explicitly lists one of these permissions
;; (i.e. :platform-admin) satisfies it now.
(def ^:private platform-permissions #{:platform/manage})

(defn has-permission? [role permission]
  (let [perms (get permissions role #{})]
    (or (contains? perms permission)
        (and (contains? perms :all) (not (contains? platform-permissions permission))))))

(defn require-permission
  "Throws ex-info if identity lacks the required permission."
  [identity permission]
  (when-not (has-permission? (:role identity) permission)
    (throw (ex-info "Forbidden"
                    {:type       :forbidden
                     :permission permission
                     :role       (:role identity)}))))

;; ── Branch-scoped data isolation ─────────────────────────────────────────────
;; A branch manager (or loan officer) should only see customers/loans/
;; applications at their own branch, not every branch in the tenant. Roles
;; not listed here (credit-officer, finance, risk-officer, auditor, admin)
;; are head-office / cross-branch by design and see everything.

(def ^:private branch-scoped-roles #{:branch-manager :loan-officer})

(defn branch-scoped? [role]
  (contains? branch-scoped-roles role))

(defn scope-branch-id
  "Returns the branch-id to filter by for this identity, or nil for
   'no filter' (unscoped roles only). A branch-scoped role with no branch
   set is a misconfigured account, not an unscoped one — denies outright
   rather than silently falling through to an unfiltered result, which is
   what 'nil means no filter' previously did for this exact case."
  [identity]
  (if (branch-scoped? (:role identity))
    (or (:branch-id identity)
        (throw (ex-info "Your account has no assigned branch — contact an administrator"
                        {:type :forbidden :reason :no-branch-assigned})))
    nil))
