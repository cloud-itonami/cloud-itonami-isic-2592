# ADR-0001: MetalTreatmentAdvisor ⊣ Metal Treatment, Coating & Machining Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2592` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2592` publishes an OSS blueprint for a metal-
treatment/coating/machining job shop's **plant operations
coordination** (production-batch product-category/weight/defect-rate
data logging, electroplating-bath/anodizing-tank/heat-treatment-
furnace/CNC-machine maintenance scheduling, safety-concern flagging,
and outbound electroplated/anodized/coated/galvanized/heat-treated/
machined product shipment coordination). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

The closest architectural analog is `cloud-itonami-isic-2593`
(Manufacture of cutlery, hand tools and general hardware): both are
back-office coordination actors for a fixed processing PLANT with
heavy manufacturing equipment and a real physical safety dimension,
and both share the same four-op shape (`:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`)
and the same two-entity verified/registered gate structure (equipment
for maintenance scheduling, batch for shipment coordination). The two
verticals are, however, distinct plants with distinct hazard profiles:
2593's central physical hazard is sharp-edge laceration risk from
freshly forged/ground blades and edges, forging-hammer pinch/crush
hazard, grinding-wheel/abrasive-dust exposure, and heat-treatment-
furnace burn/radiant-heat exposure, while 2592's is electroplating-
bath chemical-toxicity exposure (cyanide, hexavalent-chromium and acid
plating/etching baths), CNC-machining swarf/cutting-tool laceration
hazard, and heat-treatment-furnace burn/radiant-heat exposure. This
build mirrors 2593's architecture closely but adapts the hazard
profile and equipment/product vocabulary to the metal-treatment/
coating/machining job shop: 2592's permanent equipment-actuation block
guards an electroplating bath/anodizing tank/heat-treatment furnace/
CNC machine (`:actuate-plating-machining-line?`) rather than a forging
hammer/grinding wheel (`:actuate-forge-grind-line?`); and 2592's
production-batch record declares a `:product-category` (spanning
electroplated, anodized, powder-coated, galvanized, heat-treated and
machined-part items, per ISIC 2592's own scope) rather than 2593's
`:product-category` covering cutlery, hand tools and general hardware.

`cloud-itonami-isic-2592` is also distinct from three sibling classes
in the same ISIC 259 group: `cloud-itonami-isic-2591` (Forging,
pressing, stamping and roll-forming of metal -- the primary heavy
metal-forming process upstream of a downstream finishing shop),
`cloud-itonami-isic-2593` (Manufacture of cutlery, hand tools and
general hardware -- a finished-goods shop), and `cloud-itonami-
isic-2599` (Manufacture of other fabricated metal products n.e.c.).
ISIC 2592 is the specific "treatment and coating of metals; machining"
class: a job shop that electroplates, anodizes, powder-coats,
galvanizes, heat-treats and precision-machines metal parts on a
fee-or-contract basis for other manufacturers -- a distinct plant,
distinct process shape (surface-finishing/heat-treatment/machining,
not forging/roll-forming of bulk metal stock or finished-goods
assembly), and this build follows the 2593-style four-op propose-only
pattern specified for this class.

This vertical has NO pre-existing `kotoba-lang/metaltreatmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`metaltreatmfg.registry` (equipment/batch verification, shipment-weight
recompute, product-category validation, defect-rate plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2593`'s `hardwaremfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:metal-treatment-coating-machining-plant-operations-governor`, is
grep-verified UNIQUE fleet-wide (`gh search code
"metal-treatment-coating-machining-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external metal-treatment/coating/machining capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
metal-treatment/coating/machining vertical has NO pre-existing
capability library to wrap. The equipment/batch-verification /
shipment-weight / product-category / defect-rate validation functions
live as pure functions in `metaltreatmfg.registry` and are
re-verified independently by `metaltreatmfg.governor` — the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2593`'s `hardwaremfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of metal-
treatment/coating/machining job-shop plant operations. It does NOT:
- Control the electroplating bath, anodizing tank, heat-treatment furnace or CNC machine directly
- Make shop-safety or materials-safety decisions (exclusive to the human shop supervisor)
- Actuate the electroplating bath, anodizing tank, heat-treatment furnace or CNC machine

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human shop-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: metal-treatment/coating/machining has
real physical hazards (electroplating-bath chemical-toxicity exposure
from cyanide/hexavalent-chromium/acid baths, CNC-machining swarf/
cutting-tool laceration hazard, heat-treatment-furnace burn/radiant-
heat exposure). Safety-concern flagging NEVER auto-commits. All safety
concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (electroplating-bath chemical-toxicity
exposure, CNC-machining swarf/cutting-tool laceration hazard, heat-
treatment-furnace burn/radiant-heat exposure, equipment-safety
concern) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" — it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2593`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight — never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`metaltreatmfg.governor`, mirroring `cloud-itonami-isic-2593`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Any proposal touching plating/machining-line-equipment control, or direct plating/machining-line actuation, is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Metal-treatment/coating/machining job-shop plant operations
back-office now has a documented, governed, auditable coordination
layer that funnels all decisions through independent validation before
human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human shop-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or plating/machining-line actuation.
Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and plating/machining-line
operation remain human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2592`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, plating-machining-
  line-actuate-blocked, already-scheduled, invalid-product-category,
  invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
