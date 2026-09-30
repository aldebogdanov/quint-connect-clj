(ns org.clojars.aldebogdanov.quint-connect.choreo-test
  "A Choreo spec driving a Clojure two-phase commit, both ways it can: a spec
  that records its transitions, through annotations, and Choreo's own spec as
  written, through one adapter for \"step\". The replays need no Quint; the
  ^:integration tests generate."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.clojars.aldebogdanov.quint-connect.core :as q]
            [org.clojars.aldebogdanov.quint-connect.report :as report]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]
            [tpc.core :as tpc]))

;; --- the adaptation: Choreo's shapes, from the implementation's ----------
;; Readers are defined above the drivers that scan this namespace, because a
;; namespace scanning itself sees only what it has defined so far.

(defn- variant
  "A Quint sum-type value as the decoder yields it. A variant without an
  argument carries the empty tuple."
  ([tag] {:tag tag :value []})
  ([tag v] {:tag tag :value v}))

(defn- tag [k] (str/capitalize (name k)))

(defn system
  "Choreo's `s.system`: node id -> its local state, which repeats the id."
  {:quint/state :system}
  []
  (into {} (map (fn [[id {:keys [role stage]}]]
                  [id {:process_id id
                       :role       (variant (tag role))
                       :stage      (variant (tag stage))}]))
        @tpc/nodes))

(defn- message [m]
  (case m
    :abort  (variant "CoordinatorAbort")
    :commit (variant "CoordinatorCommit")
    (variant "ParticipantPrepared" (second m))))

(defn messages
  "Choreo's `s.messages`: node id -> every message delivered to it."
  {:quint/state :messages}
  []
  (update-vals @tpc/inboxes #(into #{} (map message) %)))

;; --- a spec that records its own transitions -----------------------------

(q/defdriver tracked
  {:spec        "dev/fixtures/choreo/two_phase_commit_tracked.qnt"
   :scan        '[tpc.core org.clojars.aldebogdanov.quint-connect.choreo-test]
   :state-path  [:s]
   :action-path [:extensions :actionTaken :tag]
   :nondet-path [:extensions :actionTaken :value]
   ;; type Event = (): nothing ever happens there, and nothing here models it.
   :ignore      #{:events}})

(deftest a-recorded-transition-is-dispatched-by-name
  (let [r (q/replay-file tracked "dev/fixtures/choreo/tpc_tracked_run_0.itf.json")]
    (is (:ok? r) (report/failure-str r))
    (is (= 9 (:steps r)))
    (testing "coverage is per transition, not \"step\""
      (is (= {"DecidesOnAbort" 1 "SpontaneouslyAborts" 2 "AbortsAsInstructed" 5}
             (get-in r [:coverage :used])))
      (is (= #{"SpontaneouslyPrepares" "CommitsAsInstructed" "DecidesOnCommit"}
             (get-in r [:coverage :unused]))))))

(deftest a-scripted-choreo-run-drives-the-implementation
  ;; quint test writes no mbt:: at all; the recording is the only thing here
  ;; that says which transition was taken.
  (let [r (q/replay-file tracked "dev/fixtures/choreo/tpc_tracked_test_commitTest.itf.json")]
    (is (:ok? r) (report/failure-str r))
    (is (= 8 (:steps r)))
    (is (= {"SpontaneouslyPrepares" 3 "DecidesOnCommit" 1 "CommitsAsInstructed" 3}
           (get-in r [:coverage :used])))))

(deftest a-coordinator-that-forgets-to-broadcast-is-named
  (with-redefs [tpc/decide-commit! (fn [node] (swap! tpc/nodes assoc-in [node :stage] :committed))]
    (let [r (q/replay-file tracked "dev/fixtures/choreo/tpc_tracked_test_commitTest.itf.json")
          f (:failure r)
          s (report/failure-str r)]
      (is (not (:ok? r)))
      (is (= 4 (:step f)))
      (is (= "DecidesOnCommit" (:action f)))
      (is (= {:node "c"} (:picks f)))
      (is (= #'tpc/decide-commit! (:handler f)))
      (is (= {:messages #'messages} (:readers f)))
      (is (str/includes? s "CoordinatorCommit") "the diff shows the missing message"))))

;; --- Choreo as written: one adapter for "step" ---------------------------

(defn- step
  "Choreo as written names no transition, so map the outcome back to the
  operation that produces it. Two operations produce a participant `Aborted`
  with no effects, and nothing in the outcome tells them apart; asking the
  implementation which one it can still do is a guess this adapter has to make
  and an instrumented spec does not."
  [{:keys [v transition]}]
  (let [{:keys [role stage]} (:post_state transition)]
    (case [(:tag role) (:tag stage)]
      ["Participant" "Prepared"]  (tpc/prepare! v)
      ["Participant" "Committed"] (tpc/handle-commit! v)
      ["Participant" "Aborted"]   (if (= :working (get-in @tpc/nodes [v :stage]))
                                    (tpc/give-up! v)
                                    (tpc/handle-abort! v))
      ["Coordinator" "Committed"] (tpc/decide-commit! v)
      ["Coordinator" "Aborted"]   (tpc/decide-abort! v))))

(q/defdriver as-written
  {:spec       "dev/fixtures/choreo/two_phase_commit.qnt"
   :scan       '[tpc.core org.clojars.aldebogdanov.quint-connect.choreo-test]
   :state-path [:s]
   :actions    {"step" step}
   ;; Choreo as written has no extensions to supply: type Extensions = ().
   :ignore     #{:events :extensions}})

(deftest choreo-as-written-replays-through-one-adapter
  (let [r (q/replay-file as-written "dev/fixtures/choreo/tpc_run_0.itf.json")]
    (is (:ok? r) (report/failure-str r))
    (is (= 6 (:steps r)))
    (is (= 5 (get-in r [:coverage :used "step"]))
        "and all the coverage report can say is \"step\"")))

(deftest a-participant-that-ignores-an-abort-is-caught
  ;; The classic way to block two-phase commit: once a participant has voted
  ;; yes it waits for a commit, and ignores the abort that comes instead. The
  ;; recording has p3 voting yes after the coordinator aborted.
  (with-redefs [tpc/handle-abort! (fn [node]
                                    (when (= :working (get-in @tpc/nodes [node :stage]))
                                      (swap! tpc/nodes assoc-in [node :stage] :aborted)))]
    (let [r (q/replay-file as-written "dev/fixtures/choreo/tpc_run_0.itf.json")
          f (:failure r)]
      (is (not (:ok? r)))
      (is (= 5 (:step f)))
      (is (= "p3" (get-in f [:picks :v])))
      (is (= {:tag "Aborted" :value []} (get-in f [:expected :system "p3" :stage])))
      (is (= {:tag "Prepared" :value []} (get-in f [:actual :system "p3" :stage]))))))

;; --- generating, which needs quint on PATH -------------------------------

(deftest ^:integration an-instrumented-choreo-spec-checks
  (let [r (qt/check tracked {:traces 20 :max-steps 12 :seed 42})]
    (is (< 20 (:steps r)))))

(deftest ^:integration a-scripted-choreo-run-checks
  (qt/check-run tracked {:test "commitTest"}))

(deftest ^:integration choreo-as-written-checks-through-one-adapter
  (qt/check as-written {:traces 20 :max-steps 12 :seed 42}))
