# ADR-0001: ForestryAdvisor ⊣ Forest Coordination Governor architecture

## Status

Accepted. `cloud-itonami-isic-0210` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-0210` publishes an OSS blueprint for community
silviculture and forestry operations (stand assessment, field operation
scheduling, forest-health monitoring, and supply procurement). Like every
actor in this fleet, the blueprint alone is not an implementation: this ADR
records the governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase 0->3
rollout pattern established across the cloud-itonami fleet.

This vertical has NO bespoke domain capability library in `kotoba-lang` to
wrap (verified: no `kotoba-lang/forestry`-style repo exists). This build
therefore uses self-contained domain logic — pure functions in
`forestry.registry` (stand maturity checks, health-status validation, supply
budget verification) are re-verified independently by the governor.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:forest-coordination-governor`, is grep-verified UNIQUE fleet-wide.

## Decision

### Decision 1: Self-contained domain logic (no external forestry capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
silviculture vertical has NO pre-existing capability library to wrap. The
stand-maturity / forest-health / supply-budget validation functions live as
pure functions in `forestry.registry` and are re-verified independently by
`forestry.governor` — the same "ground truth, not self-report" discipline
established across prior actors.

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of silviculture operations.
It does NOT:
- Control logging equipment or field operations directly
- Make forest-management planning decisions (exclusive to human forester)
- Authorize or execute harvest operations

All proposals are `:effect :propose` only. The advisor proposes; the governor
validates; escalation paths funnel to human forester approval. This is not a
replacement for forester authority — it is a proposal-screening and
documentation layer.

### Decision 3: Dual-escalation shape: forest-health concerns and supply orders both require approval

`:flag-forest-health-concern` (pest/disease/wildfire risk) ALWAYS escalates,
never auto-commits. Supply orders above a cost threshold also escalate.
Neither are "low-stakes proposals that the coordinator can decide alone."

### Decision 4: HARD invariants (no override)

Three HARD governor checks that block proposals and cannot be overridden by
human approval:
1. Stand must exist and be registered in the SSoT before any action
2. Proposals must be `:effect :propose` only (never direct control)
3. Direct logging-equipment control or harvest-plan finalization is permanently blocked

## Consequences

(+) Forestry operations back-office now has a documented, governed, auditable
coordination layer that funnels all decisions through independent validation
before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human forester sign-off.

(+) Scope is bounded and verifiable: three HARD invariants protect against
scope creep into unauthorized equipment operation or harvest authorization.

(-) Still a simulation/proposal layer, not a real field-operations control
system. Equipment dispatch and harvest execution remain human-controlled via
external channels.

(-) No integration with real forestry-management databases (GIS, growth models,
regulatory reporting) — this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-0210`: `clojure -M:dev:test` green (all tests pass),
  `clojure -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and HARD hold scenarios.
- All source is `.cljc` (portable ClojureScript / JVM / nbb).
- Audit ledger is append-only, all decisions are traced.
