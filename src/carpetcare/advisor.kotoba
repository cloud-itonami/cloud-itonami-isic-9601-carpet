(ns carpetcare.advisor
  "The **contained intelligence**: a CarpetCareAdvisor that drafts proposals
  and nothing else. Every proposal it produces is censored by
  `carpetcare.governor` and gated by `carpetcare.phase` before anything
  reaches the SSoT.

  **The advisor reports missing basis honestly.** When a jurisdiction has
  no spec-basis on file it emits empty `:cites` and does NOT raise its
  confidence -- fabricating a textile-care requirement would make the
  governor's spec-basis gate unreachable, which is worse than a hold.

  **The advisor never proposes a liability or valuation decision**,
  because there is no op for one. If a real LLM advisor describes one in
  prose, the governor's scope-exclusion check catches it on the way
  through."
  (:require [carpetcare.facts :as facts]
            [carpetcare.store :as store]))

(defprotocol Advisor
  (-advise [a store request] "Draft a proposal for this request."))

(defn trace
  [request proposal]
  {:t          :advisor-proposed
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})

;; ----------------------------- proposal generators -----------------------------

(defn- propose-intake
  "`:patch` arrives at the TOP level of the request (the OS shim merges
  the envelope's payload into the request map)."
  [_st {:keys [subject patch confidence]}]
  {:op :ticket/intake
   :summary (str subject " の預りカーペット受付票を起票")
   :rationale "受付の記録行為。現物に触れる行為は含まない。"
   :cites (vec (keys patch))
   :effect :ticket/upsert
   :value (merge {:id subject} patch)
   :confidence (or confidence 0.9)})

(defn- propose-careplan [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        iso3 (:jurisdiction t)
        sb (facts/spec-basis iso3)]
    (if-not sb
      {:op :careplan/verify
       :summary (str iso3 " の公式spec-basis(繊維表示/消費者保護)が見つかりません")
       :rationale (str iso3 " は台帳に無い法域。繊維ケアの要件を推測で作らない。")
       :cites []
       :effect :careplan/set
       :value {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :confidence 0.2}
      {:op :careplan/verify
       :summary (str subject " の洗浄ケア計画を " iso3 " 基準で検証")
       :rationale (str (:legal-basis sb) " および " (:consumer-basis sb)
                       " に基づく必要書類の充足確認。")
       :cites [(:legal-basis sb) (:consumer-basis sb) (:provenance sb)]
       :effect :careplan/set
       :value {:jurisdiction iso3
               :checklist (facts/required-evidence iso3)
               :spec-basis (:provenance sb)}
       :confidence 0.88})))

(defn- propose-fibre-screen [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        lapsed? (true? (:colourfastness-not-confirmed? t))]
    {:op :fibre/screen
     :summary (str subject " の繊維鑑別と色堅牢度を確認"
                   (if lapsed? " -- 色堅牢度が未確認" " -- 有効"))
     :rationale "繊維と色堅牢度は台帳の事実から読む。提案者の自己申告では判定しない。"
     :cites [:colourfastness-test]
     :effect :fibre-screening/set
     :value {:ticket-id subject :colourfastness-not-confirmed? lapsed?}
     :confidence 0.9}))

(defn- propose-cleaning [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        sb (facts/spec-basis (:jurisdiction t))]
    {:op :actuation/apply-cleaning-process
     :summary (str subject " に洗浄工程(" (:proposed-cleaning-process t) ")を適用")
     :rationale "受付・ケア計画・繊維確認を経た洗浄工程の適用。実施は人の承認を要する。"
     :cites (if sb [(:legal-basis sb) subject] [])
     :effect :ticket/mark-cleaned
     :value {:ticket-id subject :spec-basis (:provenance sb)}
     :confidence 0.85}))

(defn- propose-return [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        sb (facts/spec-basis (:jurisdiction t))]
    {:op :actuation/return-carpet
     :summary (str subject " のカーペットを顧客へ返却")
     :rationale "預りカーペットの返却。所有権は動かず占有だけが戻る行為で、人の承認を要する。"
     :cites (if sb [(:legal-basis sb) subject] [])
     :effect :ticket/mark-returned
     :value {:ticket-id subject :spec-basis (:provenance sb)}
     :confidence 0.85}))

(defn infer
  [st {:keys [op] :as request}]
  (case op
    :ticket/intake                     (propose-intake st request)
    :careplan/verify                   (propose-careplan st request)
    :fibre/screen                (propose-fibre-screen st request)
    :actuation/apply-cleaning-process  (propose-cleaning st request)
    :actuation/return-carpet           (propose-return st request)
    {:op op :summary "未対応の操作" :rationale (str op)
     :cites [] :effect :noop :value {} :confidence 0.0}))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ st request] (infer st request))))
