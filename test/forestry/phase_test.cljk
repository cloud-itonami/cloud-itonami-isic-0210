(ns forestry.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-field-operation` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [forestry.phase :as phase]))

(deftest schedule-field-operation-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real field-operation schedule"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-field-operation))
          (str "phase " n " must not auto-commit :schedule-field-operation")))))

(deftest flag-forest-health-concern-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :flag-forest-health-concern))
        (str "phase " n " must not auto-commit :flag-forest-health-concern"))))

(deftest order-supplies-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :order-supplies))
        (str "phase " n " must not auto-commit :order-supplies"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-stand-record carries no physical/financial risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-stand-record} (:auto (get phase/phases 3))))))

(deftest schedule-field-operation-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :schedule-field-operation))
  (is (not (contains? (:writes (get phase/phases 2)) :schedule-field-operation)))
  (is (not (contains? (:writes (get phase/phases 1)) :schedule-field-operation))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-stand-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-field-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-forest-health-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :order-supplies} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-stand-record} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-stand-record} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
