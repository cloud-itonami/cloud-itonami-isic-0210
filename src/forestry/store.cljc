(ns forestry.store
  "SSoT for the community-silviculture coordination actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every `cloud-itonami-isic-*` actor in this fleet uses.

  Scope note: unlike some sibling actors, this build ships a single
  `MemStore` backend only (atom of EDN) -- the deterministic default
  for dev/tests/demo, no deps. Per docs/adr/0001-architecture.md
  Decision 1, this vertical is self-contained (no external forestry
  capability library, no jurisdiction-scoped Datomic-parity requirement
  driving a second backend); a `langchain.db`-backed store can be added
  later behind the same protocol without changing any caller.

  Three kinds of entity live here:
    - `stands`           -- the central entity. A silviculture stand's
                            inventory/site record. `:verified?` marks
                            whether the stand's own site/boundary has
                            actually been surveyed (never inferred from
                            a routine inventory-log patch).
    - `field-operations` -- a scheduled planting/thinning/harvest DRAFT
                            against a stand (`forestry.registry`'s
                            `register-field-operation`). Dedicated
                            `:scheduled?` double-schedule guard (never a
                            `:status` value -- the same discipline every
                            prior governor's guards establish, informed
                            by `cloud-itonami-isic-6492`'s status-
                            lifecycle bug, ADR-2607071320).
    - `supply-orders`    -- a proposed procurement DRAFT
                            (`forestry.registry`'s `register-supply-
                            order`).

  Plus a generic `records` map (id -> raw record) used only for direct,
  domain-agnostic `commit-record!` calls (a record with no `:effect`
  key) -- the store-level primitive every sibling actor's own MemStore
  exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which stand was proposed, which field
  operation was scheduled against a verified stand, which supply order
  was placed and at what independently-recomputed total, approved by
  whom' is always a query over an immutable log -- the audit trail a
  forestry cooperative or landowner trusting this coordinator needs."
  (:require [forestry.registry :as registry]))

(defprotocol Store
  (stand [s id])
  (all-stands [s])
  (field-operation [s id])
  (all-field-operations [s])
  (supply-order [s id])
  (health-concerns [s] "the append-only forest-health-concern log")
  (ledger [s])
  (operation-history [s] "the append-only field-operation-schedule history (forestry.registry drafts)")
  (order-history [s] "the append-only supply-order history (forestry.registry drafts)")
  (next-operation-sequence [s] "next field-operation-number sequence")
  (next-order-sequence [s] "next supply-order-number sequence")
  (field-operation-already-scheduled? [s operation-id] "has this field operation already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-stands [s stands] "replace/seed the stand directory (map id->stand)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-stands []
  {"stand-001" {:id "stand-001" :site "North Ridge" :species "Douglas Fir"
                :area-ha 12.5 :age-years 35 :verified? true
                :health-status :healthy :last-assessed "2026-06-01"}
   "stand-002" {:id "stand-002" :site "East Hollow" :species "Sitka Spruce"
                :area-ha 8.0 :age-years 8 :verified? true
                :health-status :healthy :last-assessed "2026-06-01"}
   "stand-003" {:id "stand-003" :site "South Basin" :species "Western Hemlock"
                :area-ha 20.0 :age-years 45 :verified? false
                :health-status :concern :last-assessed "2026-05-15"}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-field-operation!
  "Backend-agnostic `:field-operation/schedule` -- drafts the field-
  operation-schedule record via `forestry.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s operation-id stand-id]
  (let [seq-n (next-operation-sequence s)
        result (registry/register-field-operation operation-id stand-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :operation-number (get result "operation_number")}}))

(defn- propose-supply-order!
  "Backend-agnostic `:supply-order/propose` -- drafts the supply-order
  record via `forestry.registry` and returns {:result .. :patch ..} for
  the caller to persist."
  [s order-id]
  (let [seq-n (next-order-sequence s)
        result (registry/register-supply-order order-id seq-n)]
    {:result result
     :patch {:order-number (get result "order_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (stand [_ id] (get-in @a [:stands id]))
  (all-stands [_] (sort-by :id (vals (:stands @a))))
  (field-operation [_ id] (get-in @a [:field-operations id]))
  (all-field-operations [_] (sort-by :id (vals (:field-operations @a))))
  (supply-order [_ id] (get-in @a [:supply-orders id]))
  (health-concerns [_] (:health-concerns @a))
  (ledger [_] (:ledger @a))
  (operation-history [_] (:operation-history @a))
  (order-history [_] (:order-history @a))
  (next-operation-sequence [_] (:operation-sequence @a 0))
  (next-order-sequence [_] (:order-sequence @a 0))
  (field-operation-already-scheduled? [_ operation-id]
    (boolean (get-in @a [:field-operations operation-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :stand/upsert)
      (swap! a update-in [:stands (first path)] merge (assoc value :id (first path)))

      (= effect :field-operation/schedule)
      (let [operation-id (first path)
            stand-id (:stand-id value)
            {:keys [result patch]} (schedule-field-operation! s operation-id stand-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :operation-sequence (fnil inc 0))
                       (update-in [:field-operations operation-id] merge (assoc value :id operation-id) patch)
                       (update :operation-history registry/append result))))
        result)

      (= effect :health-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :health-concerns conj concern)
        concern)

      (= effect :supply-order/propose)
      (let [order-id (first path)
            {:keys [result patch]} (propose-supply-order! s order-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :order-sequence (fnil inc 0))
                       (update-in [:supply-orders order-id] merge (assoc value :id order-id) patch)
                       (update :order-history registry/append result))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `chemmineops`-style siblings expose as their
      ;; own low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-stands [s stands] (when (seq stands) (swap! a assoc :stands stands)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:stands {} :field-operations {} :supply-orders {}
                      :records {} :health-concerns []
                      :ledger [] :operation-sequence 0 :operation-history []
                      :order-sequence 0 :order-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained stand set --
  one verified mature stand (harvest-eligible), one verified immature
  stand (too young to harvest), one UNVERIFIED stand (blocks any field-
  operation scheduling against it) -- so the actor + demo + tests run
  offline. Returns `s` (thread-friendly with `->`)."
  [s]
  (with-stands s (sample-stands))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name the actor's own demo/test
;; harness (docs/adr/0001-architecture.md-era code) already calls.

(defn get-ledger [s] (ledger s))
