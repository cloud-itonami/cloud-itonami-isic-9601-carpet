(ns carpetcare.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it had no demo page and
  no generator at all. This namespace drives the REAL actor stack --
  `carpetcare.operation` (a langgraph StateGraph) -> `carpetcare.governor`
  -> `carpetcare.store` -- through a scenario extended from this repo's
  own `carpetcare.sim` demo driver (`clojure -M:dev:run`, run BEFORE this
  file was written to confirm the real seeded ticket ids `ticket-1`..
  `ticket-5` and the real hold basis vectors).

  **Nothing on the page is typed by hand.** Every ticket id, customer,
  carpet description, fibre, weight, jurisdiction and hold reason is read
  back out of `carpetcare.store/demo-data` or produced by a real
  `carpetcare.governor/check` during this run. The reference tables
  (fibre incompatibility, spec-basis, phase gate) are rendered from
  `carpetcare.registry/fibre-forbidden-processes`,
  `carpetcare.facts/spec-basis-table`, `carpetcare.phase/phases` and
  `carpetcare.governor/high-stakes` rather than transcribed, so the page
  cannot drift from the code it documents.

  Deterministic: no timestamps, no randomness, every map iteration
  explicitly sorted, every float formatted under `Locale/ROOT`. Two runs
  against the same seed are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [carpetcare.advisor :as advisor]
            [carpetcare.facts :as facts]
            [carpetcare.governor :as governor]
            [carpetcare.operation :as op]
            [carpetcare.phase :as phase]
            [carpetcare.registry :as registry]
            [carpetcare.store :as store]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :cleaning-supervisor :phase 3})

;; ----------------------------- the rogue-advisor probe -----------------------------

(def ^:private smuggled-term
  "One of the governor's OWN `scope-excluded-terms`, selected from that
  vector rather than retyped here -- so if the table changes, this probe
  follows it instead of silently testing a string nobody scans for."
  (or (first (filter #(str/includes? % "鑑定") governor/scope-excluded-terms))
      (first governor/scope-excluded-terms)))

(defn- rogue-advisor
  "The repo's own mock advisor with one scope-excluded term spliced into
  its `:rationale`, injected through `operation/build`'s documented
  `:advisor` seam. This is the only way to reach the governor's
  `:scope-excluded` check, which exists precisely because a real LLM
  advisor can describe a permanently out-of-scope decision in prose while
  proposing an otherwise-legitimate op. The op, the ticket and the
  resulting hold are all real; only the advisor is swapped."
  []
  (reify advisor/Advisor
    (-advise [_ st request]
      (let [p (advisor/infer st request)]
        (update p :rationale str "　顧客には" smuggled-term "を提示済み。")))))

;; ----------------------------- the scenario -----------------------------

(defn run-demo!
  "Runs a freshly seeded store through every disposition this actor can
  reach, and returns `{:db store :audit [facts]}`.

  Committed path -- `ticket-1` (JPN, synthetic, colourfastness confirmed,
  claimed moisture agreeing with its own weights) clears the whole
  lifecycle: intake auto-commits at phase 3 (a recording act, no capital
  risk), then care-plan verification, fibre/colourfastness screening, the
  cleaning application and the carpet return each escalate to a human and
  are approved. The two actuations escalate at phase 3 by construction --
  they are absent from every phase's `:auto` set AND members of
  `governor/high-stakes`, two independent layers saying the same thing.

  HARD holds -- one per governor rule, each on the seeded ticket built to
  reach it:

    ticket-2  `:no-spec-basis`                       (jurisdiction \"ATL\")
    ticket-3  `:cleaning-process-forbidden-by-fibre` (alkaline on wool)
    ticket-4  `:colourfastness-not-confirmed`        (naturally-dyed kilim)
    ticket-5  `:moisture-claim-mismatch`             (0.05 claimed vs 0.30)
    ticket-3  `:evidence-incomplete`                 (no care plan on file)
    ticket-1  `:already-cleaned` / `:already-returned` (double actuation)
    ticket-1  `:op-not-allowed`                      (op outside the vocabulary)
    ticket-1  `:scope-excluded`                      (rogue advisor, above)

  And one human REJECTION -- `ticket-3`'s care plan is governor-clean and
  escalates, and the approver declines. That is a `:approval-rejected`
  fact, deliberately NOT counted as a governor hard hold: a human said no,
  the machine did not."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        rogue (op/build db {:advisor (rogue-advisor)})
        order (atom [])
        audits (atom {})
        keep! (fn [tid r]
                (when-not (some #{tid} @order) (swap! order conj tid))
                ;; a resumed run returns its thread's CUMULATIVE audit, so
                ;; the last write for a thread-id is the whole story --
                ;; assoc, never conj, or approvals would be double-counted.
                (swap! audits assoc tid (vec (:audit (:state r))))
                r)
        exec! (fn [a tid request]
                (keep! tid (g/run* a {:request request :context operator}
                                   {:thread-id tid})))
        resume! (fn [tid approval]
                  (keep! tid (g/run* actor {:approval approval}
                                     {:thread-id tid :resume? true})))
        approve! (fn [tid] (resume! tid {:status :approved :by "op-1"}))
        reject! (fn [tid] (resume! tid {:status :rejected :by "op-1"}))]

    ;; --- ticket-1: the full committed lifecycle ---
    (exec! actor "t1-intake" {:op :ticket/intake :subject "ticket-1"
                              :patch {:id "ticket-1" :customer "Sakura Tanaka"}})

    (exec! actor "t1-careplan" {:op :careplan/verify :subject "ticket-1"})
    (approve! "t1-careplan")

    (exec! actor "t1-fibre" {:op :fibre/screen :subject "ticket-1"})
    (approve! "t1-fibre")

    (exec! actor "t1-clean" {:op :actuation/apply-cleaning-process :subject "ticket-1"})
    (approve! "t1-clean")

    (exec! actor "t1-return" {:op :actuation/return-carpet :subject "ticket-1"})
    (approve! "t1-return")

    ;; --- HARD holds, one per governor rule ---
    (exec! actor "h-no-spec-basis" {:op :careplan/verify :subject "ticket-2"})
    (exec! actor "h-fibre-forbidden" {:op :actuation/apply-cleaning-process :subject "ticket-3"})
    (exec! actor "h-colourfastness" {:op :fibre/screen :subject "ticket-4"})
    (exec! actor "h-moisture" {:op :actuation/apply-cleaning-process :subject "ticket-5"})
    (exec! actor "h-evidence" {:op :actuation/return-carpet :subject "ticket-3"})
    (exec! actor "h-op-not-allowed" {:op :actuation/appraise-value :subject "ticket-1"})
    (exec! actor "h-already-cleaned" {:op :actuation/apply-cleaning-process :subject "ticket-1"})
    (exec! actor "h-already-returned" {:op :actuation/return-carpet :subject "ticket-1"})
    (exec! rogue "h-scope-excluded" {:op :careplan/verify :subject "ticket-1"})

    ;; --- a human declining a governor-clean proposal ---
    (exec! actor "r-rejected" {:op :careplan/verify :subject "ticket-3"})
    (reject! "r-rejected")

    {:db db
     :audit (vec (mapcat #(get @audits %) @order))}))

;; ----------------------------- derived readings -----------------------------

(defn hard-holds
  "The un-overridable governor holds in this ledger. A `:governor-hold`
  with an empty basis would be a phase gate, not a rule violation, and an
  `:approval-rejected` is a human decision -- neither is counted."
  [ledger]
  (vec (filter #(and (= :governor-hold (:t %)) (seq (:basis %))) ledger)))

(defn- approver-in
  "Any approver recorded on a committed artefact, or nil.

  Derived rather than hard-coded to `:approved-by`: ANY key whose name
  contains \"approv\" counts, keyword or string. So if the store is later
  fixed to persist `:approver`, `\"approved_by\"` or `:approved-by`, this
  page reports the name instead of the gap, with no edit here."
  [record]
  (when (map? record)
    (some (fn [k]
            (when (str/includes? (str/lower-case (if (keyword? k) (name k) (str k)))
                                 "approv")
              (some-> (get record k) str)))
          (sort-by str (keys record)))))

(defn- ssot-record-for
  "The SSoT artefact a committed op actually wrote, read back through the
  `Store` protocol -- not the record the graph handed to the store."
  [db op subject]
  (case op
    :ticket/intake (store/ticket db subject)
    :careplan/verify (store/careplan-of db subject)
    :fibre/screen (store/fibre-screening-of db subject)
    :actuation/apply-cleaning-process
    (first (filter #(= subject (get % "ticket_id")) (store/cleaning-history db)))
    :actuation/return-carpet
    (first (filter #(= subject (get % "ticket_id")) (store/return-history db)))
    nil))

(defn approval-trail
  "One reading per committed ledger fact: was a human asked, did they
  approve, and did that approver survive into the SSoT and the ledger?

  `:route` distinguishes an auto-commit (nobody was asked, so nobody is
  missing) from an approved commit (somebody was asked and named), which
  is what makes a dropped approver legible as a defect rather than as an
  absence of approval."
  [{:keys [db audit]}]
  (let [ledger (vec (store/ledger db))
        granted (filter #(= :approval-granted (:t %)) audit)
        requested (filter #(= :approval-requested (:t %)) audit)
        by-key (fn [coll] (into {} (map (juxt (juxt :op :subject) identity) coll)))
        granted-idx (by-key granted)
        requested-idx (by-key requested)]
    (vec
     (for [{:keys [op subject] :as f} (filter #(= :committed (:t %)) ledger)]
       (let [k [op subject]
             g-fact (get granted-idx k)
             asked? (contains? requested-idx k)
             record (ssot-record-for db op subject)]
         {:op op
          :subject subject
          :route (cond g-fact :approved
                       asked? :requested-not-granted
                       :else :auto-commit)
          :approver-in-run (:by g-fact)
          :approver-in-ssot (approver-in record)
          :approver-in-ledger (approver-in f)
          :record-shape (when (map? record)
                          (str/join ", " (sort (map #(if (keyword? %) (str %) (str "\"" % "\""))
                                                    (keys record)))))})))))

(defn- approver-gaps
  "Approved commits whose approver reached neither the SSoT record nor
  the ledger fact. Derived every run: this is empty the day the store
  keeps the approver, and the page's disclosure disappears with it."
  [trail]
  (vec (filter #(and (= :approved (:route %))
                     (nil? (:approver-in-ssot %))
                     (nil? (:approver-in-ledger %)))
               trail)))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- span [cls content] (str "<span class=\"" cls "\">" content "</span>"))
(defn- code [v] (str "<code>" (esc v) "</code>"))
(defn- row [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))
(defn- rows [xs] (str/join "\n" xs))

(defn- fmt-rate
  "Locale-independent fixed-point rendering -- `clojure.core/format` would
  emit a comma decimal separator under a European default locale, which
  would make this page's bytes depend on the machine that built it."
  [x]
  (when (number? x)
    (String/format java.util.Locale/ROOT "%.4f" (into-array Object [(double x)]))))

(defn- table [headers body-rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" body-rows "\n      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- sections -----------------------------

(defn- last-fact-for [ledger id]
  (last (filter #(= id (:subject %)) ledger)))

(defn- status-cell [ledger id]
  (let [f (last-fact-for ledger id)]
    (case (:t f)
      :committed (span "ok" "committed")
      :approval-rejected (span "warn" "held &middot; approver declined")
      :governor-hold (span "critical"
                           (str "HARD hold &middot; "
                                (esc (str/join ", " (map name (:basis f))))))
      :approval-requested (span "warn" "awaiting approval")
      (span "muted" "no activity"))))

(defn- lifecycle-cell [{:keys [cleaning-applied? carpet-returned?]}]
  (cond carpet-returned? (span "ok" "cleaned &amp; returned")
        cleaning-applied? (span "warn" "cleaned, not yet returned")
        :else (span "muted" "in intake")))

(defn- tickets-section [db ledger]
  (section
   "Care tickets (seeded bailment ledger)"
   (str "Every row is read back out of <code>carpetcare.store</code> after the run &mdash; "
        "the identifying fields are this repo's own seed data, the lifecycle and status "
        "columns are what the real governor and store did with them.")
   (table ["Ticket" "Customer" "Carpet" "Fibre" "Proposed process" "Jurisdiction"
           "Lifecycle" "Last decision"]
          (rows (for [{:keys [id customer carpet fibre proposed-cleaning-process
                              jurisdiction] :as t} (store/all-tickets db)]
                  (row (code id) (esc customer) (esc carpet) (code fibre)
                       (code proposed-cleaning-process)
                       (str (code jurisdiction) " "
                            (if (facts/covered? jurisdiction)
                              (span "ok" "basis on file")
                              (span "critical" "no basis on file")))
                       (lifecycle-cell t)
                       (status-cell ledger id)))))))

(defn- fibre-table-section []
  (section
   "Fibre &times; process incompatibility"
   (str "Rendered from <code>carpetcare.registry/fibre-forbidden-processes</code>, not "
        "transcribed. Set membership, so there is no threshold to lower: wool felts and "
        "yellows irreversibly in alkali, silk loses its hand and its dye to oxygen bleach, "
        "natural dyes bleed, and a jute secondary backing browns when over-wetted.")
   (table ["Recorded fibre" "Forbidden processes"]
          (rows (for [[fibre banned] (sort-by (comp name key) registry/fibre-forbidden-processes)]
                  (row (code fibre)
                       (if (seq banned)
                         (str/join " " (map code (sort-by name banned)))
                         (span "muted" "none"))))))))

(defn- fibre-recompute-section [db]
  (section
   "Independent recompute &mdash; fibre gate"
   (str "The governor never asks the advisor whether a process is safe. It recomputes the "
        "answer from two permanent ground-truth fields already on the ticket "
        "(<code>:fibre</code>, <code>:proposed-cleaning-process</code>) via "
        "<code>registry/cleaning-process-forbidden-by-fibre?</code>, which is re-run here "
        "per ticket at render time.")
   (table ["Ticket" "Fibre" "Proposed process" "Forbidden set for this fibre" "Verdict"]
          (rows (for [{:keys [id fibre proposed-cleaning-process] :as t} (store/all-tickets db)]
                  (let [banned (sort-by name (get registry/fibre-forbidden-processes fibre #{}))]
                    (row (code id) (code fibre) (code proposed-cleaning-process)
                         (if (seq banned)
                           (str/join " " (map code banned))
                           (span "muted" "none"))
                         (if (registry/cleaning-process-forbidden-by-fibre? t)
                           (span "critical" "FORBIDDEN &middot; hard hold")
                           (span "ok" "compatible")))))))))

(defn- moisture-section [db]
  (section
   "Independent recompute &mdash; residual-moisture identity"
   (str "<code>(wet &minus; dry) / dry</code> recomputed from the ticket's own weights and "
        "compared with the rate it claims. An identity, not an estimate &mdash; the 0.005 "
        "tolerance is on the float comparison, not on the rule. Over-wetting is what causes "
        "mould and cellulosic browning, so this is the number a dispute turns on.")
   (table ["Ticket" "Dry (g)" "Wet (g)" "Claimed rate" "Recomputed rate" "Verdict"]
          (rows (for [{:keys [id dry-weight-g wet-weight-g claimed-residual-moisture]
                       :as t} (store/all-tickets db)]
                  (row (code id)
                       (str "<span class=\"num\">" (esc dry-weight-g) "</span>")
                       (str "<span class=\"num\">" (esc wet-weight-g) "</span>")
                       (str "<span class=\"num\">" (esc (fmt-rate claimed-residual-moisture)) "</span>")
                       (str "<span class=\"num\">"
                            (esc (or (fmt-rate (registry/residual-moisture-rate t)) "n/a"))
                            "</span>")
                       (if (registry/moisture-claim-mismatch? t)
                         (span "critical" "MISMATCH &middot; hard hold")
                         (span "ok" "agrees"))))))))

(defn- spec-basis-section [db]
  (let [seeded (sort (distinct (map :jurisdiction (store/all-tickets db))))]
    (section
     "Jurisdictional spec-basis"
     (str (esc (facts/coverage-summary))
          " Rendered from <code>carpetcare.facts/spec-basis-table</code>; the seeded-tickets "
          "column is recomputed with <code>facts/covered?</code>.")
     (str
      (table ["ISO3" "Jurisdiction" "Legal basis" "Consumer basis" "Provenance" "Required records"]
             (rows (for [[iso3 {:keys [name legal-basis consumer-basis provenance
                                       required-evidence]}]
                         (sort-by key facts/spec-basis-table)]
                     (row (code iso3) (esc name) (esc legal-basis) (esc consumer-basis)
                          (esc provenance)
                          (str/join "<br>" (map esc required-evidence))))))
      "    <h3>Jurisdictions appearing on seeded tickets</h3>\n"
      (table ["Jurisdiction" "Tickets" "Basis on file"]
             (rows (for [j seeded]
                     (row (code j)
                          (str/join " " (map (comp code :id)
                                             (filter #(= j (:jurisdiction %))
                                                     (store/all-tickets db))))
                          (if (facts/covered? j)
                            (span "ok" "yes")
                            (span "critical" "NO &mdash; every proposal touching it is held"))))))))))

(defn- action-gate-section []
  (section
   "Action gate (Carpet Care Governor &times; rollout phase)"
   (str "Derived from <code>carpetcare.phase/phases</code> and "
        "<code>carpetcare.governor/high-stakes</code> at render time, so this table cannot "
        "drift from the code. The two <code>:actuation/*</code> ops are members of "
        "<code>write-ops</code> but of no phase's <code>:auto</code> set &mdash; a permanent "
        "structural fact, not a rollout milestone still to come. A carpet left for cleaning "
        "is a bailment: the customer keeps ownership while the operator holds possession, "
        "and a rug destroyed by the wrong process cannot be un-destroyed.")
   (str
    (table ["Op" "May write in phase" "May auto-commit in phase" "High-stakes"]
           (rows (let [ps (sort (keys phase/phases))]
                   (for [o (sort-by str governor/allowed-ops)]
                     (let [writes (filter #(contains? (:writes (get phase/phases %)) o) ps)
                           autos (filter #(contains? (:auto (get phase/phases %)) o) ps)]
                       (row (code o)
                            (if (seq writes)
                              (esc (str/join ", " writes))
                              (span "muted" "never"))
                            (if (seq autos)
                              (span "ok" (esc (str/join ", " autos)))
                              (span "warn" "NEVER &middot; always human-approved"))
                            (if (contains? governor/high-stakes o)
                              (span "critical" "yes")
                              (span "muted" "no"))))))))
    "    <h3>Rollout phases</h3>\n"
    (table ["Phase" "Label" "Auto-eligible ops"]
           (rows (for [[p {:keys [label auto]}] (sort-by key phase/phases)]
                   (row (str "<span class=\"num\">" (esc p) "</span>")
                        (esc label)
                        (if (seq auto)
                          (str/join " " (map code (sort-by str auto)))
                          (span "muted" "none"))))))
    "    <p class=\"muted\">Every op any phase may auto-commit, unioned across all phases: "
    (let [ae (phase/auto-eligible-ops)]
      (if (seq ae) (str/join " " (map code (sort-by str ae))) (span "muted" "none")))
    ". Ops absent from this line never auto-commit at any phase.</p>\n")))

(defn- hard-holds-section [ledger]
  (let [holds (hard-holds ledger)]
    (section
     (str "HARD holds this run (" (count holds) ")")
     (str "Un-overridable. Each row is one violation from a real "
          "<code>carpetcare.governor/check</code> verdict, with the governor's own "
          "<code>:detail</code> text. None of these reached a human &mdash; a HARD hold is "
          "not an escalation.")
     (table ["Op" "Ticket" "Rule" "Governor detail"]
            (rows (for [{:keys [op subject violations]} holds
                        {:keys [rule detail]} violations]
                    (row (code op) (code subject)
                         (span "critical" (esc (name rule)))
                         (esc detail))))))))

(defn- approval-section [trail]
  (let [gaps (approver-gaps trail)
        approved (filter #(= :approved (:route %)) trail)]
    (section
     "Human approval trail &mdash; and where the approver survives"
     (str "One row per committed fact. <em>Route</em> separates an auto-commit (nobody was "
          "asked, so nobody is missing) from an approved commit (a human was named). The "
          "last two columns are probed at render time by scanning the artefact the store "
          "actually holds for any key naming an approver, so a fix in the store is "
          "reported here automatically.")
     (str
      (table ["Op" "Ticket" "Route" "Approver (run audit)"
              "Approver retained in SSoT record" "Approver retained in ledger fact"]
             (rows (for [{:keys [op subject route approver-in-run approver-in-ssot
                                 approver-in-ledger record-shape]} trail]
                     (row (code op) (code subject)
                          (case route
                            :approved (span "warn" "human approval required &rarr; granted")
                            :requested-not-granted (span "warn" "approval requested")
                            (span "muted" "auto-commit &middot; no approval required"))
                          (if approver-in-run
                            (span "ok" (esc approver-in-run))
                            (span "muted" "&mdash; nobody was asked"))
                          (cond
                            approver-in-ssot (span "ok" (esc approver-in-ssot))
                            (= :approved route)
                            (str (span "critical" "NOT RETAINED")
                                 " <span class=\"muted\">record holds only "
                                 (esc (or record-shape "no record")) "</span>")
                            :else (span "muted" "&mdash; no approver to retain"))
                          (cond
                            approver-in-ledger (span "ok" (esc approver-in-ledger))
                            (= :approved route) (span "critical" "NOT RETAINED")
                            :else (span "muted" "&mdash;")))))
             )
      (if (seq gaps)
        (str "    <p class=\"critical\">Measured this run: "
             (count gaps) " of " (count approved)
             " approved commits keep no approver anywhere in the SSoT &mdash; "
             (str/join ", " (map #(code (:op %)) gaps))
             ". A human was required, named and recorded in the run's audit channel, and "
             "that name is then dropped on the way to storage: "
             "<code>store/commit-record!</code>'s <code>:ticket/mark-cleaned</code> and "
             "<code>:ticket/mark-returned</code> branches read neither <code>:value</code> "
             "nor <code>:payload</code> &mdash; they rebuild the record from "
             "<code>carpetcare.registry</code> &mdash; and <code>operation/commit-fact</code> "
             "carries no approver field, while the <code>:approval-granted</code> fact never "
             "reaches <code>store/append-ledger!</code> at all. These are exactly the two "
             "acts the whole repo exists to gate. The care-plan and fibre-screening branches "
             "store <code>:payload</code> and do keep the name, which is why this row set "
             "shows both outcomes.</p>\n")
        (str "    <p class=\"ok\">Measured this run: every approved commit retains its "
             "approver in the SSoT. </p>\n"))))))

(defn- ledger-section [ledger]
  (section
   (str "Audit ledger, append-only (" (count ledger) " facts)")
   (str "Every decision fact this scenario wrote through "
        "<code>store/append-ledger!</code>, in order. Which rug was screened, which process "
        "was applied, which rug was handed back, on what jurisdictional basis &mdash; always "
        "a query over an immutable log, which is the evidence both sides need when a rug "
        "comes back yellowed or bled.")
   (table ["#" "Fact" "Op" "Ticket" "Basis"]
          (rows (map-indexed
                 (fn [i {:keys [t op subject basis disposition]}]
                   (row (str "<span class=\"num\">" (inc i) "</span>")
                        (case t
                          :committed (span "ok" (esc (name t)))
                          :governor-hold (span "critical" (esc (name t)))
                          :approval-rejected (span "warn" (esc (name t)))
                          (span "muted" (esc (name t))))
                        (code op) (code subject)
                        (if (seq basis)
                          (esc (str/join " · " (map #(if (keyword? %) (name %) (str %)) basis)))
                          (span "muted" (esc (or (some-> disposition clojure.core/name) ""))))))
                 ledger)))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the operator console from a completed `run-demo!` result."
  [{:keys [db] :as run}]
  (let [ledger (vec (store/ledger db))
        holds (hard-holds ledger)
        trail (approval-trail run)
        committed (filter #(= :committed (:t %)) ledger)
        rules (sort (distinct (map name (mapcat :basis holds))))]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-9601-carpet &middot; carpet &amp; rug cleaning operator console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Carpet &amp; rug cleaning &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · cleaning &amp; return always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"banner\">\n"
     "    <p>Generated at build time by <code>carpetcare.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by running the real actor stack &mdash; "
     "<code>carpetcare.operation</code> (a langgraph StateGraph) &rarr; "
     "<code>carpetcare.governor</code> &rarr; <code>carpetcare.store</code> &mdash; over this "
     "repo's own seeded tickets. No figure below is typed by hand.</p>\n"
     "    <p class=\"muted\">This run: <strong>" (count committed) "</strong> committed facts · "
     "<strong>" (count holds) "</strong> HARD governor holds covering "
     "<strong>" (count rules) "</strong> distinct rules ("
     (str/join ", " (map code rules)) ") · "
     "<strong>" (count (filter #(= :approval-rejected (:t %)) ledger)) "</strong> approver "
     "rejection · <strong>" (count (store/cleaning-history db)) "</strong> cleaning "
     "application and <strong>" (count (store/return-history db)) "</strong> carpet return "
     "registered.</p>\n"
     "    <p class=\"muted\">ISIC attribution is <em>declared, not claimed</em>: this "
     "workspace's ISIC mirror enumerates carpet cleaning in no class, so this actor states "
     "an operating model (off-premises intake &mdash; the rug is collected, cleaned and "
     "returned) and derives 9601 from that. <code>:actuation/declare-isic-class</code> is "
     "deliberately absent from the vocabulary, so this actor cannot settle the question on "
     "a statistical authority's behalf.</p>\n"
     "  </section>\n"
     (tickets-section db ledger)
     (fibre-table-section)
     (fibre-recompute-section db)
     (moisture-section db)
     (spec-basis-section db)
     (action-gate-section)
     (hard-holds-section ledger)
     (approval-section trail)
     (ledger-section ledger)
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-9601-carpet · AGPL-3.0-or-later · regenerate with "
     "<code>clojure -M:dev:render-html</code>. Deterministic: no timestamps, no randomness, "
     "every map iteration explicitly sorted &mdash; two runs against the same seed are "
     "byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        run (run-demo!)
        db (:db run)
        ledger (vec (store/ledger db))
        holds (hard-holds ledger)
        html (render run)]
    ;; The HARD-hold requirement is a build-time invariant, not a
    ;; convention: a scenario that stopped reaching the governor's
    ;; un-overridable branch would render a page that looks fine and
    ;; proves nothing, so refuse to write it.
    (when (zero? (count holds))
      (throw (ex-info "refusing to write an operator console with 0 HARD governor holds"
                      {:ledger-facts (count ledger)
                       :committed (count (filter #(= :committed (:t %)) ledger))})))
    (spit out html)
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count holds) " HARD governor holds, "
                  (count (approver-gaps (approval-trail run))) " approved commits with a "
                  "dropped approver, "
                  (count (store/cleaning-history db)) " cleaning applications, "
                  (count (store/return-history db)) " carpet returns)"))))
