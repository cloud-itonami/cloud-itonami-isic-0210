# cloud-itonami-isic-0210: Silviculture and other forestry activities

An autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office silviculture operations: stand assessment, field operation scheduling, forest-health monitoring, and supply procurement.

## What this actor does

Proposes **coordination** of silviculture operations:
- `:log-stand-record` — forest stand inventory/growth data logging (proposal only)
- `:schedule-field-operation` — planting/thinning/harvest scheduling proposal
- `:flag-forest-health-concern` — surface a pest/disease/wildfire risk (always escalates)
- `:order-supplies` — seedling/equipment procurement proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY:**
- Does NOT control logging equipment or field operations directly
- Does NOT make forest-management planning decisions (that's the forester's exclusive human authority)
- Does NOT authorize or execute harvest operations (human forester decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval

## Architecture

Classic governed-actor pattern (`forestry.operation/build`, a langgraph-clj StateGraph):
1. **`forestry.advisor`** (sealed intelligence node, `ForestryAdvisor`): proposes decisions only, never commits
2. **`forestry.governor`** (independent, `Forest Coordination Governor`): validates against domain rules, re-derived from `forestry.registry`'s pure functions and `forestry.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct logging-equipment control)
     - Finalizing a harvest plan (`:finalize? true`) is a PERMANENT, unconditional block
     - A field operation may only be scheduled against a stand independently verified in the SSoT
     - A harvest may only be scheduled against a stand independently confirmed mature (`forestry.registry/maturity-threshold-years`)
     - No double-scheduling the same field-operation record
     - No fabricated `:health-status` value on a stand-record patch
     - A supply order's claimed total must independently recompute correctly from its own line items
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-forest-health-concern` always escalates, regardless of confidence
     - `:order-supplies` whose independently-recomputed total exceeds `forestry.registry/supply-order-cost-threshold`
     - Low-confidence proposals
3. **`forestry.phase`** (Phase 0->3 rollout): `:schedule-field-operation`/`:flag-forest-health-concern`/`:order-supplies` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-stand-record` may auto-commit at phase 3 when clean
4. **`forestry.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
