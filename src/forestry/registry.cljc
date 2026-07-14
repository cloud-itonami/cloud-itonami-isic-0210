(ns forestry.registry
  "Pure-function domain logic for the community-silviculture coordination
  actor -- stand-maturity checks, health-status validation, supply-order
  budget verification, and draft field-operation/supply-order record
  construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/forestry`-style capability library to wrap
  (verified: no such repo exists). The domain logic therefore lives here
  as pure functions, re-verified INDEPENDENTLY by `forestry.governor` --
  the same 'ground truth, not self-report' discipline every sibling
  actor's own registry establishes (e.g. `chemmineops.registry/royalty-
  matches-claim?`, `leathergoods.registry/parts-cost-matches-claim?`):
  never trust a proposal's own self-reported total/verdict when the
  inputs needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real forestry-operations system. It builds the DRAFT
  record a forestry coordinator would keep (a scheduled field operation,
  a supply order), not the act of dispatching equipment, executing a
  harvest, or placing a real purchase order (this actor NEVER does
  either -- see README `What this actor does NOT do`).")

;; ----------------------------- constants -----------------------------

(def maturity-threshold-years
  "A stand below this age is IMMATURE -- scheduling a harvest against it
  is a HARD, unconditional governor block (see `forestry.governor`'s
  `stand-immature-for-harvest-violations`). Thinning/planting have no
  maturity floor (only harvest removes the standing crop)."
  20)

(def valid-health-statuses
  "The closed set of health-status values a stand record may declare.
  Anything else is a fabricated/unrecognized status -- the governor
  HARD-holds rather than let an invented status pass through."
  #{:healthy :concern :critical})

(def supply-order-cost-threshold
  "Supply orders whose independently-recomputed total exceeds this
  amount always escalate to a human forester/purchasing approver,
  regardless of confidence -- see `forestry.governor`'s high-stakes
  gate."
  5000.0)

;; ----------------------------- stand checks -----------------------------

(defn stand-verified?
  "Ground-truth check: has `stand`'s own record been marked verified
  (i.e. its site/boundary has actually been surveyed and registered in
  the SSoT, not merely logged from an unverified inventory patch)? A
  pure predicate over the stand's own permanent field -- no proposal
  inspection needed."
  [stand]
  (true? (:verified? stand)))

(defn stand-immature-for-harvest?
  "Ground-truth check for a `:harvest` field-operation proposal: is
  `stand`'s own recorded `:age-years` below `maturity-threshold-years`?
  Needs no proposal inspection or stored-verdict lookup -- its input is
  a permanent field already on the stand record, the same shape every
  sibling actor's own cost/total-matching check uses."
  [stand]
  (let [age (:age-years stand)]
    (and (number? age) (< age maturity-threshold-years))))

(defn health-status-valid?
  "Is `status` one of the closed, known health-status values? nil/blank
  is treated as invalid (a stand-record patch must declare a real
  status, not omit it silently)."
  [status]
  (contains? valid-health-statuses status))

;; ----------------------------- supply-order checks -----------------------------

(defn order-total
  "The ground-truth total for `order`'s own `:items` (each `{:qty n
  :unit-cost c}`) -- independent of whatever `:claimed-total` the
  proposal itself carries."
  [{:keys [items]}]
  (reduce (fn [acc {:keys [qty unit-cost]}]
            (+ acc (* (double (or qty 0)) (double (or unit-cost 0)))))
          0.0
          items))

(defn order-total-matches-claim?
  "Does `order`'s own `:claimed-total` equal the independently
  recomputed `order-total`? An honest reapplication of the SAME
  ground-truth-recompute discipline every sibling actor's own cost/
  total-matching check establishes (e.g. `chemmineops.registry/
  royalty-matches-claim?`), reapplied to a supply-order line rather
  than a royalty or repair-parts line."
  [{:keys [claimed-total] :as order}]
  (and (number? claimed-total)
       (== (double claimed-total) (order-total order))))

(defn order-exceeds-threshold?
  "Does `order`'s own independently-recomputed total exceed
  `supply-order-cost-threshold`? Computed from the order's own line
  items, never from a self-reported `:stake` or confidence value."
  [order]
  (> (order-total order) supply-order-cost-threshold))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human forester's/purchasing approver's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-field-operation
  "Validate + construct the FIELD-OPERATION SCHEDULING DRAFT -- a
  proposed planting/thinning/harvest date on a verified stand. Pure
  function -- does not dispatch equipment or execute any field
  operation; it builds the RECORD a coordinator would keep.
  `forestry.governor` independently re-verifies the stand's own
  verified/maturity ground truth, and permanently blocks any attempt to
  set `:finalize? true` on a harvest (see README `Actuation`), before
  this is ever allowed to commit."
  [operation-id stand-id sequence]
  (when-not (and operation-id (not= operation-id ""))
    (throw (ex-info "field-operation: operation_id required" {})))
  (when-not (and stand-id (not= stand-id ""))
    (throw (ex-info "field-operation: stand_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "field-operation: sequence must be >= 0" {})))
  (let [operation-number (str "FOP-" (zero-pad sequence 6))
        record {"record_id" operation-number
                "kind" "field-operation-schedule-draft"
                "operation_id" operation-id
                "stand_id" stand-id
                "immutable" true}]
    {"record" record "operation_number" operation-number
     "certificate" (unsigned-certificate "FieldOperationSchedule" operation-number operation-number)}))

(defn register-supply-order
  "Validate + construct the SUPPLY-ORDER DRAFT -- a proposed seedling/
  equipment procurement. Pure function -- does not place any real
  purchase order; it builds the RECORD a coordinator would keep.
  `forestry.governor` independently re-verifies the order's own claimed-
  total against `order-total`, and escalates (never auto-commits) any
  order whose recomputed total exceeds `supply-order-cost-threshold`,
  before this is ever allowed to commit."
  [order-id sequence]
  (when-not (and order-id (not= order-id ""))
    (throw (ex-info "supply-order: order_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "supply-order: sequence must be >= 0" {})))
  (let [order-number (str "ORD-" (zero-pad sequence 6))
        record {"record_id" order-number
                "kind" "supply-order-draft"
                "order_id" order-id
                "immutable" true}]
    {"record" record "order_number" order-number
     "certificate" (unsigned-certificate "SupplyOrder" order-number order-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
