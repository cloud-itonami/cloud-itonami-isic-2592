(ns metaltreatmfg.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean metal-treatment/
  coating/machining job shop through intake -> maintenance scheduling
  (escalate/approve) -> safety-concern flag (escalate/approve) ->
  shipment coordination (escalate/approve), then shows HARD-hold
  scenarios: a mis-wired request whose own `:effect` is not
  `:propose`, an unrecognized op, maintenance scheduled against an
  UNVERIFIED/unregistered equipment unit, a shipment coordinated
  against an UNVERIFIED/unregistered batch, a shipment proposal that
  would exceed the batch's own logged production weight, a proposal
  that tries to ACTUATE the electroplating bath or CNC machine
  directly (permanently blocked, no override), a double-schedule of
  the same maintenance window, a production-batch patch with a
  fabricated product category, and a production-batch patch with an
  implausible defect-rate reading.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [metaltreatmfg.store :as store]
            [metaltreatmfg.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :shop-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-production-batch batch-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:product-category :electroplated-item :last-assessed "2026-07-14"}}
                       coordinator))

    (println "== schedule-maintenance mnt-1 on plate-001 (verified, registered, electroplating bath -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                       :value {:equipment-id "plate-001" :maintenance-type :bath-chemistry-refresh
                               :scheduled-date "2026-08-01" :actuate-plating-machining-line? false}}
                      coordinator)]
      (println r)
      (println "-- human shop supervisor approves --")
      (println (approve! actor "t2")))

    (println "== flag-safety-concern concern-1 on plate-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:equipment-id "plate-001" :severity :moderate
                               :description "電気めっき槽の排気フード吸引力低下、シアン化物系薬液の蒸気曝露リスクを確認"}}
                      coordinator)]
      (println r)
      (println "-- human shop supervisor approves --")
      (println (approve! actor "t3")))

    (println "== coordinate-shipment ship-1 on batch-001 (verified, registered, within weight -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :coordinate-shipment :effect :propose :subject "ship-1"
                       :value {:batch-id "batch-001" :weight-kg 500.0
                               :destination "buyer-yard-north"}}
                      coordinator)]
      (println r)
      (println "-- human shipping approver approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-production-batch with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-production-batch :effect :direct-write :subject "batch-001"
                        :patch {:product-category :electroplated-item}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :actuate-plating-line :effect :propose :subject "batch-001"}
                       coordinator))

    (println "== schedule-maintenance mnt-2 on cnc-002 (UNVERIFIED/unregistered CNC machine -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                        :value {:equipment-id "cnc-002" :maintenance-type :tool-change
                                :scheduled-date "2026-08-01" :actuate-plating-machining-line? false}}
                       coordinator))

    (println "== coordinate-shipment ship-2 on batch-003 (UNVERIFIED/unregistered batch -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :weight-kg 500.0
                                :destination "buyer-yard-south"}}
                       coordinator))

    (println "== coordinate-shipment ship-3 on batch-002 (1000 kg would exceed weight 6000 vs shipped 5600 -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :weight-kg 1000.0
                                :destination "buyer-yard-east"}}
                       coordinator))

    (println "== schedule-maintenance mnt-3 on plate-001 with :actuate-plating-machining-line? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                        :value {:equipment-id "plate-001" :maintenance-type :force-run
                                :scheduled-date "2026-09-01" :actuate-plating-machining-line? true}}
                       coordinator))

    (println "== schedule-maintenance mnt-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "plate-001" :maintenance-type :bath-chemistry-refresh
                                :scheduled-date "2026-08-01" :actuate-plating-machining-line? false}}
                       coordinator))

    (println "== log-production-batch batch-001 with a fabricated product-category -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:product-category :unobtainium-gadget}}
                       coordinator))

    (println "== log-production-batch batch-001 with an implausible defect-rate reading -> HARD hold ==")
    (println (exec-op actor "t13"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:defect-rate-percent 999.0}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft maintenance records ==")
    (doseq [r (store/maintenance-history db)] (println r))

    (println "\n== draft shipment records ==")
    (doseq [r (store/shipment-history db)] (println r))))
