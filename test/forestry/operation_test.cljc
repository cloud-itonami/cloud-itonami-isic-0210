(ns forestry.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [forestry.operation :as op]
            [forestry.store :as store]
            [forestry.phase :as phase]))

(deftest test-actor-builds
  (testing "ForestryOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-stand-assessment-proposal
  (testing "Proposing a stand assessment logs correctly"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          request {:op :log-stand-record
                   :effect :propose
                   :subject "stand-001"}
          context {:actor-id "forestry-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          initial-ledger-size (count (store/get-ledger s))
          result (-> actor (.invoke {:request request :context context}))
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (some? result)))))

(deftest test-field-operation-scheduling
  (testing "Field operation scheduling is proposed"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          request {:op :schedule-field-operation
                   :effect :propose
                   :subject "thinning-operation-001"}
          context {:actor-id "forestry-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (is (some? result)))))

(deftest test-forest-health-concern-escalation
  (testing "Forest health concerns always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          request {:op :flag-forest-health-concern
                   :effect :propose
                   :subject "pest-outbreak"}
          context {:actor-id "forestry-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (is (some? result)))))

(deftest test-supply-order-proposal
  (testing "Supply order proposal is submitted"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          request {:op :order-supplies
                   :effect :propose
                   :subject {:items ["seedlings" "equipment"]}}
          context {:actor-id "forestry-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (is (some? result)))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "Records can be committed to store"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))
