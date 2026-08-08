(ns carpetcare.governor-contract-test
  "Every HARD check must actually fire, and each must fire for its own
  reason. A gate that cannot be shown to refuse is theatre."
  (:require [clojure.test :refer [deftest is testing]]
            [carpetcare.governor :as governor]
            [carpetcare.registry :as registry]
            [carpetcare.facts :as facts]
            [carpetcare.store :as store]))

(def ctx {:actor-id "op-1" :actor-role :cleaning-supervisor :phase 3})

(defn- db [] (store/seed-db))

(defn- clean-proposal [op subject]
  {:op op :summary "ok" :rationale "ok"
   :cites ["クリーニング業法 第3条（営業者の届出・遵守事項）"]
   :effect :noop :value {:ticket-id subject} :confidence 0.9})

(defn- rules-of [verdict] (set (map :rule (:violations verdict))))

;; ----------------------------- the closed vocabulary -----------------------------

(deftest an-op-outside-the-allowlist-is-hard-held
  (let [v (governor/check {:op :actuation/appraise-value :subject "ticket-1"}
                          ctx (clean-proposal :actuation/appraise-value "ticket-1") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :op-not-allowed))))

(deftest the-vocabulary-has-no-valuation-or-liability-or-classification-op
  (doseq [absent [:actuation/appraise-value :actuation/settle-damage-claim
                  :actuation/declare-isic-class]]
    (is (not (contains? governor/allowed-ops absent))
        "these are absent from the vocabulary, not merely gated")))

;; ----------------------------- spec basis -----------------------------

(deftest a-proposal-with-no-cites-is-hard-held
  (let [p (assoc (clean-proposal :careplan/verify "ticket-2") :cites [])
        v (governor/check {:op :careplan/verify :subject "ticket-2"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :no-spec-basis))))

(deftest an-unseeded-jurisdiction-can-never-satisfy-its-evidence
  (is (nil? (facts/required-evidence-satisfied? "ATL" ["anything"]))
      "a missing spec-basis is not 'no requirements'"))

;; ----------------------------- ground-truth recomputations -----------------------------

(deftest a-forbidden-process-is-recomputed-from-the-ticket-not-the-proposal
  ;; ticket-3 is wool + alkaline detergent. The proposal is clean and
  ;; well-cited; the hold comes only from the ticket's own two fields.
  (let [v (governor/check {:op :actuation/apply-cleaning-process :subject "ticket-3"}
                          ctx (clean-proposal :actuation/apply-cleaning-process "ticket-3") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :cleaning-process-forbidden-by-fibre)))
  (testing "and it is set membership, so there is no threshold to lower"
    (is (registry/cleaning-process-forbidden-by-fibre?
         {:fibre :wool :proposed-cleaning-process :alkaline-detergent}))
    (is (not (registry/cleaning-process-forbidden-by-fibre?
              {:fibre :synthetic :proposed-cleaning-process :alkaline-detergent})))))

(deftest a-moisture-claim-that-disagrees-with-its-own-weights-is-hard-held
  (let [v (governor/check {:op :actuation/apply-cleaning-process :subject "ticket-5"}
                          ctx (clean-proposal :actuation/apply-cleaning-process "ticket-5") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :moisture-claim-mismatch)))
  (testing "it is an identity, computed from the ticket alone"
    (is (registry/moisture-claim-mismatch?
         {:dry-weight-g 4000 :wet-weight-g 5200 :claimed-residual-moisture 0.05}))
    (is (not (registry/moisture-claim-mismatch?
              {:dry-weight-g 4000 :wet-weight-g 4200 :claimed-residual-moisture 0.05})))))

;; ----------------------------- colourfastness -----------------------------

(deftest an-unconfirmed-colourfastness-holds-from-the-screening-ops-own-finding
  (let [p (assoc (clean-proposal :fibre/screen "ticket-4")
                 :value {:ticket-id "ticket-4" :colourfastness-not-confirmed? true})
        v (governor/check {:op :fibre/screen :subject "ticket-4"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :colourfastness-not-confirmed))))

(deftest an-unconfirmed-colourfastness-on-file-holds-an-actuation
  (let [st (db)]
    (store/commit-record! st {:effect :fibre-screening/set :path ["ticket-1"]
                              :payload {:ticket-id "ticket-1"
                                        :colourfastness-not-confirmed? true}})
    (let [v (governor/check {:op :actuation/apply-cleaning-process :subject "ticket-1"}
                            ctx (clean-proposal :actuation/apply-cleaning-process "ticket-1") st)]
      (is (:hard? v))
      (is (contains? (rules-of v) :colourfastness-not-confirmed)))))

;; ----------------------------- evidence -----------------------------

(deftest an-actuation-without-a-verified-careplan-is-hard-held
  (let [v (governor/check {:op :actuation/return-carpet :subject "ticket-1"}
                          ctx (clean-proposal :actuation/return-carpet "ticket-1") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :evidence-incomplete))))

(deftest a-complete-careplan-clears-the-evidence-check
  (let [st (db)]
    (store/commit-record! st {:effect :careplan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (let [v (governor/check {:op :actuation/return-carpet :subject "ticket-1"}
                            ctx (clean-proposal :actuation/return-carpet "ticket-1") st)]
      (is (not (contains? (rules-of v) :evidence-incomplete))))))

;; ----------------------------- double actuation -----------------------------

(deftest the-same-carpet-cannot-be-cleaned-or-returned-twice
  (let [st (db)]
    (store/commit-record! st {:effect :careplan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (store/commit-record! st {:effect :ticket/mark-cleaned :path ["ticket-1"]})
    (store/commit-record! st {:effect :ticket/mark-returned :path ["ticket-1"]})
    (is (contains? (rules-of (governor/check
                              {:op :actuation/apply-cleaning-process :subject "ticket-1"}
                              ctx (clean-proposal :actuation/apply-cleaning-process "ticket-1") st))
                   :already-cleaned))
    (is (contains? (rules-of (governor/check
                              {:op :actuation/return-carpet :subject "ticket-1"}
                              ctx (clean-proposal :actuation/return-carpet "ticket-1") st))
                   :already-returned))))

;; ----------------------------- scope exclusion -----------------------------

(deftest prose-reaching-for-an-excluded-decision-is-hard-held
  (let [p (assoc (clean-proposal :ticket/intake "ticket-1")
                 :rationale "appraised value 800,000 JPY")
        v (governor/check {:op :ticket/intake :subject "ticket-1"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :scope-excluded))))

;; ----------------------------- high stakes -----------------------------

(deftest high-stakes-is-decided-on-the-op-not-the-advisors-self-report
  (let [st (db)]
    (store/commit-record! st {:effect :careplan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (testing "an actuation escalates even when the advisor declares no stake"
      (let [p (dissoc (clean-proposal :actuation/return-carpet "ticket-1") :stake)
            v (governor/check {:op :actuation/return-carpet :subject "ticket-1"} ctx p st)]
        (is (:high-stakes? v))
        (is (:escalate? v))
        (is (not (:ok? v)))))
    (testing "and intake does not escalate on stakes"
      (let [v (governor/check {:op :ticket/intake :subject "ticket-1"} ctx
                              (clean-proposal :ticket/intake "ticket-1") st)]
        (is (not (:high-stakes? v)))
        (is (:ok? v))))))

(deftest low-confidence-escalates-without-being-hard
  (let [st (db)
        p (assoc (clean-proposal :ticket/intake "ticket-1") :confidence 0.1)
        v (governor/check {:op :ticket/intake :subject "ticket-1"} ctx p st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
