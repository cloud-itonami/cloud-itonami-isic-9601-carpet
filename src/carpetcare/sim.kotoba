(ns carpetcare.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean ticket through
  intake -> care-plan verification -> fibre/colourfastness screening ->
  cleaning application (escalate/approve/commit) -> carpet return
  (escalate/approve/commit), then shows every HARD-hold scenario:

    - a jurisdiction with no spec-basis (ticket-2, \"ATL\")
    - alkaline detergent on a hand-knotted wool rug (ticket-3) -- felts
      and yellows irreversibly
    - colourfastness not confirmed on a naturally-dyed kilim (ticket-4)
    - a claimed residual moisture that disagrees with its own weights (ticket-5)
    - an op outside the closed vocabulary
    - a proposal whose prose reaches for a valuation or liability decision

  Deterministic and offline."
  (:require [langgraph.graph :as g]
            [carpetcare.store :as store]
            [carpetcare.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :cleaning-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== ticket/intake ticket-1 (JPN, synthetic) ==")
    (exec-op actor "t1" {:op :ticket/intake :subject "ticket-1"
                         :patch {:id "ticket-1" :customer "Sakura Tanaka"}} operator)

    (println "== careplan/verify ticket-1 (escalates -- approves) ==")
    (exec-op actor "t2" {:op :careplan/verify :subject "ticket-1"} operator)
    (approve! actor "t2")

    (println "== fibre/screen ticket-1 (confirmed; escalates -- approves) ==")
    (exec-op actor "t3" {:op :fibre/screen :subject "ticket-1"} operator)
    (approve! actor "t3")

    (println "== actuation/apply-cleaning-process ticket-1 (never auto) ==")
    (exec-op actor "t4" {:op :actuation/apply-cleaning-process :subject "ticket-1"} operator)
    (approve! actor "t4")

    (println "== actuation/return-carpet ticket-1 (never auto) ==")
    (exec-op actor "t5" {:op :actuation/return-carpet :subject "ticket-1"} operator)
    (approve! actor "t5")

    (println "== HOLD: no spec-basis (ticket-2, ATL) ==")
    (exec-op actor "h1" {:op :careplan/verify :subject "ticket-2"} operator)

    (println "== HOLD: alkaline detergent on a wool rug (ticket-3) ==")
    (exec-op actor "h2" {:op :actuation/apply-cleaning-process :subject "ticket-3"} operator)

    (println "== HOLD: colourfastness not confirmed (ticket-4) ==")
    (exec-op actor "h3" {:op :fibre/screen :subject "ticket-4"} operator)

    (println "== HOLD: moisture claim mismatch (ticket-5) ==")
    (exec-op actor "h4" {:op :actuation/apply-cleaning-process :subject "ticket-5"} operator)

    (println "== HOLD: op outside the closed vocabulary ==")
    (exec-op actor "h5" {:op :actuation/appraise-value :subject "ticket-1"} operator)

    (println "== HOLD: double cleaning (ticket-1 already cleaned above) ==")
    (exec-op actor "h6" {:op :actuation/apply-cleaning-process :subject "ticket-1"} operator)

    (println "\n== ledger (append-only) ==")
    (doseq [f (store/ledger db)]
      (println " " (:t f) (:op f) (:subject f) (or (:basis f) "")))))
