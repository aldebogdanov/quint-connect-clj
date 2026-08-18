(ns queue.broken-test
  "What a failure looks like, without editing anything.

  Tagged ^:broken and excluded from `clojure -M:test`. Run on purpose:

    clojure -M:test:broken

  Both are *supposed* to fail."
  (:require [clojure.test :refer [deftest]]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]
            [queue.core :as core]
            [queue.model-test :refer [large small]]))

;; A queue that pops the end it pushed to is a stack. The spec drops the front
;; (`items.slice(1, length(items))`), so this diverges the first time a pop
;; happens with more than one item queued.
;;
;; Note what is *not* here: a capacity bug. `push` is only enabled by the spec
;; when there is room, so an implementation that ignores the bound is never
;; asked to exceed it — the model fires `reject` instead. A bug the
;; specification makes unreachable is a bug this kind of testing cannot find,
;; which is worth knowing before you rely on it.
(defn- pops-the-wrong-end []
  (swap! core/items #(if (seq %) (pop %) %)))

(deftest ^:broken small-queue-must-be-first-in-first-out
  (with-redefs [core/pop-item pops-the-wrong-end]
    (qt/check small {:traces 30 :max-steps 15})))

(deftest ^:broken large-queue-must-be-first-in-first-out
  (with-redefs [core/pop-item pops-the-wrong-end]
    (qt/check large {:traces 30 :max-steps 15})))
