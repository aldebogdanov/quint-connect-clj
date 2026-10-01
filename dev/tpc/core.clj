(ns tpc.core
  "A two-phase commit in memory, annotated for quint-connect and matching
  dev/fixtures/choreo/two_phase_commit_tracked.qnt. The same implementation as
  examples/two-phase-commit, which keeps its own copy so that it can be copied
  out and run.

  One coordinator, three participants, and a network that delivers every
  message to every node and forgets nothing. Note what is absent: any :require
  of quint-connect, and any trace of Choreo's shapes. Stages are keywords here;
  turning them into the spec's variants is the test namespace's job.")

(def coordinator "c")
(def participants #{"p1" "p2" "p3"})

(def nodes
  "Node id -> {:role :coordinator|:participant,
               :stage :working|:prepared|:committed|:aborted}."
  (atom {}))

(def inboxes
  "Node id -> every message delivered to it: :abort, :commit, or
  [:prepared participant]. Nothing is ever taken out, so a node can act on
  anything it has received, and an instruction delivered twice is a no-op."
  (atom {}))

(defn start! {:quint/init true} []
  (reset! nodes (into {coordinator {:role :coordinator :stage :working}}
                      (map (fn [p] [p {:role :participant :stage :working}]))
                      participants))
  (reset! inboxes (zipmap (keys @nodes) (repeat #{}))))

(defn- stage [node] (get-in @nodes [node :stage]))

(defn- stage! [node s] (swap! nodes assoc-in [node :stage] s))

(defn- received? [node msg] (contains? (get @inboxes node) msg))

(defn- broadcast! [msg] (swap! inboxes update-vals #(conj % msg)))

;; --- participants --------------------------------------------------------

(defn prepare!
  "Vote yes: this participant can commit, and says so to everyone."
  {:quint/action "SpontaneouslyPrepares"}
  [node]
  (when (= :working (stage node))
    (stage! node :prepared)
    (broadcast! [:prepared node])))

(defn give-up!
  "Vote no by aborting unilaterally, which a participant may only do before it
  has voted yes."
  {:quint/action "SpontaneouslyAborts"}
  [node]
  (when (= :working (stage node))
    (stage! node :aborted)))

(defn handle-abort! {:quint/action "AbortsAsInstructed"} [node]
  (when (received? node :abort)
    (stage! node :aborted)))

(defn handle-commit! {:quint/action "CommitsAsInstructed"} [node]
  (when (received? node :commit)
    (stage! node :committed)))

;; --- the coordinator -----------------------------------------------------

(defn decide-commit!
  "Commit once every participant has voted yes."
  {:quint/action "DecidesOnCommit"}
  [node]
  (when (and (= :working (stage node))
             (every? #(received? node [:prepared %]) participants))
    (stage! node :committed)
    (broadcast! :commit)))

(defn decide-abort! {:quint/action "DecidesOnAbort"} [node]
  (when (= :working (stage node))
    (stage! node :aborted)
    (broadcast! :abort)))
