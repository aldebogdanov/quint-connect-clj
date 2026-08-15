(ns uno.michelada.quint-connect.fixtures.keyed
  "The same annotations under a driver-supplied :key-ns.")

(def ^{:acme.mbt/state :balances} accounts (atom {}))

(defn deposit {:acme.mbt/action "deposit"} [who amount] [who amount])
