(ns carpetcare.registry
  "Pure record drafting + the ground-truth recomputation the Carpet Care
  Governor relies on. Nothing here reads a proposal."
  (:require [clojure.string :as str]))

;; ----------------------------- fibre / process compatibility -----------------------------

(def fibre-forbidden-processes
  "Recorded fibre or dye characteristic -> cleaning processes that must
  never be applied to it.

  A table of **material incompatibility**, not a policy knob:

    - wool felts and yellows irreversibly in alkali, and shrinks in
      high-temperature extraction
    - silk loses its hand and its dye to oxygen bleach
    - natural dyes bleed; there is no bleach setting that is gentle
      enough
    - a jute secondary backing browns when over-wetted (cellulosic
      browning migrating to the face yarn)

  Set membership, so there is no threshold to lower."
  {:wool          #{:hot-water-extraction-high-temp :alkaline-detergent}
   :silk          #{:hot-water-extraction-high-temp :oxygen-bleach :alkaline-detergent}
   :natural-dye   #{:oxygen-bleach :alkaline-detergent}
   :jute-backing  #{:hot-water-extraction-high-temp}
   :synthetic     #{}})

(defn cleaning-process-forbidden-by-fibre?
  "Independently recompute, from the two permanent ground-truth fields
  already on the ticket, whether the carpet's own proposed process is
  forbidden by its own recorded fibre. Needs no proposal at all."
  [{:keys [proposed-cleaning-process fibre]}]
  (boolean (and proposed-cleaning-process fibre
                (contains? (get fibre-forbidden-processes fibre #{})
                           proposed-cleaning-process))))

;; ----------------------------- residual-moisture identity -----------------------------

(defn- abs* [x] (if (neg? x) (- x) x))

(defn residual-moisture-rate
  "(wet weight - dry weight) / dry weight, or nil when either figure is
  missing. An identity, not an estimate. Over-wetting is what causes
  mould and cellulosic browning, so this is the number a dispute turns
  on."
  [{:keys [dry-weight-g wet-weight-g]}]
  (when (and (number? dry-weight-g) (number? wet-weight-g) (pos? dry-weight-g))
    (/ (double (- wet-weight-g dry-weight-g)) (double dry-weight-g))))

(defn moisture-claim-mismatch?
  "Does the ticket's own claimed residual-moisture rate disagree with the
  rate recomputed from its own weights? The 0.005 tolerance is on the
  float comparison, **not on the rule**."
  [{:keys [claimed-residual-moisture] :as ticket}]
  (when-let [actual (residual-moisture-rate ticket)]
    (and (number? claimed-residual-moisture)
         (> (abs* (- (double claimed-residual-moisture) actual)) 0.005))))

;; ----------------------------- record drafting -----------------------------

(defn- seq->number [prefix jurisdiction seq-n]
  (str prefix "-" (str/upper-case (or jurisdiction "XXX")) "-"
       (str/join (repeat (max 0 (- 4 (count (str (inc seq-n))))) "0"))
       (inc seq-n)))

(defn register-cleaning-application [ticket-id jurisdiction seq-n]
  {"cleaning_number" (seq->number "CLN" jurisdiction seq-n)
   "ticket_id" ticket-id "jurisdiction" jurisdiction})

(defn register-carpet-return [ticket-id jurisdiction seq-n]
  {"return_number" (seq->number "RET" jurisdiction seq-n)
   "ticket_id" ticket-id "jurisdiction" jurisdiction})

(defn append [coll record] (conj (vec coll) record))
