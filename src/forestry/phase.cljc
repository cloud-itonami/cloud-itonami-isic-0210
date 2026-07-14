(ns forestry.phase
  "Phase gates for the forestry operations actor: Phase 0->3 rollout.
  Each phase allows different operations to auto-commit vs. require approval.

  Phase 0 (Planning): Most operations require approval, no auto-commit
  Phase 1 (Assessment): Initial stand assessments can auto-commit if clean
  Phase 2 (Scheduling): Field operation scheduling requires approval
  Phase 3 (Operations): No auto-commit, all actuation requires human approval")

(def default-phase :phase-0)

(def phase-specs
  {:phase-0 {:name "Planning"
             :auto #{}  ; Nothing auto-commits in phase 0
             :requires-approval #{:log-stand-record
                                  :schedule-field-operation
                                  :flag-forest-health-concern
                                  :order-supplies}}
   :phase-1 {:name "Assessment"
             :auto #{}  ; Assessment only auto-commits if low-risk
             :requires-approval #{:log-stand-record
                                  :schedule-field-operation
                                  :order-supplies}}
   :phase-2 {:name "Scheduling"
             :auto #{}
             :requires-approval #{:schedule-field-operation
                                  :order-supplies}}
   :phase-3 {:name "Operations"
             :auto #{}
             :requires-approval #{:log-stand-record
                                  :schedule-field-operation
                                  :order-supplies}}})

(defn verdict->disposition [verdict]
  "Map governor verdict to base disposition."
  (if (seq (:violations verdict))
    :hold
    (if (:escalate? verdict)
      :escalate
      :commit)))

(defn gate [phase _request base-disposition]
  "Apply phase-specific gating rules. Can only add caution, not remove it."
  (case base-disposition
    :hold
    {:disposition :hold :reason nil}  ; Hard violations stay held

    :escalate
    {:disposition :escalate :reason nil}  ; Escalation stays escalated

    :commit
    ;; Commit: check phase requirements
    (let [phase-spec (get phase-specs phase default-phase)]
      (if (= phase :phase-1)
        ;; Phase 1: some ops can auto-commit
        {:disposition :commit :reason nil}
        ;; All other phases: require approval for actions
        {:disposition :escalate
         :reason (str "Phase " (:name phase-spec) " requires human approval")}))))
