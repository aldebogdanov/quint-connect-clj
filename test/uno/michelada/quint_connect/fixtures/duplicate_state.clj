(ns uno.michelada.quint-connect.fixtures.duplicate-state)

(def ^{:quint/state :balances} from-an-atom (atom {}))

(defn from-a-getter {:quint/state :balances} [] {})
