(ns cache.broken-test
  "What a failure looks like, without editing anything.

  Tagged ^:broken and excluded from `clojure -M:test`. Run on purpose:

    clojure -M:test:broken

  It is *supposed* to fail. The point is the message, and where in the trace
  the failure turns up."
  (:require [cache.lru :as lru]
            [cache.lru-test :refer [cache]]
            [clojure.test :refer [deftest]]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

(deftest ^:broken a-read-must-count-as-a-use
  ;; Looks right, and is wrong: reading a key should make it most-recently
  ;; used, so forgetting to reorder evicts the wrong entry later. Note the step
  ;; number it fails at — this is not a bug step 1 would ever find.
  (with-redefs [lru/lookup (fn [k] (get @lru/entries k))]
    (qt/check cache {:traces 200 :max-steps 25})))
