(ns carpetcare.store
  "SSoT for the carpet-care actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore` -- atom of EDN. The deterministic default for
                    dev/tests/demo (no deps).

  **`DatomicStore` is not implemented here yet.** The protocol and the
  contract test are here so adding one is a drop-in; claiming a Datomic
  backend before writing it would be exactly the kind of unverified
  assertion this fleet's ADRs forbid.

  Two actuation events (cleaning application, carpet return) act on the
  SAME entity (a care ticket), each with its OWN history collection,
  sequence counter and dedicated double-actuation guard boolean
  (`:cleaning-applied?` / `:carpet-returned?`, **never a `:status`
  value** -- a status is a view, a boolean is a fact).

  The ledger stays append-only: 'which rug was screened for colourfastness,
  which process was applied, which rug was handed back, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the evidence both sides need when a rug comes back
  yellowed or bled."
  (:require [carpetcare.registry :as registry]))

(defprotocol Store
  (ticket [s id])
  (all-tickets [s])
  (fibre-screening-of [s ticket-id] "committed fibre/colourfastness screening verdict, or nil")
  (careplan-of [s ticket-id] "committed care-plan verification, or nil")
  (ledger [s])
  (cleaning-history [s])
  (return-history [s])
  (next-cleaning-sequence [s jurisdiction])
  (next-return-sequence [s jurisdiction])
  (carpet-already-cleaned? [s ticket-id])
  (carpet-already-returned? [s ticket-id])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (with-tickets [s tickets]))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A self-contained cleaning-ticket set. Each ticket exists to make
  exactly one governor rule reachable:

    ticket-1  the clean path (synthetic, colourfastness confirmed,
              moisture claim matches its own weights)
    ticket-2  jurisdiction \"ATL\" -- no spec-basis on file
    ticket-3  wool + alkaline detergent -- felts and yellows irreversibly
    ticket-4  colourfastness not confirmed
    ticket-5  claimed residual moisture 0.05 vs (5200-4000)/4000 = 0.30"
  []
  {:tickets
   {"ticket-1" {:id "ticket-1" :customer "Sakura Tanaka"
                :carpet "Machine-made runner (routine clean)"
                :fibre :synthetic :proposed-cleaning-process :low-moisture-encapsulation
                :dry-weight-g 4000 :wet-weight-g 4200 :claimed-residual-moisture 0.05
                :colourfastness-not-confirmed? false
                :cleaning-applied? false :carpet-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-2" {:id "ticket-2" :customer "Atlantis Doe"
                :carpet "Area rug (routine clean)"
                :fibre :synthetic :proposed-cleaning-process :low-moisture-encapsulation
                :dry-weight-g 4000 :wet-weight-g 4200 :claimed-residual-moisture 0.05
                :colourfastness-not-confirmed? false
                :cleaning-applied? false :carpet-returned? false
                :jurisdiction "ATL" :status :intake}
    "ticket-3" {:id "ticket-3" :customer "鈴木一郎"
                :carpet "Hand-knotted wool rug (stain removal)"
                :fibre :wool :proposed-cleaning-process :alkaline-detergent
                :dry-weight-g 4000 :wet-weight-g 4200 :claimed-residual-moisture 0.05
                :colourfastness-not-confirmed? false
                :cleaning-applied? false :carpet-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-4" {:id "ticket-4" :customer "田中花子"
                :carpet "Naturally-dyed kilim (full clean)"
                :fibre :natural-dye :proposed-cleaning-process :low-moisture-encapsulation
                :dry-weight-g 4000 :wet-weight-g 4200 :claimed-residual-moisture 0.05
                :colourfastness-not-confirmed? true
                :cleaning-applied? false :carpet-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-5" {:id "ticket-5" :customer "佐藤次郎"
                :carpet "Jute-backed broadloom (extraction)"
                :fibre :synthetic :proposed-cleaning-process :low-moisture-encapsulation
                :dry-weight-g 4000 :wet-weight-g 5200 :claimed-residual-moisture 0.05
                :colourfastness-not-confirmed? false
                :cleaning-applied? false :carpet-returned? false
                :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- apply-cleaning-process! [s ticket-id]
  (let [t (ticket s ticket-id)
        seq-n (next-cleaning-sequence s (:jurisdiction t))
        result (registry/register-cleaning-application ticket-id (:jurisdiction t) seq-n)]
    {:result result
     :ticket-patch {:cleaning-applied? true
                    :cleaning-number (get result "cleaning_number")}}))

(defn- return-carpet! [s ticket-id]
  (let [t (ticket s ticket-id)
        seq-n (next-return-sequence s (:jurisdiction t))
        result (registry/register-carpet-return ticket-id (:jurisdiction t) seq-n)]
    {:result result
     :ticket-patch {:carpet-returned? true
                    :return-number (get result "return_number")}}))

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (ticket [_ id] (get-in @a [:tickets id]))
  (all-tickets [_] (sort-by :id (vals (:tickets @a))))
  (fibre-screening-of [_ id] (get-in @a [:fibre-screenings id]))
  (careplan-of [_ id] (get-in @a [:careplans id]))
  (ledger [_] (:ledger @a))
  (cleaning-history [_] (:cleanings @a))
  (return-history [_] (:returns @a))
  (next-cleaning-sequence [_ j] (get-in @a [:cleaning-sequences j] 0))
  (next-return-sequence [_ j] (get-in @a [:return-sequences j] 0))
  (carpet-already-cleaned? [_ id] (boolean (get-in @a [:tickets id :cleaning-applied?])))
  (carpet-already-returned? [_ id] (boolean (get-in @a [:tickets id :carpet-returned?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :ticket/upsert
      (swap! a update-in [:tickets (:id value)] merge value)

      :careplan/set
      (swap! a assoc-in [:careplans (first path)] payload)

      :fibre-screening/set
      (swap! a assoc-in [:fibre-screenings (first path)] payload)

      :ticket/mark-cleaned
      (let [ticket-id (first path)
            {:keys [result ticket-patch]} (apply-cleaning-process! s ticket-id)
            j (:jurisdiction (ticket s ticket-id))]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:cleaning-sequences j] (fnil inc 0))
                       (update-in [:tickets ticket-id] merge ticket-patch)
                       (update :cleanings registry/append result))))
        result)

      :ticket/mark-returned
      (let [ticket-id (first path)
            {:keys [result ticket-patch]} (return-carpet! s ticket-id)
            j (:jurisdiction (ticket s ticket-id))]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:return-sequences j] (fnil inc 0))
                       (update-in [:tickets ticket-id] merge ticket-patch)
                       (update :returns registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-tickets [s tickets] (when (seq tickets) (swap! a assoc :tickets tickets)) s))

(defn seed-db
  "A MemStore seeded with the demo cleaning-ticket set. The deterministic
  default -- the same shape every sibling actor exposes, which is what
  lets the 営み OS adapter be a three-line shim."
  []
  (->MemStore (atom (assoc (demo-data)
                           :careplans {} :fibre-screenings {}
                           :ledger [] :cleaning-sequences {} :cleanings []
                           :return-sequences {} :returns []))))
