(ns bucket.model-test
  (:require [bucket.core]
            [clojure.test :refer [deftest is testing]]
            [org.clojars.aldebogdanov.quint-connect.core :as q]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

(q/defdriver bucket
  {:spec        "spec/tokens.qnt"
   :main        "tokensTest"
   :scan        '[bucket.core]
   ;; The spec records what it did, so both `check` and `verify` can dispatch.
   :action-path [:lastAction]
   :nondet-path [:lastPick]})

;; Sampling: fifty random traces, looking for a divergence.
(deftest bucket-conforms-to-spec
  (qt/check bucket {:traces 50 :max-steps 20}))

;; Proving: Apalache checks every reachable state within the bound. Slower, and
;; a different kind of answer — see the README.
(deftest ^:slow capacity-is-never-exceeded
  (qt/verify bucket {:invariant "neverOverCapacity" :max-steps 4}))

;; And what a broken invariant looks like. `neverStarved` is not true of this
;; design: grant enough and the bucket empties. Apalache produces the trace
;; that shows it, and that trace is then replayed against the implementation —
;; which agrees with it, so `:failure` is nil and the spec is where to look.
(deftest ^:slow a-too-strong-invariant-hands-back-the-trace-that-breaks-it
  (let [r (q/verify bucket {:invariant "neverStarved" :max-steps 4})]
    (is (false? (:ok? r)) "a violated invariant is never a pass")
    (is (false? (get-in r [:invariant :holds?])))
    (testing "and the implementation reproduced it faithfully"
      (is (nil? (:failure r))))))
