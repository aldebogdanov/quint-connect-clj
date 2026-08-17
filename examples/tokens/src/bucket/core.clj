(ns bucket.core
  "A token bucket. Nothing here knows quint-connect exists.")

(def capacity 5)

(def ^{:quint/state :tokens} tokens (atom capacity))

(defn open! {:quint/init true} []
  (reset! tokens capacity))

(defn grant {:quint/action "grant" :quint/args [:n]} [n]
  (swap! tokens - n))

(defn refuse {:quint/action "refuse"} [_n]
  nil)

(defn refill {:quint/action "refill"} [n]
  ;; Drop the `min` to see both tests fail, in two different ways.
  (swap! tokens #(min capacity (+ % n))))
