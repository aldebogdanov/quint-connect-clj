(ns org.clojars.aldebogdanov.quint-connect.fixtures.args-count
  "More :quint/args entries than the handler has parameters.")

(defn deposit
  {:quint/action "deposit" :quint/args [:who :amount :extra]}
  [who amount]
  [who amount])
