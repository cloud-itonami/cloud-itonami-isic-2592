(ns metaltreatmfg.render-html
  "Build-time HTML renderer for the operator console.

  Drives the REAL MetalTreatmentOperationActor (`metaltreatmfg.operation/
  build` -> a compiled langgraph-clj StateGraph) over the REAL seeded
  store (`metaltreatmfg.store/sample-data!`), through the REAL Metal
  Treatment, Coating & Machining Plant Operations Governor
  (`metaltreatmfg.governor/check`) and the REAL rollout phase gate
  (`metaltreatmfg.phase/gate`), and renders whatever those produced.
  Nothing on the page is written by hand:

    - every table row is read back out of the store after the run
      (`store/ledger`, `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`,
      `store/safety-concerns`, `store/maintenance-history`,
      `store/shipment-history`),
    - every HARD-hold rule name and every violation detail string is
      the governor's own `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the phase gate table is derived from `metaltreatmfg.phase/phases`
      and `metaltreatmfg.governor/allowed-ops`, and the governor
      configuration / ground-truth bound tables from
      `metaltreatmfg.governor` and `metaltreatmfg.registry` public vars.

  The one hand-written thing on the page is each scenario's
  `:exercises` sentence -- a static description of the FIXED op-gate
  contract this repo's governor implements, i.e. documentation of
  intent, not runtime telemetry. It is rendered in a visually muted
  column and never contributes a number, a status or a rule name; if
  the governor's behaviour and that sentence ever disagree, the
  adjacent Governor / Final columns (both derived) are the truth.

  Subject provenance (the demo may not invent subjects): every batch and
  equipment id driven below is either seeded by `store/sample-data!`
  (`batch-001` `batch-002` `batch-003` `plate-001` `cnc-002`) or created
  by an intake op inside this demo itself -- `batch-004` is created by
  the `t01` `:log-production-batch` commit, and every `mnt-*` /
  `ship-*` / `concern-*` subject is the draft record that its own op
  registers via `metaltreatmfg.registry`.

  Fields rendered are only fields the domain model actually carries. In
  particular `:approved-by` is NOT rendered on a committed shipment /
  maintenance record: `metaltreatmfg.operation`'s `:request-approval`
  node puts the approver on the record's `:payload`, while
  `store/commit-record!` persists `:value` -- so the approver is shown
  from the run timeline (where it is real), not from the stored record
  (where it does not exist).

  Deterministic: no clock, no randomness, no network, no timestamp in
  the page content. Re-running writes a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [metaltreatmfg.governor :as governor]
            [metaltreatmfg.operation :as op]
            [metaltreatmfg.phase :as phase]
            [metaltreatmfg.registry :as registry]
            [metaltreatmfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :shop-coordinator :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`).

  `:exercises` is the one hand-written string on the page -- a static
  description of the fixed op-gate contract (see this namespace's
  docstring). Every other cell in the timeline is derived from what the
  graph actually did."
  [{:tid "t01"
    :exercises "Intake of a NEW production batch. Governor-clean, and :log-production-batch is the one op in phase 3's :auto set -> auto-commit. batch-004 exists for the rest of this page only because this op created it."
    :request {:op :log-production-batch :effect :propose :subject "batch-004"
              :patch {:product-category :powder-coated-item
                      :process-type :powder-coating
                      :material "Powder-Coated Bracket Lot"
                      :weight-kg 900.0
                      :defect-rate-percent 0.7
                      :last-assessed "2026-07-20"}}}

   {:tid "t02"
    :exercises "Maintenance window against a verified + registered electroplating bath. Never auto-eligible at any phase -> escalates; the human shop supervisor approves."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "plate-001"
                      :maintenance-type :bath-chemistry-refresh
                      :scheduled-date "2026-08-01"
                      :actuate-plating-machining-line? false}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Safety concern on the plating bath's fume extraction. Always high-stakes, so the governor escalates regardless of confidence; the human approves."
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "plate-001" :severity :moderate
                      :description "電気めっき槽の排気フード吸引力低下、シアン化物系薬液の蒸気曝露リスクを確認"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t04"
    :exercises "Shipment against a verified + registered batch with headroom. Escalates; the human shipping approver approves and the batch's shipped weight advances."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :weight-kg 500.0
                      :destination "buyer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "Governor-clean shipment the human VETOES. Distinct from a HARD hold: the governor cleared it, a person did not."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-001" :weight-kg 100.0
                      :destination "buyer-yard-west"}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "Shipment against batch-004 -- the batch t01 just created, which carries no verified?/registered? ground truth of its own. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-004" :weight-kg 100.0
                      :destination "buyer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "Shipment against the seeded UNVERIFIED / unregistered heat-treated batch. A QC inspection that never happened cannot be inferred from an intake patch. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-003" :weight-kg 500.0
                      :destination "buyer-yard-south"}}}

   {:tid "t08"
    :exercises "Shipment whose claimed weight would push batch-002 past its own recorded production weight. The governor recomputes from the batch's own fields, never the proposal's claim. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-5"
              :value {:batch-id "batch-002" :weight-kg 1000.0
                      :destination "buyer-yard-east"}}}

   {:tid "t09"
    :exercises "Maintenance against the seeded CNC machine, which is neither inspected nor on file. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "cnc-002"
                      :maintenance-type :tool-change
                      :scheduled-date "2026-08-05"
                      :actuate-plating-machining-line? false}}}

   {:tid "t10"
    :exercises "A maintenance proposal that tries to ACTUATE the plating / machining line rather than draft a window. Permanent scope boundary -- never reaches a human, even with an approval waiting. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "plate-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01"
                      :actuate-plating-machining-line? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t11"
    :exercises "The SAME maintenance window as t02, scheduled twice. Guarded off a dedicated :scheduled? fact, never a :status value. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "plate-001"
                      :maintenance-type :bath-chemistry-refresh
                      :scheduled-date "2026-08-01"
                      :actuate-plating-machining-line? false}}}

   {:tid "t12"
    :exercises "A batch patch declaring a product category outside the closed metal-treatment / coating / machining family. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:product-category :unobtainium-gadget}}}

   {:tid "t13"
    :exercises "A batch patch claiming a defect rate above 100% -- fabricated or sensor-error QC data. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:defect-rate-percent 999.0}}}

   {:tid "t14"
    :exercises "A mis-wired caller whose own request :effect is not :propose -- checked before anything else, so proposal-only mode cannot be bypassed. HARD hold."
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-category :electroplated-item}}}

   {:tid "t15"
    :exercises "An op outside the closed allowlist. Both the op allowlist and the proposal-effect allowlist reject it. HARD hold."
    :request {:op :actuate-plating-line :effect :propose :subject "batch-001"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario.
  Returns {:db store :runs [..]}."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model has no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- num-cell [v]
  (if (nil? v) "—" (str "<span class=\"num\">" (esc v) "</span>")))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"muted\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- yes-no [ok? yes no]
  (if ok?
    (str "<span class=\"ok\">" yes "</span>")
    (str "<span class=\"critical\">" no "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation
  order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

(defn- round4
  "Round to the same 1/10000 granularity `registry/shipment-weight-
  exceeded?` itself compares at, so a derived remaining-weight cell
  never shows a double-representation artefact."
  [x]
  (when (number? x) (/ (Math/round (* 10000.0 (double x))) 10000.0)))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "Every `:governor-hold` fact the run actually put on the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger "
               "after driving " (esc (count runs)) " requests through "
               (code "metaltreatmfg.operation/build") ". "
               "Note: " (code ":approval-granted") " is emitted to the graph's in-memory "
               (code ":audit") " channel only — " (code "metaltreatmfg.operation")
               " never appends it to the store ledger, so it is not a fact this page counts. "
               "An approved request is visible as the " (code ":committed") " fact it produced.")
          (table ["Measure" "Count"]
                 [(tr "requests driven" (num-cell (count runs)))
                  (tr "ledger facts" (num-cell (count led)))
                  (tr "commits" (num-cell (n :committed)))
                  (tr "governor HARD holds" (num-cell (n :governor-hold)))
                  (tr "human rejections on the ledger" (num-cell (n :approval-rejected)))
                  (tr "human approvals handed back"
                      (num-cell (count (filter #(= :approved (:human %)) runs))))
                  (tr "distinct governor rules exercised"
                      (num-cell (count (into #{} (mapcat :basis) (holds db)))))]))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one <code>langgraph.graph/run*</code> over the compiled actor. "
             "The governor column is the verdict map the governor itself returned; the human "
             "column is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ". Only the last column is hand-written "
             "— it describes the fixed op-gate contract, not this run.")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is a " (code ":governor-hold") " fact on the append-only ledger. The rule "
               "name and the detail text are the governor's own " (code ":violations")
               " entries — this page holds no rule text of its own. A HARD hold is never "
               "overridable and never reaches a human.")
          (table ["Rule" "Op" "Subject" "Advisor confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (num-cell (:confidence h))
                       (esc (:detail v))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Advisor confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r)) (num-cell (:confidence r)))))))))

(defn- phase-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Rollout phase gate — phase " ph " (" label ")")
          (str "Derived from " (code "metaltreatmfg.phase/phases") ". A governor HOLD always stays a "
               "HOLD; an op that may write but is not auto-eligible escalates to a human even when "
               "the governor is clean. " (code ":schedule-maintenance") " is deliberately absent "
               "from every phase's " (code ":auto") " set — a permanent structural fact, not a "
               "rollout milestone still to come.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (yes-no (contains? writes o) "yes" "no — HOLD (:phase-disabled)")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "metaltreatmfg.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (num-cell governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal effects" (kw-codes governor/allowed-proposal-effects))
                (tr "always-human stakes" (kw-codes governor/high-stakes))])))

(defn- bounds-section []
  (card "Independent ground-truth bounds"
        (str "The values " (code "metaltreatmfg.registry") " uses to re-derive the truth itself, "
             "rather than believing the advisor's rationale.")
        (table ["Bound" "Value"]
               [(tr "valid product categories" (kw-codes registry/valid-product-categories))
                (tr "valid process types" (kw-codes registry/valid-process-types))
                (tr "defect rate (%)"
                    (str (code registry/defect-rate-min-percent) " … "
                         (code registry/defect-rate-max-percent)))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- remaining-kg [b]
  (let [w (:weight-kg b) s (:shipped-weight-kg b 0.0)]
    (when (and (number? w) (number? s)) (round4 (- (double w) (double s))))))

(defn- batches-section [db]
  (let [led (ledger-of db)]
    (card "Production batches"
          (str "Read back from " (code "metaltreatmfg.store/all-batches") " after the run. "
               (code "batch-001") " " (code "batch-002") " " (code "batch-003")
               " are seeded by " (code "store/sample-data!") "; " (code "batch-004")
               " exists because the " (code "t01") " intake op committed it. "
               "A field the record does not carry shows as —; " (code "batch-004")
               " has no " (code ":shipped-weight-kg") " of its own yet, so <em>Remaining</em> "
               "uses the same " (code "0.0") " default " (code "metaltreatmfg.registry")
               " itself applies when it recomputes headroom.")
          (table ["Batch" "Product category" "Process" "Material" "Weight (kg)"
                  "Shipped (kg)" "Remaining (kg)" "Defect rate (%)" "verified?" "registered?"
                  "ready?" "Last assessed" "Ledger status"]
                 (for [b (store/all-batches db)]
                   (tr (code (:id b)) (fmt (:product-category b)) (fmt (:process-type b))
                       (fmt (:material b))
                       (num-cell (:weight-kg b)) (num-cell (:shipped-weight-kg b))
                       (num-cell (remaining-kg b)) (num-cell (:defect-rate-percent b))
                       (flag (:verified? b)) (flag (:registered? b))
                       (yes-no (registry/batch-ready? b) "yes" "no")
                       (fmt (:last-assessed b))
                       (subject-status led (:id b))))))))

(defn- equipment-section [db]
  (card "Plating / machining equipment"
        (str "Read back from " (code "metaltreatmfg.store/all-equipment") ". Equipment ids are "
             "never a request " (code ":subject") " in this domain (a maintenance draft id is), "
             "so no ledger-status column is shown for them — "
             (code ":last-scheduled-maintenance-date")
             " is the field the commit path actually writes onto an equipment record.")
        (table ["Unit" "Kind" "verified?" "registered?" "ready?" "Last maintenance"
                "Last scheduled maintenance" "Maintenance drafts on file"]
               (for [e (store/all-equipment db)]
                 (tr (code (:id e)) (fmt (:kind e))
                     (flag (:verified? e)) (flag (:registered? e))
                     (yes-no (registry/equipment-ready? e) "yes" "no")
                     (fmt (:last-maintenance-date e))
                     (fmt (:last-scheduled-maintenance-date e))
                     (num-cell (count (filter #(= (:id e) (:equipment-id %))
                                              (store/all-maintenance db)))))))))

(defn- maintenance-section [db]
  (let [ms (store/all-maintenance db)]
    (card "Maintenance schedule drafts"
          (str "Committed drafts from " (code "metaltreatmfg.store/all-maintenance") ". The "
               "maintenance number is minted by "
               (code "metaltreatmfg.registry/register-maintenance")
               " at commit time. Nothing here actuates any bath, tank, furnace or machine.")
          (if (seq ms)
            (table ["Draft" "Equipment" "Type" "Scheduled date"
                    "actuate-plating-machining-line?" "scheduled?" "Maintenance number"]
                   (for [m ms]
                     (tr (code (:id m)) (code (:equipment-id m)) (fmt (:maintenance-type m))
                         (fmt (:scheduled-date m))
                         (flag (:actuate-plating-machining-line? m))
                         (flag (:scheduled? m)) (fmt (:maintenance-number m)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- shipments-section [db]
  (let [hist (store/shipment-history db)
        ships (keep #(store/shipment db (get % "shipment_id")) hist)]
    (card "Shipment coordination drafts"
          (str "Committed drafts, joined from " (code "metaltreatmfg.store/shipment-history")
               " back to each stored shipment record. This is a draft a coordinator keeps — it "
               "dispatches no freight carrier.")
          (if (seq ships)
            (table ["Draft" "Batch" "Weight (kg)" "Destination" "Shipment number"]
                   (for [s ships]
                     (tr (code (:id s)) (code (:batch-id s)) (num-cell (:weight-kg s))
                         (fmt (:destination s)) (fmt (:shipment-number s)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- concerns-section [db]
  (let [cs (store/safety-concerns db)]
    (card "Safety concerns"
          (str "The append-only safety-concern log (" (code "metaltreatmfg.store/safety-concerns")
               "). A concern may be raised against any equipment, verified or not — it is never "
               "blocked on an administrative technicality.")
          (if (seq cs)
            (table ["Concern" "Equipment" "Severity" "Description"]
                   (for [c cs]
                     (tr (code (:id c)) (code (:equipment-id c)) (fmt (:severity c))
                         (fmt (:description c)))))
            "<p class=\"muted\">none flagged in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as "
             (code "metaltreatmfg.store/ledger") " returns it.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (num-cell (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-2592 (metaltreatmfg)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<span class=\"badge\">ISIC 2592</span>"
       "<span class=\"badge\">metaltreatmfg</span>"
       "<span class=\"badge\">read-only sample</span>"
       "</header>\n"
       "<h1>Metal treatment, coating &amp; machining — plant operations operator console</h1>"
       "<p class=\"subtitle\">governor "
       (code "metal-treatment-coating-machining-plant-operations-governor")
       " · actor " (esc (:actor-id coordinator))
       " · role " (code (:actor-role coordinator))
       " · phase " (esc (:phase coordinator))
       "</p>\n<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (bounds-section)
                          (batches-section db)
                          (equipment-section db)
                          (maintenance-section db)
                          (shipments-section db)
                          (concerns-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>metaltreatmfg.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>metaltreatmfg.operation</code> actor graph over the real "
       "<code>metaltreatmfg.store</code> seed. Deterministic — no clock, no randomness, no "
       "network. No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; Build-time invariant: a console that shows no real HARD hold is
    ;; not evidence of a governor. Do not weaken this.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))
                       :requests (count runs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
