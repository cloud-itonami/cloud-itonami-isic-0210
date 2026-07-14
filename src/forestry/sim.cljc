(ns forestry.sim
  "Demo driver for the ForestryOperationActor. Walk through a few silviculture
  scenarios: stand assessment logging, field operation scheduling."
  (:require [forestry.operation :as op]
            [forestry.store :as store]
            [forestry.phase :as phase]))

(defn -main [& _args]
  (let [s (-> (store/mem-store) (store/sample-data!))
        actor (op/build s)]

    (println "=== ForestryOperationActor Demo ===\n")

    ;; Scenario 1: Log a stand record (assessment proposal)
    (println "Scenario 1: Proposing stand assessment log")
    (let [request {:op :log-stand-record
                   :effect :propose
                   :subject "stand-001"}
          context {:actor-id "forestry-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (println "  Disposition:" (:disposition result))
      (println "  Ledger entry count:" (count (store/get-ledger s)))
      (println))

    ;; Scenario 2: Field operation scheduling (always requires approval)
    (println "Scenario 2: Proposing field operation schedule")
    (let [request {:op :schedule-field-operation
                   :effect :propose
                   :subject "thinning-operation-001"}
          context {:actor-id "forestry-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (println "  Disposition:" (:disposition result))
      (println "  Reason:" (first (filter #(= :approval-requested (:t %))
                                           (store/get-ledger s))))
      (println))

    ;; Scenario 3: Forest health concern (always escalates)
    (println "Scenario 3: Flagging forest health concern (always escalates)")
    (let [request {:op :flag-forest-health-concern
                   :effect :propose
                   :subject "pest-outbreak-detection"}
          context {:actor-id "forestry-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (println "  Disposition:" (:disposition result))
      (println "  Ledger entries:" (count (store/get-ledger s)))
      (println))

    (println "=== Demo Complete ===")
    (println "\nFinal audit ledger:")
    (doseq [entry (store/get-ledger s)]
      (println "  " entry))))
