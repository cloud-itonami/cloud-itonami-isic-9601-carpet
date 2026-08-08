# ADR-0001: A carpet-cleaning actor whose ISIC attribution is stated, not asserted

**Status**: accepted
**Date**: 2026-08-08
**Superproject record**: `com-junkawasaki/root` ADR-2800004000 §3

## Context

ADR-2800004000 §3 recorded carpet cleaning as "unimplemented, not out of
scope." When the three missing cleaning industries were finally
scaffolded, the ISIC attribution of each was read from this workspace's
spec mirror (`cloud-itonami/org-un-isic`) rather than from memory. Two of
the three were explicit:

- car washing → `4520.includes` names "washing, polishing"
- pet care → `9609.includes` names "pet care services (grooming,
  boarding, training)"

**Carpet cleaning was not in any class.** 8121, 8129 and 9601 were all
checked; none enumerates it. The only classes naming carpet are
1391–1393 (manufacture) and 4753 (retail).

## Decision

### 1. Declare an operating model; do not declare a classification

Two readings are defensible and this ADR records both:

- **9601** — "washing, cleaning, dyeing, and pressing of **textile
  products**" (the class's own `description`). A carpet is a textile
  product. This is the reading the repo operates under.
- **8121** — "interior cleaning of buildings." Carpet cleaned *in situ*,
  on the customer's floor, is arguably interior building cleaning.

The repo resolves this by **narrowing its own model rather than widening
its claim**: it implements an *off-premises intake* — the rug is
collected, cleaned and returned — which is squarely textile cleaning. An
in-situ operator would be a different actor and might well sit under
8121.

To make sure the actor cannot quietly promote its own reading,
**`:actuation/declare-isic-class` is absent from `allowed-ops`** and
"classified under isic" / "isic 分類を確定" are in
`scope-excluded-terms`. A statistical authority decides classification;
this actor does not.

Owner decision (2026-08-08): 9601 satellite, textile reading.

### 2. The bailment shape, for the fourth time

intake → verify → screen → actuate → return, two actuations, `:auto`
containing only intake. This is now the fourth repetition (9601 garments,
9522 appliances, 9523 footwear, `4520-carwash` vehicles) and the shape
held without modification.

### 3. Two checks chosen because they cannot be loosened

- **`cleaning-process-forbidden-by-fibre?`** — set membership over the
  rug's own recorded fibre and its own proposed process. Wool felts and
  yellows irreversibly in alkali and shrinks in high-temperature
  extraction; silk loses its hand and its dye to oxygen bleach; natural
  dyes bleed; a jute secondary backing browns when over-wetted. No
  threshold exists to lower.
- **`moisture-claim-mismatch?`** — the claimed residual-moisture rate
  against `(wet − dry) / dry` recomputed from the ticket's own weights.
  An identity. The 0.005 tolerance is on the float comparison, not on the
  rule.

### 4. `high-stakes` on op names; request key `:subject`

Following 9601 (this repo's parent) and `4520-carwash` rather than
9522/9523: a permanent invariant must not depend on the censored party's
self-reported `:stake`. And `:subject` avoids adding to the fleet's
subject-key debt (ADR-2800004000: 5229 `:target-id`, 4759 `:store-id`,
8121/8129 `:site-id`).

## Consequences

### What this buys

- Carpet cleaning has an implementation, and the classification
  uncertainty is visible in the repo rather than resolved by assertion.
- 5 commits and 6 distinct governor holds, each naming its own rule —
  checkable with `clojure -M:dev:run`.

### What it costs, stated rather than hidden

- **The attribution may be revised.** If a future ISIC revision or a
  national statistical office places carpet cleaning explicitly, this
  repo may need to move under a different parent. The repo name pins the
  parent, and CLAUDE.md's naming rules say names are discovery aliases
  rather than identity — so a revision would be a new registration with
  the old one superseded, not a silent rename.
- **In-situ carpet cleaning is not covered.** The model is off-premises
  intake. An operator who cleans on the customer's floor needs different
  evidence (occupied-building access, re-entry timing) and would be a
  different actor.
- **`DatomicStore` does not exist here.** Only `MemStore`.
- **Not on the shared surface.** `os.edn` declaration is a separate step.
