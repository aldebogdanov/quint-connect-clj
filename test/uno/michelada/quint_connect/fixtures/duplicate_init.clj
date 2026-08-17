(ns uno.michelada.quint-connect.fixtures.duplicate-init
  "Two inits in one namespace. `ns-interns` returns a map, so which one used to
  win was down to hash order — the reason this is an error and not a choice.")

(def started (atom 0))

(defn open! {:quint/init true} [] (swap! started inc))

(defn reopen! {:quint/init true} [] (reset! started 1))
