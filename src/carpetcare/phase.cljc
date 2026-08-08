(ns carpetcare.phase
  "Phase 0->3 staged rollout -- the carpet-care analog of
  `cloud-itonami-isic-9601`'s `laundry.phase`.

    Phase 0  read-only         -- no writes, still governor-gated.
    Phase 1  assisted-intake   -- cleaning-ticket intake allowed, every write
                                  needs human approval.
    Phase 2  assisted-verify   -- adds care-plan verification + fibre/
                                  colourfastness screening writes,
                                  still approval.
    Phase 3  supervised auto   -- governor-clean, high-confidence
                                  `:ticket/intake` (no capital risk yet)
                                  may auto-commit.
                                  `:actuation/apply-cleaning-process` and
                                  `:actuation/return-carpet` NEVER
                                  auto-commit, at any phase.

  Those two actuations are deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- **a permanent structural fact, not
  a rollout milestone still to come**. A carpet left for cleaning is a
  bailment: the customer keeps ownership while the operator holds
  possession, and a wool or naturally-dyed rug destroyed by the wrong
  process cannot be un-destroyed. `carpetcare.governor`'s high-stakes
  set asserts the same invariant independently -- two layers agree.

  `:fibre/screen` is likewise never auto-eligible, matching the posture
  every sibling's screening op has (9601 solvent handling, 9522
  refrigerant handling, 9523 brand authenticity)."
  (:require [clojure.set :as set]))

(def read-ops #{})

(def write-ops #{:ticket/intake :careplan/verify :fibre/screen
                 :actuation/apply-cleaning-process :actuation/return-carpet})

;; NOTE the invariant: the two `:actuation/*` ops are members of
;; `write-ops` (governor-gated like any write) but are NEVER members of
;; any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                :auto #{}}
   1 {:label "assisted-intake" :writes #{:ticket/intake}   :auto #{}}
   2 {:label "assisted-verify"
      :writes #{:ticket/intake :careplan/verify :fibre/screen}
      :auto   #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:ticket/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - the two actuations are never auto-eligible at any phase, so they
    always escalate once the governor clears them (or hold if it does not)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Carpet Care Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))

(defn auto-eligible-ops
  "Every op that any phase may auto-commit. Used by the tests to assert
  the permanent invariant in one place."
  []
  (reduce set/union #{} (map :auto (vals phases))))
