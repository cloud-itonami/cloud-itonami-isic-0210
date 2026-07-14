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

Classic governed-actor pattern:
1. **ForestryAdvisor** (sealed LLM node): proposes decisions
2. **Forest Coordination Governor** (independent): validates against domain rules
   - HARD invariants (always `:hold`, no override):
     - Stand must exist and be verified in SSoT
     - All proposals are `:effect :propose` only
     - No direct logging-equipment control or harvest-plan finalization
   - ESCALATE (always human sign-off):
     - Forest health concerns always escalate
     - Supply orders above cost threshold
     - Low-confidence proposals
3. **Phase gates** (Phase 0->3 rollout): only human-approved paths
4. **Audit ledger** (append-only): complete decision trace

## Development

```bash
# Install dependencies (workspace offline mode)
clojure -M:dev

# Run tests
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
