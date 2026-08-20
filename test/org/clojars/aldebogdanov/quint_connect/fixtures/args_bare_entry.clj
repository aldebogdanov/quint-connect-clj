(ns org.clojars.aldebogdanov.quint-connect.fixtures.args-bare-entry
  "An entry that is neither a pick name nor a map building one argument.")

(defn deposit {:quint/action "deposit" :quint/args ["who"]} [who] who)
