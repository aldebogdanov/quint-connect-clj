(ns uno.michelada.quint-connect.fixtures.forms
  "The remaining annotation forms: a nested read, a whole-state reader, halt,
  and a private var.")

(def ^{:quint/state {:var :lastError :path [:err]}} status (ref {:err "boom"}))

(defn everything {:quint/state :*} []
  {:balances {"alice" 1} :pending 2})

(def stopped (atom 0))

(defn stop {:quint/halt true} [] (swap! stopped inc))

(defn- ^{:quint/action "audit"} audit [] :audited)
