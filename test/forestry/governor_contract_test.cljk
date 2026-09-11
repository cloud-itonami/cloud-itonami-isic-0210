(ns forestry.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT control logging equipment or field
  operations directly... does NOT authorize or execute harvest
  operations') implemented faithfully. The single invariant under test:

    ForestryAdvisor never schedules a field operation, flags a health
    concern, or places a supply order the Forest Coordination Governor
    would reject; `:schedule-field-operation`/`:flag-forest-health-
    concern`/`:order-supplies` (over threshold) NEVER auto-commit at
    any phase; `:log-stand-record` (no physical/financial risk) MAY
    auto-commit when clean; and every decision (commit OR hold) leaves
    exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [forestry.store :as store]
            [forestry.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :forestry-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-stand-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-stand-record :effect :propose :subject "stand-001"
                   :patch {:health-status :healthy}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :healthy (:health-status (store/stand db "stand-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-field-operation-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-field-operation :effect :propose :subject "op-1"
                     :value {:stand-id "stand-001" :operation-type :thinning
                             :scheduled-date "2026-08-01" :finalize? false}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/field-operation db "op-1"))))
        (is (= 1 (count (store/operation-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-stand-record :effect :direct-write :subject "stand-001"
                     :patch {:health-status :healthy}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :dispatch-harvester :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest stand-not-verified-is-held-and-unoverridable
  (testing "scheduling against an unverified stand -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-field-operation :effect :propose :subject "op-3"
                     :value {:stand-id "stand-003" :operation-type :thinning
                             :scheduled-date "2026-08-01" :finalize? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:stand-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/operation-history db))))))

(deftest stand-immature-for-harvest-is-held-and-unoverridable
  (testing "a harvest scheduled against a stand below the maturity threshold -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :schedule-field-operation :effect :propose :subject "op-4"
                     :value {:stand-id "stand-002" :operation-type :harvest
                             :scheduled-date "2026-08-01" :finalize? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:stand-immature-for-harvest} (-> (store/ledger db) last :basis)))
      (is (empty? (store/operation-history db))))))

(deftest harvest-finalize-is-held-and-permanently-blocked
  (testing "a proposal that sets :finalize? true on a harvest -> HOLD, PERMANENT, never reaches request-approval even though the stand is verified and mature"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :schedule-field-operation :effect :propose :subject "op-5"
                     :value {:stand-id "stand-001" :operation-type :harvest
                             :scheduled-date "2026-09-01" :finalize? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:harvest-finalize-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/operation-history db))))))

(deftest schedule-field-operation-double-schedule-is-held
  (testing "scheduling the SAME field-operation record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t8a" {:op :schedule-field-operation :effect :propose :subject "op-1"
                                  :value {:stand-id "stand-001" :operation-type :thinning
                                          :scheduled-date "2026-08-01" :finalize? false}} coordinator)
          _ (approve! actor "t8a")
          res (exec-op actor "t8" {:op :schedule-field-operation :effect :propose :subject "op-1"
                                   :value {:stand-id "stand-001" :operation-type :thinning
                                           :scheduled-date "2026-08-01" :finalize? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/operation-history db))) "still only the one earlier schedule"))))

(deftest invalid-health-status-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t9" {:op :log-stand-record :effect :propose :subject "stand-001"
                                 :patch {:health-status :thriving-fabricated}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-health-status} (-> (store/ledger db) last :basis)))
    (is (not= :thriving-fabricated (:health-status (store/stand db "stand-001"))) "fabricated status never lands in the SSoT")))

(deftest order-total-mismatch-is-held
  (testing "a claimed total that doesn't equal the sum of its own line items -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t10" {:op :order-supplies :effect :propose :subject "order-2"
                                    :value {:items [{:qty 100 :unit-cost 7.5}] :claimed-total 1000.0}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:order-total-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/order-history db))))))

(deftest order-exceeds-threshold-escalates-not-holds
  (testing "a CLEAN order whose own recomputed total exceeds the cost threshold -> ESCALATE, not a HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :order-supplies :effect :propose :subject "order-3"
                                    :value {:items [{:qty 2 :unit-cost 4000.0}] :claimed-total 8000.0}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t11")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/order-history db))))))))

(deftest health-concern-always-escalates-even-high-confidence
  (testing "flag-forest-health-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :flag-forest-health-concern :effect :propose :subject "concern-1"
                                    :value {:stand-id "stand-001" :severity :moderate
                                            :description "早期の樹皮甲虫被害の兆候"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t12")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/health-concerns db))))))))

(deftest health-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t13" {:op :flag-forest-health-concern :effect :propose :subject "concern-2"
                                :value {:stand-id "stand-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t13")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/health-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-stand-record :effect :propose :subject "stand-001"
                          :patch {:health-status :healthy}} coordinator)
      (exec-op actor "b" {:op :log-stand-record :effect :propose :subject "stand-001"
                          :patch {:health-status :fabricated}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
