(ns tpc.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tpc.core :as tpc]
            [org.clojars.aldebogdanov.quint-connect.core :as q]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

;; --- adaptation: the implementation's state, in Choreo's shapes ----------
;; The spec's stages and messages are sum types, and a sum-type value decodes
;; to {:tag "Working" :value []}. The implementation says :working. These two
;; readers translate, so that neither side has to speak the other's language.
;;
;; They sit above the driver, which scans this namespace: a namespace that
;; scans itself sees only what it has defined so far.

(defn- variant
  ([tag] {:tag tag :value []})             ; a variant without an argument
  ([tag v] {:tag tag :value v}))

(defn- tag [k] (str/capitalize (name k))) ; :working -> "Working"

(defn system
  "s.system: node id -> its local state, which repeats the id."
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
  "s.messages: node id -> every message delivered to it."
  {:quint/state :messages}
  []
  (update-vals @tpc/inboxes #(into #{} (map message) %)))

;; --- the driver ----------------------------------------------------------

(q/defdriver two-phase-commit
  {:spec        "spec/two_phase_commit.qnt"
   :scan        '[tpc.core tpc.model-test]

   ;; All of a Choreo spec's state is one variable, s. Compare its fields —
   ;; :system, :messages, :events, :extensions — as if they were variables.
   :state-path  [:s]

   ;; Read inside s. The spec records each transition as a variant, whose tag
   ;; is the action and whose record is the picks. :extensions is where it
   ;; lives, so :extensions is not compared: it is the spec's bookkeeping.
   :action-path [:extensions :actionTaken :tag]
   :nondet-path [:extensions :actionTaken :value]

   ;; type Event = (): nothing ever happens there, and nothing here models it.
   :ignore      #{:events}})

(deftest two-phase-commit-conforms-to-spec
  (let [r (qt/check two-phase-commit {:traces 50 :max-steps 20})]
    ;; Random traces rarely reach a commit: every participant has to vote yes
    ;; before the coordinator gives up. Coverage says which transitions a run
    ;; exercised, per transition, because the spec names them.
    (is (contains? (get-in r [:coverage :used]) "DecidesOnAbort"))))

(deftest the-commit-scenario-conforms-to-spec
  ;; So the path random traces rarely take is written down in the spec, as
  ;; the run commitTest, and replayed here step by step.
  (let [r (qt/check-run two-phase-commit {:test "commitTest"})]
    (is (= 3 (get-in r [:coverage :used "CommitsAsInstructed"])))))
