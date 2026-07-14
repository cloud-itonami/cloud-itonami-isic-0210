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
`forestry.registry` (`stand-immature-for-harvest?` stand-maturity checks
against `maturity-threshold-years`, `health-status-valid?` health-status
validation, `order-total-matches-claim?`/`order-exceeds-threshold?` supply
budget verification, plus `register-field-operation`/`register-supply-order`
draft-record construction) are re-verified independently by
`forestry.governor` — the same "ground truth, not self-report" discipline
established across prior actors. `forestry.store` ships a single `MemStore`
backend (no second Datomic-backed store): this vertical's SSoT needs no
jurisdiction-scoped parity requirement, and a second backend can be added
later behind the same `Store` protocol without changing any caller.

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

- `cloud-itonami-isic-0210`: `clojure -M:test` (and the equivalent
  `clojure -M:dev:test`) green (all tests pass) across
  `forestry.operation-test`, `forestry.governor-contract-test`,
  `forestry.phase-test`, `forestry.store-contract-test` and
  `forestry.registry-test`; `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, stand-not-verified,
  stand-immature-for-harvest, harvest-finalize-blocked, already-scheduled,
  invalid-health-status, order-total-mismatch) plus the over-threshold
  order-supplies ESCALATE (not HOLD) case.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no JVM-only
  interop; the actor graph is invoked exclusively via `langgraph.graph/run*`
  (not `.invoke`, which an earlier draft of this repo used and which is not
  cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
