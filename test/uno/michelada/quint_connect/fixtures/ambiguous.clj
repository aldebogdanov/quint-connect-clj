(ns uno.michelada.quint-connect.fixtures.ambiguous)

(defn ^{:quint/action "sweep"} sweep
  ([from to] (sweep from to 1))
  ([from to amount] [from to amount]))
