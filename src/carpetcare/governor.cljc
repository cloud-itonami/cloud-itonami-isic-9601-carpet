(ns carpetcare.governor
  "The **Carpet Care Governor** -- an independent censor, a different
  system than the advisor it censors. It never asks the advisor whether a
  proposal is safe; it recomputes what it can from the ticket the
  operator already recorded, and holds when it cannot.

  ## What is absent from the vocabulary, not merely gated

  `allowed-ops` is the whole vocabulary -- five ops. **No op settles a
  damage-liability decision, appraises the rug's value, or declares its
  own ISIC classification authoritative.**

  The third one is unusual and deliberate: this workspace's ISIC spec
  mirror (`cloud-itonami/org-un-isic`) **does not enumerate carpet
  cleaning in any class** (measured 2026-08-08). This actor therefore
  declares its own operating model -- an off-premises intake, i.e. the
  rug is collected, cleaned and returned, which is why 9601's
  description of \"washing, cleaning, dyeing, and pressing of textile
  products\" is the reading it operates under -- and claims nothing
  beyond that. See `docs/adr/0001-architecture.md` §1.

  ## The checks, and why none can be talked out of

    1. Spec basis missing       -- a jurisdiction with no consumer/textile
                                   basis on file cannot be operated in. HARD.
    2. Evidence incomplete      -- for either actuation, the jurisdiction's
                                   required records (customer consent, intake
                                   condition, fibre identification, process
                                   record) must actually be present.
    3. Colourfastness not       -- reported by this proposal OR already on
       confirmed                  file. HARD, un-overridable. A rug whose dye
                                   has not been tested must not meet a wet
                                   process.
    4. Process forbidden by     -- recomputed from the carpet's own recorded
       fibre                      fibre and its own proposed process. **Set
                                   membership -- no threshold to lower.** Wool
                                   felts in alkali; silk loses its dye to
                                   oxygen bleach; a jute backing browns when
                                   over-wetted.
    5. Moisture claim mismatch  -- the ticket's claimed residual-moisture rate
                                   vs the rate recomputed from its own dry/wet
                                   weights. An identity, not an estimate --
                                   over-wetting is what causes mould and
                                   cellulosic browning.
    6/7. Already cleaned /      -- double-actuation guards off dedicated
       already returned           booleans, never a `:status` value.
    8. Scope excluded           -- the advisor's own prose reaching for one of
                                   the three absent decisions.

  ## high-stakes is a set of OPS, not of advisor-reported stakes

  Following `cloud-itonami-isic-9601` -- which is also this repo's parent
  class -- rather than 9522/9523. A permanent invariant must not depend
  on the censored party's own `:stake` report: if the advisor omits or
  mislabels it, an op-name set still holds and a `:stake` set does not.
  (superproject ADR-2800004000 records that this fleet carries both.)"
  (:require [kotoba.lang.text :as str]
            [carpetcare.facts :as facts]
            [carpetcare.registry :as registry]
            [carpetcare.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction."
  #{:ticket/intake :careplan/verify :fibre/screen
    :actuation/apply-cleaning-process :actuation/return-carpet})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Applying a real cleaning process to a real rug and handing a real rug
  back are the two real-world acts this actor performs."
  #{:actuation/apply-cleaning-process :actuation/return-carpet})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as reaching for a
  permanently out-of-scope decision. Scanned against the advisor's own
  prose so an otherwise-legitimate op cannot smuggle one in."
  ["damage claim approved" "damage liability accepted" "損害賠償を認め"
   "appraised value" "鑑定価格" "査定額"
   "classified under isic" "isic 分類を確定"])

;; ----------------------------- checks -----------------------------

(defn- op-not-allowed-violations
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこの actor の語彙に存在しない")}]))

(defn- spec-basis-violations
  [{:keys [op]} proposal]
  (when (contains? #{:careplan/verify
                     :actuation/apply-cleaning-process
                     :actuation/return-carpet} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basis(繊維表示/消費者保護)の引用が無い提案は運営基準として扱えない"}]))))

(defn- evidence-incomplete-violations
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/apply-cleaning-process :actuation/return-carpet} op)
    (let [t (store/ticket st subject)
          plan (store/careplan-of st subject)]
      (when-not (and plan
                     (facts/required-evidence-satisfied?
                      (:jurisdiction t) (:checklist plan)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(顧客同意記録/受取時状態記録/繊維鑑別記録/洗浄工程記録)が充足していない"}]))))

(defn- colourfastness-not-confirmed-violations
  "An unconfirmed colourfastness result -- reported by THIS proposal (e.g.
  a `:fibre/screen` that just found the dye bleeds) or already on file --
  is a HARD, un-overridable hold. Evaluated UNCONDITIONALLY so the
  screening op can hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (true? (get-in proposal [:value :colourfastness-not-confirmed?]))
        ticket-id (when (contains? #{:fibre/screen
                                     :actuation/apply-cleaning-process
                                     :actuation/return-carpet} op)
                    subject)
        hit-on-file? (and ticket-id
                          (true? (:colourfastness-not-confirmed?
                                  (store/fibre-screening-of st ticket-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :colourfastness-not-confirmed
        :detail "色堅牢度が未確認の状態で湿式工程に進む提案は進められない"}])))

(defn- process-forbidden-by-fibre-violations
  "For `:actuation/apply-cleaning-process`, INDEPENDENTLY recompute
  whether the carpet's own proposed process is forbidden by its own
  recorded fibre. Inputs are permanent ground-truth fields already on the
  ticket -- no proposal inspection at all."
  [{:keys [op subject]} st]
  (when (= op :actuation/apply-cleaning-process)
    (let [t (store/ticket st subject)]
      (when (registry/cleaning-process-forbidden-by-fibre? t)
        [{:rule :cleaning-process-forbidden-by-fibre
          :detail (str subject " の提案洗浄工程(" (:proposed-cleaning-process t)
                       ")が繊維" (:fibre t) "の禁止工程に含まれている")}]))))

(defn- moisture-claim-mismatch-violations
  "For `:actuation/apply-cleaning-process`, the ticket's claimed residual
  moisture must equal the rate recomputed from its own weights. An
  identity -- there is nothing to loosen."
  [{:keys [op subject]} st]
  (when (= op :actuation/apply-cleaning-process)
    (let [t (store/ticket st subject)]
      (when (registry/moisture-claim-mismatch? t)
        [{:rule :moisture-claim-mismatch
          :detail (str subject " の申告残留水分率(" (:claimed-residual-moisture t)
                       ")が乾湿重量から再計算した値と一致しない")}]))))

(defn- already-cleaned-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/apply-cleaning-process)
    (when (store/carpet-already-cleaned? st subject)
      [{:rule :already-cleaned :detail (str subject " は既に洗浄処理済み")}])))

(defn- already-returned-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/return-carpet)
    (when (store/carpet-already-returned? st subject)
      [{:rule :already-returned :detail (str subject " は既に返却済み")}])))

(defn- scope-exclusion-violations
  [proposal]
  (let [text (str (:summary proposal) " " (:rationale proposal))
        lower (str/lower text)]
    (when-let [hit (first (filter #(str/includes? lower (str/lower (str %)))
                                  scope-excluded-terms))]
      [{:rule :scope-excluded
        :detail (str "恒久的にスコープ外の判断に触れる文言を含む: " hit)}])))

(defn check
  "Censors a CarpetCareAdvisor proposal against the governor rules."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (colourfastness-not-confirmed-violations request proposal st)
                           (process-forbidden-by-fibre-violations request st)
                           (moisture-claim-mismatch-violations request st)
                           (already-cleaned-violations request st)
                           (already-returned-violations request st)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
