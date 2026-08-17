(ns org.clojars.aldebogdanov.quint-connect.fixtures.two-specs
  "One namespace serving two drivers. `reset-both!` is shared; the two inits
  are not, and without :quint/driver they would be a :duplicate-init.")

(def ledger (atom 0))
(def cache (atom {}))

(def ^{:quint/state :ledger} ledger-state ledger)

(defn open-ledger! {:quint/init true :quint/driver :ledger} []
  (reset! ledger 0))

(defn open-cache! {:quint/init true :quint/driver :cache} []
  (reset! cache {}))

(defn post {:quint/action "post" :quint/driver #{:ledger :cache}} [amount]
  (swap! ledger + amount))

(defn audit {:quint/action "audit"} [] :audited)
