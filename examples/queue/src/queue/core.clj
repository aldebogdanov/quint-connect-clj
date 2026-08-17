(ns queue.core
  "A bounded queue whose capacity is configured at startup. Nothing here knows
  quint-connect exists.")

(def capacity (atom 0))

(def ^{:quint/state :items} items (atom []))

;; Two specs, two capacities, two resets — and one namespace. Without
;; :quint/driver these would be :duplicate-init, because the registry cannot
;; tell two setups for two specs from two vars fighting over one job.
(defn open-small! {:quint/init true :quint/driver :small} []
  (reset! capacity 2)
  (reset! items []))

(defn open-large! {:quint/init true :quint/driver :large} []
  (reset! capacity 5)
  (reset! items []))

;; Everything below is unscoped, so both drivers read it.
(defn push {:quint/action "push" :quint/args [:x]} [x]
  (when (< (count @items) @capacity)
    (swap! items conj x)))

(defn reject {:quint/action "reject"} [_x]
  nil)

(defn pop-item {:quint/action "popItem"} []
  (swap! items #(if (seq %) (subvec % 1) %)))
