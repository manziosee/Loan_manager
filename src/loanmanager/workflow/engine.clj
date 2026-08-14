(ns loanmanager.workflow.engine
  "Configurable approval workflow engine.
   Workflow steps are derived from product approval-rules config.")

(def ^:private default-steps
  {:auto-approve  []
   :standard      [{:step "branch_manager"   :label "Branch Manager Review"}]
   :large         [{:step "branch_manager"   :label "Branch Manager Review"}
                   {:step "credit_committee" :label "Credit Committee"}]
   :very-large    [{:step "branch_manager"   :label "Branch Manager Review"}
                   {:step "credit_committee" :label "Credit Committee"}
                   {:step "risk_officer"     :label "Risk Officer"}
                   {:step "final_approval"   :label "Final Approval"}]})

(defn determine-workflow
  "Returns the list of approval steps for a given loan amount and product rules.
   approval-rules example: {:auto-approve-below 5000
                             :standard-below 50000
                             :large-below 250000}"
  [amount {:keys [auto-approve-below standard-below large-below]
           :or   {auto-approve-below 5000
                  standard-below     50000
                  large-below        250000}}]
  (cond
    (< amount auto-approve-below) {:type :auto-approve  :steps []}
    (< amount standard-below)     {:type :standard      :steps (:standard default-steps)}
    (< amount large-below)        {:type :large         :steps (:large default-steps)}
    :else                         {:type :very-large    :steps (:very-large default-steps)}))

(defn next-step
  "Returns the next pending step from workflow state, or nil if complete."
  [steps completed-steps]
  (let [done (set completed-steps)]
    (first (remove #(done (:step %)) steps))))

(defn advance!
  "Returns updated workflow-state after an actor takes action on a step.
   action: :approved | :rejected | :returned"
  [workflow-state step-name action actor-id comments]
  (-> workflow-state
      (update :completed-steps (fnil conj []) step-name)
      (assoc  :last-action     {:step     step-name
                                :action   action
                                :actor-id actor-id
                                :comments comments})))

(defn complete?
  "Returns true when all required steps are approved."
  [steps workflow-state]
  (let [done (set (:completed-steps workflow-state))]
    (every? #(done (:step %)) steps)))

;; ── Step → role authorization ─────────────────────────────────────────────────
;; Maker-checker only means something if the right person acts at each step —
;; otherwise anyone with :application/approve could rubber-stamp every step
;; themselves. :admin can always act (matches its :all permission elsewhere).

(def ^:private step-required-role
  {"branch_manager"   :branch-manager
   "credit_committee" :credit-officer
   "risk_officer"     :risk-officer
   "final_approval"   :admin})

(defn authorized-for-step?
  "True if actor-role may act on the given step name (by role, or :admin)."
  [step-name actor-role]
  (or (= :admin actor-role)
      (= (get step-required-role step-name) actor-role)))
