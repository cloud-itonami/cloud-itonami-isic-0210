# Business Model: Silviculture and Other Forestry Activities

## Classification
- Repository: `cloud-itonami-isic-0210`
- ISIC Rev.5: `0210` — silviculture and other forestry activities —
  stand assessment, field-operation (planting/thinning/harvest)
  scheduling, forest-health monitoring, and seedling/equipment supply
  procurement
- Social impact: sustainable-forestry, forest-health, rural-employment

## Customer
- forestry cooperatives and private landowners needing auditable stand-
  inventory and field-operation-scheduling records
- foresters needing an independently-verified stand-maturity ground
  truth before authorizing planting, thinning or harvest work
- purchasing approvers needing an independently-recomputed order total
  before fulfilling a seedling/equipment procurement request
- programs that cannot accept closed, unauditable forestry back-office
  coordination platforms

## Offer
- stand inventory/growth-data logging against a closed, validated
  health-status vocabulary (`:healthy` / `:concern` / `:critical`)
- field-operation (planting/thinning/harvest) scheduling proposals,
  gated on independent stand-verification and independent stand-age
  re-derivation against a 20-year maturity threshold before any harvest
  can be scheduled
- forest-health concern flagging (pest/disease/wildfire risk) that
  always reaches a human forester, regardless of advisor confidence
- seedling/equipment supply-order proposals whose claimed total is
  independently recomputed from its own line items before it can commit
- role-based actor/governor separation and an append-only audit ledger
  (`forestry.store/ledger`) recording every commit and hold
- a staged Phase 0→3 rollout (`forestry.phase`) so a new deployment can
  start read-only and expand write authority incrementally

## Revenue
- self-host setup fee
- managed hosting subscription per forestry cooperative / landowner
- support retainer with SLA
- integration work connecting this coordinator's stand/field-operation
  records to an existing GIS, growth-model or regulatory-reporting
  system (out of scope for this build itself — see
  docs/adr/0001-architecture.md Consequences)

## Trust Controls
- a field operation may only be scheduled against a stand independently
  confirmed `:verified?` in the SSoT — never trusted from the advisor's
  own rationale
- a harvest may only be scheduled against a stand independently
  confirmed mature; finalizing a harvest plan (`:finalize? true`) is a
  PERMANENT, unconditional governor block with no human override
- a supply order's claimed total must independently recompute correctly
  from its own line items, or it HARD-holds rather than commit on a
  mismatched figure
- forest-health-concern flags and over-threshold supply orders always
  escalate to a human forester or purchasing approver — never
  auto-commit at any phase
- every settled request (commit or hold) leaves exactly one append-only
  ledger fact; nothing is silently dropped
- this actor never controls logging equipment, never makes forest-
  management planning decisions, and never authorizes or executes a
  harvest — all proposals are `:effect :propose` only (see README `What
  this actor does NOT do`)
