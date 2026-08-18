(ns bucket.broken-test
  "What a failure looks like, without editing anything.

  Tagged ^:broken and excluded from `clojure -M:test`. Run on purpose:

    clojure -M:test:broken

  It is *supposed* to fail."
  (:require [bucket.core :as core]
            [bucket.model-test :refer [bucket]]
            [clojure.test :refer [deftest]]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

(deftest ^:broken refill-must-respect-the-cap
  ;; `check` catches this. `verify` on neverOverCapacity does not, and that is
  ;; the lesson of this example: the spec still caps correctly, so Apalache
  ;; finds no violation and never looks at your code. See the README.
  (with-redefs [core/refill (fn [n] (swap! core/tokens + n))]
    (qt/check bucket {:traces 50 :max-steps 20})))
