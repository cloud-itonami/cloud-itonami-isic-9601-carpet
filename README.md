# cloud-itonami-isic-9601-carpet

Open Business Blueprint for **carpet and rug cleaning** — a role-suffix
satellite of
[`cloud-itonami-isic-9601`](https://github.com/cloud-itonami/cloud-itonami-isic-9601)
(ISIC 9601: washing and (dry-)cleaning of textile and fur products).

## The classification is not settled, and this repo says so

**This workspace's ISIC spec mirror (`cloud-itonami/org-un-isic`) does not
enumerate carpet cleaning in any class** (measured 2026-08-08). The
`includes` lists that were checked:

| Class | What it enumerates | carpet? |
|---|---|---|
| 8121 general building cleaning | interior, window, chimney, industrial machinery | no |
| 8129 other building/industrial cleaning | disinfecting, pool, steam-blast, ice/snow, tankers | no |
| 9601 washing and dry-cleaning | laundry, dry-clean, dyeing, coin-operated, rental | no |

The only classes naming carpet at all are 1391–1393 (manufacture) and
4753 (retail).

**So this repo does not claim an authoritative classification.** It
declares an operating model and derives its parent from that: the rug is
**collected, cleaned off-premises and returned**, which makes it
"cleaning of a textile product" — the wording of 9601's own
`description` ("washing, cleaning, dyeing, and pressing of textile
products"). On-premises carpet cleaning could equally be read as 8121
interior cleaning; that reading is recorded in
`docs/adr/0001-architecture.md` §1 rather than argued away, and
`:actuation/declare-isic-class` is deliberately **absent from the
vocabulary** so this actor cannot decide the question on a statistical
authority's behalf.

## Scope

A carpet left for cleaning is a bailment: the customer keeps ownership
while the operator holds possession, and a wool or naturally-dyed rug
destroyed by the wrong process cannot be un-destroyed. Both real-world
acts — applying a cleaning process, handing the rug back — never
auto-commit at any phase. Two independent layers say so
(`carpetcare.phase` and `carpetcare.governor/high-stakes`).

**Absent from the vocabulary, not gated:**

| Not here | Whose it is |
|---|---|
| damage-liability decision | the operator, their insurer and the customer |
| appraisal / valuation | an appraiser |
| declaring an ISIC class | a statistical authority |

## The two checks that cannot be loosened

**Fibre × process** — `registry/fibre-forbidden-processes` is recomputed
from the rug's own recorded fibre and its own proposed process. Set
membership, no threshold:

| Recorded fibre | Forbidden |
|---|---|
| `:wool` | `:hot-water-extraction-high-temp`, `:alkaline-detergent` (felts and yellows in alkali) |
| `:silk` | + `:oxygen-bleach` |
| `:natural-dye` | `:oxygen-bleach`, `:alkaline-detergent` (bleeds) |
| `:jute-backing` | `:hot-water-extraction-high-temp` (cellulosic browning) |

**Residual moisture** — the ticket's claimed rate against
`(wet − dry) / dry` recomputed from its own weights. An identity;
the 0.005 tolerance is on the float comparison, not on the rule.
Over-wetting is what causes mould and browning, so this is the number a
dispute turns on.

## Run it

```bash
clojure -M:dev:run     # 5 commits and 6 distinct governor holds
clojure -M:dev:test    # 31 tests / 91 assertions
```

```
:committed     :ticket/intake                    ticket-1
:committed     :careplan/verify                  ticket-1
:committed     :fibre/screen                     ticket-1
:committed     :actuation/apply-cleaning-process ticket-1
:committed     :actuation/return-carpet          ticket-1
:governor-hold :careplan/verify                  ticket-2 [:no-spec-basis]
:governor-hold :actuation/apply-cleaning-process ticket-3 [:evidence-incomplete :cleaning-process-forbidden-by-fibre]
:governor-hold :fibre/screen                     ticket-4 [:colourfastness-not-confirmed]
:governor-hold :actuation/apply-cleaning-process ticket-5 [:evidence-incomplete :moisture-claim-mismatch]
:governor-hold :actuation/appraise-value         ticket-1 [:op-not-allowed]
:governor-hold :actuation/apply-cleaning-process ticket-1 [:already-cleaned]
```

## Honest state

- **`DatomicStore` is not implemented.** Only `MemStore`.
- **The classification question is open**, deliberately. See §1 above.
- **Not connected to the 営み OS yet.** Standard-form, so the adapter
  will be a three-line shim, but the `os.edn` declaration is a separate
  step.
- **`high-stakes` is a set of op names**, not of advisor-reported
  `:stake` values — following 9601 (this repo's parent) rather than
  9522/9523. The request key is `:subject`, so no shim translation is
  needed.

## License

AGPL-3.0-or-later. See `LICENSE`.
