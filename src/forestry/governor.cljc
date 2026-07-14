(ns forestry.governor
  "Forest Coordination Governor -- the independent compliance layer that
  earns the ForestryAdvisor the right to commit. The advisor has no
  notion of whether a stand it wants to schedule a field operation
  against has actually been surveyed/verified, whether a harvest
  proposal secretly tries to FINALIZE (rather than merely draft-
  schedule) a harvest, whether a stand is actually mature enough to
  harvest, whether a supply order's own claimed total actually equals
  the sum of its own line items, or when an act stops being a
  coordination proposal and becomes direct equipment control, so this
  MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  `:itonami.blueprint/governor` is `:forest-coordination-governor`
  (see docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:equipment/actuate`
                                       or `:harvest/finalize`) is the
                                       'direct logging-equipment
                                       control' scope violation this
                                       actor must NEVER perform -- HARD,
                                       PERMANENT, unconditional.
    4. Harvest-finalize blocked    -- for `:schedule-field-operation`,
                                       does the proposal's own `:value`
                                       declare `:finalize? true`?
                                       Finalizing a harvest plan is this
                                       actor's other permanent scope
                                       boundary (see README `What this
                                       actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `forestry.phase`: this op is
                                       never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    5. Stand not verified          -- for `:schedule-field-operation`,
                                       INDEPENDENTLY verify the
                                       referenced stand's own
                                       `:verified?` is true
                                       (`forestry.registry/stand-
                                       verified?`) -- never trust the
                                       advisor's own rationale about
                                       verification status. Grounded in
                                       this blueprint's own scope
                                       boundary: field operations must
                                       never be scheduled against a
                                       stand whose site/boundary has not
                                       actually been surveyed.
    6. Stand immature for harvest  -- for a `:harvest`
                                       `:schedule-field-operation`,
                                       INDEPENDENTLY recompute whether
                                       the stand's own recorded
                                       `:age-years` falls below
                                       `forestry.registry/maturity-
                                       threshold-years`
                                       (`forestry.registry/stand-
                                       immature-for-harvest?`) -- ground
                                       truth from the stand's own
                                       permanent field, never a self-
                                       reported maturity claim.
    7. Already scheduled           -- for `:schedule-field-operation`,
                                       refuses to schedule the SAME
                                       field-operation record twice, off
                                       a dedicated `:scheduled?` fact
                                       (never a `:status` value).
    8. Invalid health status       -- for `:log-stand-record`, if the
                                       patch declares a `:health-status`
                                       outside the closed known set
                                       (`forestry.registry/health-
                                       status-valid?`), the stand record
                                       is rejected rather than let a
                                       fabricated status through.
    9. Supply-order total mismatch -- for `:order-supplies`,
                                       INDEPENDENTLY recompute whether
                                       the order's own `:claimed-total`
                                       equals the sum of its own
                                       `:items` (`forestry.registry/
                                       order-total-matches-claim?`) --
                                       an honest reapplication of the
                                       SAME ground-truth-recompute
                                       discipline every sibling actor's
                                       own cost/total-matching check
                                       establishes.
   10. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/health-concern`,
                                       ALWAYS set for `:flag-forest-
                                       health-concern`), OR (for
                                       `:order-supplies`) the order's
                                       own independently-recomputed
                                       total exceeds `forestry.registry/
                                       supply-order-cost-threshold` --
                                       escalate to a human forester/
                                       purchasing approver. SOFT: the
                                       human may approve."
  (:require [forestry.registry :as registry]
            [forestry.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-stand-record :schedule-field-operation
    :flag-forest-health-concern :order-supplies})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct-equipment-
  control or harvest-finalize effect."
  #{:stand/upsert :field-operation/schedule
    :health-concern/flag :supply-order/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Forest-health concerns are the one op in this domain that always
  demands human eyes regardless of confidence."
  #{:coordination/health-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct logging-equipment control, a fabricated
  actuation effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は伐採機材の直接操作/伐採計画確定に該当する可能性があり、恒久的に禁止")}]))

(defn- harvest-finalize-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-field-operation`
  proposal whose own `:value` declares `:finalize? true` is attempting
  to finalize a harvest plan directly -- this actor may only ever
  propose/schedule a DRAFT, never finalize one. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-field-operation)
             (true? (:finalize? (:value proposal))))
    [{:rule :harvest-finalize-blocked
      :detail "伐採計画の確定(finalize)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- stand-not-verified-violations
  "For `:schedule-field-operation`, INDEPENDENTLY verify the referenced
  stand exists and is `:verified?` -- never trust the advisor's own
  report."
  [{:keys [op]} proposal st]
  (when (= op :schedule-field-operation)
    (let [stand-id (:stand-id (:value proposal))
          s (and stand-id (store/stand st stand-id))]
      (when-not (and s (registry/stand-verified? s))
        [{:rule :stand-not-verified
          :detail (str stand-id " は未検証、または存在しない -- 検証済み林分記録が無い状態での作業予定提案")}]))))

(defn- stand-immature-for-harvest-violations
  "For a `:harvest` `:schedule-field-operation`, INDEPENDENTLY recompute
  whether the stand's own recorded age falls below the maturity
  threshold -- ground truth from the stand's own permanent field."
  [{:keys [op]} proposal st]
  (when (= op :schedule-field-operation)
    (let [{:keys [stand-id operation-type]} (:value proposal)
          s (and stand-id (store/stand st stand-id))]
      (when (and s (= operation-type :harvest) (registry/stand-immature-for-harvest? s))
        [{:rule :stand-immature-for-harvest
          :detail (str stand-id " の林齢(" (:age-years s) "年)が伐採可能齢("
                       registry/maturity-threshold-years "年)未満")}]))))

(defn- already-scheduled-violations
  "For `:schedule-field-operation`, refuses to schedule the SAME
  field-operation record twice, off a dedicated `:scheduled?` fact
  (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-field-operation)
    (when (store/field-operation-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- invalid-health-status-violations
  "For `:log-stand-record`, if the patch declares a `:health-status`
  outside the closed known set, reject rather than let a fabricated
  status through."
  [{:keys [op]} proposal]
  (when (= op :log-stand-record)
    (let [status (:health-status (:value proposal))]
      (when (and (some? status) (not (registry/health-status-valid? status)))
        [{:rule :invalid-health-status
          :detail (str status " は既知の health-status 値ではない")}]))))

(defn- order-total-mismatch-violations
  "For `:order-supplies`, INDEPENDENTLY recompute whether the order's
  own claimed total equals the sum of its own line items via
  `forestry.registry/order-total-matches-claim?` -- needs no store
  lookup at all, an honest reapplication of the same discipline every
  sibling actor's own cost/total-matching check establishes."
  [{:keys [op]} proposal]
  (when (= op :order-supplies)
    (let [order (:value proposal)]
      (when-not (registry/order-total-matches-claim? order)
        [{:rule :order-total-mismatch
          :detail (str "申告合計(" (:claimed-total order)
                       ")が独立再計算値(" (registry/order-total order) ")と一致しない")}]))))

(defn check
  "Censors a ForestryAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (harvest-finalize-blocked-violations request proposal)
                           (stand-not-verified-violations request proposal st)
                           (stand-immature-for-harvest-violations request proposal st)
                           (already-scheduled-violations request st)
                           (invalid-health-status-violations request proposal)
                           (order-total-mismatch-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        high-cost-order? (and (= (:op request) :order-supplies)
                               (registry/order-exceeds-threshold? (:value proposal)))
        stakes? (or (boolean (high-stakes (:stake proposal))) high-cost-order?)
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
