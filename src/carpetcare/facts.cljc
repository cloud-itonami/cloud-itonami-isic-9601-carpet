(ns carpetcare.facts
  "Jurisdictional spec-basis table for carpet and rug cleaning.

  A jurisdiction NOT in this table has **no spec-basis, full stop** --
  the advisor reports that honestly (empty `:cites`) and the Carpet Care
  Governor turns it into a HARD hold.

  What is regulated here is **consumer protection over a bailed item**:
  the operator holds someone else's rug, and the records a dispute turns
  on are the intake condition, the fibre identification, the customer's
  consent to the process and the process actually applied. JPN's
  クリーニング業法 is the closest instrument and it is why the required
  evidence list looks the way it does."
  (:require [clojure.string :as str]))

(def spec-basis-table
  {"JPN" {:name "Japan"
          :legal-basis "クリーニング業法 第3条（営業者の届出・遵守事項）"
          :consumer-basis "消費者契約法 / クリーニング事故賠償基準（全国クリーニング生活衛生同業組合連合会）"
          :provenance "e-Gov 法令検索 昭和25年法律第207号"
          :required-evidence ["顧客同意記録 (customer-consent-record)"
                              "受取時状態記録 (intake-condition-record)"
                              "繊維鑑別記録 (fibre-identification-record)"
                              "洗浄工程記録 (cleaning-process-record)"]}
   "USA" {:name "United States"
          :legal-basis "FTC Care Labeling Rule (16 CFR Part 423)"
          :consumer-basis "State consumer-protection statutes (varies by state)"
          :provenance "16 CFR 423"
          :required-evidence ["Customer consent record"
                              "Intake condition record"
                              "Fibre identification record"
                              "Cleaning process record"]}
   "DEU" {:name "Germany"
          :legal-basis "Textilkennzeichnungsgesetz (TextilKennzG)"
          :consumer-basis "BGB SS 631 ff. (Werkvertrag)"
          :provenance "Bundesgesetzblatt TextilKennzG 2016"
          :required-evidence ["Kunden-Einwilligungsprotokoll (customer-consent-record)"
                              "Zustandsprotokoll bei Annahme (intake-condition-record)"
                              "Faserbestimmungsprotokoll (fibre-identification-record)"
                              "Reinigungsprotokoll (cleaning-process-record)"]}})

(defn spec-basis [iso3] (get spec-basis-table (some-> iso3 str/upper-case)))
(defn covered? [iso3] (some? (spec-basis iso3)))

(defn coverage-summary []
  (str (count spec-basis-table)
       " jurisdictions seeded with an official spec-basis. "
       "A jurisdiction outside this set has NO basis on file and every "
       "proposal touching it is held."))

(defn required-evidence [iso3] (:required-evidence (spec-basis iso3) []))

(defn required-evidence-satisfied?
  "A missing spec-basis can NEVER be satisfied -- returns nil (falsey)."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (= (count required-evidence)
       (count (filter (set submitted) required-evidence)))))
