# cloud-itonami-isic-2592: Treatment and coating of metals; machining

Open Business Blueprint for **ISIC Rev.5 2592**: treatment and coating of metals; machining — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **metal-treatment/coating/machining job-shop plant operations**: production-batch data logging (product-category/weight/defect-rate), electroplating-bath/anodizing-tank/heat-treatment-furnace/CNC-machine maintenance scheduling, safety-concern flagging, and outbound electroplated/anodized/coated/galvanized/heat-treated/machined product shipment coordination.

This repository designs a forkable OSS business for metal-treatment/
coating/machining job-shop plant operations: run by a qualified
operator so a shop keeps its own operating records instead of renting
a closed SaaS.

## Scope: the metal-treatment/coating/machining job shop, not forging/pressing, cutlery/hardware manufacture, or other fabricated metal products

ISIC 2592 covers the shop that electroplates, anodizes, powder-coats,
galvanizes, heat-treats and precision-machines metal parts on a fee-
or-contract basis (a job shop that treats/finishes/machines OTHER
manufacturers' parts, not a maker of a finished consumer product
itself). This is distinct from `cloud-itonami-isic-2591` (Forging,
pressing, stamping and roll-forming of metal), the primary heavy
metal-forming process; from `cloud-itonami-isic-2593` (Manufacture of
cutlery, hand tools and general hardware), a finished-goods shop; and
from `cloud-itonami-isic-2599` (Manufacture of other fabricated metal
products n.e.c.), the residual stamping/pressing/wire-forming shop, a
distinct, more general product family. This actor's own hazard profile
centers on electroplating-bath chemical-toxicity exposure (cyanide,
hexavalent-chromium and acid plating/etching baths), CNC-machining
swarf/cutting-tool laceration hazard, and heat-treatment-furnace
burn/radiant-heat exposure.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-category/weight/defect-rate data logging (administrative, not an operational decision)
- `:schedule-maintenance` — electroplating-bath/anodizing-tank/heat-treatment-furnace/CNC-machine maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-hazard (electroplating-bath toxicity)/CNC-machining-swarf-laceration/heat-treatment-furnace-burn/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound electroplated/anodized/coated/galvanized/heat-treated/machined product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-relevant domain**
(electroplating-bath chemical-toxicity exposure from cyanide/
hexavalent-chromium/acid baths, CNC-machining swarf/cutting-tool
laceration hazard, heat-treatment-furnace burn/radiant-heat exposure):

- Does NOT control the electroplating bath, anodizing tank, heat-treatment furnace or CNC machine directly
- Does NOT make shop-safety or materials-safety decisions (that's the shop supervisor's exclusive human authority)
- Does NOT actuate the electroplating bath, anodizing tank, heat-treatment furnace or CNC machine (human shop supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`metaltreatmfg.operation/build`, a langgraph-clj StateGraph):
1. **`metaltreatmfg.advisor`** (sealed intelligence node, `MetalTreatmentAdvisor`): proposes decisions only, never commits
2. **`metaltreatmfg.governor`** (independent, `Metal Treatment, Coating & Machining Plant Operations Governor`): validates against domain rules, re-derived from `metaltreatmfg.registry`'s pure functions and `metaltreatmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Shop/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct plating/machining-line-equipment control)
     - Any proposal touching plating/machining-line-equipment control is a hard, permanent block (`:actuate-plating-machining-line? true` on a `:schedule-maintenance` proposal)
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-category` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`metaltreatmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`metaltreatmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

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
