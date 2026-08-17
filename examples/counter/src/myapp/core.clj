(ns myapp.core)

(def ^{:quint/state :count}  counter (atom 0))
(def ^{:quint/state :lastOp} last-op (atom ""))

(defn start! {:quint/init true} []
  (reset! counter 0)
  (reset! last-op ""))

(defn add {:quint/action "add"} [n]              ; picks bind by parameter name
  (swap! counter + n)
  (reset! last-op "add"))

(defn take-out {:quint/action "take" :quint/args [:n]} [amount]
  (swap! counter - amount)                       ; parameter is not named n,
  (reset! last-op "take"))                       ; so :quint/args says the order

(defn refuse {:quint/action "refuse"} [_n]
  (reset! last-op "refused"))
