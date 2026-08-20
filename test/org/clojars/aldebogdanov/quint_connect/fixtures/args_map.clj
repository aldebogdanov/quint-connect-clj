(ns org.clojars.aldebogdanov.quint-connect.fixtures.args-map
  "Handlers whose own signatures take a map. :quint/args builds it from the
  picks, so the function is annotated where it stands rather than wrapped.")

(defn transfer
  {:quint/action "transfer" :quint/args [{:from :src :to :dst :amount :amt}]}
  [{:keys [from to amount]}]
  [from to amount])

;; a pick, then a built map: the two entry forms compose
(defn credit
  {:quint/action "credit" :quint/args [:who {:amount :amt}]}
  [who {:keys [amount]}]
  [who amount])
