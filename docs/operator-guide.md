# Operator Guide

## First Deployment
1. Register verified stands (`forestry.store/with-stands`) — a stand's
   `:verified?` flag must reflect that its site/boundary has actually
   been surveyed, never a routine inventory-log patch.
2. Import historical stand-inventory, field-operation and supply-order
   records for continuity of the audit ledger.
3. Run the demo (`clojure -M:dev:run`) to dry-run every commit, escalate
   and HARD-hold path against the sample stand set
   (`forestry.store/sample-data!`) before pointing this at real stands.
4. Configure the phase (`forestry.phase`, default 3) and confirm which
   human forester/purchasing approver resumes escalated requests.
5. Review the audit ledger produced by the dry-run.

## Minimum Production Controls
- Forest Coordination Governor gate on every proposal before it can
  commit (`forestry.governor/check`) — a hard violation always holds,
  no override
- human forester/purchasing-approver sign-off for `:flag-forest-health-
  concern` (always) and for any `:order-supplies` proposal whose
  independently-recomputed total exceeds `forestry.registry/supply-
  order-cost-threshold` (5000.0)
- `:schedule-field-operation` always requires human sign-off at every
  phase — it is never a member of any phase's `:auto` set
  (`forestry.phase`), matching the governor's own permanent posture
- audit-ledger review (`forestry.store/ledger`) after every operating
  session
- backup manual process for the human forester when the actor is
  unavailable

## Certification
Certified operators must prove: the governor stays independent of the
advisor, every commit/hold/escalation leaves an audit fact, and no
human approval can override a HARD violation (propose-only effect,
closed op/effect allowlists, harvest-finalize block, unverified-stand
block, immature-stand-harvest block, double-schedule guard, invalid
health-status block, supply-order-total-mismatch block).

## Operating states
intake : advise (ForestryAdvisor proposal) : govern (Forest Coordination
Governor check) : decide (phase gate) : commit | request-approval
(human forester/purchasing approver) | hold

One `forestry.operation/build` graph run = one silviculture operation
(one stand-record log, one field-operation schedule, one health-concern
flag, or one supply-order proposal). A stand's lifecycle advances via
many independent runs, each individually auditable and checkpointed
(`interrupt-before #{:request-approval}` pauses the actor for the human
step and resumes with `{:approval {:status :approved}}` or
`:rejected`).

## Audit review (social operation)

After an operating session, review the append-only facts directly from
the store — this build ships no separate CSV/EDN export module (see
docs/adr/0001-architecture.md Consequences: standalone coordinator
blueprint, no external reporting integration):

```clojure
(require '[forestry.store :as store])
(store/ledger db)             ; every commit/hold decision fact
(store/operation-history db)  ; append-only field-operation-schedule drafts
(store/order-history db)      ; append-only supply-order drafts
```

Drafts remain **unsigned** — see `forestry.registry`'s
`unsigned-certificate`: signing and any real equipment dispatch,
harvest execution or purchase order are the human forester's/
purchasing approver's own acts, never this actor's (see README `What
this actor does NOT do`).
