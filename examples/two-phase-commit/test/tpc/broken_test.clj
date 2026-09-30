(ns tpc.broken-test
  "What a failure looks like, without editing anything.

  Tagged ^:broken and excluded from `clojure -M:test`. Run on purpose:

    clojure -M:test:broken

  It is *supposed* to fail. The point is the message."
  (:require [clojure.test :refer [deftest]]
            [tpc.core :as tpc]
            [tpc.model-test :refer [two-phase-commit]]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

(deftest ^:broken a-participant-that-voted-yes-must-still-abort
  ;; The way two-phase commit blocks: a participant that voted yes waits for
  ;; the commit, and ignores the abort that comes instead. Random traces find
  ;; it — a participant votes yes, the coordinator gives up anyway.
  (with-redefs [tpc/handle-abort! (fn [node]
                                    (when (= :working (get-in @tpc/nodes [node :stage]))
                                      (swap! tpc/nodes assoc-in [node :stage] :aborted)))]
    (qt/check two-phase-commit {:traces 50 :max-steps 20})))

(deftest ^:broken a-coordinator-must-tell-everyone
  ;; Commits, and forgets to say so. The scripted run reaches it every time,
  ;; and the diff is the message that was never sent.
  (with-redefs [tpc/decide-commit! (fn [node]
                                     (swap! tpc/nodes assoc-in [node :stage] :committed))]
    (qt/check-run two-phase-commit {:test "commitTest"})))
