(ns cache.lru
  "A tiny LRU cache. Nothing here knows quint-connect exists.")

(def capacity 3)

(def ^{:quint/state :entries} entries (atom {}))
(def ^{:quint/state :recency} recency (atom []))   ; least recently used first

(defn open! {:quint/init true} []
  (reset! entries {})
  (reset! recency []))

(defn- touch [order k]
  (conj (vec (remove #{k} order)) k))

(defn write {:quint/action "write"} [k v]
  (let [bumped (touch @recency k)]
    (if (> (count bumped) capacity)
      (do (swap! entries #(-> % (dissoc (first bumped)) (assoc k v)))
          (reset! recency (vec (rest bumped))))
      (do (swap! entries assoc k v)
          (reset! recency bumped)))))

(defn lookup {:quint/action "read"} [k]
  ;; Delete the swap! below to see the model-based test catch a real bug.
  (when-some [v (get @entries k)]
    (swap! recency touch k)   ; a read is a use: it changes eviction order
    v))

(defn absent {:quint/action "absent"} [_k] nil)
