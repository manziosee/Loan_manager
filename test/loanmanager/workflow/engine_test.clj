(ns loanmanager.workflow.engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [loanmanager.workflow.engine :as wf]))

(def ^:private rules
  {:auto-approve-below 5000
   :standard-below     50000
   :large-below        250000})

(deftest determine-workflow
  (testing "Small loan auto-approves"
    (let [{:keys [type steps]} (wf/determine-workflow 4999 rules)]
      (is (= :auto-approve type))
      (is (empty? steps))))

  (testing "Standard loan has one step"
    (let [{:keys [type steps]} (wf/determine-workflow 10000 rules)]
      (is (= :standard type))
      (is (= 1 (count steps)))))

  (testing "Large loan has two steps"
    (let [{:keys [type steps]} (wf/determine-workflow 100000 rules)]
      (is (= :large type))
      (is (= 2 (count steps)))))

  (testing "Very large loan has four steps"
    (let [{:keys [type steps]} (wf/determine-workflow 500000 rules)]
      (is (= :very-large type))
      (is (= 4 (count steps))))))

(deftest next-step
  (testing "Returns first uncompleted step"
    (let [steps [{:step "branch_manager"} {:step "credit_committee"}]
          nxt   (wf/next-step steps ["branch_manager"])]
      (is (= "credit_committee" (:step nxt)))))

  (testing "Returns nil when all steps completed"
    (let [steps [{:step "branch_manager"}]
          nxt   (wf/next-step steps ["branch_manager"])]
      (is (nil? nxt)))))

(deftest advance-workflow
  (testing "Completed steps accumulate"
    (let [state  {:completed-steps []}
          state2 (wf/advance! state "branch_manager" :approved
                               #uuid "00000000-0000-0000-0000-000000000001" "Looks good")]
      (is (= ["branch_manager"] (:completed-steps state2)))
      (is (= :approved (get-in state2 [:last-action :action])))))

  (testing "Multiple advances accumulate"
    (let [state (-> {:completed-steps []}
                    (wf/advance! "branch_manager"   :approved
                                 #uuid "00000000-0000-0000-0000-000000000001" nil)
                    (wf/advance! "credit_committee" :approved
                                 #uuid "00000000-0000-0000-0000-000000000002" nil))]
      (is (= 2 (count (:completed-steps state)))))))

(deftest complete-check
  (testing "Not complete when steps remain"
    (let [steps [{:step "branch_manager"} {:step "credit_committee"}]
          state {:completed-steps ["branch_manager"]}]
      (is (not (wf/complete? steps state)))))

  (testing "Complete when all steps done"
    (let [steps [{:step "branch_manager"} {:step "credit_committee"}]
          state {:completed-steps ["branch_manager" "credit_committee"]}]
      (is (wf/complete? steps state)))))
