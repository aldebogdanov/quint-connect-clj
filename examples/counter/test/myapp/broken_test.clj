(ns myapp.broken-test
  "What a failure looks like, without editing anything.

  These deftests are tagged ^:broken and are excluded from `clojure -M:test`.
  Run them on purpose:

    clojure -M:test:broken

  They are *supposed* to fail. The point is the message they fail with."
  (:require [clojure.test :refer [deftest]]
            [myapp.core :as core]
            [myapp.model-test :refer [counter]]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

(deftest ^:broken refuse-must-not-change-the-count
  ;; The bug from the tutorial: refusing is supposed to leave the count alone.
  ;; with-redefs is how the demo injects it; in real life you would just have
  ;; written it this way.
  (with-redefs [core/refuse (fn [n]
                              (swap! core/counter + n)
                              (reset! core/last-op "refused"))]
    (qt/check counter {:traces 10 :max-steps 15})))
