(ns org.clojars.aldebogdanov.quint-connect.fixtures.bare
  "Nothing readable here: the metadata sits on the (defn ...) form, which
  Clojure silently discards. This is the trap :empty-scan exists to name.")

^{:quint/action "overdraft"}
(defn overdraft [who amount] [who amount])
