(ns loanmanager.security.rbac-test
  (:require [clojure.test :refer [deftest is testing]]
            [loanmanager.security.rbac :as rbac]))

(deftest has-permission
  (testing "loan-officer can read customers"
    (is (rbac/has-permission? :loan-officer :customer/read)))

  (testing "loan-officer cannot disburse loans"
    (is (not (rbac/has-permission? :loan-officer :loan/disburse))))

  (testing "finance can disburse"
    (is (rbac/has-permission? :finance :loan/disburse)))

  (testing "admin has all permissions via :all"
    (is (rbac/has-permission? :admin :any-permission-at-all)))

  (testing "auditor can read audit log"
    (is (rbac/has-permission? :auditor :audit/read)))

  (testing "auditor cannot create payments"
    (is (not (rbac/has-permission? :auditor :payment/create))))

  (testing "collections can manage collection cases"
    (is (rbac/has-permission? :collections :collection/create)))

  (testing "risk-officer can read fraud"
    (is (rbac/has-permission? :risk-officer :fraud/read)))

  (testing "admin's :all wildcard does NOT satisfy the platform-level permission"
    (is (not (rbac/has-permission? :admin :platform/manage))))

  (testing "platform-admin has :platform/manage"
    (is (rbac/has-permission? :platform-admin :platform/manage)))

  (testing "platform-admin has no business permissions"
    (is (not (rbac/has-permission? :platform-admin :loan/disburse)))
    (is (not (rbac/has-permission? :platform-admin :customer/read)))))

(deftest require-permission
  (testing "Throws ex-info when permission missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rbac/require-permission {:role :loan-officer} :loan/disburse))))

  (testing "Does not throw when permission present"
    (is (nil? (rbac/require-permission {:role :finance} :loan/disburse))))

  (testing "Exception has :type :forbidden"
    (try
      (rbac/require-permission {:role :auditor} :payment/create)
      (catch clojure.lang.ExceptionInfo e
        (is (= :forbidden (:type (ex-data e))))))))

(deftest scope-branch-id
  (testing "Branch-scoped role with a branch returns that branch-id"
    (is (= "b1" (rbac/scope-branch-id {:role :branch-manager :branch-id "b1"}))))

  (testing "Branch-scoped role with NO branch denies outright, doesn't fall through to unfiltered"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no assigned branch"
                          (rbac/scope-branch-id {:role :loan-officer :branch-id nil}))))

  (testing "Denial exception has :type :forbidden"
    (try
      (rbac/scope-branch-id {:role :branch-manager :branch-id nil})
      (catch clojure.lang.ExceptionInfo e
        (is (= :forbidden (:type (ex-data e)))))))

  (testing "Unscoped roles are never filtered, regardless of branch-id"
    (is (nil? (rbac/scope-branch-id {:role :admin :branch-id nil})))
    (is (nil? (rbac/scope-branch-id {:role :finance :branch-id "b1"})))))
